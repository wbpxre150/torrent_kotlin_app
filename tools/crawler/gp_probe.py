#!/usr/bin/env python3
"""gp_probe.py — repeatable `get_peers` throughput probe for the on-device DHT crawler.

What it measures (the numbers `plan/crawler-getpeers-throughput/measurements.md` tracks):

  * per-tree infohash-queue **drain** (get_peers pops/s) and BEP51 **fill** (accepted
    hashes/s) from least-squares fits over runs of consecutive `/stats` samples with a
    constant `discovery_paused` flag, plus the `discovery_paused` duty cycle;
  * get_peers efficiency: peer yield %, packets per lookup, delta-weighted mean lookup
    latency (so a 10-minute run reports *its own* mean, not the process-lifetime mean);
  * UDP send rate against `udp_rate_limit_pps`, plus throttled-send delta;
  * metadata fetch rate and first-strike rate, and shared-dispatcher drop rates;
  * peers-queue occupancy (mean/max) and active connections per tree;
  * once a minute, process health of the crawler **child** pid: thread count and VmRSS.

It polls the crawler's loopback HTTP API through an `adb forward`, owns that forward and
removes it on every exit path (normal finish, Ctrl-C/SIGINT, SIGTERM, error).

Python 3 **stdlib only** — no jq, no pip, no sudo. Needs `adb` on PATH with a device
attached and the app running.

Usage:
    python3 tools/crawler/gp_probe.py [--minutes N] [--interval S] [--port 8081]
                                      [--serial S] [--json OUT]

`--serial` defaults to $ANDROID_SERIAL; with neither, plain `adb` is used (single-device
house idiom). `--json OUT` writes a host-side file with the raw samples, the derived
table values and the health samples, for before/after diffing.

Tolerates an old crawler binary: a `/stats` body without `config`, `supervisor.get_peers`,
`supervisor.dispatcher` or `queues.infohash_push_attempts` is reported as unavailable
(`n/a`) instead of crashing, and the dashboard fallback body `{"running": false, ...}`
(crawler restarting after a config apply) is skipped.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

APP_ID = "com.torrent.app"
CRAWLER_BIN = "libdhtcrawler.so"
REMOTE_STATS_PORT = 8081

DEFAULT_MINUTES = 10.0
DEFAULT_INTERVAL_S = 2.0
DEFAULT_PORT = 8081

MIN_FIT_SAMPLES = 8
HEALTH_INTERVAL_S = 60.0
FETCH_TIMEOUT_S = 5.0
ADB_TIMEOUT_S = 20.0

GP_KEYS = (
    "lookups_total",
    "lookups_with_peers",
    "yield_percent",
    "packets_total",
    "packets_per_lookup",
    "rounds_total",
    "mean_lookup_ms",
    "answers_total",
    "from_retry_total",
)


# --------------------------------------------------------------------------- helpers


def dig(obj, *path, default=None):
    """Nested dict lookup that never raises on missing keys or non-dict nodes."""
    cur = obj
    for key in path:
        if not isinstance(cur, dict) or key not in cur:
            return default
        cur = cur[key]
    return cur


def num(value):
    """Return value if it is a real number, else None (bools and strings excluded)."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return value
    return None


def fint(value, suffix=""):
    return "n/a" if value is None else f"{int(round(value))}{suffix}"


def fbig(value):
    return "n/a" if value is None else f"{int(round(value)):,}"


def f2(value, suffix=""):
    return "n/a" if value is None else f"{value:.2f}{suffix}"


def f1(value, suffix=""):
    return "n/a" if value is None else f"{value:.1f}{suffix}"


def adb_prefix(serial):
    return ["adb"] + (["-s", serial] if serial else [])


def run_cmd(cmd, timeout=ADB_TIMEOUT_S):
    """Run a command list (never a shell string). Returns (rc|None, stdout, stderr)."""
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        return None, "", f"{type(exc).__name__}: {exc}"
    return proc.returncode, proc.stdout, proc.stderr


# ------------------------------------------------------------------- /stats parsing


def _gp_norm(obj):
    """Normalise a get_peers counter object; None when the crawler does not emit it."""
    if not isinstance(obj, dict):
        return None
    out = {key: num(obj.get(key)) for key in GP_KEYS if key in obj}
    return out or None


