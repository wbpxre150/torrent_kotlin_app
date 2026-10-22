#!/usr/bin/env bash
#
# tools/r2-synth-chain.sh — build and upload the synthetic R2 db-dump chain that
# the crawler-DB restore import is tested against on device.
#
# WHY: the real `db/` prefix in the `dht-backup` bucket was wiped by the
# operator, so a small synthetic chain is the only reproducible way to exercise
# the restore path (per-range class-index filter, resume, FTS rebuild)
# end-to-end. Its output matches the producer's formats exactly:
#   - `SqlDumpWriter.kt`: one `INSERT OR IGNORE INTO <table> VALUES(...);` per
#     line, no column list, tables in the order path_prefixes -> torrents ->
#     torrent_files, literals `NULL` / bare integer / 'text' / x'hexblob';
#   - `DbObjectKey.kt`: `<prefix>/incremental_<YYYYMMDD>_<start>-<end>.sql.gz`
#     and the `<prefix>/incremental_<YYYYMMDD>_<start>-<end>.idx.gz` sidecar;
#   - `renderClassIndex` (`SqlDumpWriter.kt`): one `"<torrent_id> <category>"`
#     line per classified torrent.
#
# MANIFEST (plan stage 5): 12 torrents `synth-01`..`synth-12` (ids 1..12), each
# with 2 `torrent_files` rows (ids 1..24, prefix_id 1), one `path_prefixes` row
# (id 1) and 10 `torrent_class` rows (ids 1..10 in torrent_id order, the 12
# columns of `CrawlerDbSchema.kt:63-77`):
#   synth-01..03 -> movie (1)   synth-04..05 -> tv (2)    synth-06..07 -> anime (3)
#   synth-08..09 -> music (4)   synth-10     -> other (11) synth-11..12 -> no row (unknown, 0)
#
# Default chain (two objects, matching the producer's date/range convention):
#   <prefix>/incremental_20260101_0-6.sql.gz    path_prefixes(1) + torrents 1-6 + torrent_files 1-12 + torrent_class 1-6
#   <prefix>/incremental_20260101_0-6.idx.gz    "1 1" "2 1" "3 1" "4 2" "5 2" "6 3"
#   <prefix>/incremental_20260102_7-12.sql.gz   torrents 7-12 + torrent_files 13-24 + torrent_class 7-10
#   <prefix>/incremental_20260102_7-12.idx.gz   "7 3" "8 4" "9 4" "10 11"
#
# Expected filtered import (wizard "categories to save"), printed by the script:
#   {movie, music}  -> 5 torrents, 10 torrent_files rows, 5 torrent_class rows
#   empty selection -> 12 torrents, 24 torrent_files rows, 10 torrent_class rows
#
# USAGE
#   tools/r2-synth-chain.sh [--prefix NAME] [--no-index] [--repeat N]
#
#   --prefix NAME  object-key prefix under r2:dht-backup/ (default
#                  `dbtest-<UTC timestamp>`). Must not contain '/', and the live
#                  `db` prefix is REFUSED: this tool never touches production.
#   --no-index     upload only the `.sql.gz` objects, no `.idx.gz` sidecars —
#                  the "chain without sidecars imports unfiltered" case.
#   --repeat N     upload N objects dated 20260101, 20260102, … each re-dumping
#                  ALL 12 torrents with range 0-12 plus its sidecar; overlapping
#                  ranges are legal per the chain rules and give the resume test
#                  a long-enough run.
#
# CREDENTIALS
#   Read at runtime from $R2_CREDS (default /home/adam/r2_creds.txt), an INI
#   file holding access_key_id, secret_access_key, endpoint, provider and acl
#   (the `[r2]` section is used when the file has section headers; no bucket key
#   is needed — the bucket is hardcoded to `dht-backup`, matching the device's
#   stored `backup_r2_db_bucket`). They are exported as an on-the-fly rclone
#   remote (`RCLONE_CONFIG_R2_*`) for this script's children only: no
#   `rclone config create` state, no secrets echoed or logged. `acl` defaults to
#   `private` when the key is absent.
#
# CLEANUP — purge the scratch chain when the test is done (the exported env
# vars live only in this script's children, so re-export the same
# RCLONE_CONFIG_R2_* values in your shell first):
#   rclone purge r2:dht-backup/<prefix>
#
# Host-only: no app code, no Gradle, no device interaction.
set -uo pipefail

BUCKET=dht-backup
CREDS="${R2_CREDS:-/home/adam/r2_creds.txt}"
FIRST_DATE=2026-01-01
# 2026-01-01T00:00:00Z, the fixed base of every synthetic added_timestamp.
BASE_TS=1767225600

