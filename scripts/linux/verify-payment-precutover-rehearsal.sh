#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${1:-http://localhost:18090}"
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File "$PROJECT_ROOT/scripts/windows/verify-payment-precutover-rehearsal.ps1" \
    -BaseUrl "$BASE_URL"
elif command -v powershell.exe >/dev/null 2>&1; then
  WINDOWS_SCRIPT="$(wslpath -w "$PROJECT_ROOT/scripts/windows/verify-payment-precutover-rehearsal.ps1")"
  powershell.exe -NoProfile -File "$WINDOWS_SCRIPT" -BaseUrl "$BASE_URL"
else
  echo 'verify-payment-precutover-rehearsal.sh requires pwsh or WSL Windows PowerShell interop' >&2
  exit 1
fi