def parse_stats(body, t=None):
    """Normalise one `/stats` body into a flat sample dict.

    Returns None for bodies that are not usable samples: the dashboard fallback body
    (`{"running": false, ...}`), non-dict payloads, or anything without a supervisor /
    trees section. Missing optional keys become None — never an exception.
    """
    if not isinstance(body, dict):
        return None
    if body.get("running") is False:
        return None
    if not isinstance(body.get("supervisor"), dict) and not isinstance(body.get("trees"), list):
        return None

    sup = body.get("supervisor") if isinstance(body.get("supervisor"), dict) else {}
    cfg = body.get("config") if isinstance(body.get("config"), dict) else {}
    agg = body.get("aggregate") if isinstance(body.get("aggregate"), dict) else {}

    trees = []
    for raw in body.get("trees") or []:
        if not isinstance(raw, dict):
            continue
        queues = raw.get("queues") if isinstance(raw.get("queues"), dict) else {}
        paused_raw = raw.get("discovery_paused")
        trees.append(
            {
                "tree_id": raw.get("tree_id"),
                "phase": raw.get("phase"),
                "paused": bool(paused_raw) if isinstance(paused_raw, (bool, int)) else None,
                "q": num(queues.get("infohash_queue_size")),
                "q_cap": num(queues.get("infohash_queue_capacity")),
                "peers_q": num(queues.get("peers_queue_size")),
                "peers_q_cap": num(queues.get("peers_queue_capacity")),
                "push_attempts": num(queues.get("infohash_push_attempts")),
                "dup_rejected": num(queues.get("infohash_duplicates_rejected")),
                "conns": num(raw.get("active_connections")),
                "metadata_count": num(raw.get("metadata_count")),
                "gp": _gp_norm(raw.get("get_peers")),
            }
        )

    return {
        "t": round(time.time() if t is None else t, 3),
        "crawler_ts": body.get("timestamp"),
        "version": body.get("version"),
        "uptime": body.get("uptime"),
        "torrents_indexed": num(body.get("torrents_indexed")),
        "files_indexed": num(body.get("files_indexed")),
        "torrents_last_hour": num(body.get("torrents_last_hour")),
        "udp_rate_limit_pps": num(body.get("udp_rate_limit_pps")),
        "udp_sends_total": num(body.get("udp_sends_total")),
        "udp_throttled_sends_total": num(body.get("udp_throttled_sends_total")),
        "workers": num(cfg.get("tree_get_peers_workers")),
        "timeout_ms": num(cfg.get("tree_get_peers_timeout_ms")),
        "has_config": isinstance(body.get("config"), dict),
        "active_trees": num(sup.get("active_trees")),
        "max_trees": num(sup.get("max_trees")),
        "total_trees_spawned": num(sup.get("total_trees_spawned")),
        "metadata_fetched": num(sup.get("total_metadata_fetched")),
        "first_strike": num(sup.get("first_strike_failures")),
        "second_strike": num(sup.get("second_strike_failures")),
        "fresh_peer_relookups": num(sup.get("fresh_peer_relookups_total")),
        "fresh_peer_rescues": num(sup.get("fresh_peer_rescues_total")),
        "draining_trees": num(sup.get("draining_trees_count")),
        "gp": _gp_norm(sup.get("get_peers")),
        "dispatcher": (
            {
                "total_responses": num(dig(sup, "dispatcher", "total_responses")),
                "routed_responses": num(dig(sup, "dispatcher", "routed_responses")),
                "dropped_responses": num(dig(sup, "dispatcher", "dropped_responses")),
                "queue_full_drops": num(dig(sup, "dispatcher", "queue_full_drops")),
            }
            if isinstance(sup.get("dispatcher"), dict)
            else None
        ),
        "metadata_success_percent": num(agg.get("metadata_success_rate_percent")),
        "aggregate_conns": num(agg.get("active_connections")),
        "trees": trees,
    }


def fetch_stats(port, timeout=FETCH_TIMEOUT_S):
    """GET http://127.0.0.1:<port>/stats. Returns (body|None, error|None)."""
    url = f"http://127.0.0.1:{port}/stats"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            raw = resp.read()
    except (urllib.error.URLError, OSError, ValueError) as exc:
        return None, f"{type(exc).__name__}: {exc}"
    try:
        return json.loads(raw.decode("utf-8", "replace")), None
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        return None, f"unparseable /stats body: {exc}"


# ------------------------------------------------------------------- health sampling


def crawler_pid(prefix):
    """Crawler *child* pid. `pidof` first (comm may truncate), `ps -A | grep` second."""
    rc, out, _ = run_cmd(prefix + ["shell", f"pidof {CRAWLER_BIN}"])
    if rc == 0:
        for token in out.split():
            if token.isdigit():
                return int(token)
    rc, out, _ = run_cmd(
        prefix + ["shell", "ps -A | grep -i dhtcrawler | awk 'NR==1{print $2}'"]
    )
    if rc == 0:
        for token in out.split():
            if token.isdigit():
                return int(token)
    return None


