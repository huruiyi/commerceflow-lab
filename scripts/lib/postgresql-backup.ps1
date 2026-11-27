function Read-VerifiedPostgresqlBackup([string]$BackupDirectory) {
  $root = (Resolve-Path -LiteralPath $BackupDirectory).Path
  $signedFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
  foreach ($line in Get-Content -LiteralPath (Join-Path $root 'SHA256SUMS.txt')) {
    if ($line -cnotmatch '^([a-f0-9]{64})  ([a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)*(?:/[a-zA-Z0-9_-]+(?:\.[a-zA-Z0-9_-]+)*)*)$') {
      throw 'Invalid backup checksum entry'
    }
    $digest = $Matches[1]
    $relative = $Matches[2]
    if (!$signedFiles.Add($relative) -or
        (Get-FileHash -LiteralPath (Join-Path $root $relative) -Algorithm SHA256).Hash -ine $digest) {
      throw "Backup digest did not match for $relative"
    }
  }
  if (!$signedFiles.Contains('recovery-manifest.json')) {
    throw 'Verified backup manifest is required'
  }
  $manifest = Get-Content -LiteralPath (Join-Path $root 'recovery-manifest.json') -Raw | ConvertFrom-Json
  if ($manifest.project -cne 'fairy-commerceflow' -or @($manifest.databases).Count -ne 13 -or
      @($manifest.databases.database | Sort-Object -Unique).Count -ne 13) {
    throw 'Expected a complete daily PostgreSQL backup'
  }
  foreach ($database in $manifest.databases) {
    $db = $database.database
    if ($db -cnotmatch '^[a-z_]+$' -or $database.image -cnotmatch '^sha256:[a-f0-9]{64}$' -or
        !$signedFiles.Contains("archives/$db.dump") -or !$signedFiles.Contains("archives/$db.globals.sql")) {
      throw 'Invalid database backup entry'
    }
  }
  [pscustomobject]@{ Root = $root; FileCount = $signedFiles.Count; Manifest = $manifest }
}
