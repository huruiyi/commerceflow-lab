function Get-VerificationMigrationVersion([string]$Directory) {
  $versions = @(Get-ChildItem -LiteralPath $Directory -File -Filter 'V*__*.sql' |
    ForEach-Object { if ($_.Name -cmatch '^V([1-9][0-9]*)__') { [int]$Matches[1] } })
  if (-not $versions.Count) { throw "No versioned Flyway migrations in $Directory" }
  ($versions | Measure-Object -Maximum).Maximum
}