TOTAL_TORRENTS=12
FILES_PER_TORRENT=2

# Category code per torrent id (unknown = 0 = no torrent_class row at all).
declare -A CATEGORY_OF=(
    [1]=1 [2]=1 [3]=1
    [4]=2 [5]=2
    [6]=3 [7]=3
    [8]=4 [9]=4
    [10]=11
)
CATEGORY_NAMES=(unknown movie tv anime music audiobook ebook comic software game image other)

PREFIX=""
NO_INDEX=0
REPEAT=0

log() { printf '[r2-synth] %s\n' "$*"; }
die() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '/^# USAGE$/,/^# Host-only/p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix)   [ $# -ge 2 ] || die "--prefix needs a value"; PREFIX=$2; shift 2 ;;
        --prefix=*) PREFIX=${1#*=}; shift ;;
        --no-index) NO_INDEX=1; shift ;;
        --repeat)   [ $# -ge 2 ] || die "--repeat needs a value"; REPEAT=$2; shift 2 ;;
        --repeat=*) REPEAT=${1#*=}; shift ;;
        -h|--help)  usage; exit 0 ;;
        *)          die "unknown argument: $1 (try --help)" ;;
    esac
done

case "$REPEAT" in
    ''|*[!0-9]*) die "--repeat needs a non-negative integer, got '$REPEAT'" ;;
esac

if [ -z "$PREFIX" ]; then
    PREFIX="dbtest-$(date -u +%Y%m%d%H%M%S)" || die "cannot stamp the default prefix"
fi
case "$PREFIX" in
    db|db/|db/*) die "refusing to write the live '$PREFIX' prefix — this tool only builds throwaway dbtest-* chains" ;;
    */*)         die "prefix must not contain '/': '$PREFIX'" ;;
    *[[:space:]]*) die "prefix must not contain whitespace: '$PREFIX'" ;;
esac

# ---------------------------------------------------------------------------
# Credentials: INI parse -> on-the-fly rclone remote (never printed).
# ---------------------------------------------------------------------------
[ -r "$CREDS" ] || die "cannot read the credentials file: $CREDS (override with R2_CREDS)"

