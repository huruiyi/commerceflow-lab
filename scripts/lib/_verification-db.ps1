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
    if ($app) {
      $runtime = (docker inspect $app | ConvertFrom-Json)[0]
      $url = $runtime.Config.Env | Where-Object { $_ -like 'DATABASE_URL=jdbc:postgresql:*' }
      if ($url) {
        if ($url -notmatch '^DATABASE_URL=jdbc:postgresql://([a-z0-9-]+):5432/(\w+)(?:\?|$)') {
          throw 'Unexpected commerce PostgreSQL endpoint'
        }
        $pgService, $pgDatabase = $Matches[1], $Matches[2]
        $project = $runtime.Config.Labels.'com.docker.compose.project'
        $pg = @(docker ps -q --filter "label=com.docker.compose.project=$project" `
          --filter "label=com.docker.compose.service=$pgService")
        if ($LASTEXITCODE -or $pg.Count -ne 1) { throw 'Could not resolve the commerce PostgreSQL container' }
        # These two expressions occur in the existing lease-expiry and scheduled-task fixtures.
        $statement = $Query.Replace('DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)', "CURRENT_TIMESTAMP - INTERVAL '1 second'").
          Replace('DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 SECOND)', "CURRENT_TIMESTAMP - INTERVAL '2 seconds'")
        $result = & docker exec -e "PGPASSWORD=$password" $pg[0] psql -h 127.0.0.1 -U $user -d $pgDatabase `
          -XqAt -F "`t" -v ON_ERROR_STOP=1 -c $statement
        if ($LASTEXITCODE) { throw "Verification SQL failed ($Scope on $pgService)" }
        return $result
      }
    }
  }
  $result = & docker compose --project-directory $root @ComposeArguments exec -T -e "MYSQL_PWD=$password" `
    $service mysql --protocol=TCP --host=127.0.0.1 --batch --skip-column-names `
    "--user=$user" "--database=$schema" "--execute=$Query"
  if ($LASTEXITCODE -ne 0) { throw "Verification SQL failed ($Scope on $service)" }
  return $result
}
