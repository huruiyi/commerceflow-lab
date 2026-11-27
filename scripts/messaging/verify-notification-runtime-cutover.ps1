#requires -Version 7.0
param(
  [string]$BaseUrl = 'http://localhost:18081',
  [ValidateRange(10, 600)][int]$MaxWaitSeconds = 120,
  [Parameter(Mandatory)][string]$ProjectName,
  [ValidateSet('PostgreSQL')][string]$Database = 'PostgreSQL'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/checkout-verification.ps1')
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Docker {
  & docker @args
  if ($LASTEXITCODE -ne 0) { throw "Docker $($args[0]) failed ($LASTEXITCODE)" }
}

function Get-Container([string]$Service, [switch]$Optional) {
  $ids = @(Invoke-Docker ps -aq --filter "label=com.docker.compose.project=$ProjectName" `
    --filter "label=com.docker.compose.service=$Service")
  $instances = @()
  if ($ids.Count -gt 0) {
    $instances = @(Invoke-Docker inspect --type container @ids | ConvertFrom-Json |
      Where-Object { $_.Config.Labels.'com.docker.compose.oneoff' -ine 'true' })
  }
  if ($Optional) { return $instances }
  if ($instances.Count -ne 1 -or !$instances[0].State.Running -or
      $instances[0].State.Health.Status -ne 'healthy') {
    throw "Expected exactly one healthy $Service in $ProjectName"
  }
  return $instances[0]
}

function Get-ContainerEnv($Container, [string]$Key) {
  $line = @($Container.Config.Env | Where-Object { $_.StartsWith("$Key=") })
  if ($line.Count -eq 1) { return $line[0].Substring($Key.Length + 1) }
}

function Assert-Target($From, [string]$Address, $Target) {
  $matches = @($networkMembers | Where-Object {
    foreach ($network in $From.NetworkSettings.Networks.PSObject.Properties) {
      $connection = $_.NetworkSettings.Networks.PSObject.Properties[$network.Name]
      if ($connection -and $connection.Value.IPAddress -and
          ($Address -cin @($connection.Value.Aliases) -or
           $Address -cin @($connection.Value.DNSNames) -or $Address -ceq $_.Name.TrimStart('/'))) {
        return $true
      }
    }
    return $false
  })
  if ($matches.Count -ne 1 -or $matches[0].Id -cne $Target.Id) {
    throw "$Address does not uniquely resolve to $($Target.Name) from $($From.Name)"
  }
}

function Notification-Sql([string]$Query) {
  $passwordKey = 'PGPASSWORD'
  $previous = [Environment]::GetEnvironmentVariable($passwordKey)
  try {
    [Environment]::SetEnvironmentVariable($passwordKey, $dbPassword)
    Invoke-Docker exec -e $passwordKey $db.Id psql -h 127.0.0.1 -U $dbUser -d notification -XAtq -v ON_ERROR_STOP=1 -c $Query
  } finally {
    [Environment]::SetEnvironmentVariable($passwordKey, $previous)
  }
}

function Read-Notifications {
  $items = Invoke-RestMethod "$BaseUrl/api/notifications" -WebSession $session -TimeoutSec 10
  return $items
}

$endpoint = [uri]$BaseUrl
if (!$endpoint.IsLoopback -or $endpoint.Scheme -cne 'http' -or $endpoint.AbsolutePath -cne '/' -or
    $endpoint.Query -or $endpoint.Fragment -or $endpoint.UserInfo) {
  throw 'BaseUrl must be the loopback HTTP origin of the selected commerce container'
}

$appService = 'notification-service-postgresql'
$dbService = 'notification-db-postgresql'
$app = Get-Container $appService
$db = Get-Container $dbService
$commerce = Get-Container 'commerce-service'
$kafka = Get-Container 'kafka-broker'
$endpointHosts = if ($endpoint.DnsSafeHost -ieq 'localhost') { @('127.0.0.1', '::1') } else { @($endpoint.DnsSafeHost) }
$bindings = @($commerce.NetworkSettings.Ports.'8080/tcp' | Where-Object {
  $_.HostPort -ceq [string]$endpoint.Port -and
    ($_.HostIp -cin $endpointHosts -or
     ($_.HostIp -ceq '0.0.0.0' -and $endpointHosts -match '^127\.') -or
     ($_.HostIp -ceq '::' -and $endpointHosts -ccontains '::1'))
})
if ($bindings.Count -eq 0) {
  throw "BaseUrl does not match a published commerce-service binding in $ProjectName"
}

$networks = @(@($commerce, $app) | ForEach-Object { $_.NetworkSettings.Networks.PSObject.Properties.Name } |
  Sort-Object -Unique)
$memberIds = @($networks | ForEach-Object { Invoke-Docker ps -q --no-trunc --filter "network=$_" } |
  Sort-Object -Unique)
$networkMembers = @(Invoke-Docker inspect --type container @memberIds | ConvertFrom-Json)
$jdbcEngine = $Database.ToLowerInvariant()
$databasePort = 5432
$databasePattern = '^jdbc:' + $jdbcEngine + '://([^:/?]+):' + $databasePort + '/notification(?:\?.*)?$'
$profile = Get-ContainerEnv $app 'SPRING_PROFILES_ACTIVE'
if ((Get-ContainerEnv $app 'DATABASE_URL') -cnotmatch $databasePattern -or
    (($profile -split ',') -contains 'postgresql') -ne ($Database -eq 'PostgreSQL')) {
  throw "Notification JDBC binding/profile does not match $Database in $ProjectName"
}
Assert-Target $app $Matches[1] $db
if ((Get-ContainerEnv $commerce 'NOTIFICATION_SERVICE_BASE_URL') -cnotmatch '^http://([^:/?]+):8080/?$') {
  throw 'Unexpected commerce notification proxy URL'
}
Assert-Target $commerce $Matches[1] $app
if ((Get-ContainerEnv $app 'KAFKA_BOOTSTRAP_SERVERS') -cnotmatch '^([^:/?]+):9092$') {
  throw 'Unexpected notification Kafka bootstrap URL'
}
Assert-Target $app $Matches[1] $kafka
$dbUser = Get-ContainerEnv $app 'DATABASE_USERNAME'
$dbPassword = Get-ContainerEnv $app 'DATABASE_PASSWORD'
if (@(Notification-Sql 'SELECT COUNT(*) FROM customer_notifications;')[0] -notmatch '^\d+$') {
  throw 'Cannot verify the schema using the notification database application account'
}
Write-Host "Verified $ProjectName targets: $appService -> $dbService ($Database), commerce proxy and Kafka."

$csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -SessionVariable session -TimeoutSec 10
$headers = @{ $csrf.headerName = $csrf.token }
$body = @{ email = 'demo@example.com'; password = 'demo-password'; rememberMe = $false } | ConvertTo-Json -Compress
Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
  -Headers $headers -ContentType application/json -Body $body -TimeoutSec 10 | Out-Null
$selected = Invoke-RestMethod "$BaseUrl/api/addresses/selected" -WebSession $session -TimeoutSec 10
if (!$selected.addressId) {
  $address = Invoke-RestMethod "$BaseUrl/api/addresses" -Method Post -WebSession $session -Headers $headers `
    -ContentType application/json -TimeoutSec 10 `
    -Body '{"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN","stateProvince":"Shanghai","city":"Shanghai","district":"Pudong","addressLine1":"100 Learning Road","postalCode":"200120","label":"Lab","isDefault":true}'
  Invoke-RestMethod "$BaseUrl/api/addresses/$($address.id)/select" -Method Put -WebSession $session `
    -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 10 | Out-Null
}
$cartItem = Invoke-RestMethod "$BaseUrl/api/cart/items" -Method Post -WebSession $session -Headers $headers `
  -ContentType application/json -Body '{"productId":1,"quantity":1,"note":"notification-runtime-acceptance"}' `
  -TimeoutSec 10
$order = Submit-VerifiedCheckout $BaseUrl $session $headers @{ itemIds = @($cartItem.id) }
Invoke-RestMethod "$BaseUrl/api/purchase-orders/$($order.id)/cancel" -Method Post -WebSession $session `
  -Headers $headers -ContentType application/json -Body '{"reason":"notification-runtime-acceptance"}' `
  -TimeoutSec 10 | Out-Null

$deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
do {
  $items = @(Read-Notifications | Where-Object { $_.orderId -eq $order.id -and $_.notificationType -ceq 'ORDER_CANCELLED' })
  if ($items.Count -gt 0) { break }
  Start-Sleep -Seconds 1
} while ([datetimeoffset]::UtcNow -lt $deadline)
if ($items.Count -ne 1 -or !$items[0].eventId) { throw "Expected one cancellation notification for order $($order.id)" }
$eventId = $items[0].eventId
$encodedEventId = [uri]::EscapeDataString($eventId)
Invoke-RestMethod "$BaseUrl/api/notifications/$encodedEventId/read" -Method Patch `
  -WebSession $session -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 10 | Out-Null
$literal = $eventId.Replace("'", "''")
$readQuery = "SELECT CONCAT(status, '/', read_at) FROM customer_notifications WHERE event_id='$literal';"
$read = Notification-Sql $readQuery
if ($read -notmatch '^READ/.+') { throw 'The selected notification database did not persist READ' }
Invoke-RestMethod "$BaseUrl/api/notifications/$encodedEventId/read" -Method Patch `
  -WebSession $session -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 10 | Out-Null
if ((Notification-Sql $readQuery) -cne $read) {
  throw 'Replaying the read request changed the first read timestamp'
}

try {
  Invoke-Docker stop $app.Id | Out-Null
  $response = Invoke-WebRequest "$BaseUrl/api/notifications" -WebSession $session -SkipHttpErrorCheck -TimeoutSec 15
  if ($response.StatusCode -ne 503) { throw "Notification outage returned HTTP $($response.StatusCode), expected 503" }
  $commerceHealth = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 10
  if ($commerceHealth.status -cne 'UP') { throw 'Core commerce became unhealthy' }
} finally {
  Invoke-Docker start $app.Id | Out-Null
  $deadline = [datetimeoffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $health = Invoke-Docker inspect --format '{{.State.Health.Status}}' $app.Id
    if ($health -ceq 'healthy') { break }
    Start-Sleep -Seconds 2
  } while ([datetimeoffset]::UtcNow -lt $deadline)
  if ($health -cne 'healthy') { throw "$appService did not recover in $ProjectName" }
}

$recovered = @(Read-Notifications | Where-Object eventId -CEQ $eventId)
if ($recovered.Count -ne 1 -or $recovered[0].status -cne 'READ') {
  throw 'The recovered notification proxy did not return the same read notification'
}
Write-Host "Notification runtime passed ($Database, order $($order.id)): Kafka, signed proxy, READ, outage 503 and recovery." -ForegroundColor Green
