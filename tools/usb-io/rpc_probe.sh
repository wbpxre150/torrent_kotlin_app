#!/usr/bin/env bash
# ANDROID fork: stage 0 device script — RPC latency probe (TRANSMISSION_USB_SAF).
#
# Usage: rpc_probe.sh USER PASS [SECONDS]
#
# Runs `adb forward tcp:9091 tcp:9091` once, then every 1 s sends a session-get
# RPC request. Only HTTP 200 and 409 responses count as latency samples;
# connection failures and timeouts (http_code 000) and any other codes are
# counted separately and never as latency samples. On exit (Ctrl-C or SECONDS
# elapsed) prints count, p50, p99 and max latency in ms plus the number of
# non-200/409 results.
#
# RPC credentials live in the app's settings (defaults: torrentapp / test).

set -u

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 USER PASS [SECONDS]" >&2
    echo "  USER/PASS: transmission RPC credentials (app settings; default torrentapp/test)" >&2
    echo "  SECONDS:   optional; stop after this many seconds (default: run until Ctrl-C)" >&2
    exit 1
fi

USER_NAME="$1"
PASS_NAME="$2"
DURATION="${3:-0}"

URL="http://127.0.0.1:9091/transmission/rpc"
MAX_TIME=600

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "ERROR: curl not found" >&2; exit 1; }
command -v awk >/dev/null 2>&1 || { echo "ERROR: awk not found" >&2; exit 1; }

# Forward the RPC port once.
if ! adb forward tcp:9091 tcp:9091 >/dev/null 2>&1; then
    echo "ERROR: adb forward tcp:9091 tcp:9091 failed (device connected?)" >&2
    exit 1
fi

latencies_ms=()
ok_count=0
other_count=0
start_epoch=$(date +%s)

report() {
    local n=${#latencies_ms[@]}
    echo
    echo "== rpc_probe summary =="
    echo "latency samples (HTTP 200/409): $n"
    echo "non-200/409 results:            $other_count"
    if [ "$n" -eq 0 ]; then
        echo "count=0 (no latency samples collected)"
        return
    fi
    printf '%s\n' "${latencies_ms[@]}" | sort -n | awk -v n="$n" '
        { v[NR] = $1 }
        END {
            max = v[n]
            # nearest-rank percentiles: index = ceil(p * n)
            p50_i = int(0.50 * n)
            if (p50_i < 0.50 * n) { p50_i = p50_i + 1 }
            p99_i = int(0.99 * n)
            if (p99_i < 0.99 * n) { p99_i = p99_i + 1 }
            if (p50_i < 1) { p50_i = 1 }
            if (p50_i > n) { p50_i = n }
            if (p99_i < 1) { p99_i = 1 }
            if (p99_i > n) { p99_i = n }
            printf "count=%d p50=%.1fms p99=%.1fms max=%.1fms\n", n, v[p50_i], v[p99_i], max
        }'
}

cleanup() {
    report
    exit 0
}
trap cleanup INT TERM

echo "rpc_probe: forwarding tcp:9091 -> device tcp:9091; probing $URL every 1 s"
echo "rpc_probe: Ctrl-C to stop and print the summary"

while :; do
    if [ "$DURATION" -gt 0 ] && [ $(( $(date +%s) - start_epoch )) -ge "$DURATION" ]; then
        break
    fi

    out=$(curl -s -o /dev/null -w '%{http_code} %{time_total}\n' \
        --max-time "$MAX_TIME" \
        -u "$USER_NAME:$PASS_NAME" \
        -X POST -d '{"method":"session-get"}' \
        "$URL") || out="000 0.000"

    code=${out%% *}
    time_s=${out##* }

    if [ "$code" = "200" ] || [ "$code" = "409" ]; then
        ok_count=$((ok_count + 1))
        latencies_ms+=("$(awk -v t="$time_s" 'BEGIN { printf "%.3f", t * 1000 }')")
    else
        other_count=$((other_count + 1))
        echo "rpc_probe: non-200/409 result: $out"
    fi

    sleep 1
done

cleanup
