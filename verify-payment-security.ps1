param([string]$BaseUrl = 'http://localhost:14201')

$ErrorActionPreference = 'Stop'

function Assert-Equal($Expected, $Actual, [string]$Message) {
  if ($Expected -ne $Actual) { throw "$Message. Expected [$Expected], actual [$Actual]" }
}

function Assert-HttpStatus([int]$Expected, [scriptblock]$Action, [string]$Message) {
  try { & $Action | Out-Null; throw "$Message. Expected HTTP $Expected but request succeeded" }
  catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $Expected) { throw }
  }
}

function New-AuthenticatedSession {
  $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
  $csrf = Invoke-RestMethod "$BaseUrl/api/auth/csrf" -WebSession $session
  $headers = @{ $csrf.headerName = $csrf.token }
  $body = @{ username = 'payment-admin'; password = 'demo-password' } | ConvertTo-Json -Compress
  $identity = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType 'application/json' -Body $body
  @{ Session = $session; Headers = $headers; Identity = $identity }
}

function Get-Sha256Hex([string]$Value) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
  [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function New-SignedHeaders([string]$AppId, [string]$Secret, [string]$Method, [string]$Target,
    [string]$Body, [Nullable[long]]$Timestamp = $null, [string]$Nonce = '') {
  if ($null -eq $Timestamp) { $Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
  if ([string]::IsNullOrEmpty($Nonce)) { $Nonce = [Guid]::NewGuid().ToString('N') }
  $requestId = [Guid]::NewGuid().ToString('N')
  $hash = Get-Sha256Hex $Body
  $canonical = "$Method`n$Target`n$Timestamp`n$Nonce`n$requestId`n$hash"
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($Secret))
  try { $signature = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))) }
  finally { $hmac.Dispose() }
  @{
    'X-App-Id' = $AppId; 'X-Signature-Algorithm' = 'HMAC_SHA256'; 'X-Timestamp' = "$Timestamp"
    'X-Nonce' = $Nonce; 'X-Request-Id' = $requestId; 'X-Body-SHA256' = $hash; 'X-Signature' = $signature
  }
}

