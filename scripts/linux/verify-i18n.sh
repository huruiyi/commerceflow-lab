#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq
BASE_URL="${1:-http://localhost:14200}"

en="$(curl -fsS "$BASE_URL/api/products?lang=en")"
zh="$(curl -fsS "$BASE_URL/api/products?lang=zh-CN")"
[[ "$(jq -r '.[0].name' <<<"$en")" != "$(jq -r '.[0].name' <<<"$zh")" ]] || { echo 'Product translation was not selected by locale' >&2; exit 1; }
jq -e '.[0].name | contains("学习")' <<<"$zh" >/dev/null || { echo 'Chinese product translation is missing' >&2; exit 1; }
keys="$(compose exec -T commerce-redis redis-cli --scan --pattern 'commerceflow:catalog:products:v1:*')"
grep -Fxq 'commerceflow:catalog:products:v1:en' <<<"$keys"
grep -Fxq 'commerceflow:catalog:products:v1:zh-CN' <<<"$keys"

temp_dir="$(new_temp_dir)"; trap 'rm -rf -- "$temp_dir"' EXIT
jar="$temp_dir/login.cookies"
csrf="$(curl -fsS -c "$jar" "$BASE_URL/api/auth/csrf")"
response="$temp_dir/error.json"
status="$(curl -sS -o "$response" -w '%{http_code}' -b "$jar" -c "$jar" -H "$(jq -r .headerName <<<"$csrf"): $(jq -r .token <<<"$csrf")" -H 'Content-Type: application/json' --data '{"email":"demo@example.com","password":"wrong-password","rememberMe":false}' "$BASE_URL/api/auth/login")"
[[ "$status" == 401 && "$(jq -r .code "$response")" == AUTH_INVALID ]] || { echo 'Stable error code AUTH_INVALID is missing' >&2; exit 1; }
curl -fsS "$BASE_URL" | grep -q '<app-root' || { echo 'Angular application unavailable' >&2; exit 1; }
echo 'Chapter 09 verification passed: English/Chinese products, isolated cache, persisted UI bundle, error code AUTH_INVALID'
