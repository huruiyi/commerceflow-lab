#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://127.0.0.1:18091}"
secret="${FULFILLMENT_INTERNAL_SECRET:-demo-fulfillment-shadow-secret}"
http=curl
null_device=/dev/null
if [[ -n "${WSL_DISTRO_NAME:-}" ]] && command -v curl.exe >/dev/null; then
  http=curl.exe
  null_device=NUL
fi

signed_post() {
  local suffix="$1" body="$2" expected="$3" path timestamp request_id hash signature reply code
  path="/internal/v1/fulfillment$suffix"
  timestamp="$(date +%s)"
  request_id="$(cat /proc/sys/kernel/random/uuid)"
  hash="$(printf '%s' "$body" | openssl dgst -sha256 | awk '{print $NF}')"
  signature="$(printf '%s\n%s\n%s\n%s\n%s' POST "$path" "$timestamp" "$request_id" "$hash" \
    | openssl dgst -sha256 -hmac "$secret" | awk '{print $NF}')"
  reply="$(printf '%s' "$body" | "$http" -sS --max-time 20 -w '\n%{http_code}' -X POST "$base_url$path" \
    -H 'Content-Type: application/json' -H "X-CommerceFlow-Timestamp: $timestamp" \
    -H "X-CommerceFlow-Request-Id: $request_id" -H "X-CommerceFlow-Content-SHA256: $hash" \
    -H "X-CommerceFlow-Signature: $signature" --data-binary @-)"
  code="${reply##*$'\n'}"
  if [[ "$code" != "$expected" ]]; then
    echo "$suffix returned $code, expected $expected" >&2
    return 1
  fi
  if [[ "$expected" == 200 ]]; then printf '%s\n' "${reply%$'\n'*}"; fi
}

unsigned="$("$http" -sS --max-time 15 -o "$null_device" -w '%{http_code}' \
  "$base_url/internal/v1/fulfillment/shadow/status")"
[[ "$unsigned" == 401 ]]
signed_post /commands '{"version":1}' 503
before="$(signed_post /shadow/snapshot '{}' 200)"
after="$(signed_post /shadow/snapshot '{}' 200)"
if [[ "$(jq -r '.watermark.sourceSequence' <<< "$before")" != "$(jq -r '.watermark.sourceSequence' <<< "$after")" ]]; then
  echo 'Source changed during the two snapshots. Rerun to compare at one watermark.' >&2
  exit 1
fi
jq -e --argjson before "$before" '
  .watermark.generation == ($before.watermark.generation + 1)
  and .appliedRows == 0 and .openOrphans == 0 and (.tables | length == 6)
  and .tables == $before.tables
' <<< "$after" >/dev/null
printf 'Fulfillment shadow verified at watermark %s, six tables, write gate 503\n' "$(jq -r '.watermark.sourceSequence' <<< "$after")"
