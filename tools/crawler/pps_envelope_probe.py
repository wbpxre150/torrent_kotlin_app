#!/usr/bin/env python3
"""Windowed probe for the UDP pps / wifi / thermal envelope experiment.

One window = a fixed wall-clock run at one `udp_rate_limit_pps` rung. The probe samples
three sources on every tick and diffs them at the end, so one invocation answers the
plan's per-rung question ("did this rung disconnect, and did it cost heat or throughput?"):

  * the crawler's own `/stats`  (throughput, throttle share, failure split, movie-kind)
  * the app's `/api/status`     (the wifi-gate dropout counters, crawler restart count)
  * the device                  (`dumpsys thermalservice` / `dumpsys battery` / the crawler
                                 process's thread count + VmHWM) — needs adb

Sources 1 and 2 are plain HTTP over the LAN (the app's CrawlerProxyServer on :8080 and its
control dashboard on :8090), so throughput keeps being measurable when adb is unavailable;
the device columns then read `n/a` and the run is marked `degraded`. Nothing here needs a
crawler restart, and nothing here writes to the device.

Why the counters are diffed rather than read once: every settings apply restarts the
crawler, which zeroes every crawler counter, so a single snapshot cannot describe a window.
The wifi-gate counters are app-level and survive those restarts — they are the only
instrument that spans rungs, which is why `--baseline` exists to pin a per-window origin.

Examples
--------
  # one 10-minute rung at 4000 pps, JSON kept for the record
  python3 tools/crawler/pps_envelope_probe.py --pps 4000 --minutes 10 \\
      --json /tmp/pps-4000.json

  # 60-minute stability hour, 5 s sampling
  python3 tools/crawler/pps_envelope_probe.py --pps 4500 --minutes 60 --interval 5

  # no adb available: throughput + dropouts only
  python3 tools/crawler/pps_envelope_probe.py --pps 4000 --minutes 10 --no-adb

Exit codes: 0 = window completed, 2 = a stop rule fired (DISCONNECT / THERMAL BREAK),
3 = a source never answered, 4 = bad arguments.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------- defaults

DEFAULT_HOST = "192.168.0.8"          # the reference device's LAN address
DEFAULT_TOKEN = ""                    # control_auth.xml:auth_token (see --token-file)
# Absolute SEVERE (3) is NOT a usable bar on this device: at 3000 pps on USB power the resting
# status is already 3 (skin sensors mStatus=3 at 45-55 C) and only falls to 2 at 1000 pps, so a
# literal "no rung reaches 3" rule fails every rung before wifi is ever tested. The
# operator-authorised bar is relative to the rung-0 window instead (recorded as a plan
# deviation in plan/pps-wifi-thermal-results.md).
ZONE_MARGIN_C = 5.0                   # CPU/skin may not exceed the rung-0 maximum by this much
DISCONNECT_EDGES = "wifiDropoutsOver2sTotal"
MEAN_US_TOLERANCE = 1.20              # plan: mean_us_per_item within +20 % of the rung-0 value


# ---------------------------------------------------------------- helpers

def http_json(url: str, timeout: float) -> dict:
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def read_token(path: str | None) -> str:
    """Pull auth_token out of a `control_auth.xml` copy, if one was supplied."""
    if not path:
        return ""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            m = re.search(r'name="auth_token">([0-9a-f]{16,})<', f.read())
            return m.group(1) if m else ""
    except OSError:
        return ""


def adb(serial: str | None, cmd: str, timeout: float) -> str | None:
    """Run one `adb shell` command; None means adb is unavailable (degraded run)."""
    argv = ["adb"] + (["-s", serial] if serial else []) + ["shell", cmd]
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    except (subprocess.TimeoutExpired, OSError):
        return None
    if p.returncode != 0:
        return None
    return p.stdout


def parse_thermal(out: str | None) -> dict:
    if not out:
        return {}
    status = None
    m = re.search(r"Thermal Status:\s*(\d+)", out)
    if m:
        status = int(m.group(1))
    cpu = [float(v) for v in re.findall(r"mValue=([\d.]+),\s*mType=0,\s*mName=CPU", out)]
    skin = [float(v) for v in re.findall(r"mValue=([\d.]+),\s*mType=3,\s*mName=skin", out)]
    return {
        "thermal_status": status,
        "cpu_max_c": max(cpu) if cpu else None,
        "skin_c": max(skin) if skin else None,
    }


def parse_battery(out: str | None) -> dict:
    if not out:
        return {}
    m = re.search(r"temperature:\s*(\d+)", out)
    lvl = re.search(r"level:\s*(\d+)", out)
    return {
        "battery_c": round(int(m.group(1)) / 10.0, 1) if m else None,
        "battery_pct": int(lvl.group(1)) if lvl else None,
    }


def parse_proc(out: str | None) -> dict:
    """`pid threads vmhwm_kib` printed by the on-device shell one-liner."""
    if not out:
        return {}
    for line in out.splitlines():
        f = line.split()
        if len(f) == 3 and f[0].isdigit():
            try:
                return {"pid": int(f[0]), "threads": int(f[1]), "vmhwm_kib": int(f[2])}
            except ValueError:
                continue
    return {}


# `comm` is truncated to 15 chars ("libdhtcrawler.s"), so match on ps NAME instead, and always
# print three fields so parse_proc() can read the line even when VmHWM is unreadable.
PROC_CMD = (
    'p=$(ps -A -o PID,NAME 2>/dev/null | grep libdhtcrawler | head -1 | awk "{print \\$1}"); '
    'if [ -n "$p" ]; then v=$(grep VmHWM /proc/$p/status 2>/dev/null | awk "{print \\$2}"); '
    'echo "$p $(ls /proc/$p/task | wc -l) ${v:-0}"; fi'
)


# ---------------------------------------------------------------- sampling

def sample(host: str, token: str, serial: str | None, use_adb: bool, timeout: float) -> dict:
    """One tick: crawler stats, app status, and (if adb) the device's thermal state."""
    s: dict = {"ts": time.time()}

    stats = http_json(f"http://{host}:8080/stats?token={token}", timeout)
    agg = stats.get("aggregate") or {}
    sup = stats.get("supervisor") or {}
    gp = sup.get("get_peers") or {}
    fail = sup.get("metadata_failures") or {}
    mk = stats.get("movie_kind") or {}
    mpf = stats.get("metadata_peer_fail") or {}

    s.update(
        pps_cap=stats.get("udp_rate_limit_pps"),
        sends=stats.get("udp_sends_total"),
        throttled=stats.get("udp_throttled_sends_total"),
        lookups=agg.get("get_peers_lookups_total"),
        lookups_with_peers=agg.get("get_peers_lookups_with_peers"),
        metadata=agg.get("total_metadata_fetched"),
        metadata_attempts=agg.get("metadata_attempts_total"),
        active_conns=agg.get("active_connections"),
        mean_lookup_ms=gp.get("mean_lookup_ms"),
        deadline_exits=gp.get("deadline_exits_total"),
        idle_exits=gp.get("idle_exits_total"),
        packets_per_lookup=gp.get("packets_per_lookup"),
        fail_connect=fail.get("connect_total"),
        fail_handshake=fail.get("handshake_total"),
        fail_stall=fail.get("stall_total"),
        fail_deadline=fail.get("deadline_total"),
        mk_thermal_pauses=mk.get("thermal_pauses"),
        mk_mean_us=mk.get("mean_us_per_item"),
        mk_classified=mk.get("total_classified"),
        mk_generation=mk.get("generation"),
        mk_model_ready=mk.get("model_ready"),
        mpf_enabled=mpf.get("enabled"),
        mpf_capacity=mpf.get("capacity"),
        mpf_ttl=mpf.get("ttl_sec"),
        trees=len(stats.get("trees") or []),
        uptime=stats.get("uptime"),
    )

    st = http_json(f"http://{host}:8090/api/status?token={token}", timeout)
    crawler = st.get("crawler") or {}
    daemons = {d.get("label"): d for d in (st.get("daemons") or [])}
    crawler_d = next((d for d in daemons.values() if d.get("label") and "rawler" in d["label"]), {})
    s.update(
        wifi_up=st.get("wifiUp"),
        edges=st.get("wifiDropoutsTotal"),
        edges_over2s=st.get(DISCONNECT_EDGES),
        down_ms=st.get("wifiDownMsTotal"),
        max_down_ms=st.get("wifiMaxDownMs"),
        down_now_ms=st.get("wifiDownNowMs"),
        last_dropout_at=st.get("lastWifiDropoutAt"),
        paused_no_wifi=crawler.get("pausedNoWifi"),
        crawler_state=crawler.get("state"),
        crawler_restarts=crawler_d.get("restarts"),
        service=st.get("service"),
    )

    if use_adb:
        s.update(parse_thermal(adb(serial, "dumpsys thermalservice", timeout)))
        s.update(parse_battery(adb(serial, "dumpsys battery", timeout)))
        s.update(parse_proc(adb(serial, PROC_CMD, timeout)))
    return s


