#requires -Version 7.0
param(
  [Parameter(Mandatory)][uri]$BaseUrl,
  [Parameter(Mandatory)][string]$Dataset,
  [Parameter(Mandatory)][string]$Version,
  [Parameter(Mandatory)][uri]$SourceUrl,
  [Parameter(Mandatory)][string]$SourceRevision,
  [Parameter(Mandatory)][string]$CsvPath,
  [PSCredential]$AdminCredential,
  [switch]$Publish
)

$ErrorActionPreference = 'Stop'
if ($BaseUrl.Scheme -notin 'http', 'https' -or (!$BaseUrl.IsLoopback -and $BaseUrl.Scheme -ne 'https')) {
  throw 'Use a loopback or HTTPS admin portal origin.'
}
$origin = $BaseUrl.GetLeftPart([UriPartial]::Authority)
$file = Get-Item -LiteralPath $CsvPath
if ($file.PSIsContainer -or $file.Length -eq 0) {
  throw 'Select a nonempty CSV file.'
}
if (!$AdminCredential) {
  if (!$BaseUrl.IsLoopback) {
    throw 'Supply an administrator credential for a remote portal.'
  }
  $AdminCredential = [PSCredential]::new('admin@example.com', ('demo-password' | ConvertTo-SecureString -AsPlainText -Force))
}

$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Get-CsrfHeader {
  $csrf = Invoke-RestMethod "$origin/api/auth/csrf" -WebSession $session -TimeoutSec 10 -MaximumRedirection 0
  @{ $csrf.headerName = $csrf.token }
}

$login = @{
  email = $AdminCredential.UserName
  password = $AdminCredential.GetNetworkCredential().Password
  rememberMe = $false
}
$user = Invoke-RestMethod "$origin/api/auth/login" -WebSession $session -Headers (Get-CsrfHeader) -Method Post `
  -ContentType 'application/json' -Body ($login | ConvertTo-Json -Compress) -TimeoutSec 15 -MaximumRedirection 0
if ($user.user.role -cne 'ADMIN') {
  throw 'Reference data imports require a platform administrator.'
}

$endpoint = "$origin/api/admin/reference-data/datasets/$([uri]::EscapeDataString($Dataset))/versions"
$release = Invoke-RestMethod $endpoint -WebSession $session -Headers (Get-CsrfHeader) -Method Post -Form @{
  file = $file
  version = $Version
  sourceUrl = $SourceUrl.AbsoluteUri
  sourceRevision = $SourceRevision
} -TimeoutSec 240 -MaximumRedirection 0
$localSha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($release.sha256 -cne $localSha256) {
  throw 'The staged version checksum did not match the submitted file.'
}

$comparison = Invoke-RestMethod "$endpoint/$($release.id)/comparison" -WebSession $session -TimeoutSec 120 `
  -MaximumRedirection 0
if ($Publish) {
  $body = @{ expectedCurrentReleaseId = $comparison.currentReleaseId } | ConvertTo-Json -Compress
  $release = Invoke-RestMethod "$endpoint/$($release.id)/publish" -WebSession $session -Headers (Get-CsrfHeader) `
    -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30 -MaximumRedirection 0
}
[pscustomobject]@{
  Dataset = $release.datasetKey
  Version = $release.version
  Id = $release.id
  RowCount = $release.rowCount
  Sha256 = $release.sha256
  PublishedAt = $release.publishedAt
  ComparedWith = $comparison.currentReleaseId
  Changes = $comparison.counts
}
