function Invoke-VerificationDb(
  [string]$Scope, [string]$Query, [string[]]$ComposeArguments = @()
) {
  $target = switch ($Scope) {
    'commerce' { @('commerce-db','commerceflow','commerce_audit','COMMERCE_AUDIT_PASSWORD','local-commerce-audit') }
    'legacy' { @('commerce-db','commerceflow','commerce_legacy_audit','COMMERCE_LEGACY_AUDIT_PASSWORD','local-legacy-audit') }
    'task' { @('commerce-db','commerceflow','commerce_task_fixture','COMMERCE_TASK_FIXTURE_PASSWORD','local-task-fixture') }
    'recovery' { @('commerce-db','commerceflow','commerce_recovery_fixture','COMMERCE_RECOVERY_FIXTURE_PASSWORD','local-recovery-fixture') }
    'lock' { @('commerce-db','commerceflow','commerce_legacy_lock','COMMERCE_LEGACY_LOCK_PASSWORD','local-legacy-lock') }
    'order' { @('order-db','orders','order_audit','ORDER_AUDIT_PASSWORD','local-order-audit') }
    'payment' { @('payment-db','payments','payment_audit','PAYMENT_AUDIT_PASSWORD','local-payment-audit') }
    default { throw "Unknown verification database scope: $Scope" }
  }
  $service, $schema, $user, $passwordVariable, $password = $target
  $configuredPassword = [Environment]::GetEnvironmentVariable($passwordVariable)
  if ($configuredPassword) { $password = $configuredPassword }
  $root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
  $result = & docker compose --project-directory $root @ComposeArguments exec -T -e "MYSQL_PWD=$password" `
    $service mysql --protocol=TCP --host=127.0.0.1 --batch --skip-column-names `
    "--user=$user" "--database=$schema" "--execute=$Query"
  if ($LASTEXITCODE -ne 0) { throw "Verification SQL failed ($Scope on $service)" }
  return $result
}
