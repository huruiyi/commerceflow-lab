#requires -Version 7.2
param(
  [switch]$SkipBuild,
  [int]$MaxWaitSeconds = 120,
  [string]$PlaywrightModule = '',
  [string]$ScreenshotDirectory = ''
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$composeFile = Join-Path $root 'infrastructure/compose/compose.invoice-commerce-verification.yaml'
$project = 'fairy-commerceflow-invoice-commerce-qa-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$callbackPath = '/internal/v1/invoice-receipts'
$paymentSecret = 'invoice-commerce-verification-payment-callback'
$invoiceCallbackSecret = 'invoice-commerce-verification-callback'
$utf8 = [Text.Encoding]::UTF8

function Compose {
  docker compose -p $project -f $composeFile @args
}

function Sql([string]$Statement) {
  $Statement | docker compose -p $project -f $composeFile exec -T commerce-db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -NB commerceflow'
}

function Assert-Equal($Actual, $Expected, [string]$Label) {
  if ($Actual -cne $Expected) { throw "${Label}: expected [$Expected], got [$Actual]" }
}

function Wait-For([scriptblock]$Condition, [string]$Label) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($MaxWaitSeconds)
  do {
    $result = & $Condition
    if ($result) { return $result }
    Start-Sleep -Milliseconds 250
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for $Label"
}

function Get-ServiceUrl([string]$Service, [int]$Port = 8080) {
  $binding = Compose port $Service $Port
  if ($binding -notmatch '^127\.0\.0\.1:\d+$') { throw "Unexpected endpoint for ${Service}: $binding" }
  return "http://$binding"
}

function Wait-Ready([string]$Service, [string]$Url) {
  $container = Compose ps -q -a $Service
  Wait-For {
    if ((docker inspect --format '{{.State.Running}}' $container) -ne 'true') { throw "$Service exited during startup" }
    try { (Invoke-RestMethod "$Url/actuator/health" -TimeoutSec 3).status -eq 'UP' } catch { $false }
  } "$Service health" | Out-Null
}

function Start-Commerce {
  Compose up -d --no-deps commerce | Out-Host
  $url = Get-ServiceUrl commerce
  Wait-Ready commerce $url
  return $url
}

function Invoke-Login([string]$Email) {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$commerceUrl/api/auth/csrf" -WebSession $session
  $body = @{email=$Email; password='demo-password'; rememberMe=$false} | ConvertTo-Json -Compress
  $options = @{Method='Post'; Body=$body; WebSession=$session; ContentType='application/json'
    Headers=@{$csrf.headerName = $csrf.token}}
  Invoke-RestMethod "$commerceUrl/api/auth/login" @options | Out-Null
  return $session
}

function Invoice([long]$Order, [string]$Method = 'Get', [int]$Expected = 200, $Session = $owner) {
  $options = @{ Method=$Method; SkipHttpErrorCheck=$true; TimeoutSec=10 }
  if ($Session) { $options.WebSession = $Session }
  if ($Method -eq 'Post' -and $Session) {
    $csrf = Invoke-RestMethod "$commerceUrl/api/auth/csrf" -WebSession $Session
    $options.Headers = @{$csrf.headerName = $csrf.token}
  }
  $response = Invoke-WebRequest "$commerceUrl/api/purchase-orders/$Order/invoices" @options
  Assert-Equal $response.StatusCode $Expected "$Method order $Order ($($response.Content))"
  if ($Expected -in (200, 202)) { return $response.Content | ConvertFrom-Json }
}

function Request-ConcurrentApplication {
  $csrf = Invoke-RestMethod "$commerceUrl/api/auth/csrf" -WebSession $owner
  $handler = [Net.Http.HttpClientHandler]::new()
  $handler.CookieContainer = $owner.Cookies
  $http = [Net.Http.HttpClient]::new($handler)
  $requests = [Collections.Generic.List[Net.Http.HttpRequestMessage]]::new()
  try {
    $tasks = foreach ($index in 1..2) {
      $message = [Net.Http.HttpRequestMessage]::new('POST', "$commerceUrl/api/purchase-orders/9101/invoices")
      $message.Headers.Add($csrf.headerName, $csrf.token)
      $requests.Add($message)
      $http.SendAsync($message)
    }
    foreach ($task in $tasks) {
      $response = $task.GetAwaiter().GetResult()
      try {
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Assert-Equal ([int]$response.StatusCode) 202 "Concurrent application ($content)"
        $content | ConvertFrom-Json
      } finally { $response.Dispose() }
    }
  } finally {
    foreach ($message in $requests) { $message.Dispose() }
    $http.Dispose()
  }
}

function Payment-Callback([string]$Resource, [hashtable]$Payload, [string]$Expected, [switch]$Replay) {
  $body = $Payload | ConvertTo-Json -Compress
  $mac = [Security.Cryptography.HMACSHA256]::HashData($utf8.GetBytes($paymentSecret), $utf8.GetBytes($body))
  $options = @{ Method='Post'; Body=$body; ContentType='application/json'; TimeoutSec=10
    Headers=@{'X-Callback-Signature'=[Convert]::ToBase64String($mac)} }
  $url = "$paymentUrl/api/$Resource-notifications/callback"
  Assert-Equal (Invoke-RestMethod $url @options).result $Expected "$Resource callback"
  if ($Replay) {
    Assert-Equal (Invoke-RestMethod $url @options).result 'ACKNOWLEDGED' "$Resource callback replay"
  }
}

function Refund([string]$Number, [decimal]$Amount, [string]$Status = 'SUCCEEDED') {
  $notice = @{
    notificationId=[guid]::NewGuid().ToString(); merchantRefundNo=$Number; channelRefundNo="CH-$Number"
    channel='ALIPAY'; status=$Status; amount=$Amount; currency='CNY'; failureReason=''
  }
  Payment-Callback 'refund' $notice "APPLIED_REFUND_$Status" -Replay
}

function Send-Receipt([string]$Request, [string]$Status) {
  $body = @{ eventId="evt-$Request"; result=@{requestId=$Request; status=$Status} } | ConvertTo-Json -Compress
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $canonical = @($timestamp, 'POST', $callbackPath, $body) -join [char]10
  $signature = [Convert]::ToHexString([Security.Cryptography.HMACSHA256]::HashData(
    $utf8.GetBytes($invoiceCallbackSecret), $utf8.GetBytes($canonical))).ToLowerInvariant()
  $options = @{Method='Post'; ContentType='application/json'; Body=$body; TimeoutSec=10
    Headers=@{'X-Invoice-Timestamp'=$timestamp; 'X-Invoice-Signature'=$signature} }
  Assert-Equal (Invoke-WebRequest "$commerceUrl$callbackPath" @options).StatusCode 204 'Signed receipt acknowledgement'
}

try {
  Write-Host "Isolated project $project"
  if (!$SkipBuild) {
    $targets = @('commerce', 'order', 'payment', 'invoice')
    if ($PlaywrightModule) { $targets += 'portal' }
    Compose build @targets | Out-Host
  }
  Compose up -d --wait commerce-db | Out-Host
  foreach ($schema in @('orders', 'payments', 'invoice_platform')) {
    Sql "CREATE DATABASE $schema; CREATE USER '$schema'@'%' IDENTIFIED BY 'invoice-commerce-verification-db';
      GRANT ALL ON $schema.* TO '$schema'@'%';"
  }
  $migrations = Join-Path $root 'services/commerce-service/src/main/resources/db/migration'
  $flywayArgs = @('--rm', '--network', "${project}_default", '--mount',
    "type=bind,source=$migrations,target=/flyway/sql,readonly", 'redgate/flyway:12.4.0',
    '-url=jdbc:mysql://commerce-db:3306/commerceflow?allowPublicKeyRetrieval=true&sslMode=DISABLED',
    '-user=root', '-password=invoice-commerce-verification-root', 'migrate')
  docker run @flywayArgs | Out-Host
  Sql "UPDATE fulfillment_write_admission SET write_owner='FULFILLMENT',frozen=TRUE WHERE singleton_id=1;"
  $grantArgs = @('exec', '-T', '-e', 'SOURCE_ROOT_PASSWORD=invoice-commerce-verification-root',
    '-e', 'RUNTIME_DATABASE_USERNAME=commerce_runtime', '-e', 'RUNTIME_DATABASE_PASSWORD=invoice-commerce-verification-db',
    '-e', 'FULFILLMENT_SOURCE_OWNER=FULFILLMENT', 'commerce-db', 'sh', '/verification/grant-runtime.sh')
  Compose @grantArgs | Out-Host
  Compose up -d redis kafka order payment invoice invoice-proxy | Out-Host
  $paymentUrl = Get-ServiceUrl payment
  $proxyUrl = Get-ServiceUrl invoice-proxy
  foreach ($service in @('order', 'payment', 'invoice')) { Wait-Ready $service (Get-ServiceUrl $service) }
  $commerceUrl = Start-Commerce
  Sql (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'invoice-commerce-fixtures.sql') -Raw)
  $owner = Invoke-Login 'demo@example.com'
  $other = Invoke-Login 'invoice-other@example.test'

  foreach ($payment in @(@(9101, 300), @(9102, 40), @(9103, 51), @(9104, 100))) {
    $notice = @{notificationId=[guid]::NewGuid().ToString(); channelTransactionNo="CH-P$($payment[0])"
      channel='ALIPAY'; status='SUCCESS'; amount=$payment[1]; currency='CNY'; failureReason=''}
    Payment-Callback 'payment' $notice 'APPLIED_PAYMENT_SUCCEEDED'
  }
  Invoice 9105 Post 409
  Invoice 9101 Get 401 -Session $null
  Invoice 9101 Post 404 -Session $other
  Refund 'INVOICE-R-PRE' 50
  Refund 'INVOICE-R-FAILED' 3 'FAILED'

  $apps = @(Request-ConcurrentApplication)
  Assert-Equal $apps.Count 2 'Both concurrent submissions returned'
  $app = $apps[0]
  Assert-Equal $apps[1].applicationNo $app.applicationNo 'Concurrent applications share the same number'
  Assert-Equal (Invoice 9101 Post 202).applicationNo $app.applicationNo 'Repeated application'
  Invoice 9101 Get 404 -Session $other
  $request = Wait-For {
    $id = Sql "SELECT request_id FROM invoice_attempts WHERE order_id=9101 AND kind='ISSUE' AND status='PENDING';"
    if ($id -and (Sql "SELECT status FROM invoice_platform.invoice_requests WHERE request_id='$id'") -eq 'ACCEPTED') { $id }
  } 'positive invoice accepted by simulator'
  $frozen = Sql "SELECT CONCAT_WS('|', gross_amount,discount_amount,freight_amount,paid_amount,amount)
    FROM invoice_attempts WHERE request_id='$request';"
  Assert-Equal $frozen '320.00|40.00|20.00|300.00|250.00' 'Frozen price'
  $included = Sql "SELECT refund_no FROM invoice_frozen_refunds WHERE request_id='$request';"
  Assert-Equal $included 'INVOICE-R-PRE' 'Only the already successful refund is frozen'
  $proxy = Invoke-RestMethod "$proxyUrl/fixture/state"
  Assert-Equal $proxy.$request.dropped $true 'First submission acknowledgement lost after channel acceptance'

  Refund 'INVOICE-R-DURING' 80
  $channelStatus = Sql "SELECT status FROM invoice_platform.invoice_requests WHERE request_id='$request';"
  Assert-Equal $channelStatus 'ACCEPTED' '80.00 refund confirmed while issue still processing at channel'
  Wait-For {
    (Sql "SELECT COUNT(*) FROM invoice_refund_facts WHERE order_id=9101 AND refund_no='INVOICE-R-DURING';") -eq '1'
  } 'successful refund observed during issuance' | Out-Null
  Compose kill -s SIGKILL commerce | Out-Host
  Wait-For {
    (Sql "SELECT status FROM invoice_platform.invoice_requests WHERE request_id='$request';") -eq 'SUCCEEDED'
  } 'simulator completes issue while commerce offline' | Out-Null
  Assert-Equal (Sql "SELECT status FROM invoice_attempts WHERE request_id='$request';") 'PENDING' 'Commerce stopped before issue confirmation'
  $commerceUrl = Start-Commerce
  $owner = Invoke-Login 'demo@example.com'
  $redRequest = Wait-For {
    $id = Sql "SELECT request_id FROM invoice_attempts WHERE reference_no='INVOICE-R-DURING';"
    if ($id -and (Sql "SELECT status FROM invoice_platform.invoice_requests WHERE request_id='$id'") -eq 'ACCEPTED') { $id }
  } 'first red invoice accepted'
  Compose kill -s SIGKILL commerce | Out-Host
  Wait-For {
    (Sql "SELECT status FROM invoice_platform.invoice_requests WHERE request_id='$redRequest';") -eq 'SUCCEEDED'
  } 'simulator completes first red while commerce offline' | Out-Null
  Assert-Equal (Sql "SELECT status FROM invoice_attempts WHERE request_id='$redRequest';") 'PENDING' 'Commerce stopped before red confirmation'
  $commerceUrl = Start-Commerce
  $owner = Invoke-Login 'demo@example.com'
  $during = Wait-For {
    $view = Invoice 9101
    if ($view.effectiveAmount -eq 170) { $view }
  } 'original invoice and 80.00 red recovered'
  Assert-Equal $during.issue.requestId $request 'Positive invoice keeps original request through restart'
  Assert-Equal $during.redAttempts[0].requestId $redRequest 'Red invoice keeps original request through restart'

  Refund 'INVOICE-R-AFTER' 100
  $null = Wait-For { (Invoice 9101).effectiveAmount -eq 70 } 'second red leaves 70.00'
  Refund 'INVOICE-R-FINAL' 70
  $final = Wait-For {
    $view = Invoice 9101
    if ($null -ne $view.effectiveAmount -and $view.effectiveAmount -eq 0) { $view }
  } 'full refund leaves zero'
  Assert-Equal $final.issue.invoiceNo $during.issue.invoiceNo 'Original invoice is stable'
  Assert-Equal $final.redAttempts.Count 3 'One red invoice for each post-freeze refund'
  Wait-For {
    (Sql "SELECT COUNT(*) FROM invoice_platform.invoice_receipts e JOIN invoice_attempts a ON a.request_id=e.request_id
      WHERE a.order_id=9101 AND e.delivered_at IS NOT NULL;") -eq '4'
  } 'all four real signed simulator receipts acknowledged' | Out-Null
  Send-Receipt $final.redAttempts[-1].requestId 'SUCCEEDED'
  Send-Receipt $final.redAttempts[-1].requestId 'SUCCEEDED'
  Send-Receipt $request 'FAILED'
  Send-Receipt $redRequest 'ACCEPTED'
  $viewAfterReplay = (Invoice 9101) | ConvertTo-Json -Depth 10 -Compress
  Assert-Equal $viewAfterReplay ($final | ConvertTo-Json -Depth 10 -Compress) 'Duplicate and stale receipts preserve confirmed results'
  $bad = Invoke-WebRequest "$commerceUrl$callbackPath" -Method Post -ContentType 'application/json' -Body '{}' -SkipHttpErrorCheck
  Assert-Equal $bad.StatusCode 401 'Unsigned invoice receipt'
  $proxy = Invoke-RestMethod "$proxyUrl/fixture/state"
  Assert-Equal $proxy.$request.post 1 'Only one positive submission reached channel despite lost response'
  if ($proxy.$request.get -lt 2) { throw 'Expected positive result query after the lost response' }
  Assert-Equal $proxy.$redRequest.post 1 'Only one red submission reached channel despite restart'

  Refund 'INVOICE-R-FULL-PRE' 40
  $null = Invoice 9102 Post 202
  $null = Wait-For { (Invoice 9102).status -eq 'EXHAUSTED' } 'already fully refunded order'
  Assert-Equal (Sql 'SELECT COUNT(*) FROM invoice_attempts WHERE order_id=9102') '0' 'No zero-value issue request'
  $null = Invoice 9103 Post 202
  $review = Wait-For {
    $view = Invoice 9103
    if ($view.reviewReason -eq 'ORDER_PAYMENT_AMOUNT_MISMATCH') { $view }
  } 'payment amount discrepancy'
  if ($review.reviewDetail -notmatch 'orderTotal=50\.00 .*paid=51\.00') {
    throw "Missing persisted reconciliation details: $($review.reviewDetail)"
  }
  Assert-Equal (Sql 'SELECT COUNT(*) FROM invoice_attempts WHERE order_id=9103') '0' 'Mismatched amount cannot issue'

  Invoke-RestMethod "$proxyUrl/fixture/fail-next-issue" -Method Post | Out-Null
  $retry = Invoice 9104 Post 202
  $failedId = Wait-For {
    $id = Sql "SELECT request_id FROM invoice_attempts WHERE order_id=9104 AND status='PENDING';"
    if ($id -and (Sql "SELECT status FROM invoice_platform.invoice_requests WHERE request_id='$id';") -eq 'ACCEPTED') { $id }
  } 'controlled failure request accepted'
  Refund 'INVOICE-R-RETRY' 25
  $issuedAfterFailure = Wait-For {
    $view = Invoice 9104
    if ($view.status -eq 'ISSUED') { $view }
  } 'new attempt issued after confirmed failure'
  Assert-Equal $issuedAfterFailure.applicationNo $retry.applicationNo 'Retries keep the original application'
  Assert-Equal $issuedAfterFailure.issue.amount 75 'Retry freezes the newly successful refund'
  Assert-Equal ($issuedAfterFailure.frozenRefundNumbers -join ',') 'INVOICE-R-RETRY' 'Retry inclusion snapshot'
  $failed = Sql "SELECT CONCAT_WS('|',amount,status,COALESCE(invoice_no,'-')) FROM invoice_attempts WHERE request_id='$failedId';"
  Assert-Equal $failed '100.00|FAILED|-' 'Failed attempt retained without ticket'
  Assert-Equal (Sql 'SELECT COUNT(*) FROM invoice_refund_facts WHERE order_id=9101') '4' 'Only successful refund facts recorded'

  $ledger = Sql @'
SELECT CONCAT_WS('|',p.payment_number,r.refund_number,r.amount,
  af.merchandise_amount,af.shipping_amount,CASE WHEN f.refund_no IS NOT NULL THEN 'FROZEN' ELSE 'RED' END,
  COALESCE(t.amount,0.00))
FROM payments.refund_records r
JOIN payments.payment_records p ON p.id=r.payment_id
JOIN commerceflow.line_after_sales af ON af.id=r.after_sale_id AND af.refund_number=r.refund_number
  AND af.merchandise_amount + af.shipping_amount = r.amount
JOIN commerceflow.invoice_refund_facts rf
  ON rf.refund_no=r.refund_number AND rf.order_id=r.order_id AND rf.payment_no=p.payment_number
  AND rf.amount=r.amount AND rf.currency=r.currency
LEFT JOIN commerceflow.invoice_frozen_refunds f ON f.order_id=rf.order_id AND f.refund_no=rf.refund_no
LEFT JOIN commerceflow.invoice_attempts a ON a.order_id=rf.order_id AND a.reference_no=rf.refund_no
  AND a.kind='RED' AND a.status='SUCCEEDED'
LEFT JOIN invoice_platform.invoice_tickets t ON t.request_id=a.request_id AND t.invoice_no=a.invoice_no
  AND t.original_invoice_no=a.original_invoice_no AND t.amount=a.amount AND t.currency=a.currency
WHERE r.order_id=9101 AND r.status='SUCCEEDED'
ORDER BY r.succeeded_at, r.id;
'@
  $expected = @(
    'INVOICE-P9101|INVOICE-R-PRE|50.00|50.00|0.00|FROZEN|0.00',
    'INVOICE-P9101|INVOICE-R-DURING|80.00|80.00|0.00|RED|80.00',
    'INVOICE-P9101|INVOICE-R-AFTER|100.00|100.00|0.00|RED|100.00',
    'INVOICE-P9101|INVOICE-R-FINAL|70.00|50.00|20.00|RED|70.00'
  )
  Assert-Equal ($ledger -join [char]10) ($expected -join [char]10) 'Line-by-line reconciliation'
  $balance = Sql "SELECT CONCAT_WS('|',t.amount,t.amount-SUM(r.amount),COUNT(r.invoice_no))
    FROM invoice_platform.invoice_tickets t JOIN invoice_platform.invoice_tickets r ON r.original_invoice_no=t.invoice_no
    WHERE t.request_id='$request' GROUP BY t.invoice_no,t.amount;"
  Assert-Equal $balance '250.00|0.00|3' 'Channel ticket net balance'
  $version = Sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='66'"
  Assert-Equal $version '1' 'Commerce invoice journal migrated'

  Write-Host "Order INVOICE-O9101, payment INVOICE-P9101, application $($app.applicationNo)"
  Write-Host "Issue $request -> $($final.issue.invoiceNo), order 320.00 - 40.00 + 20.00 = paid 300.00 CNY"
  Write-Host 'payment | refund | amount | merchandise | freight | disposition | red invoice amount'
  $ledger | Out-Host
  foreach ($red in $final.redAttempts) { Write-Host "$($red.referenceNo) -> $($red.requestId) -> $($red.invoiceNo)" }
  Write-Host 'PASS: issue 250.00 CNY, red total 250.00 CNY, effective 0.00 CNY. Lost response, replay and two process restarts verified.'
  if ($PlaywrightModule) {
    Sql (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'invoice-portal-fixtures.sql') -Raw)
    foreach ($payment in @(@(9201, 120), @(9202, 180), @(9203, 71), @(9204, 90))) {
      $notice = @{notificationId=[guid]::NewGuid().ToString(); channelTransactionNo="CH-P$($payment[0])"
        channel='ALIPAY'; status='SUCCESS'; amount=$payment[1]; currency='CNY'; failureReason=''}
      Payment-Callback 'payment' $notice 'APPLIED_PAYMENT_SUCCEEDED'
    }
    Compose up -d portal | Out-Host
    $portalUrl = Get-ServiceUrl portal 80
    Wait-For {
      try { (Invoke-WebRequest "$portalUrl/healthz" -TimeoutSec 3).StatusCode -eq 200 } catch { $false }
    } 'Angular portal health' | Out-Null
    $browserArgs = @((Join-Path $PSScriptRoot 'invoice-portal-browser.cjs'), $portalUrl, $PlaywrightModule,
      $paymentUrl, $proxyUrl, $ScreenshotDirectory)
    node @browserArgs
    $audit = Sql "SELECT GROUP_CONCAT(CONCAT_WS(':',order_id,actor_id,prior_reason) ORDER BY order_id)
      FROM invoice_review_actions WHERE order_id IN (9203,9204);"
    Assert-Equal $audit '9203:92003:ORDER_PAYMENT_AMOUNT_MISMATCH,9204:92003:ISSUE_RETRIES_EXHAUSTED' 'Audited review actions'
    $tickets = Sql "SELECT GROUP_CONCAT(CONCAT_WS(':',a.order_id,t.merchant_id,t.amount) ORDER BY a.order_id)
      FROM invoice_applications a JOIN invoice_attempts t ON a.order_id=t.order_id
      WHERE a.order_id IN (9201,9202,9204) AND t.kind='ISSUE' AND t.status='SUCCEEDED';"
    Assert-Equal $tickets '9201:92001:120.00,9202:92002:180.00,9204:92002:90.00' 'Tickets and frozen merchant scope'
    Write-Host "Browser ticket ownership $tickets"
    Write-Host "Browser operator audit $audit"
  }
} catch {
  Compose logs --tail 35 commerce payment invoice invoice-proxy | Out-Host
  throw
} finally {
  Compose down | Out-Host
  Write-Host "Retained isolated MySQL volume: ${project}_invoice-commerce-data"
}
