#requires -Version 7.0
param(
  [Parameter(Mandatory)][uri]$BaseUrl,
  [string]$Dataset = 'cn-administrative-divisions',
  [Parameter(Mandatory)][string]$Version,
  [Parameter(Mandatory)][int]$ExpectedRows,
  [string]$ChildCode = '110101001',
  [string]$ParentCode = '110101'
)

$ErrorActionPreference = 'Stop'
$origin = $BaseUrl.GetLeftPart([UriPartial]::Authority)
$key = [uri]::EscapeDataString($Dataset)
$endpoint = "$origin/api/reference-data/datasets/$key"
$release = Invoke-RestMethod "$endpoint/versions/current" -TimeoutSec 15
if ($release.version -cne $Version -or $release.rowCount -ne $ExpectedRows -or !$release.publishedAt) {
  throw 'Unexpected published reference data version or row count.'
}
$child = [uri]::EscapeDataString($ChildCode)
$parent = [uri]::EscapeDataString($ParentCode)
$node = Invoke-RestMethod "$endpoint/nodes/${child}?releaseId=$($release.id)" -TimeoutSec 15
if ($node.code -cne $ChildCode -or $node.parentCode -cne $ParentCode) {
  throw 'The published node lost its source parent.'
}
$first = Invoke-RestMethod "$endpoint/nodes?releaseId=$($release.id)&parentCode=$parent&size=1" -TimeoutSec 15
if ($first.releaseId -ne $release.id -or $first.rows.Count -ne 1 -or $first.rows[0].parentCode -cne $ParentCode) {
  throw 'The children query returned another release or parent.'
}
if ($first.nextAfter) {
  $after = [uri]::EscapeDataString($first.nextAfter)
  $next = Invoke-RestMethod "$endpoint/nodes?releaseId=$($release.id)&parentCode=$parent&size=1&after=$after" -TimeoutSec 15
  if ($next.rows.Count -ne 1 -or $next.rows[0].code -ceq $first.rows[0].code) {
    throw 'The published children cursor repeated or dropped a row.'
  }
}
$anonymous = Invoke-WebRequest "$origin/api/admin/reference-data/datasets/$key/versions" -SkipHttpErrorCheck -TimeoutSec 15
if ($anonymous.StatusCode -ne 401) {
  throw 'Expected the version maintenance endpoint to require authentication.'
}
Write-Host "Reference data verified: $Version, $ExpectedRows rows, $($node.name), pinned reads and admin authentication."
