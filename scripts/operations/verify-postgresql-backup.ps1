#requires -Version 7.0
param(
  [Parameter(Mandatory)]
  [string]$BackupDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$verificationStarted = [DateTimeOffset]::UtcNow
. (Join-Path $PSScriptRoot '../lib/postgresql-backup.ps1')
$verified = Read-VerifiedPostgresqlBackup $BackupDirectory
$root = $verified.Root
$manifest = $verified.Manifest

$historySql = @'
SELECT format(
  'SELECT %L, version, checksum, success FROM %I.flyway_schema_history ORDER BY installed_rank;',
  schemaname, schemaname
)
FROM pg_tables WHERE tablename = 'flyway_schema_history' ORDER BY schemaname
\gexec
'@
$checked = foreach ($database in $manifest.databases) {
  $db = $database.database
  $restoreName = 'fairy-commerceflow-verify-restore-' + $db.Replace('_', '-') + '-' +
    [guid]::NewGuid().ToString('N').Substring(0, 8)
  $started = [Diagnostics.Stopwatch]::StartNew()
  $container = $null
  try {
    $runArgs = @(
      'run', '-d', '--rm', '--pull', 'never', '--name', $restoreName,
      '--label', 'commerceflow.restore-verification=true',
      '--network', 'none', '--mount', 'type=tmpfs,target=/var/lib/postgresql/data,tmpfs-size=268435456',
      '-e', 'POSTGRES_USER=commerceflow_verify_restore', '-e', 'POSTGRES_DB=postgres',
      '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', $database.image
    )
    $container = docker @runArgs
    if ($LASTEXITCODE -ne 0) { throw "Could not create the restore instance for $db" }
    $state = (docker inspect --type container $container | ConvertFrom-Json)[0]
    if ($state.Name -cne "/$restoreName" -or $state.HostConfig.NetworkMode -cne 'none' -or
        @($state.Mounts | Where-Object Type -ne 'tmpfs').Count) {
      throw "Restore target is outside isolation for $db"
    }
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
      $oldPreference = $PSNativeCommandUseErrorActionPreference
      try {
        $PSNativeCommandUseErrorActionPreference = $false
        docker exec $container pg_isready -h 127.0.0.1 -U commerceflow_verify_restore -d postgres 2>$null | Out-Null
        $ready = $LASTEXITCODE -eq 0
      } finally {
        $PSNativeCommandUseErrorActionPreference = $oldPreference
      }
      if (!$ready) { Start-Sleep -Milliseconds 500 }
    } until ($ready -or [DateTime]::UtcNow -ge $deadline)
    if (!$ready) { throw "Temporary PostgreSQL did not start for $db" }

    docker cp (Join-Path $root "archives/$db.globals.sql") "${container}:/tmp/globals.sql"
    if ($LASTEXITCODE -ne 0) { throw "Could not copy the role archive for $db" }
    docker cp (Join-Path $root "archives/$db.dump") "${container}:/tmp/database.dump"
    if ($LASTEXITCODE -ne 0) { throw "Could not copy the database archive for $db" }
    docker exec $container psql -Xq -U commerceflow_verify_restore -d postgres -v ON_ERROR_STOP=1 -f /tmp/globals.sql
    if ($LASTEXITCODE -ne 0) { throw "Could not restore database roles for $db" }
    docker exec $container pg_restore -U commerceflow_verify_restore -d postgres --create --exit-on-error /tmp/database.dump
    if ($LASTEXITCODE -ne 0) { throw "Could not restore database data for $db" }
    $psql = @('psql', '-XqAt', '-U', 'commerceflow_verify_restore', '-d', $db, '-v', 'ON_ERROR_STOP=1')
    $countSql = "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema');"
    $tables = docker exec $container @psql -c $countSql
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect restored tables for $db" }
    $history = @($historySql | docker exec -i $container @psql)
    if ($LASTEXITCODE -ne 0 -or [int]$tables -lt 1 -or
        ($history -join '|') -cne ($database.flywayHistory -join '|')) {
      throw "Restored database structure or Flyway metadata did not match for $db"
    }
    Write-Host "Restored $db and verified its Flyway history ($tables tables)"
    [ordered]@{ database = $db; tables = [int]$tables; durationSeconds = [Math]::Round($started.Elapsed.TotalSeconds, 2) }
  } finally {
    if ($container) { docker stop --time 5 $container | Out-Null }
  }
}

$proofPath = Join-Path $root ('restore-verification-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.json')
[ordered]@{
  backup = $root
  startedAt = $verificationStarted
  verifiedAt = [DateTimeOffset]::UtcNow
  filesChecked = $verified.FileCount
  restoredDatabases = @($checked)
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $proofPath
Write-Host "Verified $($verified.FileCount) files and restored $(@($checked).Count) PostgreSQL databases"
Write-Output $proofPath
