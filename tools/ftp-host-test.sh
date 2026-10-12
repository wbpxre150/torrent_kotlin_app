#!/usr/bin/env bash
#
# Host smoke test for the native FTP daemon (ftpserver-host).
#
# Builds with cmake into build/ftp-host, creates a test root with tricky file
# names (spaces, brackets, UTF-8), starts the daemon on 127.0.0.1:22121 with
# PASV range 31000-31019 and FTP_USERNAME=kodi / FTP_PASSWORD=testtest, then
# runs 14 curl-based assertions, then a SAF-backend pass (15-19) against a
# python3 mock of the SafIpcServer wire protocol (skipped cleanly if python3
# is missing). Exits non-zero on any failure and always kills the daemons.

set -u
export LC_ALL=C

cd "$(dirname "$0")/.."

HOST=127.0.0.1
PORT=22121
USER=kodi
PASS=testtest
PASV_LO=31000
PASV_HI=31019

SAF_PORT=22122
SAF_PASV_LO=31020
SAF_PASV_HI=31039
SAF_BIN=build/ftp-host-saf/ftpserver-host
SAF_DAEMON_PID=""
MOCK_PID=""

BIN=build/ftp-host/ftpserver-host
FTPURL="ftp://$HOST:$PORT"

WORK=$(mktemp -d /tmp/ftp-host-test.XXXXXX)
ROOT="$WORK/root"
DAEMON_LOG="$WORK/daemon.log"
DAEMON_PID=""

PASS_COUNT=0
FAIL_COUNT=0

pass() { echo "PASS: $1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { echo "FAIL: $1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }

