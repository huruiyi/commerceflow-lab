#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${1:-http://localhost:18089}"
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File "$PROJECT_ROOT/scripts/windows/verify-order-precutover-rehearsal.ps1" \
    -BaseUrl "$BASE_URL"
else
  echo 'verify-order-precutover-rehearsal.sh requires pwsh so the paired scripts cannot drift' >&2
  exit 1
fi
