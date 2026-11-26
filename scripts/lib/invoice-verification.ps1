function Assert-Equal($Actual, $Expected, [string]$Context) {
  if ($Actual -cne $Expected) { throw "${Context}: expected [$Expected], got [$Actual]" }
}

function Wait-For([scriptblock]$Condition, [string]$Description) {
  $timer = [Diagnostics.Stopwatch]::StartNew()
  do {
    $value = & $Condition
    if ($value) { return $value }
    Start-Sleep -Milliseconds 200
  } while ($timer.Elapsed.TotalSeconds -lt $MaxWaitSeconds)
  throw "Timed out waiting for $Description"
}

function New-SignedRequest(
  [string]$Method,
  [string]$Path,
  $Body,
  [long]$Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
) {
  $json = if ($null -eq $Body) { '' } else { ConvertTo-Json -InputObject $Body -Compress -Depth 8 }
  $canonical = "$Timestamp`n$Method`n$Path`n$json"
  $hmac = [Security.Cryptography.HMACSHA256]::new($utf8.GetBytes($requestSecret))
  try {
    $signature = [BitConverter]::ToString($hmac.ComputeHash($utf8.GetBytes($canonical))).Replace('-', '').ToLowerInvariant()
  } finally {
    $hmac.Dispose()
  }
  $request = [Net.Http.HttpRequestMessage]::new($Method, $invoiceUrl + $Path)
  $request.Headers.Add('X-Invoice-Timestamp', [string]$Timestamp)
  $request.Headers.Add('X-Invoice-Signature', $signature)
  if ($Method -eq 'POST') { $request.Content = [Net.Http.StringContent]::new($json, $utf8, 'application/json') }
  return $request
}

function Read-Response([Net.Http.HttpResponseMessage]$Response) {
  try {
    $raw = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $body = if ($raw) { ConvertFrom-Json -InputObject $raw }
    return @{ Status = [int]$Response.StatusCode; Body = $body }
  } finally {
    $Response.Dispose()
  }
}

function Invoke-Invoice(
  [string]$Method,
  [string]$Path,
  $Body = $null,
  [int]$Expected = 200,
  [long]$Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
) {
  $request = New-SignedRequest $Method $Path $Body $Timestamp
  try {
    $result = Read-Response ($client.SendAsync($request).GetAwaiter().GetResult())
    Assert-Equal $result.Status $Expected "$Method $Path response code ($($result.Body | ConvertTo-Json -Compress))"
    return $result.Body
  } finally {
    $request.Dispose()
  }
}