def _run_as_out(prefix, remote_cmd):
    rc, out, _ = run_cmd(
        prefix + ["exec-out", "run-as", APP_ID, "sh", "-c", remote_cmd]
    )
    return out if rc == 0 else None


def health_sample(prefix):
    """One health record for the crawler child: {t, crawler_pid, threads, vmrss_kb}.

    Uses /proc/<crawler pid>/task and /proc/<crawler pid>/status via `run-as` — never
    /proc/self/task (that would measure the run-as shell) and never `dumpsys meminfo`
    (that reports the app JVM, not the daemon).
    """
    rec = {
        "t": round(time.time(), 3),
        "crawler_pid": None,
        "threads": None,
        "vmrss_kb": None,
    }
    pid = crawler_pid(prefix)
    if pid is None:
        return rec
    rec["crawler_pid"] = pid

    out = _run_as_out(prefix, f"ls /proc/{pid}/task | wc -l")
    if out:
        tokens = out.split()
        if tokens and tokens[0].isdigit():
            rec["threads"] = int(tokens[0])

    out = _run_as_out(prefix, f"grep VmRSS /proc/{pid}/status")
    if out:
        match = re.search(r"VmRSS:\s+(\d+)\s+kB", out)
        if match:
            rec["vmrss_kb"] = int(match.group(1))
    return rec


# ------------------------------------------------------------------- queue fits


def linear_slope(points):
    """Least-squares slope dy/dt over [(t, y), ...]; None when degenerate."""
    n = len(points)
    if n < 2:
        return None
    mean_t = sum(p[0] for p in points) / n
    mean_y = sum(p[1] for p in points) / n
    sxx = sum((p[0] - mean_t) ** 2 for p in points)
    if sxx <= 0:
        return None
    sxy = sum((p[0] - mean_t) * (p[1] - mean_y) for p in points)
    return sxy / sxx


def consecutive_runs(flags):
    """Index ranges [start, end] (inclusive) of consecutive True values."""
    runs = []
    start = None
    for idx, flag in enumerate(flags):
        if flag:
            if start is None:
                start = idx
        elif start is not None:
            runs.append((start, idx - 1))
            start = None
    if start is not None:
        runs.append((start, len(flags) - 1))
    return runs


def _mean(values):
    return sum(values) / len(values) if values else None


def queue_fits(samples):
    """Per-tree drain/fill from runs of >= MIN_FIT_SAMPLES samples with a constant flag.

    drain: least-squares slope of `infohash_queue_size` while `discovery_paused` is true,
           negated (the queue is being popped).
    fill:  net rise (last-first)/dt while `discovery_paused` is false, plus drain.
    """
    series = {}
    for sample in samples:
        for tree in sample["trees"]:
            tree_id = tree.get("tree_id")
            if tree_id is None or tree.get("q") is None or tree.get("paused") is None:
                continue
            series.setdefault(tree_id, []).append((sample["t"], tree["q"], tree["paused"]))

    drain_runs = []
    fill_runs = []
    drain_by_tree = {}
    fill_by_tree = {}

    for tree_id in sorted(series, key=lambda v: (v is None, v)):
        points = series[tree_id]
        flags = [p[2] for p in points]
        # Both kinds of run matter: paused -> drain, unpaused -> fill.
        for want_paused in (True, False):
            for start, end in consecutive_runs([flag == want_paused for flag in flags]):
                if end - start + 1 < MIN_FIT_SAMPLES:
                    continue
                seg = [(points[i][0], points[i][1]) for i in range(start, end + 1)]
                dt = seg[-1][0] - seg[0][0]
                if dt <= 0:
                    continue
                if want_paused:
                    slope = linear_slope(seg)
                    if slope is None:
                        continue
                    rec = {
                        "tree_id": tree_id,
                        "kind": "paused",
                        "n": len(seg),
                        "dt_s": round(dt, 3),
                        "slope_per_s": round(slope, 4),
                        "drain_per_s": round(-slope, 4),
                    }
                    drain_runs.append(rec)
                    drain_by_tree.setdefault(tree_id, []).append(-slope)
                else:
                    net_rise = (seg[-1][1] - seg[0][1]) / dt
                    rec = {
                        "tree_id": tree_id,
                        "kind": "unpaused",
                        "n": len(seg),
                        "dt_s": round(dt, 3),
                        "net_rise_per_s": round(net_rise, 4),
                    }
                    fill_runs.append(rec)
                    fill_by_tree.setdefault(tree_id, []).append(net_rise)

    drain_per_tree = _mean([_mean(v) for v in drain_by_tree.values()]) if drain_by_tree else None
    fill_net_per_tree = _mean([_mean(v) for v in fill_by_tree.values()]) if fill_by_tree else None
    fill_per_tree = (
        fill_net_per_tree + drain_per_tree
        if fill_net_per_tree is not None and drain_per_tree is not None
        else None
    )

    return {
        "drain_per_tree_per_s": drain_per_tree,
        "drain_runs": len(drain_runs),
        "drain_trees": len(drain_by_tree),
        "drain_samples": sum(r["n"] for r in drain_runs),
        "fill_net_per_tree_per_s": fill_net_per_tree,
        "fill_per_tree_per_s": fill_per_tree,
        "fill_runs": len(fill_runs),
        "fill_trees": len(fill_by_tree),
        "fill_samples": sum(r["n"] for r in fill_runs),
        "runs": drain_runs + fill_runs,
    }


