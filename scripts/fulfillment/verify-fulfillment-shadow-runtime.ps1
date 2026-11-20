param([string]$BaseUrl = 'http://127.0.0.1:18091')
$ErrorActionPreference = 'Stop'
$secret = if ($env:FULFILLMENT_INTERNAL_SECRET) { $env:FULFILLMENT_INTERNAL_SECRET } else { 'demo-fulfillment-shadow-secret' }

function Invoke-Shadow([string]$Suffix, [string]$Body, [int]$Expected) {
  $path = "/internal/v1/fulfillment$Suffix"
  $encoding = [Text.Encoding]::UTF8
  $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($encoding.GetBytes($Body))).ToLowerInvariant()
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $requestId = [guid]::NewGuid().ToString()
  $canonical = @('POST', $path, $timestamp, $requestId, $hash) -join [char]10
  $mac = [Security.Cryptography.HMACSHA256]::new($encoding.GetBytes($secret))
  try { $signature = [Convert]::ToHexString($mac.ComputeHash($encoding.GetBytes($canonical))).ToLowerInvariant() }
  finally { $mac.Dispose() }
  $headers = @{
    'X-CommerceFlow-Timestamp' = $timestamp
    'X-CommerceFlow-Request-Id' = $requestId
    'X-CommerceFlow-Content-SHA256' = $hash
    'X-CommerceFlow-Signature' = $signature
  }
  $response = Invoke-WebRequest "$BaseUrl$path" -Method Post -ContentType application/json -Body $Body -Headers $headers -SkipHttpErrorCheck -TimeoutSec 20

  if ($response.StatusCode -ne $Expected) { throw "$Suffix returned $($response.StatusCode), expected $Expected" }
  if ($Expected -eq 200) { return ($response.Content | ConvertFrom-Json) }
}

$unsigned = Invoke-WebRequest "$BaseUrl/internal/v1/fulfillment/shadow/status" -SkipHttpErrorCheck -TimeoutSec 15
if ($unsigned.StatusCode -ne 401) { throw "Unsigned shadow status returned $($unsigned.StatusCode)" }
Invoke-Shadow /commands '{"version":1}' 503
$before = Invoke-Shadow /shadow/snapshot '{}' 200
$after = Invoke-Shadow /shadow/snapshot '{}' 200
if ($after.watermark.sourceSequence -ne $before.watermark.sourceSequence) {
  throw 'Source changed during the two snapshots. Rerun to compare at one watermark.'
}
if ($after.watermark.generation -ne ($before.watermark.generation + 1) -or $after.appliedRows -ne 0 -or
    $after.openOrphans -ne 0 -or $after.tables.Count -ne 6 -or
    (ConvertTo-Json -InputObject $before.tables -Compress) -ne (ConvertTo-Json -InputObject $after.tables -Compress)) {
  throw 'Repeated snapshot or table digest validation failed'
}
Write-Output "Fulfillment shadow verified at watermark $($after.watermark.sourceSequence), six tables, write gate 503"
