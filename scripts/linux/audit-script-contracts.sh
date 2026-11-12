#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
contracts="$PROJECT_ROOT/scripts/script-contracts.tsv"
fail(){ echo "Script audit failed: $*" >&2;exit 1; }

mapfile -t windows < <(for file in "$PROJECT_ROOT"/scripts/windows/verify-*.ps1;do basename "$file" .ps1;done|sort)
mapfile -t linux < <(for file in "$PROJECT_ROOT"/scripts/linux/verify-*.sh;do basename "$file" .sh;done|sort)
mapfile -t declared < <(awk -F '\t' '!/^#/&&NF{print $1}' "$contracts"|sort)
[[ "$(printf '%s\n' "${windows[@]}")" == "$(printf '%s\n' "${linux[@]}")" ]]||fail 'Windows/Bash verify-* filenames differ'
[[ "$(printf '%s\n' "${windows[@]}")" == "$(printf '%s\n' "${declared[@]}")" ]]||fail 'contract manifest coverage differs from verify-* files'

while IFS=$'\t' read -r name _ bindings;do
  [[ "$name" == \#* || -z "$name" ]]&&continue
  source_file="$PROJECT_ROOT/scripts/linux/$name.sh"
  IFS=',' read -ra items<<<"$bindings"
  for binding in "${items[@]}";do
    if [[ "$binding" == @* ]];then grep -Fq -- "--${binding#@}" "$source_file"||fail "$name.sh is missing $binding";continue;fi
    variable="${binding%%=*}";position="${binding#*=}"
    if [[ "$variable" == ARG ]];then grep -Fq -- "\${$position:-" "$source_file"||fail "$name.sh is missing $binding"
    else grep -Eq "${variable}[[:space:]]*=.*\\\$\\{${position}:-" "$source_file"||fail "$name.sh is missing $binding";fi
  done
done <"$contracts"

scan_files=()
for file in "$PROJECT_ROOT"/scripts/windows/*.ps1 "$PROJECT_ROOT"/scripts/linux/*.sh;do [[ "$(basename "$file")" == audit-script-contracts.* ]]||scan_files+=("$file");done
if grep -En 'docker[[:space:]]+volume[[:space:]]+(rm|prune)|docker[[:space:]]+compose[^[:cntrl:]]*[[:space:]]down[[:space:]]+-v([[:space:]]|$)' "${scan_files[@]}";then fail 'unscoped Docker volume deletion found';fi
mapfile -t volume_down < <(grep -El 'down[^[:cntrl:]]*--volumes' "${scan_files[@]}"||true)
[[ "${#volume_down[@]}" == 2 ]]||fail 'expected exactly two explicit fresh-volume cleanup implementations'
printf '%s\n' "${volume_down[@]}"|grep -vq 'verify-fresh-volume-lifecycle'&&fail 'volume cleanup exists outside fresh-volume scripts'
grep -Eq 'if\(\$RemoveTemporaryVolumes\).*down.*--volumes' "$PROJECT_ROOT/scripts/windows/verify-fresh-volume-lifecycle.ps1"||fail 'PowerShell volume cleanup is not explicitly guarded'
grep -Eq 'if \[\[ "\$REMOVE_TEMPORARY_VOLUMES" == true \]\].*down --volumes' "$PROJECT_ROOT/scripts/linux/verify-fresh-volume-lifecycle.sh"||fail 'Bash volume cleanup is not explicitly guarded'
if grep -En 'rm[[:space:]]+-rf[[:space:]]+(/|~|"?\$HOME|"?\$PROJECT_ROOT)|Remove-Item[^[:cntrl:]]*(\$HOME|\$ProjectRoot)[^[:cntrl:]]*-Recurse' "${scan_files[@]}";then fail 'broad recursive deletion target found';fi

echo "Script contract audit passed: ${#declared[@]} Windows/Bash verify pairs, ordered parameter bindings aligned, no default named-volume deletion; fresh-volume cleanup is explicit only."
