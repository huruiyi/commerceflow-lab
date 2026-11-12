#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl base32 xxd od
BASE_URL="${1:-http://localhost:14201}"
temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
jar="$temp_dir/admin.cookies"
IFS=$'\t' read -r csrf_header csrf_token <<<"$(payment_admin_login "$BASE_URL" "$jar")"

sha256_hex() { printf '%s' "$1" | openssl dgst -sha256 -hex | awk '{print $NF}'; }

signed_headers() {
  local app_id="$1" secret="$2" method="$3" target="$4" body="$5" timestamp="${6:-$(date +%s)}"
  local nonce="${7:-$(uuid_hex)}" request_id hash canonical signature
  request_id="$(uuid_hex)"; hash="$(sha256_hex "$body")"
  canonical="$(printf '%s\n%s\n%s\n%s\n%s\n%s' "$method" "$target" "$timestamp" "$nonce" "$request_id" "$hash")"
  signature="$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" -binary | openssl base64 -A)"
  SIGNED_HEADERS=(-H "X-App-Id: $app_id" -H 'X-Signature-Algorithm: HMAC_SHA256' -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Request-Id: $request_id" -H "X-Body-SHA256: $hash" -H "X-Signature: $signature")
}

rsa_signed_headers() {
  local app_id="$1" private_key="$2" method="$3" target="$4" body="$5"
  local timestamp nonce request_id hash canonical signature
  timestamp="$(date +%s)"; nonce="$(uuid_hex)"; request_id="$(uuid_hex)"; hash="$(sha256_hex "$body")"
  canonical="$(printf '%s\n%s\n%s\n%s\n%s\n%s' "$method" "$target" "$timestamp" "$nonce" "$request_id" "$hash")"
  signature="$(printf '%s' "$canonical" | openssl dgst -sha256 -sign "$private_key" | openssl base64 -A)"
  SIGNED_HEADERS=(-H "X-App-Id: $app_id" -H 'X-Signature-Algorithm: RSA_SHA256' -H "X-Timestamp: $timestamp" -H "X-Nonce: $nonce" -H "X-Request-Id: $request_id" -H "X-Body-SHA256: $hash" -H "X-Signature: $signature")
}

assert_json_value() {
  local json="$1" filter="$2" expected="$3" label="$4" actual
  actual="$(jq -r "$filter" <<<"$json")"
  [[ "$actual" == "$expected" ]] || { echo "$label: expected [$expected], actual [$actual]" >&2; exit 1; }
}

signed_create() {
  local app_id="$1" secret="$2" body="$3"
  signed_headers "$app_id" "$secret" POST /api/channel/transactions "$body"
  curl -fsS -X POST "${SIGNED_HEADERS[@]}" -H 'Content-Type: application/json' --data "$body" "$BASE_URL/api/channel/transactions"
}

signed_status() {
  local body="$1"; shift
  curl -sS -o /dev/null -w '%{http_code}' -X POST "$@" -H 'Content-Type: application/json' --data "$body" "$BASE_URL/api/channel/transactions"
}

suffix="$(date +%s%3N)"
merchant_body="$(jq -nc --arg name "05A-3 Security Merchant $suffix" '{displayName:$name,notifyUrl:"http://commerce-service:8080/api/payment/notifications",signingAlgorithm:"HMAC_SHA256",merchantPublicKey:""}')"
issued="$(json_request POST "$BASE_URL/api/admin/merchants" "$jar" "$csrf_header" "$csrf_token" "$merchant_body")"
assert_json_value "$issued" '.merchant.signingAlgorithm' HMAC_SHA256 'Merchant signing algorithm'
app_id="$(jq -r .merchant.appId <<<"$issued")"; app_secret="$(jq -r .appSecret <<<"$issued")"; merchant_id="$(jq -r .merchant.id <<<"$issued")"

actions=()
for channel in ALIPAY WECHAT_PAY UNION_PAY; do
  body="$(jq -nc --arg no "SECURE-$channel-$suffix" --arg channel "$channel" '{merchantRequestNo:$no,channel:$channel,subject:("05A-3 " + $channel),amount:12.34,currency:"CNY",faultScenario:"NONE"}')"
  transaction="$(signed_create "$app_id" "$app_secret" "$body")"
  assert_json_value "$transaction" .status PENDING "$channel signed create"
  actions+=("$(jq -r .actionType <<<"$transaction")")
done
[[ "${actions[*]}" == 'REDIRECT QR_CODE GATEWAY' ]] || { echo "Unexpected channel actions: ${actions[*]}" >&2; exit 1; }

