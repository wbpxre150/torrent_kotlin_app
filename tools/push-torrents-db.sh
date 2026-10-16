#!/usr/bin/env bash
# Push ~/torrents.db (or any crawler db) INTO the phone app's private
# crawler data dir over adb — the reverse of ~/torrents.db.parts/pull.sh.
#
#   tools/push-torrents-db.sh [SOURCE_DB]      default: ~/torrents.db
#
# Design (same as pull.sh, mirrored): the file is streamed in 1 GiB chunks
# via `adb exec-out run-as com.torrent.app sh -c 'cat >> ...'`, so no
# /data/local/tmp detour (that would need 2x the space and write every byte
# twice). Each chunk is size-verified on the device before the next starts;
# a failed chunk is truncated back and retried, so a flaky USB link never
# loses progress. Re-running resumes from the last verified chunk.
#
# Progress: dd prints a live rate line within each chunk; every chunk logs
# a cumulative bytes/percent/rate/ETA line.
#
# Safety:
#   - stop the dht crawler on the phone first (script aborts if it sees one)
#   - the db is assembled at files/crawler/data/torrents.db.incoming and only
#     swapped into torrents.db after the full byte count verifies — an
#     interrupted run never touches the phone's current database
#   - stale sqlite sidecars (-wal/-shm/-journal) are removed before the swap
#   - the old db is kept as torrents.db.bak
#
# Expect ~40+ min for ~80 GB (USB adb typically sustains 20-40 MB/s).
#
# Env:
#   ANDROID_SERIAL  pick a device when several are attached
#   CHUNK_BYTES     per-chunk size (default 1 GiB; must be a 1 MiB multiple)
#   TRIES           per-chunk retries (default 8)
#   FORCE=1         skip the "crawler is running" guard
set -uo pipefail

PKG=com.torrent.app
DATA_DIR=files/crawler/data
DEST="$DATA_DIR/torrents.db"
INCOMING="$DEST.incoming"
META="$INCOMING.meta"
BLOCK=1048576

SRC="${1:-$HOME/torrents.db}"
CHUNK="${CHUNK_BYTES:-1073741824}"
TRIES="${TRIES:-8}"

log()   { echo "[$(date +%H:%M:%S)] $*"; }
die()   { echo "FATAL: $*" >&2; exit 1; }
h()     { awk -v b="$1" 'BEGIN{u="BKMGT";i=0;while(b>=1024&&i<4){b/=1024;i++}printf "%.1f%sB",b,substr(u,i+1,1)}'; }
remote(){ "${ADB[@]}" exec-out "run-as $PKG sh -c '$*'" 2>/dev/null; }
devsize(){ remote "stat -c %s $INCOMING" | tr -cd '0-9'; }

[ -f "$SRC" ] || die "no such file: $SRC"
SIZE=$(stat -c %s "$SRC") || die "stat failed: $SRC"
[ "$SIZE" -gt 0 ] || die "$SRC is empty"
BLOCKS=$((CHUNK / BLOCK))
[ $((BLOCKS * BLOCK)) -eq "$CHUNK" ] || die "CHUNK_BYTES must be a multiple of 1 MiB"
if [ -f "$SRC-wal" ] && [ "$(stat -c %s "$SRC-wal" 2>/dev/null || echo 0)" -gt 0 ]; then
  die "$SRC-wal is non-empty — the source db was not closed cleanly; stop whatever uses it, checkpoint it, then retry"
fi

if [ -n "${ANDROID_SERIAL:-}" ]; then ADB=(adb -s "$ANDROID_SERIAL"); else ADB=(adb); fi
"${ADB[@]}" get-state >/dev/null 2>&1 || die "no adb device online (plug the phone in; set ANDROID_SERIAL if several are attached)"
remote true >/dev/null || die "run-as $PKG failed (app not installed, or not a debug build?)"

CRAWLERS=$(remote "ps -A -o NAME= | grep -i dht" || true)
if [ -n "$CRAWLERS" ] && [ "${FORCE:-0}" != "1" ]; then
  die "the dht crawler looks running on the phone — stop it first (or FORCE=1 to ignore):
$CRAWLERS"
fi
APID=$(remote "pidof com.torrent.app" | tr -cd '0-9')
[ -z "$APID" ] || log "note: app (pid $APID) is still running — safe until the final swap, but the crawler must stay stopped"

remote "mkdir -p $DATA_DIR" || die "cannot create $DATA_DIR on the device"

