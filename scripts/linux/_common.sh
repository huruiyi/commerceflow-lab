#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

compose() {
  docker compose --project-directory "$PROJECT_ROOT" "$@"
}

require_commands() {
  local command
  for command in "$@"; do
    command -v "$command" >/dev/null 2>&1 || {
      echo "Missing required command: $command" >&2
      exit 1
    }
  done
}

new_temp_dir() {
  mktemp -d 2>/dev/null || mktemp -d -t commerceflow
}

http_status() {
  local method="$1" url="$2" cookie_jar="${3:-}" csrf_header="${4:-}" csrf_token="${5:-}" body="${6:-}"
  local args=(-sS -o /dev/null -w '%{http_code}' -X "$method")
  [[ -n "$cookie_jar" ]] && args+=(-b "$cookie_jar" -c "$cookie_jar")
  [[ -n "$csrf_header" ]] && args+=(-H "$csrf_header: $csrf_token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$url"
}

assert_status() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "$label expected HTTP $expected but received $actual" >&2
    exit 1
  fi
  printf '  %-43s %s\n' "$label" "$actual"
}

storefront_login() {
  local base_url="$1" email="$2" cookie_jar="$3"
  local csrf header token status
  csrf="$(curl -fsS -c "$cookie_jar" -b "$cookie_jar" "$base_url/api/auth/csrf")"
  header="$(jq -r '.headerName' <<<"$csrf")"
  token="$(jq -r '.token' <<<"$csrf")"
  status="$(http_status POST "$base_url/api/auth/login" "$cookie_jar" "$header" "$token" \
    "$(jq -nc --arg email "$email" '{email:$email,password:"demo-password",rememberMe:false}')")"
  [[ "$status" == 200 ]] || { echo "Login failed for $email: HTTP $status" >&2; exit 1; }
  printf '%s\t%s\n' "$header" "$token"
}

payment_admin_login() {
  local base_url="$1" cookie_jar="$2"
  local csrf header token status
  csrf="$(curl -fsS -c "$cookie_jar" -b "$cookie_jar" "$base_url/api/auth/csrf")"
  header="$(jq -r '.headerName' <<<"$csrf")"
  token="$(jq -r '.token' <<<"$csrf")"
  status="$(http_status POST "$base_url/api/auth/login" "$cookie_jar" "$header" "$token" \
    '{"username":"payment-admin","password":"demo-password"}')"
  [[ "$status" == 200 ]] || { echo "Payment admin login failed: HTTP $status" >&2; exit 1; }
  printf '%s\t%s\n' "$header" "$token"
}

json_request() {
  local method="$1" url="$2" cookie_jar="${3:-}" csrf_header="${4:-}" csrf_token="${5:-}" body="${6:-}"
  local args=(-fsS -X "$method")
  [[ -n "$cookie_jar" ]] && args+=(-b "$cookie_jar" -c "$cookie_jar")
  [[ -n "$csrf_header" ]] && args+=(-H "$csrf_header: $csrf_token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$url"
}

uuid_hex() {
  openssl rand -hex 16
}