private_key="$temp_dir/rsa-private.pem"; public_key="$temp_dir/rsa-public.pem"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$private_key" >/dev/null 2>&1
openssl pkey -in "$private_key" -pubout -out "$public_key" >/dev/null 2>&1
rsa_merchant_body="$(jq -nc --arg name "05A-3 RSA Merchant $suffix" --rawfile key "$public_key" '{displayName:$name,notifyUrl:"http://commerce-service:8080/api/payment/notifications",signingAlgorithm:"RSA_SHA256",merchantPublicKey:$key}')"
rsa_issued="$(json_request POST "$BASE_URL/api/admin/merchants" "$jar" "$csrf_header" "$csrf_token" "$rsa_merchant_body")"
rsa_body="$(jq -nc --arg no "SECURE-RSA-$suffix" '{merchantRequestNo:$no,channel:"UNION_PAY",subject:"05A-3 RSA verification",amount:23.45,currency:"CNY",faultScenario:"NONE"}')"
rsa_signed_headers "$(jq -r .merchant.appId <<<"$rsa_issued")" "$private_key" POST /api/channel/transactions "$rsa_body"
rsa_transaction="$(curl -fsS -X POST "${SIGNED_HEADERS[@]}" -H 'Content-Type: application/json' --data "$rsa_body" "$BASE_URL/api/channel/transactions")"
assert_json_value "$rsa_transaction" .status PENDING 'RSA signed create'

test_body="$(jq -nc --arg no "REPLAY-$suffix" '{merchantRequestNo:$no,channel:"ALIPAY",subject:"security negative tests",amount:8.88,currency:"CNY",faultScenario:"NONE"}')"
signed_headers "$app_id" "$app_secret" POST /api/channel/transactions "$test_body"
curl -fsS -X POST "${SIGNED_HEADERS[@]}" -H 'Content-Type: application/json' --data "$test_body" "$BASE_URL/api/channel/transactions" >/dev/null
assert_status 'duplicate nonce rejection' 409 "$(signed_status "$test_body" "${SIGNED_HEADERS[@]}")"

signed_headers "$app_id" "$app_secret" POST /api/channel/transactions "$test_body"
tampered="${test_body/8.88/9.99}"
assert_status 'tampered amount rejection' 401 "$(signed_status "$tampered" "${SIGNED_HEADERS[@]}")"
signed_headers "$app_id" "$app_secret" POST /api/channel/transactions "$test_body" "$(( $(date +%s) - 600 ))"
assert_status 'expired timestamp rejection' 401 "$(signed_status "$test_body" "${SIGNED_HEADERS[@]}")"
signed_headers "$app_id" wrong-secret POST /api/channel/transactions "$test_body"
assert_status 'wrong signature rejection' 401 "$(signed_status "$test_body" "${SIGNED_HEADERS[@]}")"
signed_headers unknown-app unknown-secret POST /api/channel/transactions "$test_body"
assert_status 'unknown merchant rejection' 401 "$(signed_status "$test_body" "${SIGNED_HEADERS[@]}")"

totp_code() {
  local secret="$1" counter_hex key_hex digest offset binary
  key_hex="$(printf '%s' "$secret" | tr -d ' =\r\n' | base32 --decode | od -An -tx1 | tr -d ' \n')"
  counter_hex="$(printf '%016x' "$(( $(date +%s) / 30 ))")"
  read -ra digest <<<"$(printf '%s' "$counter_hex" | xxd -r -p | openssl dgst -sha1 -mac HMAC -macopt "hexkey:$key_hex" -binary | od -An -tu1 | tr '\n' ' ')"
  offset=$(( digest[19] & 15 ))
  binary=$(( ((digest[offset] & 127) << 24) | (digest[offset+1] << 16) | (digest[offset+2] << 8) | digest[offset+3] ))
  printf '%06d' "$((binary % 1000000))"
}

enrollment="$(json_request POST "$BASE_URL/api/auth/mfa/enroll" "$jar" "$csrf_header" "$csrf_token" '{"password":"demo-password"}')"
totp="$(totp_code "$(jq -r .secret <<<"$enrollment")")"
recovery="$(json_request POST "$BASE_URL/api/auth/mfa/confirm" "$jar" "$csrf_header" "$csrf_token" "$(jq -nc --arg code "$totp" '{code:$code}')")"
[[ "$(jq '.codes | length' <<<"$recovery")" == 8 ]] || { echo 'Recovery code count is not 8' >&2; exit 1; }
totp="$(totp_code "$(jq -r .secret <<<"$enrollment")")"
step_up="$(json_request POST "$BASE_URL/api/auth/step-up" "$jar" "$csrf_header" "$csrf_token" "$(jq -nc --arg code "$totp" '{password:"demo-password",code:$code}')")"
step_token="$(jq -r .token <<<"$step_up")"
rotated="$(curl -fsS -X POST -b "$jar" -c "$jar" -H "$csrf_header: $csrf_token" -H "X-Step-Up-Token: $step_token" "$BASE_URL/api/admin/merchants/$merchant_id/rotate-secret")"
assert_json_value "$rotated" '.merchant.credentialVersion' 2 'Credential rotation version'
replay_status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST -b "$jar" -H "$csrf_header: $csrf_token" -H "X-Step-Up-Token: $step_token" "$BASE_URL/api/admin/merchants/$merchant_id/rotate-secret")"
assert_status 'one-time step-up token replay rejection' 401 "$replay_status"
audit="$(json_request GET "$BASE_URL/api/admin/audit-logs" "$jar")"
jq -e '.[0].eventHash and .[0].previousHash' <<<"$audit" >/dev/null || { echo 'Audit hash chain fields are missing' >&2; exit 1; }
echo '05A-3 verification passed: HMAC-channels=3, RSA-channel=1, negative-signature-cases=5, MFA=TOTP+8-recovery-codes, step-up=one-time, audit-chain=present'
