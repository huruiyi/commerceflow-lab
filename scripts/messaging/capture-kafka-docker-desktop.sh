#!/bin/sh
set -eu
export LC_ALL=C

destination=$1
engine=/mnt/docker-desktop-disk/data/docker
volume=fairy-commerceflow_kafka-broker-persistent-data
source=$engine/volumes/$volume/_data
log_dir=/var/lib/kafka/data

# Docker Desktop for Windows stores the single daily broker in its Linux VM.
# Resolve its current process and check the actual mount before reading it.
set -- $(grep -l '"Name":"/fairy-commerceflow-kafka-broker"' "$engine"/containers/*/config.v2.json)
test "$#" -eq 1
config=$1
container=$(basename "$(dirname "$config")")
pid=$(
  grep -l -x "0::/docker/$container" /proc/[0-9]*/cgroup 2>/dev/null |
    cut -d/ -f3 | while read -r candidate; do
      if [ "$(cat "/proc/$candidate/comm" 2>/dev/null)" = java ]; then
        printf '%s\n' "$candidate"
        break
      fi
    done
)
test -n "$pid"
start_time=$(awk '{print $22}' "/proc/$pid/stat")
test "$(stat -c '%d:%i' "$source")" = "$(stat -c '%d:%i' "/proc/$pid/root$log_dir")"
image=$(sed -n 's/.*"Image":"\(sha256:[a-f0-9]*\)".*/\1/p' "$config")
test "${#image}" -eq 71

tmp=$(mktemp -d /mnt/docker-desktop-disk/data/commerceflow-kafka-capture.XXXXXXXX)
mounted=false
cleanup() {
  if [ "$mounted" = true ]; then
    umount "$tmp/source" || return 1
  fi
  # Never descend into the mount path when cleaning temporary files.
  if [ -d "$tmp/source" ]; then
    rmdir "$tmp/source" || return 1
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT
mkdir "$tmp/source" "$tmp/data"
mount --bind "$source" "$tmp/source"
mounted=true
mount -o remount,bind,ro "$tmp/source"
source=$tmp/source

kafka() {
  tool=$1
  shift
  if ! timeout 20 nsenter -e -t "$pid" -m -n -u -i -p -r -w -S 1000 -G 1000 -- \
    /usr/bin/env -u KAFKA_LOG4J_OPTS "/opt/kafka/bin/kafka-$tool.sh" --bootstrap-server kafka-broker:9092 "$@" 2>"$tmp/kafka.stderr"; then
    printf 'The Kafka %s read failed\n' "$tool" >&2
    return 1
  fi
}

positions() {
  prefix=$1
  kafka topics --describe >"$tmp/topics"
  awk '/TopicId:/ { print $2 "," $4 "," $6 }' "$tmp/topics" >"$tmp/topic-ids"
  sort "$tmp/topic-ids" >"$prefix-topics.csv"
  for edge in earliest latest; do
    kafka get-offsets --time "$edge" >"$tmp/offsets"
    awk -F: '$1 !~ /^__/ && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ {print}' "$tmp/offsets" >"$tmp/business-offsets"
    test -s "$tmp/business-offsets"
    sort "$tmp/business-offsets" >"$prefix-$edge.txt"
  done
  kafka consumer-groups --all-groups --describe --offsets >"$tmp/groups"
  awk '$3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ {print $1 "," $2 "," $3 "," $4 "," $5 "," $6}' \
    "$tmp/groups" >"$tmp/commits"
  test -s "$tmp/commits"
  sort "$tmp/commits" >"$prefix-commits.csv"
}

positions "$tmp/before"
cp -a "$source/." "$tmp/data/"
cd "$tmp/data"
find . -type f -print >"$tmp/captured"
(cd "$source" && find . -type f -print) >"$tmp/live"
sort "$tmp/captured" >"$tmp/files"
sort "$tmp/live" >"$tmp/source-files"
cmp "$tmp/files" "$tmp/source-files"
test -s meta.properties
test -d __cluster_metadata-0
test -d __consumer_offsets-0
while IFS= read -r file; do
  case "$file" in
    ./__cluster_metadata-0/*.log)
      # The active KRaft log may append while the business offsets stay still.
      size=$(stat -c '%s' "$file")
      test "$(stat -c '%s' "$source/$file")" -ge "$size"
      head -c "$size" "$source/$file" | cmp -s "$file" - ;;
    *) cmp -s "$file" "$source/$file" ;;
  esac || { printf 'Kafka file changed during the copy: %s\n' "$file" >&2; exit 1; }
done <"$tmp/files"
positions "$tmp/after"
test "$(awk '{print $22}' "/proc/$pid/stat")" = "$start_time"
for state in topics.csv earliest.txt latest.txt commits.csv; do
  cmp "$tmp/before-$state" "$tmp/after-$state"
  cp "$tmp/after-$state" "$destination/$state"
done

# Capture an independent digest of the newest retained record in each nonempty
# business partition. These direct reads never join or commit a consumer group.
awk -F: 'NR==FNR {first[$1 FS $2]=$3; next} $3>first[$1 FS $2] {print $1 ":" $2 ":" $3-1}' \
  "$tmp/after-earliest.txt" "$tmp/after-latest.txt" >"$tmp/samples"
while IFS=: read -r topic partition offset; do
  kafka console-consumer --topic "$topic" --partition "$partition" --offset "$offset" \
    --max-messages 1 --timeout-ms 10000 --consumer-property enable.auto.commit=false \
    --property print.headers=true --property print.key=true >"$tmp/record"
  test -s "$tmp/record"
  if ! grep -q '^Processed a total of 1 messages' "$tmp/kafka.stderr"; then
    echo 'The Kafka verification read did not return one record' >&2
    exit 1
  fi
  digest=$(sha256sum "$tmp/record" | cut -d' ' -f1)
  printf '%s,%s,%s,%s\n' "$topic" "$partition" "$offset" "$digest"
done <"$tmp/samples" >"$destination/history.csv"

xargs sha256sum <"$tmp/files" >"$destination/data-files.sha256"
tar -czf "$destination/kafka-data.tar.gz" .
cp meta.properties "$destination/meta.properties"
printf '%s\n' "$image" >"$destination/image.txt"
printf '%s\n' "$container" >"$destination/source-container.txt"
