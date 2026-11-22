#!/usr/bin/env python3
"""Gate G2 -- C-vs-reference verdict parity (plan exit criterion 5, design §10).

Two scorers, the same rows, the same container:

  * C leg: `c_verdict_harness`, linking the prepared tree's `src/cram.c`
    (`cram_features` + `cram_predict`) with the staged `models/v15` files
    sha-verified against `manifest.json`.
  * Reference leg: `~/apps/cram/tvkind/scripts/cram_export.py`'s validated
    `Design` (feature builder -- codepoint-window char grams) and `Engine`
    (float64 masked 48-state argmin, first-minimum tie-break, raw adult
    marginal, T-tempered category confidence). It is imported, never
    re-derived: a second implementation would test the test.

Bars (design §10 G2): **>= 99.9 %** agreement each for category, tv_kind and
adult; max |adult_p| difference **<= 1e-6**; every label mismatch a near-tie
(|dE| between the top two states < 1e-6 relative, dumped and reviewed); verdict
diversity >= 10 categories, >= 5 kinds and **both adult values** (the
constant-output / inverted-filter P0 witness). FAIL = STOP.

    python3 tools/cram-parity/g1_ft_parity.py    # writes results/g1_ft.jsonl
    python3 tools/cram-parity/g2_verdict_parity.py

Writes (large, gitignored):  results/g2_c_verdicts.jsonl, results/g2_mismatches.dump
Writes (committed):          results/summary.json  ("g2" block)
"""

from __future__ import annotations

import argparse
import collections
import datetime
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
RESULTS = HERE / "results"
SUMMARY = RESULTS / "summary.json"
BUILD = HERE / "build"
HARNESS = BUILD / "c_verdict_harness"
MODEL_DIR = REPO / "app/src/main/cpp/dht_crawler-android/models/v15"
PREPARED = REPO / "app/build/native/src/dht_crawler"
CRAM_SCRIPTS = Path(os.environ.get("CRAM_SCRIPTS", "~/apps/cram/tvkind/scripts")).expanduser()

ADULT_THRESHOLD = 0.375          # shipped point; config > manifest, both agree
MIN_AGREEMENT = 0.999
MAX_ADULT_P_DELTA = 1e-6
MAX_NEAR_TIE_REL = 1e-6
MIN_CATEGORIES = 10
MIN_KINDS = 5
CHUNK = 500