def link_report(serial: str | None, use_adb: bool, timeout: float) -> str:
    """The wifi link evidence the plan's attribution section needs."""
    if not use_adb:
        return "link: n/a (no adb)"
    out = adb(serial, "dumpsys wifi", timeout)
    if not out:
        return "link: n/a (dumpsys wifi failed)"
    rssi = re.findall(r"rssi=(-?\d+)", out)
    link = re.findall(r"link=(\d+)", out)
    freq = re.findall(r"f=(\d+)", out)
    ssid = re.findall(r'"([^"]{1,40})"\s+d0:|"([^"]{1,40})"\s+[0-9a-f]{2}:', out)
    name = ssid[0][0] if ssid and ssid[0][0] else (ssid[0][1] if ssid and ssid[0][1] else "?")
    return (
        f"link: SSID {name} rssi {rssi[-1] if rssi else '?'} dBm "
        f"f {freq[-1] if freq else '?'} MHz link {link[-1] if link else '?'} Mbps"
    )


# ---------------------------------------------------------------- arithmetic

def delta(a: dict, b: dict, key: str) -> float | None:
    x, y = a.get(key), b.get(key)
    if isinstance(x, (int, float)) and isinstance(y, (int, float)):
        return y - x
    return None


def rate(d: float | None, seconds: float) -> float | None:
    return None if d is None or seconds <= 0 else d / seconds


