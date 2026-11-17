#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
fail(){ echo "Payment CUTOVER review drifted: $*" >&2; exit 1; }
assert_file(){ [[ -f "$PROJECT_ROOT/$2" ]] || fail "$1 is missing";
  printf '  PASS evidence present: %s\n' "$1"; }
assert_contains(){ grep -Eq "$3" "$PROJECT_ROOT/$2" || fail "$1 is no longer present";
  printf '  PASS evidence present: %s\n' "$1"; }
assert_absent(){ local label="$1" pattern="$2";shift 2
  if grep -RInE "$pattern" "$@" >/dev/null 2>&1;then fail "$label was found";fi
  printf '  PASS evidence confirmed: %s\n' "$label"
}

cd "$PROJECT_ROOT"
docker compose config --quiet

echo 'Payment CUTOVER safety gates:'
assert_contains 'independent service traffic defaults to closed' 'compose.yaml' \
  'PAYMENT_ACCEPTING_TRAFFIC: \$\{PAYMENT_ACCEPTING_TRAFFIC:-false\}'
assert_absent 'PostgreSQL is not configured' '(postgres|jdbc:postgresql)' compose.yaml compose.ha.yaml

echo 'Payment CUTOVER blockers:'
assert_file 'commerce local payment implementation' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/payment/infrastructure/adapter/LocalPaymentOrchestrationAdapter.java'
assert_file 'commerce payment callback owner' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/payment/api/PaymentNotificationController.java'
assert_file 'commerce refund initiation owner' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundController.java'
assert_file 'commerce refund callback owner' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundNotificationController.java'
assert_contains 'payment callback still targets commerce' 'compose.yaml' \
  'PAYMENT_CALLBACK_URL: http://commerce-service:8080/api/payment-notifications/callback'
assert_contains 'refund callback still targets commerce' 'compose.yaml' \
  'REFUND_CALLBACK_URL: http://commerce-service:8080/api/refund-notifications/callback'
assert_absent 'independent service has no remote Order V1 or Inventory command client' \
  '(OrderStore|RemoteOrder|OrderV1Client|InventoryCommandClient|RemoteInventory)' \
  services/payment-orchestration-service/src/main/java
assert_absent 'independent service has no payment Outbox dispatcher' \
  '(FROM|UPDATE)[[:space:]]+payment_outbox|PaymentOutboxDispatcher' \
  services/payment-orchestration-service/src/main/java
assert_absent 'independent service has no refund initiation command endpoint' \
  'PostMapping\("/refunds?"\)' \
  services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/api
assert_absent 'commerce runtime grant freezer still grants payment source tables' \
  "'payment_records'|'refund_records'|'payment_outbox'" \
  infrastructure/commerce/freeze-source-access.sh

echo 'CUTOVER review result: REJECTED. Keep ordinary traffic on commerce until every blocker is removed in one reviewed change.'
