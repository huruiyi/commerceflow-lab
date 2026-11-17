#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"
BASE_URL="${1:-http://localhost:18089}"
TEMPO_URL="${2:-http://localhost:13200}"

# The PowerShell verifier is the canonical cross-process scenario. This Bash entrypoint deliberately
# executes the same file so both platforms exercise identical IDs, signatures, SQL assertions and cleanup.
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File "$PROJECT_ROOT/scripts/windows/verify-order-remote-protocol.ps1" \
    -BaseUrl "$BASE_URL" -TempoUrl "$TEMPO_URL"
else
  echo 'verify-order-remote-protocol.sh requires pwsh so the paired scripts cannot drift' >&2
  exit 1
fi