# ------------------------------------------------------------------- derivation


def series_delta(samples, getter):
    """Delta of a cumulative counter between the first and last sample that has it."""
    points = [(s["t"], getter(s)) for s in samples]
    points = [(t, v) for (t, v) in points if v is not None]
    if len(points) < 2:
        return None
    t0, v0 = points[0]
    t1, v1 = points[-1]
    dt = t1 - t0
    if dt <= 0:
        return None
    return {
        "first": v0,
        "last": v1,
        "delta": v1 - v0,
        "dt": dt,
        "per_s": (v1 - v0) / dt,
        "reset": v1 < v0,
    }


def delta_mean_lookup_ms(samples):
    """Run-local mean lookup latency: d(mean_lookup_ms * lookups_total)/d(lookups_total)."""
    points = []
    for sample in samples:
        gp = sample.get("gp") or {}
        mean_ms = gp.get("mean_lookup_ms")
        lookups = gp.get("lookups_total")
        if mean_ms is None or lookups is None:
            continue
        points.append((sample["t"], mean_ms * lookups * 1000.0, lookups))
    if len(points) < 2:
        return None
    _, us0, lookups0 = points[0]
    t1, us1, lookups1 = points[-1]
    d_lookups = lookups1 - lookups0
    if d_lookups <= 0:
        return None
    return {
        "mean_ms": (us1 - us0) / d_lookups / 1000.0,
        "lookups": d_lookups,
        "dt": t1 - points[0][0],
    }


def tree_push_delta(samples):
    """Per-tree deltas of infohash_push_attempts/duplicates, only for trees in both ends."""
    first = {t["tree_id"]: t for t in samples[0]["trees"] if t.get("push_attempts") is not None}
    last = {t["tree_id"]: t for t in samples[-1]["trees"] if t.get("push_attempts") is not None}
    shared = [tid for tid in first if tid in last]
    if not shared:
        return None
    pushes = sum(last[tid]["push_attempts"] - first[tid]["push_attempts"] for tid in shared)
    dups = 0
    have_dups = False
    for tid in shared:
        a = first[tid].get("dup_rejected")
        b = last[tid].get("dup_rejected")
        if a is not None and b is not None:
            dups += b - a
            have_dups = True
    return {
        "trees": len(shared),
        "pushes": pushes,
        "duplicates": dups if have_dups else None,
    }


