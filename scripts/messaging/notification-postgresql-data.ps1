# One transfer definition for MySQL export, PostgreSQL import, and the reverse recovery rehearsal.
# Types: i = integer, b = boolean, t = UTC timestamp, j = JSON. Unmarked columns are text.
$notificationTables = [ordered]@{
  customer_notifications = 'id:i,event_id,customer_id:i,order_id:i,notification_type,title,message,status,read_at:t,created_at:t'
  notification_inbox = 'id:i,consumer_name,event_id,event_type,schema_version:i,correlation_id,causation_id,traceparent,processed_at:t'
  notification_delivery_outbox = 'id:i,event_id,topic,payload:j,status,attempts:i,next_attempt_at:t,last_error,created_at:t,published_at:t,processed_at:t'
  notification_storage_state = 'id:i,source_watermark:i,backfill_completed:b,completed_at:t,updated_at:t'
  notification_storage_audits = 'id:i,matched_count:i,missing_count:i,orphan_count:i,mismatch_count:i,backfill_completed:b,passed:b,checked_at:t'
  notification_storage_quarantine = 'id:i,original_notification_id:i,event_id,customer_id:i,order_id:i,notification_type,title,message,status,read_at:t,created_at:t,reason,quarantined_at:t'
  offsite_notification_inbox = 'event_id,event_type,recipient_id:i,order_id:i,reference,fingerprint,created_at:t'
  offsite_notification_deliveries = 'request_id,event_id,channel,recipient,subject,message,status,attempts:i,provider_id,last_error,lease,next_attempt_at:t,created_at:t,updated_at:t'
}

function Format-JsonElement([System.Text.Json.JsonElement]$Value) {
  switch ($Value.ValueKind) {
    'Object' {
      $properties = [Collections.Generic.SortedDictionary[string, string]]::new([StringComparer]::Ordinal)
      foreach ($property in $Value.EnumerateObject()) {
        $key = ConvertTo-Json -InputObject $property.Name -Compress
        $properties[$property.Name] = $key + ':' + (Format-JsonElement $property.Value)
      }
      return '{' + ($properties.Values -join ',') + '}'
    }
    'Array' {
      $elements = foreach ($element in $Value.EnumerateArray()) { Format-JsonElement $element }
      return '[' + ($elements -join ',') + ']'
    }
    'String' { return (ConvertTo-Json -InputObject $Value.GetString() -Compress) }
    default { return $Value.GetRawText() }
  }
}

function Read-TransferRow([string]$Text) {
  $document = [System.Text.Json.JsonDocument]::Parse($Text)
  try {
    $values = [Collections.Generic.List[object]]::new()
    foreach ($element in $document.RootElement.EnumerateArray()) { $values.Add($element.GetString()) }
    return ,$values.ToArray()
  } finally { $document.Dispose() }
}

function Read-NotificationSnapshot($Db) {
  $snapshot = [ordered]@{}
  foreach ($table in $notificationTables.Keys) {
    $columns = $notificationTables[$table].Split(',')
    $expressions = foreach ($column in $columns) {
      $name, $kind = $column -split ':'
      if ($Db.Engine -eq 'mysql') {
        if ($kind -eq 't') { "DATE_FORMAT($name, '%Y-%m-%d %H:%i:%s.%f')" }
        else { "CAST($name AS CHAR)" }
      } elseif ($kind -eq 't') {
        "to_char($name AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')"
      } elseif ($kind -eq 'b') {
        "(${name}::integer)::text"
      } else {
        "${name}::text"
      }
    }
    $rowFunction = if ($Db.Engine -eq 'mysql') { 'JSON_ARRAY' } else { 'json_build_array' }
    $rows = [string[]]@(
      foreach ($line in (Sql $Db "SELECT $rowFunction($($expressions -join ',')) FROM $table")) {
        $values = Read-TransferRow $line
        for ($i = 0; $i -lt $columns.Count; $i++) {
          if ($columns[$i].EndsWith(':j') -and $null -ne $values[$i]) {
            $json = [System.Text.Json.JsonDocument]::Parse($values[$i])
            try { $values[$i] = Format-JsonElement $json.RootElement }
            finally { $json.Dispose() }
          }
        }
        ConvertTo-Json -InputObject $values -Compress
      }
    )
    [Array]::Sort($rows, [StringComparer]::Ordinal)
    $snapshot[$table] = $rows
  }
  $snapshot
}

function Write-SnapshotSummary($Snapshot, [string]$File) {
  $summary = [ordered]@{}
  foreach ($table in $Snapshot.Keys) {
    $text = $Snapshot[$table] -join [char]10
    $summary[$table] = @{ rows = $Snapshot[$table].Count
      sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))) }
  }
  $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $File
}

function Assert-Snapshot($Expected, $Actual, [string]$Stage) {
  foreach ($table in $notificationTables.Keys) {
    if ($Expected[$table].Count -ne $Actual[$table].Count -or
        ($Expected[$table] -join [char]10) -cne ($Actual[$table] -join [char]10)) {
      throw "$Stage differs in table $table"
    }
  }
}

function Write-NotificationSnapshot($Db, $Snapshot) {
  $pgTypes = @{ i = 'bigint'; b = 'boolean'; t = 'timestamptz'; j = 'jsonb' }
  $sql = [Collections.Generic.List[string]]::new()
  $sql.Add('START TRANSACTION;')
  $keys = @($notificationTables.Keys)
  if ($Db.Engine -eq 'mysql') { $sql.Add("SET time_zone = '+00:00';") }
  for ($i = $keys.Count - 1; $i -ge 0; $i--) { $sql.Add("DELETE FROM $($keys[$i]);") }
  foreach ($table in $keys) {
    $columns = $notificationTables[$table].Split(',')
    $names = ($columns | ForEach-Object { ($_ -split ':')[0] }) -join ','
    foreach ($row in $Snapshot[$table]) {
      $fields = Read-TransferRow $row
      $values = for ($i = 0; $i -lt $fields.Count; $i++) {
        if ($null -eq $fields[$i]) {
          'NULL'
        } else {
          $hex = [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes([string]$fields[$i]))
          if ($Db.Engine -eq 'mysql') {
            "CONVERT(UNHEX('$hex') USING utf8mb4)"
          } else {
            $kind = ($columns[$i] -split ':', 2)[1]
            $literal = "convert_from(decode('$hex','hex'),'UTF8')"
            if ($kind) { "${literal}::$($pgTypes[$kind])" } else { $literal }
          }
        }
      }
      $sql.Add("INSERT INTO $table ($names) VALUES ($($values -join ','));")
    }
  }
  $sql.Add('COMMIT;')
  Sql $Db ($sql -join [char]10) | Out-Null
  if ($Db.Engine -eq 'postgresql') {
    foreach ($table in @('customer_notifications', 'notification_inbox', 'notification_delivery_outbox',
        'notification_storage_audits', 'notification_storage_quarantine')) {
      Sql $Db "SELECT setval(pg_get_serial_sequence('$table','id'), COALESCE(MAX(id),1), MAX(id) IS NOT NULL) FROM $table" | Out-Null
    }
  }
}