def pct(num: float | None, den: float | None) -> float | None:
    if num is None or not den:
        return None
    return 100.0 * num / den


def fmt(v, spec=".1f", dash="—"):
    if v is None:
        return dash
    if isinstance(v, bool):
        return "yes" if v else "no"
    try:
        return format(v, spec)
    except (TypeError, ValueError):
        return str(v)


def summarise(base: dict, end: dict, seconds: float) -> dict:
    d_sends = delta(base, end, "sends")
    d_thr = delta(base, end, "throttled")
    d_look = delta(base, end, "lookups")
    d_look_peers = delta(base, end, "lookups_with_peers")
    d_meta = delta(base, end, "metadata")
    d_attempts = delta(base, end, "metadata_attempts")
    d_deadline = delta(base, end, "deadline_exits")
    d_edges = delta(base, end, "edges")
    d_over2s = delta(base, end, "edges_over2s")
    d_down_ms = delta(base, end, "down_ms")
    d_mk_pauses = delta(base, end, "mk_thermal_pauses")
    d_restarts = delta(base, end, "crawler_restarts")
    d_fail = {
        k: delta(base, end, f"fail_{k}")
        for k in ("connect", "handshake", "stall", "deadline")
    }
    # mean_us_per_item is cumulative since the crawler started, so a raw window reading is
    # diluted by everything before it. Derive the window's own mean from the two counts.
    d_classified = delta(base, end, "mk_classified")
    mk_window_us = None
    if (
        isinstance(d_classified, (int, float)) and d_classified >= 50
        and isinstance(base.get("mk_mean_us"), (int, float))
        and isinstance(end.get("mk_mean_us"), (int, float))
        and isinstance(base.get("mk_classified"), (int, float))
        and isinstance(end.get("mk_classified"), (int, float))
    ):
        mk_window_us = (
            end["mk_mean_us"] * end["mk_classified"]
            - base["mk_mean_us"] * base["mk_classified"]
        ) / d_classified
    fail_total = None
    if all(v is not None for v in d_fail.values()):
        fail_total = sum(d_fail.values())

    return {
        "seconds": round(seconds, 1),
        "pps_cap": end.get("pps_cap"),
        "pps_achieved": rate(d_sends, seconds),
        "throttled": d_thr,
        "throttled_pct_of_sends": pct(d_thr, d_sends),
        "lookups": d_look,
        "lookups_per_s": rate(d_look, seconds),
        "yield_pct": pct(d_look_peers, d_look),
        "metadata": d_meta,
        "metadata_per_s": rate(d_meta, seconds),
        "metadata_attempts": d_attempts,
        "metadata_success_pct": pct(d_meta, d_attempts),
        "mean_lookup_ms": end.get("mean_lookup_ms"),
        "deadline_exit_pct": pct(d_deadline, d_look),
        "idle_exits": delta(base, end, "idle_exits"),
        "packets_per_lookup": end.get("packets_per_lookup"),
        "active_conns": end.get("active_conns"),
        "fail_connect": d_fail["connect"],
        "fail_handshake": d_fail["handshake"],
        "fail_stall": d_fail["stall"],
        "fail_deadline": d_fail["deadline"],
        "fail_connect_pct": pct(d_fail["connect"], fail_total),
        "fail_handshake_pct": pct(d_fail["handshake"], fail_total),
        "dropouts": d_edges,
        "dropouts_over2s": d_over2s,
        "down_ms": d_down_ms,
        "max_down_ms": end.get("max_down_ms"),
        "mk_thermal_pauses": d_mk_pauses,
        "mk_mean_us": end.get("mk_mean_us"),
        "mk_classified": d_classified,
        "mk_window_us": mk_window_us,
        "mk_generation": end.get("mk_generation"),
        "thermal_status_max": max(
            v for v in (base.get("thermal_status"), end.get("thermal_status")) if isinstance(v, int)
        ) if isinstance(base.get("thermal_status"), int) or isinstance(end.get("thermal_status"), int) else None,
        "cpu_max_c": max(
            v for v in (base.get("cpu_max_c"), end.get("cpu_max_c")) if isinstance(v, (int, float))
        ) if isinstance(base.get("cpu_max_c"), (int, float)) or isinstance(end.get("cpu_max_c"), (int, float)) else None,
        "skin_c": end.get("skin_c"),
        "battery_c": end.get("battery_c"),
        "threads": end.get("threads"),
        "vmhwm_kib": end.get("vmhwm_kib"),
        "trees": end.get("trees"),
        "crawler_restarts": d_restarts,
        "uptime_end": end.get("uptime"),
        "mpf": f"{end.get('mpf_enabled')}/{end.get('mpf_capacity')}/{end.get('mpf_ttl')}",
        "mk_model_ready": end.get("mk_model_ready"),
    }


