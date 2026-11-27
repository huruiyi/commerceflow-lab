#requires -Version 7.0
param(
  [Parameter(Mandatory)]
  [string]$BackupRoot
)

$ErrorActionPreference = 'Stop'
if (!$IsWindows) { throw 'The daily Kafka backup requires Docker Desktop with WSL on Windows' }

function ConvertTo-DesktopPath([string]$Path) {
  if ($Path -notmatch '^([a-zA-Z]):\\(.+)$') { throw 'Use a drive-letter path available to Docker Desktop' }
  '/mnt/host/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2].Replace('\', '/')
}

$root = (Resolve-Path -LiteralPath $BackupRoot).Path
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$relative = [IO.Path]::GetRelativePath($workspace, $root)
if ($relative -eq '.' -or (!$relative.StartsWith('..\') -and $relative -ne '..' -and ![IO.Path]::IsPathRooted($relative))) {
  throw 'Use a backup directory outside the source repository'
}

$startedAt = [DateTimeOffset]::UtcNow
$name = 'kafka-recovery-' + $startedAt.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$pending = (New-Item -ItemType Directory -Path (Join-Path $root "$name.incomplete")).FullName
$acl = Get-Acl -LiteralPath $pending
$acl.SetAccessRuleProtection($true, $false)
$principals = @(
  [Security.Principal.WindowsIdentity]::GetCurrent().User
  [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
  [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
)
foreach ($principal in $principals) {
  $rule = [Security.AccessControl.FileSystemAccessRule]::new(
    $principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
  )
  $acl.SetAccessRule($rule)
}
Set-Acl -LiteralPath $pending -AclObject $acl

$source = Join-Path $workspace 'scripts/messaging/capture-kafka-docker-desktop.sh'
$output = ConvertTo-DesktopPath $pending
$capture = ConvertTo-DesktopPath $source
for ($attempt = 1; $attempt -le 3; $attempt++) {
  Write-Host "Capturing the daily Kafka data volume, attempt $attempt"
  & wsl.exe -d docker-desktop -u root --exec /usr/bin/unshare --mount --propagation private /bin/sh $capture $output 2>&1 |
    Out-Host
  if ($LASTEXITCODE -eq 0) { break }
  if ($attempt -eq 3) { throw 'Kafka copy or its read-only source checks failed after three attempts' }
  Start-Sleep -Seconds 5
}

$files = @('kafka-data.tar.gz', 'data-files.sha256', 'meta.properties', 'image.txt', 'source-container.txt',
  'topics.csv', 'earliest.txt', 'latest.txt', 'commits.csv', 'history.csv')
foreach ($file in $files) {
  if ((Get-Item -LiteralPath (Join-Path $pending $file)).Length -eq 0) { throw "Empty Kafka backup file: $file" }
}
& wsl.exe -d docker-desktop -u root --exec /bin/gzip -t "$output/kafka-data.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'Kafka archive failed compression integrity verification' }
$meta = Get-Content -LiteralPath (Join-Path $pending 'meta.properties') -Raw | ConvertFrom-StringData
if (!$meta.'cluster.id' -or !$meta.'directory.id' -or $meta.'node.id' -ne '1') { throw 'Unexpected KRaft metadata' }
$hashes = foreach ($file in $files) {
  '{0}  {1}' -f (Get-FileHash -LiteralPath (Join-Path $pending $file) -Algorithm SHA256).Hash.ToLowerInvariant(), $file
}
$hashes | Set-Content -LiteralPath (Join-Path $pending 'SHA256SUMS.txt')
$destination = Join-Path $root $name
[IO.Directory]::Move($pending, $destination)
Write-Host "Completed Kafka archive in $([Math]::Round(([DateTimeOffset]::UtcNow - $startedAt).TotalSeconds, 2)) seconds"
Write-Output $destination
