param([string]$BaseUrl = 'http://localhost:18089')
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $ProjectRoot
if ($env:COMPOSE_PROJECT_NAME -notlike 'fairy-commerceflow-shadow-?*' -or
    @(docker compose config --services) -notcontains 'legacy-shadow-access') {
  throw 'Use compose.yaml and infrastructure/compose/compose.legacy-shadow.yaml in an isolated fairy-commerceflow-shadow-* project.'
}

function Fail([string]$Message) {
  throw "Order shadow verification failed: $Message"
}

docker compose up -d --build --wait order-service | Out-Null
$before = Invoke-RestMethod "$BaseUrl/internal/v1/order-shadow/watermark"
$snapshot = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/order-shadow/snapshot"
if ($snapshot.generation -le $before.generation) { Fail 'repeatable snapshot did not advance generation' }

$sourceSequence = [long](docker compose exec -T commerce-db mysql -uroot `
  -plocal-root-password --batch --skip-column-names commerceflow `
  -e 'UPDATE shop_orders SET status=status WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM shop_orders) s); SELECT last_sequence FROM order_shadow_change_clock WHERE singleton_id=1;' |
  Select-Object -Last 1)
$deadline = (Get-Date).AddSeconds(30)
do {
  Start-Sleep -Seconds 2
  $watermark = Invoke-RestMethod "$BaseUrl/internal/v1/order-shadow/watermark"
} until ($watermark.sourceSequence -ge $sourceSequence -or (Get-Date) -gt $deadline)
if ($watermark.sourceSequence -lt $sourceSequence) { Fail 'incremental catch-up did not reach source watermark' }

$auditDeadline = (Get-Date).AddSeconds(20)
do {
  $audit = Invoke-RestMethod -Method Post "$BaseUrl/internal/v1/order-shadow/audit"
  $mismatches = @($audit | Where-Object { $_.status -ne 'MATCH' })
  if ($mismatches.Count -eq 0 -and $audit.Count -eq 7) { break }
  Start-Sleep -Seconds 2
} until ((Get-Date) -gt $auditDeadline)
if ($mismatches.Count -ne 0 -or $audit.Count -ne 7) { Fail 'source/target audit is not clean' }
$openOrphans = [long](docker compose exec -T order-db mysql -uorders -porders-password `
  --batch --skip-column-names orders -e "SELECT COUNT(*) FROM order_shadow_orphan_quarantine WHERE status='OPEN'")
if ($openOrphans -ne 0) { Fail "$openOrphans orphan rows remain open" }

$command = @{
  schemaVersion = 1; commandId = [guid]::NewGuid().ToString(); commandType = 'CANCEL'
  occurredAt = [DateTime]::UtcNow.ToString('o'); producer = 'commerce-service'; orderId = 1
  actorId = 1; expectedStatus = 'PENDING_PAYMENT'; reason = 'closed-gate-check'
  correlationId = 'closed-gate'; causationId = 'closed-gate'
  traceparent = '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01'
} | ConvertTo-Json
$target = '/internal/v1/orders/commands'
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$requestId = [guid]::NewGuid().ToString()
$bodyBytes = [Text.Encoding]::UTF8.GetBytes($command)
$contentHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bodyBytes)).ToLowerInvariant()
$canonical = "POST`n$target`n$timestamp`n$requestId`n$contentHash"
$hmac = [Security.Cryptography.HMACSHA256]::new(
  [Text.Encoding]::UTF8.GetBytes('demo-order-internal-secret'))
try {
  $signature = [Convert]::ToHexString(
    $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant()
} finally { $hmac.Dispose() }
$headers = @{
  'X-CommerceFlow-Service'='commerce-service'; 'X-CommerceFlow-Timestamp'=$timestamp
  'X-CommerceFlow-Request-Id'=$requestId; 'X-CommerceFlow-Content-SHA256'=$contentHash
  'X-CommerceFlow-Signature'=$signature
}
try {
  Invoke-WebRequest -Method Post "$BaseUrl$target" -Headers $headers `
    -ContentType 'application/json' -Body $command | Out-Null
  Fail 'remote V1 traffic gate accepted a business command'
} catch {
  if ([int]$_.Exception.Response.StatusCode -ne 503) { throw }
}

Write-Host (("Order shadow verification passed: generation {0}, watermark {1}, " +
  "7 table audits MATCH, no open orphan, remote V1 gate closed.") -f `
  $snapshot.generation, $watermark.sourceSequence)
