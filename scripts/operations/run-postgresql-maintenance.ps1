#requires -Version 7.0
param(
  [Parameter(Mandatory)]
  [ValidateSet('Backup', 'Restore')]
  [string]$Operation,
  [Parameter(Mandatory)]
  [string]$BackupRoot,
  [Parameter(Mandatory)]
  [string]$StateDirectory
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/postgresql-backup.ps1')

$null = New-Item -ItemType Directory -Path $StateDirectory -Force
$operationName = $Operation.ToLowerInvariant()
$statusFile = Join-Path $StateDirectory "$operationName.json"
$runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$log = Join-Path $StateDirectory "$operationName-$runId.log"
$runLock = [IO.File]::Open((Join-Path $StateDirectory "$operationName.lock"), 'OpenOrCreate', 'ReadWrite', 'None')
$status = [ordered]@{
  operation = $operationName
  status = 'running'
  startedAt = [DateTimeOffset]::UtcNow
  completedAt = $null
  lastSuccessAt = $null
  backupDirectory = $null
  verifiedFiles = 0
  verificationReport = $null
  log = $log
}

function Save-Status {
  $pending = Join-Path $StateDirectory "$operationName-$runId.pending"
  $status | ConvertTo-Json | Set-Content -LiteralPath $pending
  [IO.File]::Move($pending, $statusFile, $true)
}

$hasTranscript = $false
try {
  if (Test-Path -LiteralPath $statusFile) {
    $previous = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json
    $status.lastSuccessAt = $previous.lastSuccessAt
  }
  Save-Status

  Start-Transcript -LiteralPath $log | Out-Null
  $hasTranscript = $true
  if ($Operation -eq 'Backup') {
    $backup = & (Join-Path $PSScriptRoot 'backup-daily-postgresql.ps1') -BackupRoot $BackupRoot
    $verified = Read-VerifiedPostgresqlBackup $backup
    $status.backupDirectory = $verified.Root
    $status.verifiedFiles = $verified.FileCount
  } else {
    $backup = Get-ChildItem -LiteralPath $BackupRoot -Directory -Filter 'postgresql-recovery-*' |
      Sort-Object Name -Descending |
      Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'SHA256SUMS.txt') -PathType Leaf } |
      Select-Object -First 1
    if (!$backup) { throw 'No completed PostgreSQL backup was found' }
    $status.backupDirectory = $backup.FullName
    $status.verificationReport = & (Join-Path $PSScriptRoot 'verify-postgresql-backup.ps1') -BackupDirectory $backup.FullName
    $report = Get-Content -LiteralPath $status.verificationReport -Raw | ConvertFrom-Json
    if (@($report.restoredDatabases).Count -ne 13) { throw 'The recovery drill did not verify all 13 databases' }
    $status.verifiedFiles = $report.filesChecked
  }
  $status.status = 'succeeded'
  $status.completedAt = $status.lastSuccessAt = [DateTimeOffset]::UtcNow
  Save-Status
} catch {
  $status.status = 'failed'
  $status.completedAt = [DateTimeOffset]::UtcNow
  if ($hasTranscript) { Write-Host $_.Exception.Message }
  Save-Status
  throw
} finally {
  try {
    if ($hasTranscript) { Stop-Transcript | Out-Null }
  } finally {
    $runLock.Dispose()
  }
}