function New-RsaSignedHeaders([string]$AppId, [Security.Cryptography.RSA]$PrivateKey,
    [string]$Method, [string]$Target, [string]$Body) {
  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $nonce = [Guid]::NewGuid().ToString('N'); $requestId = [Guid]::NewGuid().ToString('N')
  $hash = Get-Sha256Hex $Body
  $canonical = "$Method`n$Target`n$timestamp`n$nonce`n$requestId`n$hash"
  $signature = $PrivateKey.SignData([Text.Encoding]::UTF8.GetBytes($canonical),
    [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
  @{
    'X-App-Id' = $AppId; 'X-Signature-Algorithm' = 'RSA_SHA256'; 'X-Timestamp' = "$timestamp"
    'X-Nonce' = $nonce; 'X-Request-Id' = $requestId; 'X-Body-SHA256' = $hash
    'X-Signature' = [Convert]::ToBase64String($signature)
  }
}

function ConvertFrom-Base32([string]$Value) {
  $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  $output = [Collections.Generic.List[byte]]::new()
  $buffer = 0; $bits = 0
  foreach ($char in $Value.ToUpperInvariant().ToCharArray()) {
    $index = $alphabet.IndexOf($char); if ($index -lt 0) { continue }
    $buffer = ($buffer -shl 5) -bor $index; $bits += 5
    if ($bits -ge 8) { $bits -= 8; $output.Add([byte](($buffer -shr $bits) -band 0xff)) }
  }
  $output.ToArray()
}

function Get-Totp([string]$Secret) {
  $counter = [long]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() / 30)
  $counterBytes = [BitConverter]::GetBytes($counter)
  if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($counterBytes) }
  $hmac = [Security.Cryptography.HMACSHA1]::new((ConvertFrom-Base32 $Secret))
  try { $hash = $hmac.ComputeHash($counterBytes) } finally { $hmac.Dispose() }
  $offset = $hash[$hash.Length - 1] -band 0x0f
  $binary = (($hash[$offset] -band 0x7f) -shl 24) -bor (($hash[$offset + 1] -band 0xff) -shl 16) `
    -bor (($hash[$offset + 2] -band 0xff) -shl 8) -bor ($hash[$offset + 3] -band 0xff)
  ($binary % 1000000).ToString('D6')
}

$admin = New-AuthenticatedSession
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$merchantBody = @{ displayName = "05A-3 Security Merchant $suffix"; notifyUrl = 'http://backend:8080/api/payment/notifications'; signingAlgorithm = 'HMAC_SHA256'; merchantPublicKey = '' } | ConvertTo-Json -Compress
$issued = Invoke-RestMethod "$BaseUrl/api/admin/merchants" -Method Post -WebSession $admin.Session `
  -Headers $admin.Headers -ContentType 'application/json' -Body $merchantBody
Assert-Equal 'HMAC_SHA256' $issued.merchant.signingAlgorithm 'Merchant signing algorithm'

$created = @()
foreach ($channel in @('ALIPAY', 'WECHAT_PAY', 'UNION_PAY')) {
  $body = @{ merchantRequestNo = "SECURE-$channel-$suffix"; channel = $channel; subject = "05A-3 $channel"; amount = 12.34; currency = 'CNY'; faultScenario = 'NONE' } | ConvertTo-Json -Compress
  $headers = New-SignedHeaders $issued.merchant.appId $issued.appSecret 'POST' '/api/channel/transactions' $body
  $transaction = Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
  Assert-Equal 'PENDING' $transaction.status "$channel signed create"
  $created += $transaction
}
Assert-Equal 'REDIRECT' $created[0].actionType 'Alipay action'
Assert-Equal 'QR_CODE' $created[1].actionType 'WeChat action'
Assert-Equal 'GATEWAY' $created[2].actionType 'UnionPay action'

$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
  $rsaMerchantBody = @{ displayName = "05A-3 RSA Merchant $suffix"; notifyUrl = 'http://backend:8080/api/payment/notifications'; signingAlgorithm = 'RSA_SHA256'; merchantPublicKey = $rsa.ExportSubjectPublicKeyInfoPem() } | ConvertTo-Json -Compress
  $rsaIssued = Invoke-RestMethod "$BaseUrl/api/admin/merchants" -Method Post -WebSession $admin.Session `
    -Headers $admin.Headers -ContentType 'application/json' -Body $rsaMerchantBody
  $rsaBody = @{ merchantRequestNo = "SECURE-RSA-$suffix"; channel = 'UNION_PAY'; subject = '05A-3 RSA verification'; amount = 23.45; currency = 'CNY'; faultScenario = 'NONE' } | ConvertTo-Json -Compress
  $rsaHeaders = New-RsaSignedHeaders $rsaIssued.merchant.appId $rsa 'POST' '/api/channel/transactions' $rsaBody
  $rsaTransaction = Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $rsaHeaders -ContentType 'application/json' -Body $rsaBody
  Assert-Equal 'PENDING' $rsaTransaction.status 'RSA signed create'
} finally { $rsa.Dispose() }

$testBody = @{ merchantRequestNo = "REPLAY-$suffix"; channel = 'ALIPAY'; subject = 'security negative tests'; amount = 8.88; currency = 'CNY'; faultScenario = 'NONE' } | ConvertTo-Json -Compress
$validHeaders = New-SignedHeaders $issued.merchant.appId $issued.appSecret 'POST' '/api/channel/transactions' $testBody
$first = Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $validHeaders -ContentType 'application/json' -Body $testBody
Assert-HttpStatus 409 { Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $validHeaders -ContentType 'application/json' -Body $testBody -ErrorAction Stop } 'Duplicate nonce rejection'

$tampered = $testBody.Replace('8.88', '9.99')
$tamperHeaders = New-SignedHeaders $issued.merchant.appId $issued.appSecret 'POST' '/api/channel/transactions' $testBody
Assert-HttpStatus 401 { Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $tamperHeaders -ContentType 'application/json' -Body $tampered -ErrorAction Stop } 'Tampered amount rejection'

$expired = [DateTimeOffset]::UtcNow.AddMinutes(-10).ToUnixTimeSeconds()
$expiredHeaders = New-SignedHeaders $issued.merchant.appId $issued.appSecret 'POST' '/api/channel/transactions' $testBody $expired
Assert-HttpStatus 401 { Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $expiredHeaders -ContentType 'application/json' -Body $testBody -ErrorAction Stop } 'Expired timestamp rejection'

$badHeaders = New-SignedHeaders $issued.merchant.appId 'wrong-secret' 'POST' '/api/channel/transactions' $testBody
Assert-HttpStatus 401 { Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $badHeaders -ContentType 'application/json' -Body $testBody -ErrorAction Stop } 'Wrong signature rejection'
$unknownHeaders = New-SignedHeaders 'unknown-app' 'unknown-secret' 'POST' '/api/channel/transactions' $testBody
Assert-HttpStatus 401 { Invoke-RestMethod "$BaseUrl/api/channel/transactions" -Method Post -Headers $unknownHeaders -ContentType 'application/json' -Body $testBody -ErrorAction Stop } 'Unknown merchant rejection'

$enrollment = Invoke-RestMethod "$BaseUrl/api/auth/mfa/enroll" -Method Post -WebSession $admin.Session `
  -Headers $admin.Headers -ContentType 'application/json' -Body (@{ password = 'demo-password' } | ConvertTo-Json -Compress)
$totp = Get-Totp $enrollment.secret
$recovery = Invoke-RestMethod "$BaseUrl/api/auth/mfa/confirm" -Method Post -WebSession $admin.Session `
  -Headers $admin.Headers -ContentType 'application/json' -Body (@{ code = $totp } | ConvertTo-Json -Compress)
Assert-Equal 8 @($recovery.codes).Count 'Recovery code count'
$totp = Get-Totp $enrollment.secret
$stepUp = Invoke-RestMethod "$BaseUrl/api/auth/step-up" -Method Post -WebSession $admin.Session `
  -Headers $admin.Headers -ContentType 'application/json' -Body (@{ password = 'demo-password'; code = $totp } | ConvertTo-Json -Compress)
$sensitiveHeaders = @{} + $admin.Headers
$sensitiveHeaders['X-Step-Up-Token'] = $stepUp.token
$rotated = Invoke-RestMethod "$BaseUrl/api/admin/merchants/$($issued.merchant.id)/rotate-secret" -Method Post `
  -WebSession $admin.Session -Headers $sensitiveHeaders
Assert-Equal 2 $rotated.merchant.credentialVersion 'Credential rotation version'
Assert-HttpStatus 401 { Invoke-RestMethod "$BaseUrl/api/admin/merchants/$($issued.merchant.id)/rotate-secret" -Method Post -WebSession $admin.Session -Headers $sensitiveHeaders -ErrorAction Stop } 'One-time step-up token replay rejection'

$audit = Invoke-RestMethod "$BaseUrl/api/admin/audit-logs" -WebSession $admin.Session
if (-not $audit[0].eventHash -or -not $audit[0].previousHash) { throw 'Audit hash chain fields are missing' }

Write-Output "05A-3 verification passed: HMAC-channels=3, RSA-channel=1, negative-signature-cases=5, MFA=TOTP+8-recovery-codes, step-up=one-time, audit-chain=present"