def derive(samples, health, meta):
    """Compute every printed table value from the raw samples."""
    out = {
        "samples": len(samples),
        "skipped": meta.get("samples_skipped", 0),
        "interval": meta.get("interval"),
        "span_s": None,
        "active_trees": None,
        "max_trees": None,
        "uptime": None,
        "config_line": None,
        "queue": None,
        "paused_fraction": None,
        "yield_percent": None,
        "peer_entries_per_s": None,
        "packets_per_lookup": None,
        "mean_rounds_per_lookup": None,
        "mean_lookup_ms": None,
        "pps": None,
        "pps_cap": None,
        "throttled_delta": None,
        "metadata_per_s": None,
        "first_strike_per_s": None,
        "metadata_success_percent": None,
        "dropped_per_s": None,
        "queue_full_drops_per_s": None,
        "dispatcher_drops_delta": None,
        "queue_full_drops_delta": None,
        "peers_queue_mean": None,
        "peers_queue_max": None,
        "active_conns_per_tree": None,
        "infohash_pushes_per_s": None,
        "infohash_dup_percent": None,
        "health_last": health[-1] if health else None,
    }
    if not samples:
        return out

    first, last = samples[0], samples[-1]
    out["span_s"] = last["t"] - first["t"]
    out["active_trees"] = last.get("active_trees") or len(last["trees"])
    out["max_trees"] = last.get("max_trees")
    out["uptime"] = last.get("uptime")
    out["config_line"] = config_line(last)
    out["pps_cap"] = last.get("udp_rate_limit_pps")
    out["metadata_success_percent"] = last.get("metadata_success_percent")

    # Queue drain / fill / paused duty cycle (works on the old binary too).
    fits = queue_fits(samples)
    out["queue"] = fits
    paused_pairs = 0
    total_pairs = 0
    peers_q = []
    conns = []
    for sample in samples:
        for tree in sample["trees"]:
            if tree.get("paused") is not None:
                total_pairs += 1
                if tree["paused"]:
                    paused_pairs += 1
            if tree.get("peers_q") is not None:
                peers_q.append(tree["peers_q"])
            if tree.get("conns") is not None:
                conns.append(tree["conns"])
    out["paused_fraction"] = paused_pairs / total_pairs if total_pairs else None
    out["peers_queue_mean"] = _mean(peers_q)
    out["peers_queue_max"] = max(peers_q) if peers_q else None
    out["active_conns_per_tree"] = _mean(conns)

    # get_peers efficiency (new binary only).
    lookups = series_delta(samples, lambda s: dig(s, "gp", "lookups_total"))
    with_peers = series_delta(samples, lambda s: dig(s, "gp", "lookups_with_peers"))
    packets = series_delta(samples, lambda s: dig(s, "gp", "packets_total"))
    rounds = series_delta(samples, lambda s: dig(s, "gp", "rounds_total"))
    if lookups and lookups["delta"] > 0:
        if with_peers:
            out["yield_percent"] = with_peers["delta"] / lookups["delta"] * 100.0
            out["peer_entries_per_s"] = with_peers["per_s"]
        if packets:
            out["packets_per_lookup"] = packets["delta"] / lookups["delta"]
        if rounds and rounds["delta"] > 0:
            out["mean_rounds_per_lookup"] = 1.0 + rounds["delta"] / lookups["delta"]
    latency = delta_mean_lookup_ms(samples)
    if latency:
        out["mean_lookup_ms"] = latency["mean_ms"]
        out["lookups_in_window"] = latency["lookups"]

    # UDP.
    sends = series_delta(samples, lambda s: s.get("udp_sends_total"))
    if sends:
        out["pps"] = sends["per_s"]
    throttled = series_delta(samples, lambda s: s.get("udp_throttled_sends_total"))
    if throttled:
        out["throttled_delta"] = throttled["delta"]

    # Metadata / first strike.
    fetched = series_delta(samples, lambda s: s.get("metadata_fetched"))
    if fetched:
        out["metadata_per_s"] = fetched["per_s"]
    strike = series_delta(samples, lambda s: s.get("first_strike"))
    if strike:
        out["first_strike_per_s"] = strike["per_s"]

    # Shared dispatcher drops.
    dropped = series_delta(samples, lambda s: dig(s, "dispatcher", "dropped_responses"))
    if dropped:
        out["dropped_per_s"] = dropped["per_s"]
        out["dispatcher_drops_delta"] = dropped["delta"]
    qfd = series_delta(samples, lambda s: dig(s, "dispatcher", "queue_full_drops"))
    if qfd:
        out["queue_full_drops_per_s"] = qfd["per_s"]
        out["queue_full_drops_delta"] = qfd["delta"]

    # New-binary infohash queue push counters (per tree, so it compares with `fill`).
    if len(samples) >= 2:
        pushes = tree_push_delta(samples)
        if pushes and pushes["trees"] and out["span_s"] and out["span_s"] > 0:
            out["infohash_pushes_per_s"] = (
                pushes["pushes"] / pushes["trees"] / out["span_s"]
            )
            if pushes["duplicates"] is not None and pushes["pushes"] > 0:
                out["infohash_dup_percent"] = pushes["duplicates"] / pushes["pushes"] * 100.0
            out["infohash_push_trees"] = pushes["trees"]

    return out


def config_line(sample):
    """Run-comparability line: the knobs that change what the numbers mean."""
    return (
        f"workers={fint(sample.get('workers'))} "
        f"timeout_ms={fint(sample.get('timeout_ms'))} "
        f"pps={fint(sample.get('udp_rate_limit_pps'))}"
    )


def missing_features(samples):
    """Which Stage-1/2 `/stats` additions the running crawler does not emit."""
    if not samples:
        return ["no usable /stats samples"]
    missing = []
    if not any(s.get("has_config") for s in samples):
        missing.append("config (tree_get_peers_workers/timeout_ms)")
    if not any(s.get("gp") for s in samples):
        missing.append("supervisor.get_peers (yield/packets/mean lookup ms)")
    if not any(s.get("dispatcher") for s in samples):
        missing.append("supervisor.dispatcher (dropped/queue_full_drops)")
    if not any(t.get("push_attempts") is not None for s in samples for t in s["trees"]):
        missing.append("queues.infohash_push_attempts/infohash_duplicates_rejected")
    return missing


