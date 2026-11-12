#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq

BASE_URL="${1:-http://localhost:14200}"
temp_dir="$(new_temp_dir)"
customer_jar="$temp_dir/customer.cookies"
admin_jar="$temp_dir/admin.cookies"
task_type='ACCEPTANCE_PROBE'
admin_header=''
admin_token=''

cleanup() {
  if [[ -n "$admin_header" && -n "$admin_token" ]]; then
    http_status DELETE "$BASE_URL/api/admin/scheduling/policies/$task_type" "$admin_jar" "$admin_header" "$admin_token" >/dev/null || true
  fi
  rm -rf -- "$temp_dir"
}
trap cleanup EXIT

customer_auth="$(storefront_login "$BASE_URL" 'demo@example.com' "$customer_jar")"
IFS=$'\t' read -r customer_header customer_token <<<"$customer_auth"
assert_status 'customer scheduling admin rejected' 403 \
  "$(http_status GET "$BASE_URL/api/admin/scheduling/policies" "$customer_jar")"

admin_page="$(curl -fsS "$BASE_URL/admin/scheduling")"
main_script="$(sed -n 's/.*src="\(main-[^"]*\.js\)".*/\1/p' <<<"$admin_page" | head -n1)"
[[ -n "$main_script" ]] || { echo 'Scheduling admin page did not reference the Angular bundle' >&2; exit 1; }
bundle="$(curl -fsS "$BASE_URL/$main_script")"
grep -Fq 'Scheduling governance' <<<"$bundle" || { echo 'English scheduling UI is missing' >&2; exit 1; }
grep -Fiq '\u4efb\u52a1\u8c03\u5ea6\u6cbb\u7406' <<<"$bundle" || { echo 'Chinese scheduling UI is missing' >&2; exit 1; }

admin_auth="$(storefront_login "$BASE_URL" 'admin@example.com' "$admin_jar")"
IFS=$'\t' read -r admin_header admin_token <<<"$admin_auth"
body='{"enabled":false,"primaryTrigger":"DATABASE","reconciliationEnabled":true,"gracePeriodSeconds":120,"claimLeaseSeconds":120,"maxAttempts":5,"retryDelaySeconds":30}'
saved="$(json_request PUT "$BASE_URL/api/admin/scheduling/policies/$task_type" "$admin_jar" "$admin_header" "$admin_token" "$body")"
[[ "$(jq -r .taskType <<<"$saved")" == "$task_type" ]] || { echo 'Unexpected task type' >&2; exit 1; }
[[ "$(jq -r .source <<<"$saved")" == DATABASE_OVERRIDE ]] || { echo 'Policy override was not stored' >&2; exit 1; }
[[ "$(jq -r .handlerAvailable <<<"$saved")" == false ]] || { echo 'Probe unexpectedly has executable code' >&2; exit 1; }

events="$(json_request GET "$BASE_URL/api/admin/scheduling/policies/$task_type/events" "$admin_jar")"
[[ "$(jq -r '.[0].action' <<<"$events")" == UPSERT ]] || { echo 'Policy audit event is missing' >&2; exit 1; }
[[ "$(jq -r '.[0].operatedBy' <<<"$events")" == admin@example.com ]] || { echo 'Policy audit operator is invalid' >&2; exit 1; }

reset="$(json_request DELETE "$BASE_URL/api/admin/scheduling/policies/$task_type" "$admin_jar" "$admin_header" "$admin_token")"
admin_header=''
admin_token=''
[[ "$(jq -r .source <<<"$reset")" == CONFIG_DEFAULT ]] || { echo 'Policy did not reset to configuration' >&2; exit 1; }

echo 'Generic scheduling verification passed: RBAC, bilingual admin UI, database override, audit event and reset-to-config.'
