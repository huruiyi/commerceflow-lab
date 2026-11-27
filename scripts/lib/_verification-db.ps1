. "$PSScriptRoot/commerce-ledger.ps1"

function Invoke-VerificationDb(
  [string]$Scope, [string]$Query, [string[]]$ComposeArguments = @()
) {
  $root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
  $service, $database, $schema = switch ($Scope) {
    { $_ -in 'commerce','task','legacy','recovery','lock' } { @('commerce-db-postgresql','commerceflow','commerceflow') }
    'order' { @('order-db-postgresql','orders','public') }
    'payment' { @('payment-db-postgresql','payments','public') }
    default { throw "Unknown verification database scope: $Scope" }
  }
  $container = docker compose --project-directory $root @ComposeArguments ps -q $service
  if ($LASTEXITCODE -or !$container) { throw "Could not find $service" }
  if ($Scope -eq "task") {
    $password = if ($env:COMMERCE_TASK_FIXTURE_PASSWORD) { $env:COMMERCE_TASK_FIXTURE_PASSWORD } else { 'local-task-fixture' }
    $result = docker exec -e "PGPASSWORD=$password" $container psql -h 127.0.0.1 -U commerce_task_fixture -d $database -XqAt -v ON_ERROR_STOP=1 -c $Query
  } else {
    $options = "PGOPTIONS=-c default_transaction_read_only=on -c search_path=$schema"
    $result = docker exec -e $options $container psql -U postgres -d $database -XqAt -v ON_ERROR_STOP=1 -c $Query
  }
  if ($LASTEXITCODE -ne 0) { throw "Verification SQL failed ($Scope on $service)" }
  return $result
}