# ------------------------------------------------------------------- rendering


def render_header(meta, sample):
    lines = [
        "== gp_probe ==",
        f"device:    {' '.join(adb_prefix(meta.get('serial')))}",
        f"forward:   tcp:{meta.get('port')} -> tcp:{REMOTE_STATS_PORT}",
        f"duration:  {meta.get('minutes')} min, interval {meta.get('interval')} s",
        f"trees:     {fint(sample.get('active_trees') or len(sample['trees']))}"
        f" / {fint(sample.get('max_trees'))}  (active / max)",
        f"uptime:    {sample.get('uptime') or 'n/a'}",
        f"config:    {config_line(sample)}",
    ]
    if not sample.get("has_config"):
        lines.append(
            "           (no `config` object in /stats: old crawler binary — "
            "workers/timeout_ms unknown)"
        )
    return "\n".join(lines)


def render_table(derived, meta):
    """Markdown table per plan stage-1 §6: | metric | value | notes |."""
    d = derived
    queue = d.get("queue") or {}
    health = d.get("health_last") or {}
    rows = []

    rows.append(
        (
            "trees",
            f"{fint(d['active_trees'])} / {fint(d['max_trees'])}",
            "`supervisor.active_trees` / `max_trees`",
        )
    )
    rows.append(("uptime", str(d.get("uptime") or "n/a"), "`/stats.uptime` (H:MM)"))
    rows.append(
        (
            "config",
            str(d.get("config_line") or "n/a"),
            "`config.tree_get_peers_workers`, `config.tree_get_peers_timeout_ms`, "
            "`udp_rate_limit_pps`",
        )
    )
    rows.append(
        (
            "samples",
            f"{d['samples']} usable / {d['skipped']} skipped",
            f"{f1(d.get('interval'), ' s')} interval, {f1(d.get('span_s'), ' s')} span, "
            f"{len(meta.get('health') or [])} health sample(s)",
        )
    )

    drain_note = (
        f"{queue.get('drain_runs', 0)} paused runs / {queue.get('drain_trees', 0)} trees "
        f"({queue.get('drain_samples', 0)} samples, >={MIN_FIT_SAMPLES} each)"
    )
    if d.get("active_trees") and queue.get("drain_per_tree_per_s") is not None:
        drain_note += f"; {f1(queue['drain_per_tree_per_s'] * d['active_trees'], ' /s')} across trees"
    rows.append(
        (
            "drain /s per tree",
            f2(queue.get("drain_per_tree_per_s")),
            "least-squares fit of `infohash_queue_size` while `discovery_paused`, negated; "
            + drain_note,
        )
    )
    rows.append(
        (
            "fill /s per tree",
            f2(queue.get("fill_per_tree_per_s")),
            f"net rise {f2(queue.get('fill_net_per_tree_per_s'), ' /s')} while unpaused "
            f"({queue.get('fill_runs', 0)} runs / {queue.get('fill_trees', 0)} trees) + drain",
        )
    )
    ratio = None
    if queue.get("fill_per_tree_per_s") and queue.get("drain_per_tree_per_s"):
        ratio = queue["fill_per_tree_per_s"] / queue["drain_per_tree_per_s"]
    rows.append(
        ("fill : drain", f1(ratio) + " : 1" if ratio is not None else "n/a", "queue buffer pressure")
    )
    rows.append(
        (
            "discovery_paused fraction",
            f1(d["paused_fraction"] * 100.0, " %") if d.get("paused_fraction") is not None else "n/a",
            "share of (tree, sample) pairs with `discovery_paused == true`",
        )
    )
    rows.append(
        (
            "peer yield %",
            f1(d.get("yield_percent")),
            f"d`lookups_with_peers`/d`lookups_total`; "
            f"{f2(d.get('peer_entries_per_s'), ' peer entries/s')}",
        )
    )
    rows.append(
        ("packets per lookup", f2(d.get("packets_per_lookup")), "d`packets_total`/d`lookups_total`")
    )
    rows.append(
        (
            "mean rounds",
            f2(d.get("mean_rounds_per_lookup")),
            "1 + d`rounds_total`/d`lookups_total`",
        )
    )
    rows.append(
        (
            "mean lookup ms",
            fint(d.get("mean_lookup_ms")),
            "d(`mean_lookup_ms`*`lookups_total`)/d`lookups_total` — run-local, not lifetime",
        )
    )
    rows.append(
        (
            "pps",
            fbig(d.get("pps")),
            f"cap {fbig(d.get('pps_cap'))}; throttled delta {fbig(d.get('throttled_delta'))}",
        )
    )
    rows.append(
        (
            "metadata /s",
            f2(d.get("metadata_per_s")),
            f"first strike /s {f2(d.get('first_strike_per_s'))}; "
            f"success {f1(d.get('metadata_success_percent'), ' %')} (lifetime, `aggregate`)",
        )
    )
    rows.append(
        (
            "dispatcher drops /s",
            f2(d.get("dropped_per_s")),
            f"`queue_full_drops` /s {f2(d.get('queue_full_drops_per_s'))}",
        )
    )
    rows.append(
        (
            "peers queue",
            f"mean {f2(d.get('peers_queue_mean'))} / max {fint(d.get('peers_queue_max'))}",
            "across trees (per-tree is 0 most of the time)",
        )
    )
    rows.append(
        ("active conns / tree", f2(d.get("active_conns_per_tree")), "mean `active_connections`")
    )
    if d.get("infohash_pushes_per_s") is not None:
        rows.append(
            (
                "infohash pushes /s (per tree)",
                f2(d.get("infohash_pushes_per_s")),
                f"duplicates rejected {f1(d.get('infohash_dup_percent'), ' %')} "
                f"({d.get('infohash_push_trees')} trees) — new-binary fields",
            )
        )

    if health.get("vmrss_kb") is not None or health.get("threads") is not None:
        rows.append(
            (
                "health (last)",
                f"{fint((health.get('vmrss_kb') or 0) / 1024, ' MB') if health.get('vmrss_kb') else 'n/a'}"
                f" / {fint(health.get('threads'), ' threads')}",
                f"crawler child pid {fint(health.get('crawler_pid'))}",
            )
        )

    out = ["| metric | value | notes |", "|---|---|---|"]
    for metric, value, note in rows:
        out.append(f"| {metric} | {value} | {note} |")
    return "\n".join(out)


