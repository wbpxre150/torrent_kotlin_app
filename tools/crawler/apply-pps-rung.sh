#!/usr/bin/env bash
# Apply one `udp_rate_limit_pps` rung through the dashboard's crawler-settings form.
#
# THE TRAP THIS SCRIPT EXISTS FOR: `POST /settings/crawler` is a full-form endpoint, not a
# patch. `crawlerSettingsFromForm` reads every checkbox with `form.containsKey(...)`, so a
# body that omits `enabled` or `classifierEnabled` sets them to FALSE — a minimal
# "advancedOverrides=..." POST silently disables the crawler and the classifier. It also
# rejects an empty `saveCategories` ("tick at least one category to save"). So this script
# rebuilds the whole form from the device's own config.ini + SharedPreferences, rewrites
# exactly one line of the advanced-overrides block, posts, then asserts that nothing else
# moved.
#
# Why the form and not a hand-edited config.ini: the app persists the value in
# `app_config.xml:crawler_advanced_overrides` and rewrites config.ini from it on every apply,
# so a hand-edited config.ini is reverted by the next apply and would not survive the
# force-stop + relaunch that the plan's exit criterion 5 requires.
#
# Usage: tools/crawler/apply-pps-rung.sh <pps> [serial]
#        ANDROID_SERIAL=b078fce tools/crawler/apply-pps-rung.sh 4000
#
# Requires the crawler on 127.0.0.1:8080 and the dashboard on 127.0.0.1:8090
# (`adb forward tcp:8080 tcp:8081; adb forward tcp:8090 tcp:8090`).
# Exits non-zero unless the crawler comes back up echoing the requested rung AND the guard
# keys still hold their previous values.
set -euo pipefail

PPS=${1:?usage: apply-pps-rung.sh <pps> [serial]}
SERIAL=${2:-${ANDROID_SERIAL:-}}
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

APP=com.torrent.app
PREFS=/data/data/$APP/shared_prefs/app_config.xml
CFG=files/crawler/config.ini

TOKEN=$("${ADB[@]}" exec-out run-as $APP cat /data/data/$APP/shared_prefs/control_auth.xml \
        | grep -o 'auth_token">[0-9a-f]*' | cut -d'>' -f2)
