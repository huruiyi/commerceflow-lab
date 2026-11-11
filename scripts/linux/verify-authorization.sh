#!/usr/bin/env bash
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_common.sh"
require_commands curl jq openssl

BASE_URL="${1:-http://localhost:14200}"
temp_dir="$(new_temp_dir)"
trap 'rm -rf -- "$temp_dir"' EXIT

echo 'Anonymous and authentication checks'
assert_status 'public product catalog' 200 "$(http_status GET "$BASE_URL/api/products")"
assert_status 'anonymous cart rejected' 401 "$(http_status GET "$BASE_URL/api/cart")"
assert_status 'anonymous admin API rejected' 401 "$(http_status GET "$BASE_URL/api/users")"
assert_status 'login without CSRF rejected' 403 "$(http_status POST "$BASE_URL/api/auth/login" '' '' '' '{"email":"admin@example.com","password":"demo-password","rememberMe":false}')"

disabled_jar="$temp_dir/disabled.cookies"
csrf="$(curl -fsS -c "$disabled_jar" "$BASE_URL/api/auth/csrf")"
assert_status 'disabled user rejected' 401 "$(http_status POST "$BASE_URL/api/auth/login" "$disabled_jar" "$(jq -r .headerName <<<"$csrf")" "$(jq -r .token <<<"$csrf")" '{"email":"ada@example.com","password":"demo-password","rememberMe":false}')"

verify_role() {
  local email="$1" role="$2"; shift 2
  local jar="$temp_dir/$role.cookies" auth header token profile path expected
  auth="$(storefront_login "$BASE_URL" "$email" "$jar")"
  IFS=$'\t' read -r header token <<<"$auth"
  profile="$(curl -fsS -b "$jar" "$BASE_URL/api/auth/me")"
  [[ "$(jq -r .role <<<"$profile")" == "$role" ]] || { echo "$email returned an unexpected role" >&2; exit 1; }
  echo "Role $role"
  while (($#)); do
    path="$1"; expected="$2"; shift 2
    assert_status "$role GET $path" "$expected" "$(http_status GET "$BASE_URL$path" "$jar")"
  done
}

verify_role demo@example.com CUSTOMER /api/products 200 /api/cart 200 /api/addresses 200 /api/purchase-orders 200 /api/merchant/orders 403 /api/users 403
verify_role merchant@example.com MERCHANT /api/products 200 /api/merchant/orders 200 /api/cart 403 /api/addresses 403 /api/purchase-orders 403 /api/users 403
verify_role admin@example.com ADMIN /api/products 200 /api/users 200 /api/cart 403 /api/addresses 403 /api/purchase-orders 403 /api/merchant/orders 403
echo 'Authorization verification passed: authentication, disabled account, CSRF, all role boundaries.'
