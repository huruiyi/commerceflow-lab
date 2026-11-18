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
assert_contains 'independent service owns ordinary traffic' 'compose.yaml' \
  'PAYMENT_ACCEPTING_TRAFFIC: \$\{PAYMENT_ACCEPTING_TRAFFIC:-true\}'
assert_contains 'independent service owns downstream effects' 'compose.yaml' \
  'PAYMENT_EFFECTS_ENABLED: \$\{PAYMENT_EFFECTS_ENABLED:-true\}'
assert_contains 'shadow replication is disabled in the default graph' 'compose.yaml' \
  'PAYMENT_SHADOW_ENABLED: "false"'
assert_absent 'payment runtime has no commerce source credentials' 'PAYMENT_SOURCE_DATABASE_' compose.yaml
assert_absent 'PostgreSQL is not configured' '(postgres|jdbc:postgresql)' compose.yaml compose.ha.yaml

echo 'Payment CUTOVER prerequisite closure:'
assert_file 'independent remote Order V1 effect client' \
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/infrastructure/RemoteOrderEffectClient.java'
assert_file 'independent remote Inventory command client' \
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/infrastructure/RemoteInventoryEffectClient.java'
assert_file 'independent payment Outbox dispatcher' \
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/application/PaymentEffectDispatcher.java'
assert_file 'independent refund initiation command endpoint' \
  'services/payment-orchestration-service/src/main/java/vip/fairy/commerceflow/payment/api/RefundCommandController.java'
assert_contains 'commerce refund ingress uses Payment V1' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundController.java' \
  'PaymentOrchestrationV1'
assert_contains 'commerce payment write freeze defaults open' 'compose.yaml' \
  'PAYMENT_WRITE_FROZEN: \$\{PAYMENT_WRITE_FROZEN:-false\}'
assert_contains 'payment write admission protects cancellation before mutation' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/ordering/application/OrderCancellationService.java' \
  'paymentWrites.requireWritable()'
assert_contains 'payment write admission protects payment creation' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/ordering/application/PurchaseOrderService.java' \
  'paymentWrites.requireWritable()'
assert_contains 'payment write admission protects refund creation' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/refund/api/RefundController.java' \
  'paymentWrites.requireWritable()'

echo 'Payment CUTOVER single-owner proof:'
assert_file 'commerce remote Payment V1 adapter' \
  'services/commerce-service/src/main/java/vip/fairy/commerceflow/commerce/payment/infrastructure/remote/RemotePaymentOrchestrationAdapter.java'
assert_absent 'commerce local payment and refund runtime is absent' \
  'LocalPaymentOrchestrationAdapter|PaymentNotificationController|RefundNotificationController|class RefundService|interface RefundMapper' \
  services/commerce-service/src/main/java
assert_contains 'payment callback targets independent service' 'compose.yaml' \
  'PAYMENT_CALLBACK_URL: http://payment-orchestration-service:8080/api/payment-notifications/callback'
assert_contains 'refund callback targets independent service' 'compose.yaml' \
  'REFUND_CALLBACK_URL: http://payment-orchestration-service:8080/api/refund-notifications/callback'
assert_contains 'commerce runtime grant freezer denies payment source tables' \
  'infrastructure/commerce/freeze-source-access.sh' "'payment_records'"
assert_contains 'commerce runtime grant freezer denies refund source tables' \
  'infrastructure/commerce/freeze-source-access.sh' "'refund_records'"

echo 'CUTOVER review result: APPROVED. Payment orchestration has one remote owner.'