cleanup() {
  if [ -n "$SAF_DAEMON_PID" ] && kill -0 "$SAF_DAEMON_PID" 2>/dev/null; then
    kill "$SAF_DAEMON_PID" 2>/dev/null
    wait "$SAF_DAEMON_PID" 2>/dev/null
  fi
  if [ -n "$MOCK_PID" ] && kill -0 "$MOCK_PID" 2>/dev/null; then
    kill "$MOCK_PID" 2>/dev/null
    wait "$MOCK_PID" 2>/dev/null
  fi
  if [ -n "$DAEMON_PID" ] && kill -0 "$DAEMON_PID" 2>/dev/null; then
    kill "$DAEMON_PID" 2>/dev/null
    wait "$DAEMON_PID" 2>/dev/null
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "=== build ==="
if ! cmake -S app/src/main/cpp/ftp-server/host -B build/ftp-host > "$WORK/cmake.log" 2>&1; then
  echo "FAIL: cmake configure"; cat "$WORK/cmake.log"; exit 1
fi
if ! cmake --build build/ftp-host > "$WORK/build.log" 2>&1; then
  echo "FAIL: build"; cat "$WORK/build.log"; exit 1
fi
echo "build ok"

echo "=== make test root ==="
mkdir -p "$ROOT/sub dir" "$ROOT/tmp_rmdir"
head -c 1048576 /dev/urandom > "$ROOT/Good Luck Have Fun.mkv"
head -c 16777216 /dev/zero > "$ROOT/Big File For Abort.mkv"
printf 'hello bracket\n' > "$ROOT/[REPACK] show [YTS.MX].mkv"
printf 'utf8 content\n' > "$ROOT/héllo wörld.txt"
printf 'inner\n' > "$ROOT/sub dir/inner file.txt"
printf 'temp\n' > "$ROOT/tmp_dele.txt"

echo "=== start daemon ==="
FTP_USERNAME="$USER" FTP_PASSWORD="$PASS" "$BIN" \
  --bind "$HOST" --port "$PORT" --root "$ROOT" \
  --pasv "$PASV_LO-$PASV_HI" --max-kibps 0 --saf-socket "$WORK/saf.sock" \
  > "$DAEMON_LOG" 2>&1 &
DAEMON_PID=$!

up=0
for _ in $(seq 1 100); do
  if (exec 3<>/dev/tcp/$HOST/$PORT) 2>/dev/null; then
    up=1
    break
  fi
  if ! kill -0 "$DAEMON_PID" 2>/dev/null; then
    break
  fi
  sleep 0.1
done
if [ "$up" -ne 1 ]; then
  echo "FAIL: daemon not listening"
  cat "$DAEMON_LOG"
  exit 1
fi
echo "daemon listening (pid $DAEMON_PID)"

echo
echo "=== 1: bad password rejected (530) ==="
rc=0
curl -s --max-time 10 -u "$USER:wrongpass" "$FTPURL/" > /dev/null 2>&1 || rc=$?
if [ $rc -ne 0 ]; then
  pass "1 bad password rejected (curl exit $rc)"
else
  fail "1 bad password rejected"
fi

echo "=== 2: LIST format (baseline columns, bracketed name) ==="
list_unix=$(curl -s --max-time 10 -u "$USER:$PASS" "$FTPURL/" | tr -d '\r')
rc=$?
LINE_RE='^[-d]r-[-x]-{5,7}   1 torrent torrent +[0-9]+ (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) +[0-9]{2} ([0-9]{2}:[0-9]{2}|  [0-9]{4}) '
if [ $rc -eq 0 ] \
  && printf '%s\n' "$list_unix" | grep -Eq "$LINE_RE" \
  && printf '%s\n' "$list_unix" | grep -Fq '[REPACK] show [YTS.MX].mkv' \
  && printf '%s\n' "$list_unix" | grep -Eq '^dr-x------   1 torrent torrent +0 ' \
  && printf '%s\n' "$list_unix" | grep -Fq 'Good Luck Have Fun.mkv'; then
  pass "2 LIST format"
else
  fail "2 LIST format"
  printf '%s\n' "$list_unix" | sed 's/^/    | /'
fi

echo "=== 3: full download byte-matches ==="
if curl -s --max-time 30 -u "$USER:$PASS" -o "$WORK/dl-full" \
     "$FTPURL/Good%20Luck%20Have%20Fun.mkv" \
  && cmp -s "$WORK/dl-full" "$ROOT/Good Luck Have Fun.mkv"; then
  pass "3 full download byte-match"
else
  fail "3 full download byte-match"
fi

echo "=== 4: range download (100-199) byte-matches ==="
dd if="$ROOT/Good Luck Have Fun.mkv" bs=1 skip=100 count=100 of="$WORK/range-exp" 2>/dev/null
if curl -s --max-time 30 -u "$USER:$PASS" -r 100-199 -o "$WORK/dl-range" \
     "$FTPURL/Good%20Luck%20Have%20Fun.mkv" \
  && cmp -s "$WORK/dl-range" "$WORK/range-exp"; then
  pass "4 range download byte-match"
else
  fail "4 range download byte-match"
fi

echo "=== 5: REST-resume join of two halves byte-matches ==="
size=$(stat -c %s "$ROOT/Good Luck Have Fun.mkv")
half=$((size / 2))
if curl -s --max-time 30 -u "$USER:$PASS" -r 0-$((half - 1)) -o "$WORK/half1" \
     "$FTPURL/Good%20Luck%20Have%20Fun.mkv" \
  && curl -s --max-time 30 -u "$USER:$PASS" -r $half- -o "$WORK/half2" \
     "$FTPURL/Good%20Luck%20Have%20Fun.mkv" \
  && cat "$WORK/half1" "$WORK/half2" > "$WORK/joined" \
  && cmp -s "$WORK/joined" "$ROOT/Good Luck Have Fun.mkv"; then
  pass "5 REST-resume join byte-match"
else
  fail "5 REST-resume join byte-match"
fi

echo "=== 6: tail range (last 100 bytes) byte-matches ==="
tail -c 100 "$ROOT/Good Luck Have Fun.mkv" > "$WORK/tail-exp"
if curl -s --max-time 30 -u "$USER:$PASS" -r -100 -o "$WORK/dl-tail" \
     "$FTPURL/Good%20Luck%20Have%20Fun.mkv" \
  && cmp -s "$WORK/dl-tail" "$WORK/tail-exp"; then
  pass "6 tail range byte-match"
else
  fail "6 tail range byte-match"
fi

echo "=== 7: NLST returns plain names ==="
nlst=$(curl -s --list-only --max-time 10 -u "$USER:$PASS" "$FTPURL/" | tr -d '\r')
nlst_ok=1
for name in "Good Luck Have Fun.mkv" "[REPACK] show [YTS.MX].mkv" "sub dir" \
            "héllo wörld.txt" "Big File For Abort.mkv"; do
  if ! printf '%s\n' "$nlst" | grep -Fxq "$name"; then
    nlst_ok=0
    echo "    missing NLST entry: $name"
  fi
done
if printf '%s\n' "$nlst" | grep -q 'torrent'; then
  nlst_ok=0
  echo "    NLST output is not plain names"
fi
if [ $nlst_ok -eq 1 ]; then
  pass "7 NLST plain names"
else
  fail "7 NLST plain names"
fi

echo "=== 8: SIZE matches stat (raw control) ==="
size_test() {
  exec 3<>/dev/tcp/$HOST/$PORT || return 1
  local l
  read -t 5 -u 3 l || return 1                    # 220
  printf 'USER %s\r\n' "$USER" >&3
  read -t 5 -u 3 l || return 1
  printf 'PASS %s\r\n' "$PASS" >&3
  read -t 5 -u 3 l || return 1
  printf 'SIZE Good Luck Have Fun.mkv\r\n' >&3
  read -t 5 -u 3 l || return 1
  printf '%s' "${l#213 }" | tr -d '\r' > "$WORK/size-out"   # 213 payload, no CR
  printf 'QUIT\r\n' >&3
  exec 3<&- 3>&-
  return 0
}
exp_sz=$(stat -c %s "$ROOT/Good Luck Have Fun.mkv")
if size_test && [ "$(cat "$WORK/size-out")" = "$exp_sz" ]; then
  pass "8 SIZE matches stat ($exp_sz)"
else
  fail "8 SIZE matches stat (got '$(cat "$WORK/size-out" 2>/dev/null)', want '$exp_sz')"
fi

echo "=== 9: MDTM matches (raw control, UTC) ==="
mdtm_test() {
  exec 3<>/dev/tcp/$HOST/$PORT || return 1
  local l
  read -t 5 -u 3 l || return 1                    # 220
  printf 'USER %s\r\n' "$USER" >&3
  read -t 5 -u 3 l || return 1
  printf 'PASS %s\r\n' "$PASS" >&3
  read -t 5 -u 3 l || return 1
  printf 'MDTM Good Luck Have Fun.mkv\r\n' >&3
  read -t 5 -u 3 l || return 1
  printf '%s' "${l#213 }" | tr -d '\r' > "$WORK/mdtm-out"   # 213 payload, no CR
  printf 'QUIT\r\n' >&3
  exec 3<&- 3>&-
  return 0
}
exp_mdtm=$(date -u -d "@$(stat -c %Y "$ROOT/Good Luck Have Fun.mkv")" '+%Y%m%d%H%M%S')
if mdtm_test && [ "$(cat "$WORK/mdtm-out")" = "$exp_mdtm" ]; then
  pass "9 MDTM matches ($exp_mdtm)"
else
  fail "9 MDTM matches (got '$(cat "$WORK/mdtm-out" 2>/dev/null)', want '$exp_mdtm')"
fi

echo "=== 10: ABOR mid-transfer leaves session usable (raw control) ==="
abor_test() {
  exec 3<>/dev/tcp/$HOST/$PORT || return 1
  local l t a b c d p1 p2 dport chunk
  read -t 5 -u 3 l || return 1                    # 220
  printf 'USER %s\r\n' "$USER" >&3
  read -t 5 -u 3 l || return 1                    # 331
  printf 'PASS %s\r\n' "$PASS" >&3
  read -t 5 -u 3 l || return 1                    # 230
  printf 'PASV\r\n' >&3
  read -t 5 -u 3 l || return 1                    # 227 ... (h,h,h,h,p1,p2)
  t=${l##*( }
  t=${t%%\)*}
  IFS=, read -r a b c d p1 p2 <<< "$t"
  dport=$((p1 * 256 + p2))
  exec 4<>/dev/tcp/$HOST/$dport || return 1
  printf 'RETR Big File For Abort.mkv\r\n' >&3
  read -t 5 -u 3 l || return 1                    # 150
  read -t 5 -u 4 -n 64 chunk                      # pull a little, then stall
  sleep 0.2                                       # let the server fill its buffer
  printf 'ABOR\r\n' >&3
  read -t 5 -u 3 l || return 1
  [ "${l:0:3}" = "426" ] || return 1
  read -t 5 -u 3 l || return 1
  [ "${l:0:3}" = "226" ] || return 1
  printf 'NOOP\r\n' >&3
  read -t 5 -u 3 l || return 1
  [ "${l:0:3}" = "200" ] || return 1
  printf 'QUIT\r\n' >&3
  read -t 5 -u 3 l 2>/dev/null
  exec 3<&- 3>&- 4<&- 4>&-
  return 0
}
if abor_test; then
  pass "10 ABOR mid-transfer (426+226, session usable)"
else
  fail "10 ABOR mid-transfer"
fi

echo "=== 11: CWD into subdir + LIST ==="
sublist=$(curl -s --max-time 10 -u "$USER:$PASS" "$FTPURL/sub%20dir/" | tr -d '\r')
if printf '%s\n' "$sublist" | grep -Fq 'inner file.txt' \
  && ! printf '%s\n' "$sublist" | grep -Fq 'Good Luck Have Fun.mkv'; then
  pass "11 CWD subdir + LIST"
else
  fail "11 CWD subdir + LIST"
  printf '%s\n' "$sublist" | sed 's/^/    | /'
fi

echo "=== 12: 9th concurrent session gets 421 ==="
# 12 slow-paced downloads: each holds a session ~3s, so the cap (8) is hit.
cap_out=$(seq 1 12 | xargs -P 12 -I{} bash -c \
  'curl -s --max-time 3 --limit-rate 1024 -o /dev/null "ftp://127.0.0.1:22121/Good%20Luck%20Have%20Fun.mkv" >/dev/null 2>&1 && echo ok || echo fail')
nfail=$(printf '%s\n' "$cap_out" | grep -c '^fail$')
if [ "$nfail" -ge 1 ]; then
  pass "12 session cap (421 x$nfail of 12)"
else
  fail "12 session cap (no 421 seen)"
fi

echo "=== 13: DELE/RMD actually delete ==="
rc1=0
curl -s --max-time 10 -u "$USER:$PASS" -Q 'DELE tmp_dele.txt' "$FTPURL/" > /dev/null 2>&1 || rc1=$?
rc2=0
curl -s --max-time 10 -u "$USER:$PASS" -Q 'RMD tmp_rmdir' "$FTPURL/" > /dev/null 2>&1 || rc2=$?
if [ $rc1 -eq 0 ] && [ $rc2 -eq 0 ] \
  && [ ! -e "$ROOT/tmp_dele.txt" ] && [ ! -d "$ROOT/tmp_rmdir" ]; then
  pass "13 DELE/RMD delete"
else
  fail "13 DELE/RMD delete (rc1=$rc1 rc2=$rc2)"
fi

echo "=== 14: PORT gets 502 (raw control) ==="
port_test() {
  exec 3<>/dev/tcp/$HOST/$PORT || return 1
  local l
  read -t 5 -u 3 l || return 1                    # 220
  printf 'USER %s\r\n' "$USER" >&3
  read -t 5 -u 3 l || return 1
  printf 'PASS %s\r\n' "$PASS" >&3
  read -t 5 -u 3 l || return 1
  printf 'PORT 127,0,0,1,10,0\r\n' >&3
  read -t 5 -u 3 l || return 1
  [ "${l:0:3}" = "502" ] || return 1
  printf 'QUIT\r\n' >&3
  exec 3<&- 3>&-
  return 0
}
if port_test; then
  pass "14 PORT gets 502"
else
  fail "14 PORT gets 502"
fi

echo
echo "=== SAF-backend pass (mock SafIpcServer) ==="
if ! command -v python3 > /dev/null 2>&1; then
  echo "SKIP: python3 not found; skipping SAF-backend pass"
else
  SAF_URL="ftp://$HOST:$SAF_PORT"

  echo "=== saf: build ==="
  if ! cmake -S app/src/main/cpp/ftp-server/host -B build/ftp-host-saf \
       -DFTPD_BACKEND=saf > "$WORK/cmake-saf.log" 2>&1 \
    || ! cmake --build build/ftp-host-saf > "$WORK/build-saf.log" 2>&1; then
    echo "FAIL: saf build"; cat "$WORK/cmake-saf.log" "$WORK/build-saf.log"; exit 1
  fi
  echo "saf build ok"

  head -c 4096 /dev/urandom > "$WORK/saf-data.bin"
  cat > "$WORK/mock_saf.py" <<'PYEOF'
#!/usr/bin/env python3
"""Mock of the Kotlin SafIpcServer read-only wire protocol.

  request:  [u8 op][u16 LE payloadLen][payload]
  response: [i32 LE status][u16 LE payloadLen][payload]
  strings:  [u16 LE len][utf-8 bytes]

Ops: 10 RO_STAT -> 0 + [u8 isDir][u64 LE size][u64 LE mtimeMs] (-2 unknown)
     11 RO_LIST -> 0 + [u32 0xFFFFFFFF][u16 0]  (END, zero entries)
     12 RO_OPEN -> 0 + no payload, plus one read fd via SCM_RIGHTS (sendmsg)
     5  REMOVE  -> 0

Each received op is logged as: op=<n> path=<path>
"""
import os
import socket
import struct
import sys

sock_path, data_file, log_path = sys.argv[1], sys.argv[2], sys.argv[3]
MTIME_MS = 1700000000000
DIRS = {"", "dir"}
FILES = {"dir/file.avi": (os.path.getsize(data_file), MTIME_MS)}


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def send_with_fd(conn, fd, data):
    """Send `data` plus `fd` via SCM_RIGHTS in one datagram.
    socket.send_fds() was removed in Python 3.14; sendmsg() with an
    SCM_RIGHTS ancillary message is the same mechanism on all versions."""
    if hasattr(conn, "send_fds"):
        conn.send_fds([fd], data)
    else:
        conn.sendmsg([data], [(1, 1, struct.pack("<i", fd))])  # SOL_SOCKET, SCM_RIGHTS


logf = open(log_path, "w")
srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
if os.path.exists(sock_path):
    os.unlink(sock_path)
srv.bind(sock_path)
srv.listen(32)

while True:
    conn, _ = srv.accept()
    try:
        hdr = recv_exact(conn, 3)
        op = hdr[0]
        plen = struct.unpack("<H", hdr[1:3])[0]
        payload = recv_exact(conn, plen) if plen else b""
        path = ""
        if len(payload) >= 2:
            n = struct.unpack("<H", payload[:2])[0]
            path = payload[2:2 + n].decode("utf-8", "replace")
        logf.write("op=%d path=%s\n" % (op, path))
        logf.flush()
        if op == 10:  # RO_STAT
            if path in DIRS:
                body = struct.pack("<BQQ", 1, 0, MTIME_MS)
            elif path in FILES:
                size, mtime = FILES[path]
                body = struct.pack("<BQQ", 0, size, mtime)
            else:
                conn.sendall(struct.pack("<iH", -2, 0))
                continue
            conn.sendall(struct.pack("<iH", 0, len(body)) + body)
        elif op == 11:  # RO_LIST
            conn.sendall(struct.pack("<iHIH", 0, 6, 0xFFFFFFFF, 0))
        elif op == 12:  # RO_OPEN
            fd = os.open(data_file, os.O_RDONLY)
            try:
                send_with_fd(conn, fd, struct.pack("<iH", 0, 0))
            finally:
                os.close(fd)
        elif op == 5:  # REMOVE
            conn.sendall(struct.pack("<iH", 0, 0))
        else:
            conn.sendall(struct.pack("<iH", -11, 0))
    except Exception:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass
PYEOF

  python3 "$WORK/mock_saf.py" "$WORK/saf.sock" "$WORK/saf-data.bin" "$WORK/mock.log" &
  MOCK_PID=$!
  up=0
  for _ in $(seq 1 100); do
    if [ -S "$WORK/saf.sock" ]; then
      up=1
      break
    fi
    if ! kill -0 "$MOCK_PID" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  if [ "$up" -ne 1 ]; then
    echo "FAIL: mock socket not ready"
    exit 1
  fi

  echo "=== saf: start daemon ==="
  FTP_USERNAME="$USER" FTP_PASSWORD="$PASS" "$SAF_BIN" \
    --bind "$HOST" --port "$SAF_PORT" --root "" \
    --pasv "$SAF_PASV_LO-$SAF_PASV_HI" --max-kibps 0 --saf-socket "$WORK/saf.sock" \
    > "$WORK/saf-daemon.log" 2>&1 &
  SAF_DAEMON_PID=$!

  up=0
  for _ in $(seq 1 100); do
    if (exec 3<>/dev/tcp/$HOST/$SAF_PORT) 2>/dev/null; then
      up=1
      break
    fi
    if ! kill -0 "$SAF_DAEMON_PID" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  if [ "$up" -ne 1 ]; then
    echo "FAIL: saf daemon not listening"
    cat "$WORK/saf-daemon.log"
    exit 1
  fi
  echo "saf daemon listening (pid $SAF_DAEMON_PID)"

  echo "=== 15: SAF LIST root works (mock returns zero entries) ==="
  saf_list=$(curl -s --max-time 10 -u "$USER:$PASS" "$SAF_URL/" | tr -d '\r')
  rc=$?
  if [ $rc -eq 0 ]; then
    pass "15 SAF LIST root"
  else
    fail "15 SAF LIST root (curl exit $rc)"
  fi

  echo "=== 16: SAF SIZE nested path (depth 2, raw control) ==="
  saf_size_test() {
    exec 3<>/dev/tcp/$HOST/$SAF_PORT || return 1
    local l
    read -t 5 -u 3 l || return 1                    # 220
    printf 'USER %s\r\n' "$USER" >&3
    read -t 5 -u 3 l || return 1
    printf 'PASS %s\r\n' "$PASS" >&3
    read -t 5 -u 3 l || return 1
    printf 'SIZE dir/file.avi\r\n' >&3
    read -t 5 -u 3 l || return 1
    printf '%s' "${l#213 }" | tr -d '\r' > "$WORK/saf-size-out"   # 213 payload, no CR
    printf 'QUIT\r\n' >&3
    exec 3<&- 3>&-
    return 0
  }
  exp_saf_sz=$(stat -c %s "$WORK/saf-data.bin")
  if saf_size_test && [ "$(cat "$WORK/saf-size-out")" = "$exp_saf_sz" ]; then
    pass "16 SAF SIZE nested (213 $exp_saf_sz)"
  else
    fail "16 SAF SIZE nested (got '$(cat "$WORK/saf-size-out" 2>/dev/null)', want '$exp_saf_sz')"
  fi

  echo "=== 17: SAF RETR nested file byte-matches (fd via SCM_RIGHTS) ==="
  if curl -s --max-time 30 -u "$USER:$PASS" -o "$WORK/saf-retr" "$SAF_URL/dir/file.avi" \
    && cmp -s "$WORK/saf-retr" "$WORK/saf-data.bin"; then
    pass "17 SAF RETR nested byte-match"
  else
    fail "17 SAF RETR nested byte-match"
  fi

  echo "=== 18: SAF CWD into nested dir + relative SIZE (raw control) ==="
  saf_cwd_test() {
    exec 3<>/dev/tcp/$HOST/$SAF_PORT || return 1
    local l
    read -t 5 -u 3 l || return 1                    # 220
    printf 'USER %s\r\n' "$USER" >&3
    read -t 5 -u 3 l || return 1                    # 331
    printf 'PASS %s\r\n' "$PASS" >&3
    read -t 5 -u 3 l || return 1                    # 230
    printf 'CWD dir\r\n' >&3
    read -t 5 -u 3 l || return 1
    [ "${l:0:3}" = "250" ] || return 1
    printf 'SIZE file.avi\r\n' >&3                  # relative: cwd join
    read -t 5 -u 3 l || return 1
    [ "${l:0:3}" = "213" ] || return 1
    printf '%s' "${l#213 }" | tr -d '\r' > "$WORK/saf-size-rel-out"
    printf 'QUIT\r\n' >&3
    exec 3<&- 3>&-
    return 0
  }
  if saf_cwd_test && [ "$(cat "$WORK/saf-size-rel-out")" = "$exp_saf_sz" ]; then
    pass "18 SAF CWD nested (250) + relative SIZE ($exp_saf_sz)"
  else
    fail "18 SAF CWD nested (got '$(cat "$WORK/saf-size-rel-out" 2>/dev/null)', want '$exp_saf_sz')"
  fi

  echo "=== 19: mock log shows the nested stat arrived ==="
  if grep -Fq "op=10 path=dir/file.avi" "$WORK/mock.log"; then
    pass "19 mock log has nested RO_STAT"
  else
    fail "19 mock log has nested RO_STAT"
    sed 's/^/    | /' "$WORK/mock.log" 2>/dev/null
  fi
fi

echo
echo "=== summary: $PASS_COUNT passed, $FAIL_COUNT failed ==="
if [ $FAIL_COUNT -ne 0 ]; then
  echo "--- daemon log (tail) ---"
  tail -n 40 "$DAEMON_LOG" || true
  exit 1
fi
exit 0
