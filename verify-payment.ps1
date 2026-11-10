param(
  [string]$BaseUrl = 'http://localhost:14201'
)

$ErrorActionPreference = 'Stop'

function New-AuthenticatedSession([string]$Username) {
  $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{}
  $headers[$csrf.headerName] = $csrf.token
  $body = @{ username = $Username; password = 'demo-password' } | ConvertTo-Json
  $identity = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType 'application/json' -Body $body
  return @{ Session = $session; Headers = $headers; Identity = $identity }
}

function Assert-Equal($Expected, $Actual, [string]$Message) {
  if ($Expected -ne $Actual) {
    throw "$Message. Expected [$Expected], actual [$Actual]"
  }
}

function Assert-HttpStatus([int]$Expected, [scriptblock]$Action, [string]$Message) {
  try {
    & $Action | Out-Null
    throw "$Message. Expected HTTP $Expected but request succeeded"
  } catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Expected) {
      throw
    }
  }
}

$admin = New-AuthenticatedSession 'payment-admin'
if (-not $admin.Identity.authorities.Contains('MERCHANT_MANAGE')) {
  throw 'PAYMENT_ADMIN is missing MERCHANT_MANAGE'
}

$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$merchantBody = @{
  displayName = "Verification Merchant $suffix"
  notifyUrl = 'http://backend:8080/api/payment/notifications'
  merchantPublicKey = ''
} | ConvertTo-Json
$issued = Invoke-RestMethod "$BaseUrl/api/admin/merchants" -Method Post `
  -WebSession $admin.Session -Headers $admin.Headers -ContentType 'application/json' -Body $merchantBody
if ([string]::IsNullOrWhiteSpace($issued.appSecret)) {
  throw 'App Secret was not returned at initial issuance'
}

$created = @()
foreach ($channel in @('ALIPAY', 'WECHAT_PAY', 'UNION_PAY')) {
  $channelHeaders = @{
    'X-App-Id' = $issued.merchant.appId
    'X-Demo-App-Secret' = $issued.appSecret
  }
  $body = @{
    merchantRequestNo = "VERIFY-$channel-$suffix"
    channel = $channel
    subject = "05A-2 $channel verification"
    amount = 12.34
    currency = 'CNY'
    faultScenario = 'NONE'
  } | ConvertTo-Json
  $transaction = Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post `
    -Headers $channelHeaders -ContentType 'application/json' -Body $body
  Assert-Equal 'PENDING' $transaction.status "$channel create status"
  $created += $transaction
}
Assert-Equal 'REDIRECT' $created[0].actionType 'Alipay action'
Assert-Equal 'QR_CODE' $created[1].actionType 'WeChat Pay action'
Assert-Equal 'GATEWAY' $created[2].actionType 'UnionPay action'

$checkoutToken = $created[0].checkoutUrl.Split('/')[-1].Split('?')[0]
$paid = Invoke-RestMethod "$BaseUrl/api/checkout/$checkoutToken/complete?success=true" -Method Post
Assert-Equal 'SUCCESS' $paid.status 'Cashier completion status'

$closed = Invoke-RestMethod "$BaseUrl/api/admin/transactions/$($created[1].channelTransactionNo)/close" `
  -Method Post -WebSession $admin.Session -Headers $admin.Headers
Assert-Equal 'CLOSED' $closed.status 'Admin close status'

$replayHeaders = @{
  'X-App-Id' = $issued.merchant.appId
  'X-Demo-App-Secret' = $issued.appSecret
}
$replayBody = @{
  merchantRequestNo = $created[2].merchantRequestNo
  channel = 'UNION_PAY'
  subject = '05A-2 UNION_PAY verification'
  amount = 12.34
  currency = 'CNY'
  faultScenario = 'NONE'
} | ConvertTo-Json
$replay = Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post `
  -Headers $replayHeaders -ContentType 'application/json' -Body $replayBody
Assert-Equal $created[2].channelTransactionNo $replay.channelTransactionNo 'Idempotent replay transaction number'

$grantBody = @{ merchantId = $issued.merchant.id; permissionScope = 'OPERATE' } | ConvertTo-Json
Invoke-RestMethod "$BaseUrl/api/admin/users/2/merchants" -Method Post -WebSession $admin.Session `
  -Headers $admin.Headers -ContentType 'application/json' -Body $grantBody | Out-Null
$operator = New-AuthenticatedSession 'payment-operator'
$operatorTransactions = Invoke-RestMethod "$BaseUrl/api/admin/transactions" -WebSession $operator.Session
$currentMerchantTransactions = @($operatorTransactions | Where-Object { $_.merchantRequestNo -like "*-$suffix" })
if ($currentMerchantTransactions.Count -ne 3) {
  throw "Operator merchant-scoped transaction count. Expected [3], actual [$($currentMerchantTransactions.Count)]"
}

$auditor = New-AuthenticatedSession 'payment-auditor'
Assert-HttpStatus 403 {
  Invoke-RestMethod "$BaseUrl/api/admin/merchants" -WebSession $auditor.Session -ErrorAction Stop
} 'Auditor merchant management authorization'
Assert-HttpStatus 403 {
  Invoke-RestMethod "$BaseUrl/api/admin/transactions/$($created[2].channelTransactionNo)/close" `
    -Method Post -WebSession $auditor.Session -Headers $auditor.Headers -ErrorAction Stop
} 'Auditor close authorization'

$auditLogs = Invoke-RestMethod "$BaseUrl/api/admin/audit-logs" -WebSession $admin.Session
if ($auditLogs.Count -lt 3) {
  throw 'Expected login, merchant, and transaction audit records'
}

Write-Output "05A-2 verification passed: merchant=$($issued.merchant.merchantNo), transactions=3, RBAC=passed, audit=$($auditLogs.Count)"
