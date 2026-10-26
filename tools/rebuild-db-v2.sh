#!/usr/bin/env bash
#
# Rebuild a crawler search database into schema v2: drop the v1 trigram
# file_search* index, keep the base tables, and re-derive the contentless
# torrent_docs index on the PC (~83 GB v1 snapshot -> ~32 GB v2).
#
#   tools/rebuild-db-v2.sh --source <v1.db> --target <v2.db>   full rebuild
#   tools/rebuild-db-v2.sh --check-schema                      DDL drift check
#
# Design: the target schema is the crawler's OWN DDL, emitted at run time by
# `dht_crawler --print-schema` (src/database.c); tools/schema-v2.sql is only
# the committed check baseline, never used to build. Base tables are copied one
# transaction per table through `ATTACH 'file:$SOURCE?mode=ro'`; the target's
# own torrents_ai trigger fills torrent_search, and torrent_docs (contentless,
# so not copyable) is rebuilt in CHUNK-sized id ranges with a progress line per
# chunk. Then foreign_keys=ON, VACUUM, ANALYZE, a verification block, and an
# A/B timing block against the v1 trigram shape.
#
# Safety: the source is opened read-only and never written (SQLite may leave an
# empty -wal/-shm beside it for WAL bookkeeping); the target must not exist; a
# failed or interrupted run deletes the partial target, so re-running starts
# over. No database file is ever committed.
#
# Env: SCHEMA_BIN (crawler binary; rebuilt if missing/stale), SQLITE_BIN
# (default sqlite3, needs >= 3.43 for contentless FTS5), CHUNK (torrent_docs id
# step, default 10000), AB_TIMEOUT (seconds per A/B query, default 3600).
#
set -uo pipefail

cd "$(dirname "$0")/.."

CRAWLER_SRC=app/src/main/cpp/dht_crawler
SCHEMA_BIN_EXPLICIT=${SCHEMA_BIN:-}
SCHEMA_BIN=${SCHEMA_BIN:-app/build/native/src/dht_crawler/dht_crawler}
SQLITE_BIN=${SQLITE_BIN:-sqlite3}
CHUNK=${CHUNK:-10000}
AB_TIMEOUT=${AB_TIMEOUT:-3600}
REF_SCHEMA=tools/schema-v2.sql

GIB=1073741824
NEED_FREE=$((45 * GIB))          # target dir headroom for a ~32 GB build
MAX_TOTAL=$((40 * GIB))          # grand total budget of the rebuilt file
DOCS_MIN=$((5 * GIB))            # torrent_docs* shadow tables, expected band
DOCS_MAX=$((8 * GIB))
BUDGET_MIN_SOURCE=$((1 * GIB))   # below this the source is a fixture, not data

SOURCE=""
TARGET=""
CHECK_ONLY=0
TMP=""
TARGET_READY=0
FAILS=0

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "FATAL: $*" >&2; exit 1; }
h()   { awk -v b="$1" 'BEGIN{u="BKMGT";i=0;while(b>=1024&&i<4){b/=1024;i++}printf "%.1f%sB",b,substr(u,i+1,1)}'; }

on_signal() { echo; log "interrupted — cleaning up"; exit 130; }
cleanup() {
  local rc=$?
  if [ -n "$TMP" ] && [ -d "$TMP" ]; then rm -rf "$TMP"; fi
  if [ "$CHECK_ONLY" -eq 0 ] && [ "$TARGET_READY" -ne 1 ] && [ -n "$TARGET" ] && [ -e "$TARGET" ]; then
    log "removing the partial target $TARGET (re-run the same command to start over)"
    rm -f "$TARGET" "$TARGET-wal" "$TARGET-shm" "$TARGET-journal"
  fi
  exit "$rc"
}
trap on_signal INT TERM
trap cleanup EXIT