creds_get() {
    awk -v want="$1" '
        { sub(/\r$/, "") }
        /^[ \t]*\[/ { inr2 = ($0 ~ /^[ \t]*\[r2\][ \t]*$/); next }
        {
            eq = index($0, "=")
            if (eq == 0) next
            k = substr($0, 1, eq - 1); v = substr($0, eq + 1)
            gsub(/^[ \t]+/, "", k); gsub(/[ \t]+$/, "", k)
            gsub(/^[ \t]+/, "", v); gsub(/[ \t]+$/, "", v)
            gsub(/^["\047]|["\047]$/, "", v)
            if (k != want || v == "") next
            if (inr2) sec = v; else bare = v
        }
        END { if (sec != "") print sec; else if (bare != "") print bare }
    ' "$CREDS"
}

provider=$(creds_get provider)
access_key_id=$(creds_get access_key_id)
secret_access_key=$(creds_get secret_access_key)
endpoint=$(creds_get endpoint)
acl=$(creds_get acl)

[ -n "$provider" ] || die "no 'provider' key in $CREDS"
[ -n "$access_key_id" ] || die "no 'access_key_id' key in $CREDS"
[ -n "$secret_access_key" ] || die "no 'secret_access_key' key in $CREDS"
[ -n "$endpoint" ] || die "no 'endpoint' key in $CREDS"
[ -n "$acl" ] || acl=private

export RCLONE_CONFIG_R2_TYPE=s3
export RCLONE_CONFIG_R2_PROVIDER="$provider"
export RCLONE_CONFIG_R2_ACCESS_KEY_ID="$access_key_id"
export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$secret_access_key"
export RCLONE_CONFIG_R2_ENDPOINT="$endpoint"
export RCLONE_CONFIG_R2_ACL="$acl"
unset access_key_id secret_access_key

command -v rclone >/dev/null 2>&1 || die "rclone not found on PATH"
command -v gzip >/dev/null 2>&1 || die "gzip not found on PATH"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/r2-synth.XXXXXX") || die "mktemp failed"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# Manifest rendering (exactly SqlDumpWriter's statement shape).
# ---------------------------------------------------------------------------
info_hash_hex() {
    local b h="" i
    printf -v b '%02x' "$1"
    for ((i = 0; i < 20; i++)); do h+=$b; done
    printf '%s' "$h"
}

emit_path_prefixes() {
    printf "INSERT OR IGNORE INTO path_prefixes VALUES(1, '/synthetic/media');\n"
}

emit_torrents() { # lo hi (torrent ids)
    local id name hash
    for ((id = $1; id <= $2; id++)); do
        printf -v name 'synth-%02d' "$id"
        hash=$(info_hash_hex "$id")
        printf "INSERT OR IGNORE INTO torrents VALUES(%d, x'%s', '%s', %d, %d, %d);\n" \
            "$id" "$hash" "$name" \
            $((734003200 + id * 1048576)) $((id * 7)) $((BASE_TS + id * 3600))
    done
}

emit_torrent_files() { # lo hi (torrent ids)
    local id part fid name
    for ((id = $1; id <= $2; id++)); do
        printf -v name 'synth-%02d' "$id"
        for ((part = 0; part < FILES_PER_TORRENT; part++)); do
            fid=$(((id - 1) * FILES_PER_TORRENT + part + 1))
            printf "INSERT OR IGNORE INTO torrent_files VALUES(%d, %d, 1, '%s/%s-part%d.mkv', %d, %d);\n" \
                "$fid" "$id" "$name" "$name" $((part + 1)) \
                $((367001600 + fid * 1024)) "$part"
        done
    done
}

emit_torrent_class() { # lo hi (torrent ids; unclassed ids emit nothing)
    local id cat tv_kind adult_p season_lo season_hi episode_lo episode_hi resolution
    for ((id = $1; id <= $2; id++)); do
        cat=${CATEGORY_OF[$id]:-0}
        [ "$cat" -eq 0 ] && continue
        case "$cat" in
            1)  # movie: no tv fields
                tv_kind=NULL adult_p=0.02 season_lo=NULL season_hi=NULL
                episode_lo=NULL episode_hi=NULL resolution=1080 ;;
            2)  # tv: single episode
                tv_kind=1 adult_p=0.00 season_lo=1 season_hi=1
                episode_lo=$((id - 3)) episode_hi=$((id - 3)) resolution=1080 ;;
            3)  # anime: season pack
                tv_kind=3 adult_p=0.00 season_lo=1 season_hi=2
                episode_lo=NULL episode_hi=NULL resolution=1080 ;;
            4)  # music: no tv/resolution fields
                tv_kind=NULL adult_p=0.00 season_lo=NULL season_hi=NULL
                episode_lo=NULL episode_hi=NULL resolution=NULL ;;
            *)  # other
                tv_kind=NULL adult_p=0.00 season_lo=NULL season_hi=NULL
                episode_lo=NULL episode_hi=NULL resolution=NULL ;;
        esac
        printf "INSERT OR IGNORE INTO torrent_class VALUES(%d, %d, %s, 0, %.2f, %s, %s, %s, %s, %s, %.2f, 1);\n" \
            "$id" "$cat" "$tv_kind" "$adult_p" "$season_lo" "$season_hi" \
            "$episode_lo" "$episode_hi" "$resolution" 0.93
    done
}

# Whole object body, in the manifest's table order:
# path_prefixes (first object only) -> torrents -> torrent_files -> torrent_class.
render_sql() { # first hi with_path_prefixes(1/0); first is already clamped to >= 1
    if [ "$3" -eq 1 ]; then emit_path_prefixes; fi
    emit_torrents "$1" "$2"
    emit_torrent_files "$1" "$2"
    emit_torrent_class "$1" "$2"
}

# Class-index sidecar body: "<torrent_id> <category>" per classified torrent.
emit_class_index() { # lo hi
    local id cat
    for ((id = $1; id <= $2; id++)); do
        cat=${CATEGORY_OF[$id]:-0}
        [ "$cat" -eq 0 ] && continue
        printf '%d %d\n' "$id" "$cat"
    done
}

# ---------------------------------------------------------------------------
# Upload
# ---------------------------------------------------------------------------
upload() { # local-file key
    local src=$1 key=$2
    # --config /dev/null: the remote is the exported env, never on-disk state.
    if ! rclone --config /dev/null copyto "$src" "r2:${BUCKET}/${PREFIX}/${key}" \
        >"$TMP/rclone.out" 2>"$TMP/rclone.err"; then
        die "upload failed for ${BUCKET}/${PREFIX}/${key}:
$(cat "$TMP/rclone.err")"
    fi
    log "uploaded ${BUCKET}/${PREFIX}/${key}"
}

