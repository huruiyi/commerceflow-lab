param([string]$BaseUrl = 'http://localhost:14200')
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/verify-payment-notifications.ps1" -BaseUrl $BaseUrl
if (-not $?) { throw 'Payment notification verification failed' }
$topics = docker compose --project-directory $ProjectRoot exec -T kafka-broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka-broker:9092 --list
foreach ($required in @('payment-events','order-events','payment-callback-events','payment-reconciliation-events','merchant-notification-events','payment-events-dlq')) {
  if ($topics -notcontains $required) { throw "Kafka topic missing: $required" }
}
$deadline = (Get-Date).AddSeconds(40)
do {
  $row = docker compose --project-directory $ProjectRoot exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password commerceflow -e "SELECT CONCAT(status,':',attempts) FROM payment_outbox ORDER BY id DESC LIMIT 1" 2>$null
  $consumed = docker compose --project-directory $ProjectRoot exec -T commerce-db mysql -N -ucommerceflow -pcommerceflow-password commerceflow -e "SELECT COUNT(*) FROM kafka_consumed_event" 2>$null
  if ($row -match '^PUBLISHED:' -and [int]$consumed -gt 0) { break }
  Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ($row -notmatch '^PUBLISHED:' -or [int]$consumed -lt 1) { throw "Outbox was not published and consumed: outbox=$row consumed=$consumed" }
Write-Host "05A-5 verification passed: outbox=$row consumed-events=$consumed" -ForegroundColor Green