# Resume bookkeeping: the .incoming partial must be chunk-aligned and come
# from a source of the exact same size (the .meta marker prevents mixing
# chunks of two different databases — that would corrupt silently).
CUR=$(devsize); CUR="${CUR:-0}"
[ "$CUR" -le "$SIZE" ] || die "device partial ($CUR bytes) is larger than $SRC ($SIZE) — stale; rm $INCOMING on the phone"
if [ "$CUR" -gt 0 ] && [ "$CUR" -ne "$SIZE" ]; then
  [ $((CUR % CHUNK)) -eq 0 ] || die "device partial has unaligned size $CUR — rm $INCOMING and $META on the phone and start over"
  OLDMETA=$(remote "cat $META" | tr -cd '0-9')
  [ "$OLDMETA" = "$SIZE" ] || die "device partial is from a different source db (${OLDMETA:-no marker} recorded, expected $SIZE) — rm $INCOMING and $META and start over"
fi
if [ "$CUR" -eq 0 ]; then
  remote "rm -f $INCOMING $META" >/dev/null 2>&1 || true
  remote "echo $SIZE > $META" || die "cannot write $META on the device"
fi

TOTAL=$(( (SIZE + CHUNK - 1) / CHUNK ))
DONE=$(( CUR / CHUNK ))

AVAIL=$(remote "cd $DATA_DIR && df -k ." | awk 'NR==2{print $4}')
[ -n "$AVAIL" ] || die "could not read free space on the device"
NEED=$((SIZE - CUR))
[ $((AVAIL * 1024)) -ge "$NEED" ] || die "not enough space on the phone: need $(h "$NEED"), free only $(h $((AVAIL * 1024)))"

log "pushing $(h "$SIZE") in $TOTAL chunk(s) of $(h "$CHUNK") — starting at chunk $DONE"

trap 'echo; log "interrupted — partial is consistent on the phone; re-run $0 to resume from chunk $DONE"; exit 130' INT TERM
START=$SECONDS
SENT0=$CUR
for (( i=DONE; i<TOTAL; i++ )); do
  skip=$(( i * BLOCKS ))
  if [ $(( i + 1 )) -eq "$TOTAL" ]; then EXPECT=$(( SIZE - i * CHUNK )); else EXPECT=$CHUNK; fi
  BEFORE=$(devsize)
  [ -n "$BEFORE" ] || die "lost the device (stat failed) — re-run $0 to resume from chunk $i"
  [ "$BEFORE" -eq $(( i * CHUNK )) ] || die "device partial is $BEFORE bytes, expected $(( i * CHUNK )) — stale; rm $INCOMING and $META and start over"
  ok=0
  for (( t=1; t<=TRIES; t++ )); do
    if dd if="$SRC" bs=$BLOCK skip=$skip count=$BLOCKS status=progress \
       | "${ADB[@]}" exec-out "run-as $PKG sh -c 'cat >> $INCOMING'"; then rc=0; else rc=$?; fi
    AFTER=$(devsize); AFTER="${AFTER:-0}"
    if [ "$rc" -eq 0 ] && [ "$AFTER" -eq $(( BEFORE + EXPECT )) ]; then ok=1; break; fi
    log "chunk $i try $t: rc=$rc, got $(( AFTER - BEFORE )) of $EXPECT bytes — truncating back and retrying"
    remote "truncate -s $BEFORE $INCOMING" >/dev/null || true
    "${ADB[@]}" reconnect >/dev/null 2>&1 || true
    "${ADB[@]}" wait-for-device >/dev/null 2>&1 || true
    sleep 8
  done
  [ "$ok" -eq 1 ] || die "chunk $i failed after $TRIES tries — partial is consistent; re-run $0 to resume"
  DONE=$(( i + 1 ))
  el=$(( SECONDS - START )); [ "$el" -lt 1 ] && el=1
  rate=$(( (AFTER - SENT0) / el )); [ "$rate" -lt 1 ] && rate=1
  eta=$(( (SIZE - AFTER) / rate ))
  log "chunk $i/$((TOTAL-1)) ok — $(h "$AFTER")/$(h "$SIZE") ($(( AFTER * 100 / SIZE ))%), $(h "$rate")/s, ETA ~$(( eta / 60 ))m$(( eta % 60 ))s"
done

trap - INT TERM
FINAL=$(devsize); FINAL="${FINAL:-0}"
[ "$FINAL" -eq "$SIZE" ] || die "final size $FINAL != $SIZE — re-run $0 to resume"
log "byte count verified — removing stale sqlite sidecars, backing up the old db, swapping into place"
remote "rm -f $DEST-wal $DEST-shm $DEST-journal" >/dev/null || true
remote "mv -f $DEST $DEST.bak" >/dev/null 2>&1 || true
remote "mv $INCOMING $DEST" || die "final swap failed — the complete file is still at $INCOMING; re-run $0"
remote "rm -f $META" >/dev/null 2>&1 || true
remote "sync"
log "done: $DEST is $(h "$SIZE") on the phone ($(date -Is))"
log "the previous db was kept at $DEST.bak (drop it with: adb exec-out \"run-as $PKG sh -c 'rm $DATA_DIR/torrents.db.bak'\")"
log "start the crawler whenever you are ready"
