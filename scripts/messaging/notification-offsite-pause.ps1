function Start-OffsiteWriter($Db, [string]$Kafka, [string]$Redis, $Notification, [string]$Volume,
    [string]$Secret, [string]$Image) {
  $name = "$($Db.Name)-app"
  Start-Container $name @('-p', '127.0.0.1::8080', '--mount', "type=volume,source=$Volume,target=/gate,readonly",
    '--env', 'COMMERCEFLOW_OFFSITE_DISPATCH_PAUSE_FILE=/gate/offsite.paused',
    '--env', "DATABASE_URL=jdbc:mysql://$($Db.Name):3306/$($Db.Schema)?sslMode=DISABLED&allowPublicKeyRetrieval=true",
    '--env', 'DATABASE_USERNAME=notification', '--env', "DATABASE_PASSWORD=$password",
    '--env', "KAFKA_BOOTSTRAP_SERVERS=${Kafka}:9092", '--env', "REDIS_HOST=$Redis",
    '--env', 'PAYMENT_ACCEPTING_TRAFFIC=true', '--env', 'PAYMENT_EFFECTS_ENABLED=true',
    '--env', "FULFILLMENT_INTERNAL_SECRET=$internalSecret", '--env', "FULFILLMENT_ADMIN_SECRET=$paymentSecret",
    '--env', "COMMERCEFLOW_OFFSITE_URL=http://$($Notification.Name):8080",
    '--env', "COMMERCEFLOW_OFFSITE_SECRET=$Secret", $Image)
  "http://$(Invoke-Docker port $name '8080/tcp')"
}

function Wait-OffsitePause([string[]]$Apps, [int]$Expected) {
  foreach ($base in $Apps) {
    Wait-For {
      try {
        $m = (Invoke-WebRequest "$base/actuator/prometheus" -TimeoutSec 3).Content
        $m -match "(?m)^commerceflow_notification_offsite_dispatch_paused(?:\{[^}]*\})? $Expected(?:\.0)?\r?$"
      } catch { $false }
    } "$base pause acknowledgement $Expected" 120
  }
}

function Get-OffsiteRequestId([string]$EventId, [string]$Channel) {
  $b = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("${EventId}:$Channel"))
  $b[6] = ($b[6] -band 0xf) -bor 0x30
  $b[8] = ($b[8] -band 0x3f) -bor 0x80
  [guid]::ParseExact([Convert]::ToHexString($b), 'N').ToString()
}