usage() {
  cat <<'USAGE'
usage: tools/rebuild-db-v2.sh --source <v1.db> --target <v2.db>
       tools/rebuild-db-v2.sh --check-schema

  --source <db>   v1 crawler database to read (opened read-only, never written)
  --target <db>   v2 database to create; must not exist
  --check-schema  only verify that `dht_crawler --print-schema` matches
                  tools/schema-v2.sql (exit 0 = match, 1 = drift)

env: SCHEMA_BIN (crawler binary)  SQLITE_BIN (default sqlite3, >= 3.43)
     CHUNK (torrent_docs id step, default 10000)  AB_TIMEOUT (default 3600)
USAGE
}

# ---------------------------------------------------------------- arguments
while [ $# -gt 0 ]; do
  case "$1" in
    --source|--target)
      [ $# -ge 2 ] || die "$1 needs a value"
      if [ "$1" = "--source" ]; then SOURCE=$2; else TARGET=$2; fi
      shift 2 ;;
    --check-schema) CHECK_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done

case "$CHUNK" in
  ''|*[!0-9]*) die "CHUNK must be a positive integer (got '$CHUNK')" ;;
esac
[ "$CHUNK" -gt 0 ] || die "CHUNK must be > 0"

TMP=$(mktemp -d /tmp/rebuild-db-v2.XXXXXX) || die "cannot create a temp dir"
printf '%s\n' 'PRAGMA foreign_keys=OFF;' 'PRAGMA synchronous=OFF;' 'PRAGMA cache_size=-64000;' \
  > "$TMP/load-pragmas.sql" || die "cannot write $TMP/load-pragmas.sql"

# The scratch load session pragmas (session-only; the crawler re-applies its own
# on open). Prepended to every write connection against the target.
load_target() { cat "$TMP/load-pragmas.sql" - | "$SQLITE_BIN" -bail "$TARGET"; }

