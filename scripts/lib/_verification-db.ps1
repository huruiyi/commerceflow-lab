. "$PSScriptRoot/commerce-ledger.ps1"

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
  $root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
  if ($Scope -in 'commerce','task') {
    $app = & docker compose --project-directory $root @ComposeArguments ps --all --quiet commerce-service
    if ($LASTEXITCODE) { throw 'Could not resolve the verification project' }
    if (!$app) {
      $model = docker compose --project-directory $root @ComposeArguments config --format json | ConvertFrom-Json
      if ($LASTEXITCODE -or $model.name -eq 'fairy-commerceflow' -or
          $model.services.'commerce-service'.environment.DATABASE_URL -notlike 'jdbc:mysql://commerce-db:3306/commerceflow*') {
        throw 'The commerce application is missing; the active database cannot be identified'
      }
      # Fresh isolated MySQL verification may inspect migrations before the application is created.
    } else {
      $ledger = Get-CommerceLedger -AppId $app
      if ($ledger.Engine -eq 'mysql') {
        $result = docker exec -e "MYSQL_PWD=$password" $ledger.Container mysql --protocol=TCP --host=127.0.0.1 `
          --batch --skip-column-names "--user=$user" "--database=$($ledger.Database)" "--execute=$Query"
        if ($LASTEXITCODE) { throw "Verification SQL failed ($Scope on $($ledger.Service))" }
        return $result
      }
      # These two expressions occur in the existing lease-expiry and scheduled-task fixtures.
      $statement = $Query.Replace('DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)', "CURRENT_TIMESTAMP - INTERVAL '1 second'").
        Replace('DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 SECOND)', "CURRENT_TIMESTAMP - INTERVAL '2 seconds'")
      $result = & docker exec -e "PGPASSWORD=$password" $ledger.Container psql -h 127.0.0.1 -U $user -d $ledger.Database `
        -XqAt -F "`t" -v ON_ERROR_STOP=1 -c $statement
      if ($LASTEXITCODE) { throw "Verification SQL failed ($Scope on $($ledger.Service))" }
      return $result
    }
  }
  $result = & docker compose --project-directory $root @ComposeArguments exec -T -e "MYSQL_PWD=$password" `
    $service mysql --protocol=TCP --host=127.0.0.1 --batch --skip-column-names `
    "--user=$user" "--database=$schema" "--execute=$Query"
  if ($LASTEXITCODE -ne 0) { throw "Verification SQL failed ($Scope on $service)" }
  return $result
}
