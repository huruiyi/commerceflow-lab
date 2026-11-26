function Get-Receipt([string]$Resource,[string]$Id) {
  $page=Invoke-RestMethod "$warehouseUrl/api/admin/$($Resource)?jobId=$Id" -Headers $adminHeaders
  if ($page.total -ne 1) { throw "Expected one $Resource receipt for $Id" }
  return $page.rows[0]
}
function Submit-WarehouseCommand([string]$Resource,$Command,[string]$Caller,[string]$Secret,[int]$Expected=200) {
  if ($Resource -notin 'outbounds','returns') {throw 'Unknown warehouse resource'}
  $path="/internal/v1/$Resource"
  $json=$Command|ConvertTo-Json -Depth 5 -Compress
  $bytes=[Text.Encoding]::UTF8.GetBytes($json)
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
  $time=[datetimeoffset]::UtcNow.ToUnixTimeSeconds()
  $nonce=[guid]::NewGuid().ToString()
  $message=@('POST',$path,$time,$nonce,$hash) -join [char]10
  $signature=[Security.Cryptography.HMACSHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Secret),[Text.Encoding]::UTF8.GetBytes($message))
  $headers=@{'X-CommerceFlow-Service'=$Caller;'X-CommerceFlow-Timestamp'="$time";'X-CommerceFlow-Request-Id'=$nonce
    'X-CommerceFlow-Content-SHA256'=$hash;'X-CommerceFlow-Signature'=[Convert]::ToHexString($signature).ToLowerInvariant()}
  $response=Invoke-WebRequest "$warehouseUrl$path" -Method Post -Body $bytes -Headers $headers -ContentType 'application/json' -SkipHttpErrorCheck
  if ($response.StatusCode -ne $Expected) {throw "Unexpected warehouse submission status $($response.StatusCode)"}
  if ($Expected -eq 200) { $response.Content | ConvertFrom-Json }
}
