#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${1:-http://localhost:18090}"
TEMPO_URL="${2:-http://localhost:13200}"

# The PowerShell verifier is canonical so Windows and WSL exercise the same signed payloads,
# database assertions, outage windows and non-destructive cleanup.
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File "$PROJECT_ROOT/scripts/windows/verify-payment-remote-protocol.ps1" \
    -BaseUrl "$BASE_URL" -TempoUrl "$TEMPO_URL"
elif command -v powershell.exe >/dev/null 2>&1; then
  WINDOWS_SCRIPT="$(wslpath -w "$PROJECT_ROOT/scripts/windows/verify-payment-remote-protocol.ps1")"
  powershell.exe -NoProfile -File "$WINDOWS_SCRIPT" -BaseUrl "$BASE_URL" -TempoUrl "$TEMPO_URL"
else
  echo 'verify-payment-remote-protocol.sh requires pwsh or WSL Windows PowerShell interop' >&2
  exit 1
fi