q() { "$SQLITE_BIN" "$1" "$2" 2>&1; }          # $1 = db path or URI, $2 = sql
pass() { log "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; FAILS=$((FAILS + 1)); }
chk()  { if [ "$2" = "$3" ]; then pass "$1 = $3"; else fail "$1: expected '$2', got '$3'"; fi; }
chk_gt()  { if [ "$3" -gt "$2" ]; then pass "$1 = $3 (> $2)"; else fail "$1: expected > $2, got '$3'"; fi; }
chk_le()  { if [ "$3" -le "$2" ]; then pass "$1 = $(h "$3") (<= $(h "$2"))"; else fail "$1: expected <= $(h "$2"), got $(h "$3")"; fi; }
chk_ge()  { if [ "$3" -ge "$2" ]; then pass "$1 = $(h "$3") (>= $(h "$2"))"; else fail "$1: expected >= $(h "$2"), got $(h "$3")"; fi; }

# ------------------------------------------------------- schema emitter (DDL)
resolve_schema_bin() {
  if [ -n "$SCHEMA_BIN_EXPLICIT" ]; then
    SCHEMA_BIN=$SCHEMA_BIN_EXPLICIT
    [ -x "$SCHEMA_BIN" ] || die "SCHEMA_BIN=$SCHEMA_BIN is not an executable file"
    log "using SCHEMA_BIN=$SCHEMA_BIN"
    return 0
  fi
  local stale=0 f v
  [ -x "$SCHEMA_BIN" ] || stale=1
  for f in "$CRAWLER_SRC/src/main.c" "$CRAWLER_SRC/src/database.c" "$CRAWLER_SRC/include/database.h"; do
    [ -f "$f" ] || die "missing crawler source $f (submodule not initialised?)"
    if [ "$stale" -eq 0 ] && [ "$f" -nt "$SCHEMA_BIN" ]; then stale=1; fi
  done
  [ "$stale" -eq 1 ] || { log "using the existing crawler host binary $SCHEMA_BIN"; return 0; }

  log "crawler host binary is missing or older than its sources — rebuilding it"
  for v in JAVA_HOME ANDROID_HOME ANDROID_NDK_HOME; do
    [ -n "${!v:-}" ] || die "$v is unset — the crawler host binary must be rebuilt first:
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
  export ANDROID_HOME=\"\$HOME/Android/Sdk\"
  export ANDROID_NDK_HOME=\"\$HOME/Android/Sdk/ndk/android-ndk-r27\""
  done
  if ! ./gradlew :app:prepareDhtCrawlerSrc > "$TMP/gradle.log" 2>&1; then
    tail -n 20 "$TMP/gradle.log" >&2
    die "prepareDhtCrawlerSrc failed (full log was $TMP/gradle.log)"
  fi
  if ! make -j"$(nproc)" -C app/build/native/src/dht_crawler > "$TMP/make.log" 2>&1; then
    tail -n 20 "$TMP/make.log" >&2
    die "building the crawler host binary failed (full log was $TMP/make.log)"
  fi
  [ -x "$SCHEMA_BIN" ] || die "expected $SCHEMA_BIN after the build, but it is missing"
  log "rebuilt $SCHEMA_BIN"
}

emit_schema() {   # $1 = output file; always a FRESH emission, never the baseline
  local rc n
  "$SCHEMA_BIN" --print-schema > "$1" 2> "$TMP/schema.err"
  rc=$?
  [ "$rc" -eq 0 ] || { cat "$TMP/schema.err" >&2; die "$SCHEMA_BIN --print-schema failed (rc=$rc)"; }
  n=$(grep -c ';' "$1")
  [ "$n" -gt 0 ] || die "$SCHEMA_BIN --print-schema produced no DDL"
  log "emitted $n schema statements from $SCHEMA_BIN"
}

norm() { sed -e 's/[[:space:]]\+/ /g' -e 's/^ //' -e 's/ $//' -e '/^$/d' "$1"; }

check_schema() {
  [ -f "$REF_SCHEMA" ] || die "missing the check baseline $REF_SCHEMA"
  emit_schema "$TMP/schema.sql"
  norm "$TMP/schema.sql" > "$TMP/emitted.norm"
  norm "$REF_SCHEMA"     > "$TMP/ref.norm"
  if ! diff -u --label "$REF_SCHEMA (committed baseline)" \
                --label "dht_crawler --print-schema (emitted now)" \
                "$TMP/ref.norm" "$TMP/emitted.norm" > "$TMP/schema.diff"; then
    cat "$TMP/schema.diff" >&2
    die "schema drift: the crawler's --print-schema no longer matches $REF_SCHEMA — fix the crawler, or regenerate the baseline with:
  $SCHEMA_BIN --print-schema > $REF_SCHEMA"
  fi
  log "schema check ok: $(wc -l < "$TMP/emitted.norm") statements match $REF_SCHEMA"
}

# ---------------------------------------------------------------- preflight
ver_ge() { [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" = "$2" ]; }

preflight() {
  local v free dir need
  v=$("$SQLITE_BIN" --version 2>/dev/null | awk '{print $1}')
  [ -n "$v" ] || die "cannot run $SQLITE_BIN (set SQLITE_BIN to a sqlite3 >= 3.43)"
  ver_ge "$v" 3.43 || die "sqlite3 $v is too old — need >= 3.43 for contentless FTS5 (contentless_delete=1)"

  [ -f "$SOURCE" ] || die "no such source database: $SOURCE"
  SRC_SIZE=$(stat -c %s "$SOURCE") || die "stat failed: $SOURCE"
  [ "$SRC_SIZE" -gt 0 ] || die "$SOURCE is empty"
  if [ -f "$SOURCE-wal" ] && [ "$(stat -c %s "$SOURCE-wal")" -gt 0 ]; then
    die "$SOURCE-wal is non-empty — the source was not closed cleanly; stop whatever uses it, checkpoint it, then retry"
  fi
  local probe
  probe=$("$SQLITE_BIN" "file:$SOURCE?mode=ro" "PRAGMA schema_version;" 2>&1) \
    || die "cannot open $SOURCE read-only: $probe"
  case "$probe" in
    ''|*[!0-9]*) die "cannot open $SOURCE read-only (not a sqlite database?): $probe" ;;
  esac

  if [ -e "$TARGET" ]; then die "target $TARGET already exists — refusing to overwrite it (rm it first)"; fi
  dir=$(dirname "$TARGET")
  [ -d "$dir" ] || die "target directory $dir does not exist"
  free=$(df -B1 "$dir" | awk 'NR==2{print $4}')
  [ -n "$free" ] || die "cannot read free space of $dir"
  # 45 GiB is the budget for a ~32 GB rebuild of a full snapshot; a
  # fixture-scale source only needs room for itself.
  if [ "$SRC_SIZE" -ge "$BUDGET_MIN_SOURCE" ]; then need=$NEED_FREE; else need=$(( 2 * SRC_SIZE )); fi
  [ "$free" -ge "$need" ] \
    || die "not enough free space in $dir: need $(h "$need"), have $(h "$free")"
  if [ "$SRC_SIZE" -lt "$BUDGET_MIN_SOURCE" ]; then
    log "note: source is fixture-scale ($(h "$SRC_SIZE")) — free-space and size-budget checks use the small-file bounds"
  fi

  log "preflight ok: source $(h "$SRC_SIZE"), $(h "$free") free in $dir, sqlite3 $v"
}

# ------------------------------------------------------------ build phases
create_target() {
  log "creating $TARGET (page_size=32768, auto_vacuum=INCREMENTAL, journal_mode=WAL)"
  "$SQLITE_BIN" -bail "$TARGET" > /dev/null <<'SQL' || die "cannot create $TARGET"
PRAGMA page_size=32768;
PRAGMA auto_vacuum=INCREMENTAL;
PRAGMA journal_mode=WAL;
SQL
  log "applying the crawler's own DDL (freshly emitted, not $REF_SCHEMA)"
  "$SQLITE_BIN" -bail "$TARGET" < "$TMP/schema.sql" || die "applying the DDL to $TARGET failed"
}

copy_table() {   # $1 = table name
  local t=$1 rows
  log "copying $t"
  load_target <<SQL || die "copying $t failed"
ATTACH DATABASE 'file:$SOURCE?mode=ro' AS src;
BEGIN;
INSERT INTO $t SELECT * FROM src.$t;
COMMIT;
DETACH DATABASE src;
SQL
  rows=$(q "file:$TARGET?mode=ro" "SELECT count(*) FROM $t;")
  log "  $t: $rows rows"
}

restore_sequence() {
  log "restoring sqlite_sequence high-water marks from the source"
  # INSERT OR REPLACE cannot be used: sqlite_sequence has no unique index on
  # name, so a second row would be appended instead of replacing (breaking the
  # AUTOINCREMENT high-water marks and the equality check below).
  load_target <<SQL || die "restoring sqlite_sequence failed"
ATTACH DATABASE 'file:$SOURCE?mode=ro' AS src;
BEGIN;
DELETE FROM sqlite_sequence;
INSERT INTO sqlite_sequence(name,seq) SELECT name,seq FROM src.sqlite_sequence;
COMMIT;
DETACH DATABASE src;
SQL
}

build_docs() {
  local maxid total i lo hi
  maxid=$(q "file:$TARGET?mode=ro" "SELECT COALESCE(max(id),0) FROM torrents;")
  case "$maxid" in ''|*[!0-9]*) die "cannot read max(torrents.id) from the target: $maxid" ;; esac
  total=$(( (maxid + CHUNK - 1) / CHUNK ))
  log "building torrent_docs over ids 1..$maxid in $total chunk(s) of $CHUNK"
  i=0
  lo=0
  while [ "$lo" -lt "$maxid" ]; do
    hi=$(( lo + CHUNK ))
    i=$(( i + 1 ))
    log "torrent_docs chunk $i/$total (ids $(( lo + 1 ))..$hi)"
    load_target <<SQL || die "torrent_docs chunk $i (ids $(( lo + 1 ))..$hi) failed"
BEGIN;
INSERT INTO torrent_docs(rowid, body) SELECT t.id, t.name || ' ' || COALESCE((SELECT group_concat(f.filename,' ') FROM torrent_files f WHERE f.torrent_id=t.id),'') FROM torrents t WHERE t.id > $lo AND t.id <= $hi ORDER BY t.id;
COMMIT;
SQL
    lo=$hi
  done
}

finalize() {
  log "PRAGMA foreign_keys=ON, VACUUM, ANALYZE (the slow tail)"
  "$SQLITE_BIN" -bail "$TARGET" "PRAGMA foreign_keys=ON; VACUUM; ANALYZE;" \
    || die "VACUUM/ANALYZE of $TARGET failed"
}

# ------------------------------------------------------------- verification
verify() {
  local src_ro="file:$SOURCE?mode=ro" tgt_ro="file:$TARGET?mode=ro"
  local t n_docs n_tor total docs ids id body norm w ok bytes

  log "=== summary ==="
  chk "quick_check" ok "$(q "$tgt_ro" 'PRAGMA quick_check;')"
  chk "integrity_check" ok "$(q "$tgt_ro" 'PRAGMA integrity_check;')"

  for t in torrents path_prefixes torrent_files torrent_class classifier_meta; do
    chk "$t count (vs source)" "$(q "$src_ro" "SELECT count(*) FROM $t;")" \
                               "$(q "$tgt_ro" "SELECT count(*) FROM $t;")"
  done

  n_tor=$(q "$tgt_ro" "SELECT count(*) FROM torrents;")
  n_docs=$(q "$tgt_ro" "SELECT count(*) FROM torrent_docs;")
  chk "torrent_docs count = torrents count" "$n_tor" "$n_docs"

  chk "file_search% tables in the target" 0 \
    "$(q "$tgt_ro" "SELECT count(*) FROM sqlite_master WHERE name LIKE 'file_search%';")"
  chk_gt "torrent_docs MATCH 'ac'" 0 "$(q "$tgt_ro" "SELECT count(*) FROM torrent_docs WHERE torrent_docs MATCH 'ac';")"
  chk_gt "torrent_search MATCH 'best'" 0 "$(q "$tgt_ro" "SELECT count(*) FROM torrent_search WHERE name MATCH 'best';")"
  chk "PRAGMA foreign_key_check" "" "$(q "$tgt_ro" 'PRAGMA foreign_key_check;')"
  chk "sqlite_sequence (vs source)" "$(q "$src_ro" 'SELECT name||'"'"'='"'"'||seq FROM sqlite_sequence ORDER BY name;')" \
                                    "$(q "$tgt_ro" 'SELECT name||'"'"'='"'"'||seq FROM sqlite_sequence ORDER BY name;')"

  # Relevance: every id the four-term query returns must really carry all four
  # words in the source-computed body (word-bounded, so 'ac' is not 'ac3').
  # The body is folded the way FTS5's unicode61 tokenizer folds it before the
  # grep: unicode61 splits on '_' (so "AC_DC" indexes as ac+dc) and applies
  # remove_diacritics=2 (so "aç" indexes as ac), while `grep -w` treats '_' as a
  # word character and compares bytes — without this folding the check reports
  # false failures on bodies that the index legitimately matched.
  ids=$(q "$tgt_ro" "SELECT rowid FROM torrent_docs WHERE torrent_docs MATCH 'best AND of AND ac AND dc' ORDER BY rowid LIMIT 5;")
  if [ -z "$ids" ]; then
    fail "relevance sample: 'best AND of AND ac AND dc' matched nothing"
  else
    for id in $ids; do
      body=$(q "$src_ro" "SELECT t.name || ' ' || COALESCE((SELECT group_concat(f.filename,' ') FROM torrent_files f WHERE f.torrent_id=t.id),'') FROM torrents t WHERE t.id=$id;")
      if ! norm=$(printf '%s' "$body" | tr '_' ' ' | tr '[:upper:]' '[:lower:]' \
                    | iconv -f utf-8 -t ascii//TRANSLIT 2>/dev/null); then
        norm=$(printf '%s' "$body" | tr '_' ' ' | tr '[:upper:]' '[:lower:]')
      fi
      ok=1
      for w in best of ac dc; do
        printf '%s' "$norm" | grep -q -w -- "$w" \
          || { ok=0; fail "relevance: torrent $id lacks the word '$w' in its body"; }
      done
      [ "$ok" -eq 1 ] && pass "relevance: torrent $id has word-bounded best/of/ac/dc"
    done
  fi

  log "dbstat sizes:"
  q "$tgt_ro" "SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC;" \
    | while IFS='|' read -r t bytes; do printf '  %-24s %10s\n' "$t" "$(h "$bytes")"; done
  total=$(q "$tgt_ro" "SELECT SUM(pgsize) FROM dbstat;")
  docs=$(q "$tgt_ro" "SELECT SUM(pgsize) FROM dbstat WHERE name LIKE 'torrent_docs%';")
  total=${total:-0}
  docs=${docs:-0}
  if [ "$SRC_SIZE" -ge "$BUDGET_MIN_SOURCE" ]; then
    chk_le "dbstat total <= 40 GiB" "$MAX_TOTAL" "$total"
    chk_ge "torrent_docs* >= 5 GiB" "$DOCS_MIN" "$docs"
    chk_le "torrent_docs* <= 8 GiB" "$DOCS_MAX" "$docs"
  else
    log "NOTE: size budget checks skipped — source is $(h "$SRC_SIZE") (fixture scale);"
    log "      the 40 GiB / torrent_docs* 5-8 GiB bounds only apply to a full snapshot"
    log "      (this run: total $(h "$total"), torrent_docs* $(h "$docs"))"
  fi

  log "=== end of summary ==="
}

# ------------------------------------------------------------ A/B timings
ab_timing() {
  local src_ro="file:$SOURCE?mode=ro" tgt_ro="file:$TARGET?mode=ro"
  local glob_of glob_ac glob_dc old_sel name_term has_fs has_ts rc

  log "=== A/B timing (new: torrent_docs on the target | old: v1 trigram on the source) ==="
  log "T1 = four-term match count, T2 = match-set INSERT into a temp table, T3 = 50-row page query"

  cat > "$TMP/ab-new.sql" <<'SQL'
.timer on
SELECT 'T1 count(*)', count(*) FROM torrent_docs WHERE torrent_docs MATCH 'best AND of AND ac AND dc';
SELECT 'T2 setup';
CREATE TEMP TABLE ab_match(id INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO ab_match SELECT rowid FROM torrent_docs WHERE torrent_docs MATCH 'best AND of AND ac AND dc';
SELECT 'T2 match-set size', count(*) FROM ab_match;
SELECT 'T3 page';
SELECT DISTINCT hex(t.info_hash) AS info_hash,t.name,t.size_bytes,t.total_peers,t.added_timestamp FROM torrents t WHERE t.id IN (SELECT id FROM ab_match) GROUP BY t.id ORDER BY (t.id IN (SELECT rowid FROM torrent_search WHERE name MATCH 'best AND of AND ac AND dc')) DESC, t.total_peers DESC, t.added_timestamp DESC LIMIT 50;
SQL

  echo "--- NEW side: $TARGET (schema v2, torrent_docs) ---"
  timeout "$AB_TIMEOUT" "$SQLITE_BIN" "$tgt_ro" < "$TMP/ab-new.sql"
  rc=$?
  log "new side finished (rc=$rc)"

  has_fs=$(q "$src_ro" "SELECT count(*) FROM sqlite_master WHERE name='file_search';")
  if [ "$has_fs" != "1" ]; then
    log "old side skipped: $SOURCE has no file_search table (already schema v2?)"
    return 0
  fi
  has_ts=$(q "$src_ro" "SELECT count(*) FROM sqlite_master WHERE name='torrent_search';")

  glob_of="(LOWER(tf.filename) GLOB '*[^a-zA-Z0-9]of[^a-zA-Z0-9]*' OR LOWER(tf.filename) GLOB 'of[^a-zA-Z0-9]*' OR LOWER(tf.filename) GLOB '*[^a-zA-Z0-9]of')"
  glob_ac="(LOWER(tf.filename) GLOB '*[^a-zA-Z0-9]ac[^a-zA-Z0-9]*' OR LOWER(tf.filename) GLOB 'ac[^a-zA-Z0-9]*' OR LOWER(tf.filename) GLOB '*[^a-zA-Z0-9]ac')"
  glob_dc="(LOWER(tf.filename) GLOB '*[^a-zA-Z0-9]dc[^a-zA-Z0-9]*' OR LOWER(tf.filename) GLOB 'dc[^a-zA-Z0-9]*' OR LOWER(tf.filename) GLOB '*[^a-zA-Z0-9]dc')"
  old_sel="SELECT DISTINCT tf.torrent_id FROM torrent_files tf WHERE tf.id IN (SELECT rowid FROM file_search WHERE filename MATCH 'best') AND $glob_of AND $glob_ac AND $glob_dc"
  if [ "$has_ts" = "1" ]; then
    name_term="(t.id IN (SELECT rowid FROM torrent_search WHERE name MATCH 'best AND of AND ac AND dc'))"
  else
    name_term="0"
  fi

  {
    echo '.timer on'
    echo "SELECT 'T1' || char(39) || ' candidate-id count', count(*) FROM ($old_sel);"
    echo "CREATE TEMP TABLE ab_match_old(id INTEGER PRIMARY KEY);"
    echo "INSERT OR IGNORE INTO ab_match_old $old_sel;"
    echo "SELECT 'T2' || char(39) || ' match-set size', count(*) FROM ab_match_old;"
    echo "SELECT 'T3' || char(39) || ' page';"
    echo "SELECT DISTINCT hex(t.info_hash) AS info_hash,t.name,t.size_bytes,t.total_peers,t.added_timestamp FROM torrents t WHERE t.id IN (SELECT id FROM ab_match_old) GROUP BY t.id ORDER BY $name_term DESC, t.total_peers DESC, t.added_timestamp DESC LIMIT 50;"
  } > "$TMP/ab-old.sql"

  echo "--- OLD side: $SOURCE (schema v1, file_search trigram + GLOB) ---"
  timeout "$AB_TIMEOUT" "$SQLITE_BIN" "$src_ro" < "$TMP/ab-old.sql"
  rc=$?
  log "old side finished (rc=$rc)"
}

# -------------------------------------------------------------------- main
resolve_schema_bin
check_schema

if [ "$CHECK_ONLY" -eq 1 ]; then
  log "--check-schema only: nothing built, nothing written"
  exit 0
fi

[ -n "$SOURCE" ] || { usage >&2; die "--source is required for a rebuild"; }
[ -n "$TARGET" ] || { usage >&2; die "--target is required for a rebuild"; }
[ "$SOURCE" != "$TARGET" ] || die "--source and --target are the same file"

START=$SECONDS
preflight
create_target
copy_table torrents
copy_table path_prefixes
copy_table torrent_files
copy_table torrent_class
copy_table classifier_meta
restore_sequence
build_docs
finalize

TARGET_READY=1
verify
ab_timing

log "rebuild complete: $TARGET is $(h "$(stat -c %s "$TARGET")") ($(( (SECONDS - START) / 60 ))m elapsed)"
if [ "$FAILS" -ne 0 ]; then
  echo "FATAL: $FAILS verification check(s) failed — $TARGET is NOT fit to upload" >&2
  echo "       the target was kept for inspection; rm -f $TARGET $TARGET-wal $TARGET-shm before re-running" >&2
  exit 1
fi
log "all checks passed — upload with tools/push-torrents-db.sh $TARGET"