def verdict(s: dict, ref: dict) -> tuple[str, list[str]]:
    """Apply the stop rules to one window. Returns (verdict, reasons).

    Thermal rules are RELATIVE to the rung-0 window (`ref`), not absolute: this device rests at
    thermal status 3 at 3000 pps while charging, so an absolute bar would fire at every rung.
    """
    reasons = []
    if (s.get("dropouts_over2s") or 0) > 0:
        reasons.append(f"DISCONNECT: {int(s['dropouts_over2s'])} gate-down edge(s) >= 2 s")
    ref_status = ref.get("status")
    if isinstance(s.get("thermal_status_max"), int) and isinstance(ref_status, int):
        if s["thermal_status_max"] > ref_status:
            reasons.append(
                f"THERMAL ESCALATION: thermal status {s['thermal_status_max']} > rung-0 {ref_status}"
            )
    for key, label, ref_key in (("cpu_max_c", "CPU zone max", "cpu"), ("skin_c", "skin max", "skin")):
        v, r = s.get(key), ref.get(ref_key)
        if isinstance(v, (int, float)) and isinstance(r, (int, float)) and v > r + ZONE_MARGIN_C:
            reasons.append(f"THERMAL BREAK: {label} {v:.1f} C is >{ZONE_MARGIN_C:g} C above rung-0 {r:.1f} C")
    mk_baseline_us = ref.get("mean_us")
    if mk_baseline_us and s.get("mk_window_us"):
        if s["mk_window_us"] > mk_baseline_us * MEAN_US_TOLERANCE:
            reasons.append(
                f"THERMAL BREAK: movie_kind windowed mean {s['mk_window_us']:.1f} ms is "
                f">{(MEAN_US_TOLERANCE - 1) * 100:.0f} % above the rung-0 {mk_baseline_us:.1f} ms"
            )
    if (s.get("mk_thermal_pauses") or 0) > 0:
        reasons.append(
            f"REGRESSION: movie_kind.thermal_pauses moved by {int(s['mk_thermal_pauses'])} "
            "(structurally 0 for inline ingest classification)"
        )
    if s.get("mk_model_ready") is False:
        reasons.append("REGRESSION: movie_kind model not ready")
    if (s.get("crawler_restarts") or 0) > 0:
        reasons.append(f"CHURN: crawler restarted {int(s['crawler_restarts'])}x inside the window")
    return ("STOP" if any(r.startswith(("DISCONNECT", "THERMAL", "REGRESSION")) for r in reasons) else "OK"), reasons


