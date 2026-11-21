param(
  [string]$BaseUrl,
  [switch]$SkipBuild,
  [string]$ProjectName,
  [ValidateRange(1, 40000)][int]$PortOffset = 20000
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'lib/regression-runner.ps1') @PSBoundParameters
