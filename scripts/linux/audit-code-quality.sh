#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
fail(){ echo "Code quality audit failed: $*" >&2; exit 1; }
if locale -a 2>/dev/null | grep -Eiq '^C\.UTF-?8$'; then
  export LC_ALL=C.UTF-8
fi

check_max_line_length(){
  local file="$1" relative="$2" line='' line_number=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    ((${#line} <= 120)) || fail "$relative:$line_number exceeds 120 characters"
  done <"$file"
}

java_roots=(
  "$PROJECT_ROOT/services/commerce-service/src/main/java"
  "$PROJECT_ROOT/simulators/payment-platform/service/src/main/java"
  "$PROJECT_ROOT/simulators/logistics-platform/service/src/main/java"
)
java_source_roots=(
  "$PROJECT_ROOT/services/commerce-service/src"
  "$PROJECT_ROOT/simulators/payment-platform/service/src"
  "$PROJECT_ROOT/simulators/logistics-platform/service/src"
)
mapper_roots=(
  "$PROJECT_ROOT/services/commerce-service/src/main/resources/mapper"
  "$PROJECT_ROOT/simulators/payment-platform/service/src/main/resources/mapper"
  "$PROJECT_ROOT/simulators/logistics-platform/service/src/main/resources/mapper"
)
pom_files=(
  "$PROJECT_ROOT/services/commerce-service/pom.xml"
  "$PROJECT_ROOT/simulators/payment-platform/service/pom.xml"
  "$PROJECT_ROOT/simulators/logistics-platform/service/pom.xml"
)

mapfile -d '' -t java_files < <(find "${java_roots[@]}" -type f -name '*.java' -print0)
mapfile -d '' -t all_java_files < <(find "${java_source_roots[@]}" -type f -name '*.java' -print0)
mapfile -d '' -t mapper_files < <(find "${mapper_roots[@]}" -maxdepth 1 -type f -name '*.xml' -print0)
format_files=("${java_files[@]}" "${mapper_files[@]}" "${pom_files[@]}")

for file in "${format_files[@]}"; do
  relative="${file#"$PROJECT_ROOT/"}"
  [[ -s "$file" ]] || fail "$relative is empty"
  [[ "$(tail -c 1 "$file" | od -An -tuC | tr -d '[:space:]')" == 10 ]] ||
    fail "$relative must end with a newline"
  if grep -n $'\t' "$file" >/dev/null; then fail "$relative contains a tab"; fi
  if grep -nE '[[:blank:]]+$' "$file" >/dev/null; then fail "$relative contains trailing whitespace"; fi
done

for file in "${all_java_files[@]}"; do
  relative="${file#"$PROJECT_ROOT/"}"
  if awk '
    FNR == 1 { previous="" }
    previous ~ /^[[:space:]]*((public|protected|private|static|final|abstract|sealed|non-sealed)[[:space:]]+)*class[[:space:]]+[A-Za-z_$][A-Za-z0-9_$]*[^{]*\{[[:space:]]*$/ &&
      $0 !~ /^[[:space:]]*$/ { print FNR - 1; exit }
    { previous=$0 }
  ' "$file" | grep -q .; then
    fail "$relative must have a blank line after every class declaration"
  fi
done

for file in "${java_files[@]}"; do
  relative="${file#"$PROJECT_ROOT/"}"
  check_max_line_length "$file" "$relative"
  if grep -Eq '^[[:space:]]*import[[:space:]]+[^;]+\.\*;' "$file"; then
    fail "$relative uses a wildcard import"
  fi
  if awk '
    {
      count=0
      size=split($0, words, /[^[:alnum:]_]+/)
      for(word_index=1; word_index<=size; word_index++) {
        if(words[word_index] == "public" || words[word_index] == "protected" || words[word_index] == "private") count++
      }
      if(count > 1) found=1
    }
    END { exit found ? 0 : 1 }
  ' "$file"; then
    fail "$relative declares multiple members on one line"
  fi
  if grep -Eq '@(Select|Insert|Update|Delete)(Provider)?[[:space:]]*\(' "$file"; then
    fail "$relative contains inline MyBatis SQL"
  fi
  if [[ "$(basename "$file")" != SchedulerConfiguration.java ]] &&
      grep -Eq 'import[[:space:]]+org\.springframework\.jdbc\.' "$file"; then
    fail "$relative introduces direct Spring JDBC access"
  fi
done

logistics_java_root="$PROJECT_ROOT/simulators/logistics-platform/service/src/main/java/vip/fairy/commerceflow/simulator/logistics"
for layer in api application domain infrastructure; do
  [[ -d "$logistics_java_root/$layer" ]] || fail "logistics simulator is missing the $layer layer"
done
unexpected_logistics_root_class="$(
  find "$logistics_java_root" -maxdepth 1 -type f -name '*.java' \
    ! -name 'LogisticsSimulatorApplication.java' -print -quit
)"
[[ -z "$unexpected_logistics_root_class" ]] ||
  fail "logistics simulator root package contains $(basename "$unexpected_logistics_root_class")"

statement_count=0
for mapper_file in "${mapper_files[@]}"; do
  relative="${mapper_file#"$PROJECT_ROOT/"}"
  check_max_line_length "$mapper_file" "$relative"
  namespace="$(sed -nE 's/.*<mapper namespace="([^"]+)".*/\1/p' "$mapper_file")"
  [[ -n "$namespace" ]] || fail "$relative has no mapper namespace"
  java_relative="${namespace//.//}.java"
  mapper_interfaces=()
  for root in "${java_roots[@]}"; do
    [[ -f "$root/$java_relative" ]] && mapper_interfaces+=("$root/$java_relative")
  done
  [[ "${#mapper_interfaces[@]}" == 1 ]] ||
    fail "$relative namespace $namespace resolves to ${#mapper_interfaces[@]} Java interfaces"

  mapfile -t statement_ids < <(
    grep -E '<(select|insert|update|delete)[[:space:]]' "$mapper_file" |
      sed -nE 's/.*id="([^"]+)".*/\1/p'
  )
  duplicate="$(printf '%s\n' "${statement_ids[@]}" | sort | uniq -d | head -n 1)"
  [[ -z "$duplicate" ]] || fail "$relative contains duplicate statement id $duplicate"
  for statement_id in "${statement_ids[@]}"; do
    grep -Eq "\\b${statement_id}[[:space:]]*\\(" "${mapper_interfaces[0]}" ||
      fail "$relative statement $statement_id has no method in $namespace"
    statement_count=$((statement_count + 1))
  done
done

summary="Code quality audit passed: ${#java_files[@]} Java files, ${#mapper_files[@]} Mapper XML files, "
summary+="$statement_count mapped statements, 3 Maven POM files; class spacing across ${#all_java_files[@]} Java sources, "
summary+='format rules, logistics layers, inline SQL boundary and '
summary+='Mapper contracts are valid.'
echo "$summary"