# ---------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(
        description="Windowed UDP pps / wifi / thermal envelope probe for the on-device DHT crawler."
    )
    ap.add_argument("--pps", type=int, default=None, help="the rung this window is meant to measure (recorded, never applied)")
    ap.add_argument("--minutes", type=float, default=10.0, help="window length (default 10)")
    ap.add_argument("--interval", type=float, default=5.0, help="seconds between samples (default 5)")
    ap.add_argument("--host", default=DEFAULT_HOST, help=f"device LAN address (default {DEFAULT_HOST})")
    ap.add_argument("--token", default=DEFAULT_TOKEN, help="control token (see --token-file)")
    ap.add_argument("--token-file", default=None, help="local copy of control_auth.xml to read the token from")
    ap.add_argument("--serial", default=None, help="adb serial; omit for the only connected device")
    ap.add_argument("--no-adb", action="store_true", help="skip every device query (thermal columns read n/a)")
    ap.add_argument("--json", default=None, help="write raw samples + derived window to this path")
    ap.add_argument("--quiet", action="store_true", help="only print the final table")
    ap.add_argument("--timeout", type=float, default=20.0, help="per-source timeout in seconds")
    ap.add_argument("--mk-baseline-us", type=float, default=None,
                    help="rung-0 windowed movie_kind mean (us) for the +20 %% rule")
    ap.add_argument("--ref-status", type=int, default=None, help="rung-0 thermal status (escalation bar)")
    ap.add_argument("--ref-cpu", type=float, default=None, help="rung-0 CPU zone max, C")
    ap.add_argument("--ref-skin", type=float, default=None, help="rung-0 skin max, C")
    args = ap.parse_args()

    token = args.token or read_token(args.token_file)
    if not token:
        print("error: no control token (pass --token or --token-file)", file=sys.stderr)
        return 4
    use_adb = not args.no_adb
    if args.serial is None:
        try:
            out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10).stdout
            serials = [ln.split()[0] for ln in out.splitlines()[1:] if ln.strip().endswith("device")]
            args.serial = serials[0] if len(serials) == 1 else None
            if not serials:
                use_adb = False
        except (subprocess.TimeoutExpired, OSError):
            use_adb = False

    print(f"# pps envelope window — rung {args.pps if args.pps is not None else '(unspecified)'} pps, "
          f"{args.minutes:g} min at {args.interval:g} s, host {args.host}, adb {'on' if use_adb else 'OFF (degraded)'}")
    print(f"# {link_report(args.serial, use_adb, args.timeout)}")

    samples: list[dict] = []
    try:
        base = sample(args.host, token, args.serial, use_adb, args.timeout)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        print(f"error: baseline sample failed: {e}", file=sys.stderr)
        return 3
    samples.append(base)
    ref = {"status": args.ref_status, "cpu": args.ref_cpu, "skin": args.ref_skin,
           "mean_us": args.mk_baseline_us}
    print(f"# baseline: cap {base.get('pps_cap')} pps, uptime {base.get('uptime')}, "
          f"dropouts {base.get('edges_over2s')}, thermal {base.get('thermal_status')}, "
          f"movie_kind {base.get('mk_mean_us')} ms, threads {base.get('threads')}")

    started = time.time()
    deadline = started + args.minutes * 60.0
    fired: list[str] = []
    while time.time() < deadline:
        time.sleep(min(args.interval, max(0.0, deadline - time.time())))
        try:
            cur = sample(args.host, token, args.serial, use_adb, args.timeout)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            print(f"# sample failed at +{time.time() - started:.0f}s: {e}", file=sys.stderr)
            continue
        samples.append(cur)
        if not args.quiet:
            print(f"  +{time.time() - started:6.0f}s  cap {cur.get('pps_cap')}  "
                  f"sends {cur.get('sends')}  thr {cur.get('throttled')}  "
                  f"lookups {cur.get('lookups')}  meta {cur.get('metadata')}  "
                  f"dropouts {cur.get('edges_over2s')}  thr_status {cur.get('thermal_status')}  "
                  f"cpu {cur.get('cpu_max_c')}  batt {cur.get('battery_c')}  "
                  f"mk_us {cur.get('mk_mean_us')}")
        # A disconnect ends the window immediately: there is nothing left to learn from the
        # rung. A thermal peak does NOT end it — the window is only 10 min and the plan's
        # rule is about the peak, so the run finishes and the verdict carries the STOP.
        if (delta(base, cur, "edges_over2s") or 0) > 0:
            fired.append("disconnect")
            break

    elapsed = time.time() - started
    end = samples[-1]
    s = summarise(base, end, elapsed)
    # Maxima over every sample, not just the endpoints: a rung that peaks and recovers still
    # spent the heat, and the break rule is about the peak.
    therm = [x["thermal_status"] for x in samples if isinstance(x.get("thermal_status"), int)]
    if therm:
        s["thermal_status_max"] = max(therm)
    cpu = [x["cpu_max_c"] for x in samples if isinstance(x.get("cpu_max_c"), (int, float))]
    if cpu:
        s["cpu_max_c"] = max(cpu)
    skin = [x["skin_c"] for x in samples if isinstance(x.get("skin_c"), (int, float))]
    if skin:
        s["skin_c"] = max(skin)
    batt = [x["battery_c"] for x in samples if isinstance(x.get("battery_c"), (int, float))]
    if batt:
        s["battery_c"] = max(batt)
    v, reasons = verdict(s, ref)
    if fired:
        v = "STOP"

    print("\n| quantity | value |")
    print("|---|---|")
    for k in (
        "seconds", "pps_cap", "pps_achieved", "throttled", "throttled_pct_of_sends",
        "lookups", "lookups_per_s", "yield_pct", "metadata", "metadata_per_s",
        "metadata_attempts", "metadata_success_pct", "mean_lookup_ms", "deadline_exit_pct",
        "idle_exits", "packets_per_lookup", "active_conns",
        "fail_connect", "fail_handshake", "fail_stall", "fail_deadline",
        "fail_connect_pct", "fail_handshake_pct",
        "dropouts", "dropouts_over2s", "down_ms", "max_down_ms",
        "mk_thermal_pauses", "mk_mean_us", "mk_window_us", "mk_classified",
        "mk_generation", "mk_model_ready",
        "thermal_status_max", "cpu_max_c", "skin_c", "battery_c",
        "threads", "vmhwm_kib", "trees", "crawler_restarts", "uptime_end", "mpf",
    ):
        spec = ".0f" if k in ("dropouts", "dropouts_over2s", "down_ms", "max_down_ms",
                              "mk_thermal_pauses", "threads", "vmhwm_kib", "trees",
                              "crawler_restarts", "idle_exits", "fail_connect",
                              "fail_handshake", "fail_stall", "fail_deadline", "throttled",
                              "mk_classified", "throttled",
                              "lookups", "metadata", "metadata_attempts") else ".1f"
        print(f"| {k} | {fmt(s.get(k), spec)} |")

    print(f"\n**verdict: {v}**")
    for r in reasons:
        print(f"- {r}")
    if not reasons:
        print("- no disconnect edge, thermal status below the break, movie-kind unpaused")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"args": vars(args), "samples": samples, "window": s,
                       "verdict": v, "reasons": reasons}, f, indent=1)
        print(f"\n# raw samples + window written to {args.json}")

    return 2 if v == "STOP" else 0


if __name__ == "__main__":
    sys.exit(main())
