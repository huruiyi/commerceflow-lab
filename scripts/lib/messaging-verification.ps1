function Request([string]$Method,[string]$Path,$Body,[int]$Expected,[string]$Auth='Admin'){
  $json=if($Body-is[string]){$Body}elseif($null-eq$Body){''}else{$Body|ConvertTo-Json -Compress}
  $headers=@{}
  if($Auth-eq'Admin'){$headers.Authorization="Bearer $script:adminToken"}
  if($Auth-eq'Sender'){
    $time=[datetimeoffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce=[guid]::NewGuid().ToString()
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json))).ToLowerInvariant()
    $payload=[string]::Join([char]10,@($Method,$Path,$time,$nonce,$hash))
    $signature=[Security.Cryptography.HMACSHA256]::HashData([Text.Encoding]::UTF8.GetBytes($script:senderSecret),
      [Text.Encoding]::UTF8.GetBytes($payload))
    $headers=@{'X-CommerceFlow-Timestamp'=$time;'X-CommerceFlow-Request-Id'=$nonce;
      'X-CommerceFlow-Content-SHA256'=$hash;'X-CommerceFlow-Signature'=[Convert]::ToHexString($signature).ToLowerInvariant()}
  }
  $options=@{Method=$Method;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=12}
  if($json){$options.Body=$json;$options.ContentType='application/json; charset=utf-8'}
  $response=Invoke-WebRequest "$BaseUrl$Path" @options
  if($response.StatusCode-ne$Expected){throw "Unexpected $($response.StatusCode) for $Method $Path, expected $Expected"}
  $response
}

function Json-Body($response){
  $response.Content|ConvertFrom-Json
}

function Command([string]$Channel){
  $id=[guid]::NewGuid().ToString()
  if($Channel-eq'SMS'){
    return @{requestId=$id;channel=$Channel;recipient='+12025550100';reference="verify-$id";message='模拟短信'}
  }
  @{requestId=$id;channel=$Channel;recipient='demo@commerceflow.test';reference="verify-$id";
    subject='消息验收';message='模拟邮件'}
}

function Send($command,[int]$Expected=200){
  Request POST '/api/provider/v1/deliveries' $command $Expected Sender
}

function Lookup([string]$Id,[int]$Expected=200){
  Request GET "/api/provider/v1/deliveries/$Id" $null $Expected Sender
}

function Wait-Delivery([string]$Id,[string]$Status){
  $deadline=[datetimeoffset]::UtcNow.AddSeconds(25)
  do{
    $receipt=Json-Body (Lookup $Id)
    if($receipt.status-eq$Status){return $receipt}
    if($receipt.status-ne'ACCEPTED'){throw "Unexpected final delivery status: $($receipt.status)"}
    Start-Sleep -Milliseconds 350
  }while([datetimeoffset]::UtcNow-lt$deadline)
  throw 'Delivery did not settle'
}
