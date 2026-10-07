#!/usr/bin/env bash
# ANDROID fork: stage 0 device script — sample daemon thread states (TRANSMISSION_USB_SAF).
#
# Usage: sample_threads.sh [SECONDS]
#
# Finds the daemon pid, then the tids whose comm is tr-session, tr-diskio or
# tr-verify. Every 200 ms reads /proc/PID/task/TID/syscall and /proc/PID/task/TID/stat
# (field 3 = state) for the session thread (and tr-diskio once it exists), and
# prints a line whenever the session thread is in state D or its syscall number
# is 67 (pread64) or 68 (pwrite64). Prints a summary of counts at exit.
#
# The daemon runs as the app uid, so `run-as com.torrent.app` is used to read
# /proc/PID/task. If run-as is denied, that is reported instead of working
# around it.

set -u

DURATION="${1:-0}"
INTERVAL_MS=200
APP_ID="com.torrent.app"

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found" >&2; exit 1; }

# --- find the daemon pid -----------------------------------------------------
pid=$(adb shell pidof libtransmissiond.so 2>/dev/null | tr -d '[:space:]')
if [ -z "$pid" ]; then
    pid=$(adb shell ps -A 2>/dev/null | grep -i transmission | awk 'NR==1 {print $2}')
fi
if [ -z "$pid" ]; then
    echo "ERROR: daemon pid not found (adb shell pidof libtransmissiond.so, then ps -A | grep -i transmission). Is the daemon running?" >&2
    exit 1
fi
echo "sample_threads: daemon pid=$pid"

# --- run-as access check ----------------------------------------------------
# Do not work around a denial; report it to the user.
if ! adb shell "run-as $APP_ID sh -c 'ls /proc/$pid/task'" >/dev/null 2>&1; then
    echo "ERROR: run-as $APP_ID is denied for /proc/$pid/task, so thread sampling cannot work." >&2
    echo "       The daemon runs as the app uid; run-as access is required to read /proc/PID/task." >&2
    exit 1
fi

# --- find the tids by comm ---------------------------------------------------
# Device-side: for t in /proc/PID/task/*; do echo $t $(cat $t/comm); done
comm_cmd="for t in /proc/$pid/task/*; do echo \$t \$(cat \$t/comm); done"
thread_map=$(adb shell "run-as $APP_ID sh -c '$comm_cmd'" | tr -d '\r')

session_tid=""
diskio_tid=""
verify_tid=""
while read -r task_dir comm; do
    [ -n "$task_dir" ] || continue
    case "$comm" in
        tr-session) session_tid=${task_dir##*/} ;;
        tr-diskio) diskio_tid=${task_dir##*/} ;;
        tr-verify) verify_tid=${task_dir##*/} ;;
    esac
done <<< "$thread_map"

if [ -z "$session_tid" ]; then
    echo "ERROR: no thread named 'tr-session' found under pid $pid. Stage 0 names the" >&2
    echo "       session thread via prctl; is the stage 0 build installed and running?" >&2
    exit 1
fi
echo "sample_threads: tr-session tid=$session_tid"
if [ -n "$diskio_tid" ]; then
    echo "sample_threads: tr-diskio  tid=$diskio_tid"
else
    echo "sample_threads: tr-diskio  tid= (not present yet; stage 1+)"
fi
if [ -n "$verify_tid" ]; then
    echo "sample_threads: tr-verify  tid=$verify_tid"
else
    echo "sample_threads: tr-verify  tid= (no verification running)"
fi

# --- sampling ----------------------------------------------------------------
samples=0
session_d=0
session_pread=0
session_pwrite=0
diskio_d=0
diskio_pread=0
diskio_pwrite=0
start_epoch=$(date +%s)

summary() {
    echo
    echo "== sample_threads summary (pid $pid) =="
    echo "samples: $samples (interval ${INTERVAL_MS} ms)"
    echo "tr-session: D=$session_d pread64(67)=$session_pread pwrite64(68)=$session_pwrite"
    if [ -n "$diskio_tid" ]; then
        echo "tr-diskio:   D=$diskio_d pread64(67)=$diskio_pread pwrite64(68)=$diskio_pwrite"
    fi
}

cleanup() {
    summary
    exit 0
}
trap cleanup INT TERM

sample_thread() {
    # $1 = tid, $2 = thread label; updates the matching counters via stdout:
    #   "<d> <pread> <pwrite>"
    local tid="$1"
    local out syscall_no state
    out=$(adb shell "run-as $APP_ID sh -c 'cat /proc/$pid/task/$tid/syscall /proc/$pid/task/$tid/stat'" 2>/dev/null | tr -d '\r') || return
    [ -n "$out" ] || return

    # First line: /proc/.../syscall -> "NR arg0 arg1 ... sp pc" or "running".
    syscall_no=$(printf '%s\n' "$out" | awk 'NR==1 { print $1 }')
    # Second line: /proc/.../stat -> "pid (comm) state ..."; take field after ')'.
    state=$(printf '%s\n' "$out" | awk 'NR==2 { sub(/.*\) /, ""); print $1 }')

    local d=0 pr=0 pw=0
    if [ "$state" = "D" ]; then
        d=1
    fi
    if [ "$syscall_no" = "67" ]; then
        pr=1
    elif [ "$syscall_no" = "68" ]; then
        pw=1
    fi
    echo "$d $pr $pw"
}

echo "sample_threads: sampling every ${INTERVAL_MS} ms (Ctrl-C to stop and print the summary)"

while :; do
    if [ "$DURATION" -gt 0 ] && [ $(( $(date +%s) - start_epoch )) -ge "$DURATION" ]; then
        break
    fi

    samples=$((samples + 1))
    now=$(date +%s.%N)

    res=$(sample_thread "$session_tid") || res=""
    if [ -n "$res" ]; then
        read -r d pr pw <<< "$res"
        session_d=$((session_d + d))
        session_pread=$((session_pread + pr))
        session_pwrite=$((session_pwrite + pw))
        if [ "$d" = "1" ] || [ "$pr" = "1" ] || [ "$pw" = "1" ]; then
            why=""
            [ "$d" = "1" ] && why="$why D"
            [ "$pr" = "1" ] && why="$why pread64(67)"
            [ "$pw" = "1" ] && why="$why pwrite64(68)"
            echo "$now tr-session$why"
        fi
    fi

    if [ -n "$diskio_tid" ]; then
        res=$(sample_thread "$diskio_tid") || res=""
        if [ -n "$res" ]; then
            read -r d pr pw <<< "$res"
            diskio_d=$((diskio_d + d))
            diskio_pread=$((diskio_pread + pr))
            diskio_pwrite=$((diskio_pwrite + pw))
        fi
    fi

    sleep 0.2
done

cleanup
