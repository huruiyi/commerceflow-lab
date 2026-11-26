function Request([string]$Method,[string]$Path,$Headers,[string]$Payload) {
  # Make requests inside the private rehearsal network, including while the receiver returns HTTP 503.
  $request=@{method=$Method;path=$Path;headers=$Headers;body=$Payload} | ConvertTo-Json -Compress
  $client=@'
const {method,path,headers,body}=JSON.parse(require('node:fs').readFileSync(0,'utf8'));
fetch('http://logistics:8080'+path,{method,headers,body:method==='GET'?undefined:body})
  .then(async r=>console.log(JSON.stringify({status:r.status,content:await r.text()})))
  .catch(error=>{console.error(error.message);process.exitCode=1});
'@
  $request | docker @options exec -T receiver node -e $client | ConvertFrom-Json
}
function Admin-Shipment([string]$Number) {
  $response=Request GET "/api/admin/logistics/shipments/$Number" @{Authorization='Bearer rehearsal-admin'} ''
  if($response.status -ne 200){throw "Admin shipment lookup returned $($response.status)"}
  $response.content | ConvertFrom-Json
}
function Channel([string]$Path,$Body,[string]$Nonce=([guid]::NewGuid().ToString('N')),[int]$Expected=200) {
  $payload=if($Body){$Body|ConvertTo-Json -Compress}else{''}
  $bytes=[Text.Encoding]::UTF8.GetBytes([string]$payload)
  $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
  $time=[datetimeoffset]::UtcNow.ToUnixTimeSeconds()
  $app='commerceflow-storefront'
  $signed=[Text.Encoding]::UTF8.GetBytes("$app`n$time`n$Nonce`n$hash")
  $sig=[Security.Cryptography.HMACSHA256]::HashData([Text.Encoding]::UTF8.GetBytes('rehearsal-request'),$signed)
  $headers=@{'X-App-Id'=$app;'X-Timestamp'="$time";'X-Nonce'=$Nonce;'X-Signature'=[Convert]::ToBase64String($sig)}
  $headers['Content-Type']='application/json'
  $response=Request POST "/api/channel/shipments$Path" $headers $payload
  if($response.status -ne $Expected){throw "Shipment request on '$Path' returned $($response.status), expected $Expected"}
  if($Expected -eq 200){$response.Content|ConvertFrom-Json}
}
