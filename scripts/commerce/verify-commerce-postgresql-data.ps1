#requires -Version 7.0
param(
  [Parameter(Mandatory)][ValidateSet('ToPostgreSQL','ToMySQL','Compare')][string]$Direction,
  [Parameter(Mandatory)][string]$MySqlContainer,
  [Parameter(Mandatory)][string]$MySqlDatabase,
  [Parameter(Mandatory)][string]$PostgresContainer,
  [Parameter(Mandatory)][string]$PostgresDatabase,
  [Parameter(Mandatory)][string]$ManifestPath
)

$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'commerce-postgresql-data.mjs') $Direction $MySqlContainer $MySqlDatabase `
  $PostgresContainer $PostgresDatabase $ManifestPath
if ($LASTEXITCODE) { throw "Commerce database $Direction failed." }