build_and_upload() { # date lo hi with_path_prefixes(1/0)
    local date=$1 lo=$2 hi=$3 with_prefixes=$4
    local base="incremental_${date}_${lo}-${hi}"
    local plain="$TMP/$base.sql"
    # The key's start is `last_torrent_id + 1` (0 on the first run) while ids
    # are 1-based, so a 0-start range renders from id 1.
    local first=$lo
    [ "$first" -lt 1 ] && first=1

    render_sql "$first" "$hi" "$with_prefixes" >"$plain" || die "rendering $base.sql failed"
    gzip -n -c "$plain" >"$plain.gz" || die "gzip failed for $base.sql.gz"
    upload "$plain.gz" "$base.sql.gz"

    if [ "$NO_INDEX" -eq 0 ]; then
        local idx="$TMP/$base.idx"
        emit_class_index "$first" "$hi" >"$idx" || die "rendering $base.idx failed"
        gzip -n -c "$idx" >"$idx.gz" || die "gzip failed for $base.idx.gz"
        upload "$idx.gz" "$base.idx.gz"
    fi
}

# ---------------------------------------------------------------------------
# Manifest report (computed from the table above, not hardcoded)
# ---------------------------------------------------------------------------
print_manifest() {
    local id cat total_class=0
    local -A per_cat=()
    local sel_torrents=0 sel_class=0 sel_files=0

    for ((id = 1; id <= TOTAL_TORRENTS; id++)); do
        cat=${CATEGORY_OF[$id]:-0}
        per_cat[$cat]=$(( ${per_cat[$cat]:-0} + 1 ))
        [ "$cat" -eq 0 ] || total_class=$((total_class + 1))
        case "$cat" in
            1|4) sel_torrents=$((sel_torrents + 1)); sel_class=$((sel_class + 1)) ;;
        esac
    done
    sel_files=$((sel_torrents * FILES_PER_TORRENT))

    log "manifest: ${TOTAL_TORRENTS} torrents (synth-01..synth-$(printf '%02d' "$TOTAL_TORRENTS"))," \
        "$((TOTAL_TORRENTS * FILES_PER_TORRENT)) torrent_files rows, ${total_class} torrent_class rows, 1 path_prefixes row"
    for cat in 0 1 2 3 4 5 6 7 8 9 10 11; do
        [ "${per_cat[$cat]:-0}" -gt 0 ] || continue
        if [ "$cat" -eq 0 ]; then
            log "  category 0 (unknown, no class row): ${per_cat[$cat]} torrent(s)"
        else
            log "  category ${cat} (${CATEGORY_NAMES[$cat]}): ${per_cat[$cat]} torrent(s)"
        fi
    done
    log "expected filtered import counts:"
    log "  selection {movie, music}: ${sel_torrents} torrents, ${sel_files} torrent_files rows, ${sel_class} torrent_class rows"
    log "  empty selection (unfiltered): ${TOTAL_TORRENTS} torrents," \
        "$((TOTAL_TORRENTS * FILES_PER_TORRENT)) torrent_files rows, ${total_class} torrent_class rows"
}

# ---------------------------------------------------------------------------
# Go
# ---------------------------------------------------------------------------
print_manifest
log "target prefix: ${BUCKET}/${PREFIX}/ (sidecars: $([ "$NO_INDEX" -eq 0 ] && echo yes || echo no))"

if [ "$REPEAT" -gt 0 ]; then
    log "repeat mode: ${REPEAT} object(s), each re-dumping all ${TOTAL_TORRENTS} torrents as range 0-${TOTAL_TORRENTS}"
    for ((i = 0; i < REPEAT; i++)); do
        date=$(date -u -d "${FIRST_DATE} +${i} days" +%Y%m%d) || die "date arithmetic failed"
        build_and_upload "$date" 0 "$TOTAL_TORRENTS" 1
    done
else
    build_and_upload "$(date -u -d "${FIRST_DATE}" +%Y%m%d)" 0 6 1
    build_and_upload "$(date -u -d "${FIRST_DATE} +1 days" +%Y%m%d)" 7 "$TOTAL_TORRENTS" 0
fi

log "objects under ${BUCKET}/${PREFIX}/:"
rclone --config /dev/null lsf "r2:${BUCKET}/${PREFIX}/" | sed 's/^/  /' \
    || die "listing ${BUCKET}/${PREFIX}/ failed"

log "point the device at this chain with backup_r2_db_object_prefix=${PREFIX} (bucket ${BUCKET})"
log "purge it when done (same RCLONE_CONFIG_R2_* env vars exported in your shell):"
log "  rclone purge r2:${BUCKET}/${PREFIX}"