function Invoke-OffsitePauseVerification {
  $gate = "$run-pause-control"
  Start-Container $gate @('--mount', 'type=volume,target=/gate', '--entrypoint', '/bin/sh',
    'redis:7.4-alpine', '-c', 'touch /gate/offsite.paused && sleep 900')
  $volume = Invoke-Docker inspect --format '{{range .Mounts}}{{if eq .Destination "/gate"}}{{.Name}}{{end}}{{end}}' $gate
  $kafka = Start-Kafka 'offsite-pause'
  $redis = "$run-pause-redis"
  Start-Container $redis @('--tmpfs', '/data', 'redis:7.4-alpine')
  $provider = Start-Provider 'offsite-pause'
  $noticeDb = Start-Database 'pause-notification' mysql
  $notice = Start-Notification 'pause-notification' $noticeDb $kafka $provider $CandidateImage -ControlVolume $volume
  $commerce = Start-Database 'pause-commerce' mysql 'commerceflow'
  Sql $commerce 'SET GLOBAL log_bin_trust_function_creators=1' | Out-Null
  $migrations = Join-Path $root 'services/commerce-service/src/main/resources/db/migration'
  $migrate = @('--rm', '--network', $run, '--mount', "type=bind,source=$migrations,target=/flyway/sql,readonly",
    'redgate/flyway:12.4.0', "-url=jdbc:mysql://$($commerce.Name):3306/commerceflow?sslMode=DISABLED&allowPublicKeyRetrieval=true",
    '-user=notification', "-password=$password", 'migrate')
  Invoke-Docker run @migrate | Out-Null
  Sql $commerce "UPDATE fulfillment_write_admission SET frozen=TRUE,write_owner='FULFILLMENT' WHERE singleton_id=1" | Out-Null
  $payment = Start-Database 'pause-payment' mysql 'payments'
  $apps = @(
    Start-OffsiteWriter $commerce $kafka $redis $notice $volume $senderSecret $CommerceImage
    Start-OffsiteWriter $payment $kafka $redis $notice $volume $paymentSecret $PaymentImage
  )
  Wait-OffsitePause $apps 1
  $work = 'offsite-work:M4-WORK-PAUSE'
  $refund = 'offsite-refund:M4-REFUND-PAUSE'
  Sql $commerce @"
INSERT INTO notification_delivery_outbox(event_id,topic,payload) VALUES
('$work','offsite-notifications','{"version":1,"eventId":"$work","type":"RETURN_WORK_OPENED","recipientId":900001,"orderId":900001,"reference":"M4-WORK-PAUSE"}');
"@ | Out-Null
  Sql $payment @"
INSERT INTO payment_outbox(event_id,aggregate_type,aggregate_id,event_type,topic,payload,status,attempts,next_attempt_at,created_at)
VALUES('$refund','REFUND','M4-REFUND-PAUSE','OFFSITE_REFUND_SUCCEEDED','offsite-notifications',
  '{"version":1,"eventId":"$refund","type":"REFUND_SUCCEEDED","recipientId":900002,"orderId":900002,"reference":"M4-REFUND-PAUSE","amount":12.5,"currency":"CNY"}',
  'RETRY',2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
"@ | Out-Null
  $commerceState = "SELECT CONCAT(status,':',attempts) FROM notification_delivery_outbox WHERE event_id='$work'"
  $paymentState = "SELECT CONCAT(status,':',attempts) FROM payment_outbox WHERE event_id='$refund'"
  $receipts = "SELECT COUNT(*) FROM offsite_notification_inbox WHERE event_id IN ('$work','$refund')"
  $expected = @(foreach ($id in @($work, $refund)) {
    foreach ($channel in @('SMS', 'EMAIL')) { Get-OffsiteRequestId $id $channel }
  })
  # Five attempts at five-second intervals can be exhausted within 25 seconds.
  $timer = [Diagnostics.Stopwatch]::StartNew()
  do {
    Assert-Equal 'NEW:0' (Sql $commerce $commerceState) 'held commerce event'
    Assert-Equal 'RETRY:2' (Sql $payment $paymentState) 'held payment event'
    Assert-Equal '0' (Sql $noticeDb $receipts) 'no intake while held'
    Start-Sleep -Seconds 3
  } while ($timer.Elapsed.TotalSeconds -lt 35)
  $timer.Stop()
  Invoke-Docker exec $gate rm /gate/offsite.paused
  Wait-OffsitePause $apps 0
  Wait-For {
    (Sql $commerce $commerceState) -eq 'PUBLISHED:1' -and (Sql $payment $paymentState) -eq 'PUBLISHED:3' -and
    (Sql $noticeDb $receipts) -eq '2' -and
    (Sql $noticeDb "SELECT COUNT(*) FROM offsite_notification_deliveries WHERE event_id IN ('$work','$refund') AND status='DELIVERED'") -eq '4'
  } 'both held events to deliver once per channel'
  $list = ($expected | ForEach-Object { "'$_'" }) -join ','
  $actual = @(Sql $provider.Db "SELECT request_id FROM messaging_deliveries WHERE request_id IN ($list) AND status='DELIVERED'")
  Assert-Equal (($expected | Sort-Object) -join ',') (($actual | Sort-Object) -join ',') 'original deterministic provider request IDs'
  [ordered]@{ heldSeconds = $timer.Elapsed.TotalSeconds
    commerceHeld = 'NEW:0'; paymentHeld = 'RETRY:2'; commerceResumed = 'PUBLISHED:1'; paymentResumed = 'PUBLISHED:3'
    eventIds = @($work, $refund); providerRequestIds = $actual; passed = $true } |
    ConvertTo-Json | Set-Content (Join-Path $evidence 'offsite-pause.json')
  Write-Host "Both upstream events preserved retry state and delivered four expected request IDs."
  Invoke-NotificationReleaseVerification $gate $volume $notice $commerce $payment $expected
}

function Invoke-NotificationReleaseVerification([string]$Gate, [string]$Volume, $Source, $Commerce, $Payment, $Expected) {
  $release = Join-Path $PSScriptRoot 'release-notification-database.ps1'
  $pg = Start-Database 'release-postgresql' postgresql
  $mysql = Start-Database 'release-mysql' mysql
  foreach ($db in @($pg,$mysql)) {
    $schema = Start-Notification "$($db.Engine)-release-schema" $db $Source.Kafka $Source.Provider $CandidateImage -Paused
    Invoke-Docker stop $schema.Name | Out-Null
  }
  $prepare = @{Kafka=$Source.Kafka; Provider=$Source.Provider; Image=$CandidateImage; ControlVolume=$Volume
    RequireOffsets=$true; PrepareOnly=$true}
  $candidate = Start-Notification 'release-target' $pg @prepare
  $rollback = Start-Notification 'release-return' $mysql @prepare
  $cluster = Invoke-Docker exec $Source.Kafka /opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status |
    ForEach-Object { if ($_ -match '^ClusterId:\s+(\S+)') { $Matches[1] } }
  $initial = New-NotificationBatch 'm4-release-first' 6 940000
  Send-NotificationBatch $Source.Kafka $Source.Topic $initial.Lines
  $null = Wait-ForOffsetDrain $Source.Kafka $Source.Topic 6
  $common = @{Kafka=$Source.Kafka; ClusterId=$cluster; Control=$Gate
    Writers=@("$($Commerce.Name)-app","$($Payment.Name)-app"); Apply=$true }
  $forward = @{SourceApp=$Source.Name; TargetApp=$candidate.Name; SourceDb=$Source.Db.Name; TargetDb=$pg.Name
    EvidenceDirectory=(Join-Path $evidence 'release-forward')}
  & $release -Action Forward @common @forward
  & $release -Action Resume @common @forward
  $candidate.Base = "http://$(Invoke-Docker port $candidate.Name '8080/tcp')"
  $new = New-NotificationBatch 'm4-release-candidate' 6 941000
  Send-NotificationBatch $Source.Kafka $Source.Topic $new.Lines
  $null = Wait-ForOffsetDrain $Source.Kafka $Source.Topic 12
  Request $candidate PATCH "/internal/v1/customers/$cutoverCustomer/notifications/$($new.Ids[0])/read" -Status 204
  $readAt = Read-FirstNotification $candidate $new.Ids[0]
  $reverse = @{SourceApp=$candidate.Name; TargetApp=$rollback.Name; SourceDb=$pg.Name; TargetDb=$mysql.Name
    EvidenceDirectory=(Join-Path $evidence 'release-rollback')}
  & $release -Action Rollback @common @reverse
  & $release -Action Resume @common @reverse
  Assert-NotificationIds $mysql $initial.Prefix $initial.Ids
  Assert-NotificationIds $mysql $new.Prefix $new.Ids
  Assert-Equal $readAt (Read-FirstNotification $rollback $new.Ids[0]) 'candidate read timestamp after release rollback'
  $list = ($Expected | ForEach-Object { "'$_'" }) -join ','
  Assert-Equal '4' (Sql $mysql "SELECT COUNT(*) FROM offsite_notification_deliveries WHERE request_id IN ($list) AND status='DELIVERED'") 'preserved historical provider request IDs'
  Write-Host 'Release and reverse rollback scripts preserved Kafka commits, new writes and original provider request IDs.'
}