[ -n "$TOKEN" ] || { echo "error: no control token (is the app installed and launched?)" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
"${ADB[@]}" exec-out run-as $APP cat "$CFG" | grep -vE '^\s*#|^\s*$' > "$TMP/config.ini"
"${ADB[@]}" exec-out run-as $APP cat "$PREFS" > "$TMP/prefs.xml"

cfg() {  # cfg <config.ini key> [default]
  local v
  v=$(grep -oE "^$1 *= *[^ ]+" "$TMP/config.ini" | head -1 | sed -E "s/^$1 *= *//")
  echo "${v:-${2:-}}"
}
pref() { # pref <prefs key> [default]
  local v
  v=$(grep -oE "name=\"$1\" value=\"[^\"]*\"" "$TMP/prefs.xml" | head -1 | sed -E 's/.*value="([^"]*)"/\1/')
  echo "${v:-${2:-}}"
}

# ---- the one line that moves -------------------------------------------------
grep -o 'name="crawler_advanced_overrides">[^<]*' "$TMP/prefs.xml" \
  | sed 's/^name="crawler_advanced_overrides">//' \
  | sed 's/&#10;/\n/g; s/&quot;/"/g; s/&amp;/\&/g' > "$TMP/adv.old"
grep -q '^udp_rate_limit_pps' "$TMP/adv.old" || {
  echo "error: current overrides carry no udp_rate_limit_pps line:" >&2; cat "$TMP/adv.old" >&2; exit 1; }
sed "s/^udp_rate_limit_pps *= *[0-9]*/udp_rate_limit_pps = ${PPS}/" "$TMP/adv.old" > "$TMP/adv.new"

# ---- the rest of the form, rebuilt from the device's own state ----------------
ARGS=()
add()  { ARGS+=(--data-urlencode "$1=$2"); }
flag() { [ "$2" = "1" ] || [ "$2" = "true" ] || [ "$2" = "on" ] && ARGS+=(--data-urlencode "$1=on"); return 0; }

flag enabled "$(pref crawler_enabled true)"
flag wifiOnly "$(pref crawler_wifi_only false)"
add logLevel "$(cfg log_level INFO)"
add dhtPort "$(cfg dht_port 6881)"
add niceLevel "$(cfg nice_level 10)"
add numTrees "$(cfg num_trees 64)"
add treeFindNodeWorkers "$(cfg tree_find_node_workers 3)"
add treeBep51Workers "$(cfg tree_bep51_workers 3)"
add treeGetPeersWorkers "$(cfg tree_get_peers_workers 18)"
add treeMetadataWorkers "$(cfg tree_metadata_workers 10)"
add treeInfohashQueueCapacity "$(cfg tree_infohash_queue_capacity 500)"
add treePeersQueueCapacity "$(cfg tree_peers_queue_capacity 500)"
# Taxonomy: repeated params, one per name. Empty is rejected by the endpoint.
IFS=',' read -r -a CATS <<< "$(cfg save_categories 'movie,tv,music')"
for c in "${CATS[@]}"; do [ -n "$c" ] && add saveCategories "$c"; done
IFS=',' read -r -a TVK <<< "$(cfg save_tv_kinds '')"
for k in "${TVK[@]}"; do [ -n "$k" ] && add saveTvKinds "$k"; done
add languageFilterNonLatinThreshold "$(cfg language_filter_non_latin_threshold 33)"
flag classifierEnabled "$(cfg classifier_enabled 1)"
add adultMode "$(cfg adult_mode tag)"
add adultHideThreshold "$(cfg adult_hide_threshold 0.545726)"
add bloomCapacity "$(cfg bloom_capacity 30000000)"
add failureBloomCapacity "$(cfg failure_bloom_capacity 30000000)"
add bloomErrorRate "$(cfg bloom_error_rate 0.001)"
add proxyPort "$(pref crawler_proxy_port 8080)"
add crawlerHttpPort "$(pref crawler_crawler_http_port 8081)"
flag lanDirect "$(pref crawler_lan_direct false)"
ARGS+=(--data-urlencode "advancedOverrides@$TMP/adv.new")

echo "# applying udp_rate_limit_pps = ${PPS} (every other advanced key unchanged)"
diff "$TMP/adv.old" "$TMP/adv.new" || true
echo "# form: ${#ARGS[@]} params, $(grep -c . "$TMP/adv.new") advanced keys, ${#CATS[@]} categories, ${#TVK[@]} tv kinds"

BEFORE=$(curl -s --max-time 10 http://127.0.0.1:8080/stats | python3 -c 'import json,sys; print(json.load(sys.stdin)["uptime"])')
LOC=$(curl -s -o /dev/null -D - --max-time 180 \
  -X POST "http://127.0.0.1:8090/settings/crawler?token=$TOKEN" \
  -H 'Content-Type: application/x-www-form-urlencoded' "${ARGS[@]}" \
  | grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: //')
echo "# apply -> ${LOC:-(no redirect)}"
case "$LOC" in *ok=false*)
  echo "error: the endpoint rejected the form: $LOC" >&2; exit 1;; esac

# The apply restarts the crawler; poll until the new process echoes the rung.
for _ in $(seq 1 60); do
  sleep 3
  GOT=$(curl -s --max-time 10 http://127.0.0.1:8080/stats 2>/dev/null \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["udp_rate_limit_pps"], d["uptime"])' 2>/dev/null || true)
  [ -n "$GOT" ] || continue
  set -- $GOT
  if [ "$1" = "$PPS" ]; then
    echo "# echoed udp_rate_limit_pps=$1, crawler uptime $2 (was $BEFORE before the apply)"
    # Guard: the full-form post must not have moved anything but the rung.
    "${ADB[@]}" exec-out run-as $APP cat "$CFG" | grep -vE '^\s*#|^\s*$' > "$TMP/config.after"
    BAD=0
    for kv in "classifier_enabled = 1" "movie_kind_enabled = 1" "num_trees = $(cfg num_trees)" \
              "tree_get_peers_workers = $(cfg tree_get_peers_workers)" \
              "tree_bep51_workers = $(cfg tree_bep51_workers)" \
              "tree_parallel_peers = $(cfg tree_parallel_peers)" \
              "log_level = $(cfg log_level)"; do
      grep -qxF "$kv" "$TMP/config.after" || { echo "GUARD FAILED: missing '$kv' after the apply" >&2; BAD=1; }
    done
    [ "$BAD" = 0 ] && echo "# guard ok: classifier, movie_kind, trees, workers, log_level unchanged"
    curl -s --max-time 10 http://127.0.0.1:8080/stats | python3 -c 'import json,sys; d=json.load(sys.stdin); m=d["metadata_peer_fail"]; print("# metadata_peer_fail:", "enabled" if m["enabled"] else "DISABLED", m["capacity"], "ttl", m["ttl_sec"])'
    exit "$BAD"
  fi
done

echo "error: crawler never echoed udp_rate_limit_pps=$PPS within 180 s" >&2
echo "hint: check the apply status on /settings/crawler (a failed apply rolls back)" >&2
exit 1