HARNESS_SOURCES = (
    "src/cram.c",
    "lib/cJSON/cJSON.c",
    "tests/stub_log.c",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_summary() -> dict:
    if SUMMARY.exists():
        try:
            return json.loads(SUMMARY.read_text())
        except json.JSONDecodeError:
            return {}
    return {}


def write_summary(summary: dict) -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    SUMMARY.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")


def ensure_harness(cc: str) -> None:
    """Compile the C leg against the prepared tree (never classifier.c)."""
    if HARNESS.is_file() and os.environ.get("CRAM_PARITY_REBUILD") != "1":
        return
    sources = [PREPARED / name for name in HARNESS_SOURCES]
    for path in sources:
        if not path.is_file():
            raise SystemExit(
                f"g2: prepared tree is missing {path} -- run ./gradlew :app:prepareDhtCrawlerSrc "
                f"and make -C app/build/native/src/dht_crawler"
            )
    BUILD.mkdir(parents=True, exist_ok=True)
    cmd = [
        cc, "-Wall", "-Wextra", "-O2", "-std=c99",
        "-D_POSIX_C_SOURCE=200809L", "-D_DEFAULT_SOURCE", "-D__BSD_VISIBLE=1",
        f"-I{PREPARED / 'include'}", f"-I{PREPARED / 'lib/cJSON'}",
        "-o", str(HARNESS), str(HERE / "c_verdict_harness.c"),
        *[str(p) for p in sources], "-lm", "-lpthread",
    ]
    print(f"g2: building {HARNESS.name} ...", file=sys.stderr)
    proc = subprocess.run(cmd)
    if proc.returncode != 0:
        raise SystemExit(f"g2: compiling the C harness failed ({proc.returncode})")


def verify_models(model_dir: Path) -> dict:
    """sha256 of both files must equal the manifest (the loader's own contract)."""
    manifest = json.loads((model_dir / "manifest.json").read_text())
    models = manifest["models"]
    report = {}
    for key, name in (("cram", "cram.bin"), ("cram_vocab", "cram_vocab.bin")):
        path = model_dir / models[key]["file"]
        actual = sha256_file(path)
        expected = models[key]["sha256"]
        report[name] = {
            "bytes": path.stat().st_size,
            "sha256": actual,
            "manifest_sha256": expected,
            "match": actual == expected,
        }
        if actual != expected:
            raise SystemExit(f"g2: {path} sha256 {actual} != manifest {expected}")
    report["manifest_sha256"] = sha256_file(model_dir / "manifest.json")
    report["manifest_threshold"] = manifest.get("thresholds", {}).get("adult_hide_threshold")
    return report


def load_rows(path: Path) -> list[tuple[int, str]]:
    rows = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            rows.append((int(row["id"]), row.get("ft", "")))
    return rows


def reference_verdicts(rows: list[tuple[int, str]], model_dir: Path, threshold: float) -> dict:
    """Score every row with cram_export's Design + Engine, chunked for memory."""
    sys.path.insert(0, str(CRAM_SCRIPTS))
    import cram_export as ce  # noqa: E402  (validated upstream builder/predictor)

    parsed = ce.parse_container((model_dir / "cram.bin").read_bytes())
    vocab = ce.parse_vocab((model_dir / "cram_vocab.bin").read_bytes())
    design = ce.Design(vocab["structs"], vocab["words"], vocab["bigrams"], vocab["buckets"], ce.CHAR_NGRAMS)
    if design.ncols != parsed["ncols"]:
        raise SystemExit(f"g2: vocab builds {design.ncols} columns, container has {parsed['ncols']}")
    states = [tuple(int(x) for x in row) for row in parsed["states"].tolist()]
    engine = ce.Engine(parsed, states)
    if abs(engine.temperature - float(ce.TEMPERATURE)) > 0:
        raise SystemExit(f"g2: container T {engine.temperature} != published {ce.TEMPERATURE}")

    out: dict[int, dict] = {}
    for start in range(0, len(rows), CHUNK):
        chunk = rows[start : start + CHUNK]
        built = [(rid, design.row(ft)) for rid, ft in chunk]
        features: set[int] = set()
        for _, (indices, _values) in built:
            features.update(int(i) for i in indices.tolist())
        engine.entries_for(features)
        for rid, (indices, values) in built:
            unary = engine.unary(indices, values)
            verdict = engine.verdict(unary)
            adult_p = verdict["adult_p"]
            out[rid] = {
                "category": verdict["category"],
                "kind": verdict["kind"],
                "state": verdict["state"],
                "adult_p": adult_p,
                "adult": 1 if adult_p >= threshold else 0,
                "confidence": verdict["confidence"],
                "energy": verdict["energy"],
                "nfeat": int(len(indices)),
            }
        engine._entries.clear()
        print(f"g2: reference scored {min(start + CHUNK, len(rows))}/{len(rows)}", file=sys.stderr)
    return out


def near_tie(energies: list[float], winner: int) -> tuple[float, float]:
    """(gap, gap_rel) between the winning state and the runner-up."""
    best = energies[winner]
    others = [e for i, e in enumerate(energies) if i != winner]
    second = min(others)
    gap = second - best
    scale = max(abs(best), abs(second))
    return gap, (gap / scale if scale > 0.0 else gap)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", default=str(RESULTS / "g1_ft.jsonl"),
                        help="the CLI-produced ft rows G1 wrote (same rows as G1)")
    parser.add_argument("--model-dir", default=str(MODEL_DIR))
    parser.add_argument("--threshold", type=float, default=ADULT_THRESHOLD)
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    args = parser.parse_args()

    rows_path = Path(args.rows)
    if not rows_path.is_file():
        raise SystemExit(f"g2: {rows_path} is missing -- run g1_ft_parity.py first")
    model_dir = Path(args.model_dir)

    ensure_harness(args.cc)
    models = verify_models(model_dir)
    rows = load_rows(rows_path)
    print(f"g2: {len(rows)} rows from {rows_path}", file=sys.stderr)

    c_out_path = RESULTS / "g2_c_verdicts.jsonl"
    with rows_path.open("rb") as fin, c_out_path.open("wb") as fout:
        proc = subprocess.run(
            [str(HARNESS), str(model_dir), str(rows_path), repr(args.threshold)],
            stdout=fout,
            stderr=sys.stderr,
        )
    if proc.returncode != 0:
        raise SystemExit(f"g2: the C harness exited {proc.returncode}")

    c_rows: dict[int, dict] = {}
    with c_out_path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                row = json.loads(line)
                c_rows[int(row["id"])] = row
    if len(c_rows) != len(rows):
        raise SystemExit(f"g2: C harness produced {len(c_rows)} rows for {len(rows)} inputs")

    reference = reference_verdicts(rows, model_dir, args.threshold)

    agreements = collections.Counter()
    mismatches: list[dict] = []
    max_adult_p_delta = 0.0
    max_confidence_delta = 0.0
    max_energy_delta = 0.0
    c_categories: collections.Counter = collections.Counter()
    c_kinds: collections.Counter = collections.Counter()
    c_adults: collections.Counter = collections.Counter()
    ref_categories: collections.Counter = collections.Counter()

    for rid, _ft in rows:
        c = c_rows[rid]
        ref = reference[rid]
        c_categories[c["category"]] += 1
        c_kinds[c["kind"]] += 1
        c_adults[c["adult"]] += 1
        ref_categories[ref["category"]] += 1

        for field in ("category", "kind", "adult"):
            if c[field] == ref[field]:
                agreements[field] += 1

        adult_p_delta = abs(c["adult_p"] - ref["adult_p"])
        max_adult_p_delta = max(max_adult_p_delta, adult_p_delta)
        max_confidence_delta = max(max_confidence_delta, abs(c["confidence"] - ref["confidence"]))
        max_energy_delta = max(
            max_energy_delta, max(abs(a - b) for a, b in zip(c["e"], ref["energy"]))
        )

        disagreeing = [f for f in ("category", "kind", "adult") if c[f] != ref[f]]
        if disagreeing:
            c_gap, c_gap_rel = near_tie(c["e"], c["state"])
            ref_gap, ref_gap_rel = near_tie(ref["energy"], ref["state"])
            mismatches.append(
                {
                    "id": rid,
                    "fields": disagreeing,
                    "c": {f: c[f] for f in ("category", "kind", "adult", "state")},
                    "reference": {f: ref[f] for f in ("category", "kind", "adult", "state")},
                    "adult_p_c": c["adult_p"],
                    "adult_p_reference": ref["adult_p"],
                    "c_gap": c_gap,
                    "c_gap_rel": c_gap_rel,
                    "reference_gap": ref_gap,
                    "reference_gap_rel": ref_gap_rel,
                }
            )

    total = len(rows)
    rates = {field: agreements[field] / total for field in ("category", "kind", "adult")}
    non_near_tie = [
        m for m in mismatches
        if not (m["c_gap_rel"] < MAX_NEAR_TIE_REL or m["reference_gap_rel"] < MAX_NEAR_TIE_REL)
    ]
    diversity = {
        "distinct_categories": len(c_categories),
        "distinct_kinds": len(c_kinds),
        "adult_values": sorted(c_adults),
        "category_counts": {str(k): v for k, v in sorted(c_categories.items())},
        "kind_counts": {str(k): v for k, v in sorted(c_kinds.items())},
        "adult_counts": {str(k): v for k, v in sorted(c_adults.items())},
    }
    checks = {
        "category_agreement": rates["category"] >= MIN_AGREEMENT,
        "kind_agreement": rates["kind"] >= MIN_AGREEMENT,
        "adult_agreement": rates["adult"] >= MIN_AGREEMENT,
        "adult_p_delta": max_adult_p_delta <= MAX_ADULT_P_DELTA,
        "mismatches_are_near_ties": not non_near_tie,
        "diversity_categories": diversity["distinct_categories"] >= MIN_CATEGORIES,
        "diversity_kinds": diversity["distinct_kinds"] >= MIN_KINDS,
        "diversity_adult_values": diversity["adult_values"] == [0, 1],
    }
    passed = all(checks.values())

    dump_path = RESULTS / "g2_mismatches.dump"
    with dump_path.open("w", encoding="utf-8") as dump:
        dump.write(f"# G2 label mismatches: {len(mismatches)} of {total} rows\n")
        dump.write(f"# near-tie bar |dE|/max(|E|) < {MAX_NEAR_TIE_REL}; "
                   f"{len(non_near_tie)} rows are NOT near-ties\n")
        for item in mismatches:
            dump.write(json.dumps(item, sort_keys=True) + "\n")

    summary = load_summary()
    summary["g2"] = {
        "gate": "G2 C-vs-reference verdict parity (host x86)",
        "criterion": 5,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "bars": {
            "agreement_per_field": MIN_AGREEMENT,
            "max_adult_p_delta": MAX_ADULT_P_DELTA,
            "mismatch_near_tie_rel": MAX_NEAR_TIE_REL,
            "min_categories": MIN_CATEGORIES,
            "min_kinds": MIN_KINDS,
            "both_adult_values": True,
        },
        "result": "PASS" if passed else "FAIL",
        "stop": not passed,
        "rows": total,
        "adult_threshold": args.threshold,
        "models": models,
        "c_leg": {
            "binary": str(HARNESS),
            "sha256": sha256_file(HARNESS),
            "links": ["src/cram.c", "lib/cJSON/cJSON.c", "tests/stub_log.c"],
            "prepared_tree": str(PREPARED),
        },
        "reference_leg": {
            "module": str(CRAM_SCRIPTS / "cram_export.py"),
            "sha256": sha256_file(CRAM_SCRIPTS / "cram_export.py"),
            "reused": ["Design.row", "Engine.unary", "Engine.verdict", "parse_container", "parse_vocab"],
        },
        "agreement": {
            "category": agreements["category"],
            "kind": agreements["kind"],
            "adult": agreements["adult"],
            "category_rate": rates["category"],
            "kind_rate": rates["kind"],
            "adult_rate": rates["adult"],
        },
        "max_adult_p_delta": max_adult_p_delta,
        "max_confidence_delta": max_confidence_delta,
        "max_state_energy_delta": max_energy_delta,
        "mismatches": {"count": len(mismatches), "near_ties": len(mismatches) - len(non_near_tie),
                       "not_near_ties": len(non_near_tie),
                       "ids": [m["id"] for m in mismatches]},
        "diversity": diversity,
        "checks": checks,
        "dump": str(dump_path.relative_to(REPO)),
    }
    write_summary(summary)

    print(f"G2 {'PASS' if passed else 'FAIL'} on {total} rows (threshold {args.threshold})")
    print(f"  category {agreements['category']}/{total} = {rates['category'] * 100:.4f} %")
    print(f"  tv_kind  {agreements['kind']}/{total} = {rates['kind'] * 100:.4f} %")
    print(f"  adult    {agreements['adult']}/{total} = {rates['adult'] * 100:.4f} %")
    print(f"  max |d adult_p| = {max_adult_p_delta:.3e} (bar {MAX_ADULT_P_DELTA:g}); "
          f"max |d confidence| = {max_confidence_delta:.3e}; max |d E| = {max_energy_delta:.3e}")
    print(f"  mismatches {len(mismatches)} ({len(non_near_tie)} not near-ties) -> {dump_path}")
    print(f"  diversity categories {diversity['distinct_categories']}, kinds "
          f"{diversity['distinct_kinds']}, adult values {diversity['adult_values']}")
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        print(f"  FAILED CHECKS: {failed}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