def render_measurements_row(derived, meta):
    """One paste-ready `measurements.md` Stage-1 row (verdict left for the operator)."""
    d = derived
    queue = d.get("queue") or {}
    health = d.get("health_last") or {}
    workers = fint(d.get("workers"))
    if workers == "n/a" and meta.get("workers") is not None:
        workers = fint(meta["workers"])
    rss = f"{int(round(health['vmrss_kb'] / 1024))} MB" if health.get("vmrss_kb") else "n/a"
    cells = [
        workers,
        f2(queue.get("drain_per_tree_per_s")),
        fbig(d.get("pps")),
        fint(d.get("mean_lookup_ms")),
        f1(d.get("yield_percent")),
        f2(d.get("dropped_per_s")),
        f2(d.get("queue_full_drops_per_s")),
        rss,
        fint(health.get("threads")),
        "",
    ]
    return "| " + " | ".join(cells) + " |"


# ------------------------------------------------------------------- main


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="gp_probe.py",
        description=(
            "Repeatable get_peers throughput probe for the on-device DHT crawler "
            "(polls /stats over an adb forward, prints a markdown table, removes the forward)."
        ),
        epilog=(
            "examples:\n"
            "  python3 tools/crawler/gp_probe.py --minutes 10 --json /tmp/gp-18.json\n"
            "  ANDROID_SERIAL=b93dac74 python3 tools/crawler/gp_probe.py --minutes 1\n"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--minutes",
        type=float,
        default=DEFAULT_MINUTES,
        help=f"run length in minutes (default {DEFAULT_MINUTES:g})",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=DEFAULT_INTERVAL_S,
        help=f"seconds between /stats polls (default {DEFAULT_INTERVAL_S:g})",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"local adb-forward port for the crawler /stats API (default {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--serial",
        default=os.environ.get("ANDROID_SERIAL") or None,
        help="adb serial (default: $ANDROID_SERIAL, else plain adb / single device)",
    )
    parser.add_argument(
        "--json",
        dest="json_out",
        default=None,
        metavar="OUT",
        help="write raw samples + derived table + health samples to this host path",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    minutes = max(args.minutes, 0.0)
    interval = max(args.interval, 0.5)
    prefix = adb_prefix(args.serial)

    stop = {"flag": False}

    def _handle(signum, _frame):
        stop["flag"] = True
        print(f"\n[gp_probe] signal {signum} — finishing and removing the forward", file=sys.stderr)

    signal.signal(signal.SIGINT, _handle)
    signal.signal(signal.SIGTERM, _handle)

    meta = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "finished_at": None,
        "minutes": minutes,
        "interval": interval,
        "serial": args.serial,
        "port": args.port,
        "remote_port": REMOTE_STATS_PORT,
        "adb": " ".join(prefix),
        "tree_count": {"active": None, "max": None},
        "uptime": None,
        "config_line": None,
        "workers": None,
        "timeout_ms": None,
        "samples_usable": 0,
        "samples_skipped": 0,
        "missing_features": [],
        "interrupted": False,
        "health": [],
    }

    samples = []
    health = []
    skipped = 0
    last_error = None
    exit_code = 0
    forward_added = False

    try:
        # Own the forward: clear any stale binding for this local port first so the
        # exit path below always leaves `adb forward --list` clean.
        run_cmd(prefix + ["forward", "--remove", f"tcp:{args.port}"], timeout=10.0)
        rc, out, err = run_cmd(prefix + ["forward", f"tcp:{args.port}", f"tcp:{REMOTE_STATS_PORT}"])
        if rc != 0:
            print(
                f"ERROR: adb forward tcp:{args.port} -> tcp:{REMOTE_STATS_PORT} failed "
                f"(rc={rc}): {(err or out).strip() or 'no output'}",
                file=sys.stderr,
            )
            return 1
        forward_added = True

        health.append(health_sample(prefix))
        health_due = time.time() + HEALTH_INTERVAL_S
        deadline = time.time() + minutes * 60.0

        while not stop["flag"] and time.time() < deadline:
            now = time.time()
            body, err = fetch_stats(args.port)
            if body is None:
                skipped += 1
                last_error = err
            else:
                sample = parse_stats(body, now)
                if sample is None:
                    skipped += 1
                    last_error = "unusable /stats body (running:false or unexpected shape)"
                else:
                    samples.append(sample)
                    if len(samples) == 1:
                        print(render_header(meta, sample))
                        print()

            if time.time() >= health_due:
                health.append(health_sample(prefix))
                health_due = time.time() + HEALTH_INTERVAL_S

            target = now + interval
            while not stop["flag"] and time.time() < target:
                time.sleep(min(0.1, max(0.0, target - time.time())))

        if stop["flag"]:
            meta["interrupted"] = True

        meta["samples_usable"] = len(samples)
        meta["samples_skipped"] = skipped
        meta["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        meta["health"] = health
        meta["missing_features"] = missing_features(samples)
        if samples:
            last = samples[-1]
            meta["tree_count"] = {
                "active": last.get("active_trees") or len(last["trees"]),
                "max": last.get("max_trees"),
            }
            meta["uptime"] = last.get("uptime")
            meta["config_line"] = config_line(last)
            meta["workers"] = last.get("workers")
            meta["timeout_ms"] = last.get("timeout_ms")

        if not samples:
            print(
                f"ERROR: no usable /stats samples in {minutes:g} min "
                f"({skipped} polls skipped, last error: {last_error})",
                file=sys.stderr,
            )
            exit_code = 1
        else:
            derived = derive(samples, health, meta)
            if meta["missing_features"]:
                print(
                    "NOTE: /stats is missing: "
                    + ", ".join(meta["missing_features"])
                    + " — those metrics print n/a (old crawler binary?)",
                    file=sys.stderr,
                )
            print(render_table(derived, meta))
            print()
            print("measurements.md row (Stage 1 header, fill in the verdict):")
            print(
                "| workers | drain /s per tree | pps | mean lookup ms | yield % | "
                "dropped_responses /s | queue_full_drops /s | RSS | threads | verdict |"
            )
            print(render_measurements_row(derived, meta))

            if args.json_out:
                payload = {
                    "meta": meta,
                    "samples": samples,
                    "derived": derived,
                    "health": health,
                }
                try:
                    with open(args.json_out, "w", encoding="utf-8") as handle:
                        json.dump(payload, handle, indent=1)
                    print(f"\nwrote {args.json_out} ({len(samples)} samples, {len(health)} health)")
                except OSError as exc:
                    print(f"ERROR: cannot write {args.json_out}: {exc}", file=sys.stderr)
                    exit_code = 1
        if skipped:
            print(
                f"[gp_probe] {skipped} poll(s) skipped "
                f"(crawler restarting or /stats unparseable); last: {last_error}",
                file=sys.stderr,
            )
    finally:
        if forward_added:
            run_cmd(prefix + ["forward", "--remove", f"tcp:{args.port}"], timeout=10.0)
            print(f"[gp_probe] removed adb forward tcp:{args.port}", file=sys.stderr)

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
