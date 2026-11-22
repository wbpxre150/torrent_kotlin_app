#!/usr/bin/env python3
"""Gate G1 -- feature-text parity (plan exit criterion 4, design §10).

Compares the `ft` string the APP'S VENDORED tparse produces against the `ft`
that `~/apps/classifier-model/data/feedback_category/corpus/features.jsonl`
carries for the same corpus records (name + the first 50 files in file order,
which is what `cm sample` emitted and what the device will classify).

Bar: **10,000 / 10,000 identical**. Any mismatch is a STOP -- it means the
corpus vintage drifted from the vendored parser. Nothing here "fixes" the
parser; the gate records and reports.

`--full-corpus` additionally repeats the same comparison over every record the
two files share (the whole 120,000-row join) and records it as a diagnostic.
The gate itself is always the 10,000-row stratified sample.

    bash tools/cram-parity/build_tparse_cli.sh
    python3 tools/cram-parity/g1_ft_parity.py [--full-corpus]

Writes (large, gitignored):  results/g1_input.jsonl, results/g1_ft.jsonl,
                             results/g1_mismatches.dump (+ *_full.* with
                             --full-corpus)
Writes (committed):          results/summary.json  ("g1" block; "g2" is merged
                             in by g2_verdict_parity.py)
"""

from __future__ import annotations

import argparse
import collections
import datetime
import hashlib
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import sample_select as ss  # noqa: E402

RESULTS = HERE / "results"
DEFAULT_TPARSE = HERE / "build" / "tparse"
SUMMARY = RESULTS / "summary.json"
DUMP_ROWS = 20
NUMERIC_FIELDS = ("cat", "tv", "sl", "sh", "el", "eh", "res", "nv", "na", "ni", "nz", "nx", "nd", "fl")


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


def classify_mismatch(expected_ft: str, actual_ft: str) -> tuple[str, list[str], list[str]]:
    """Name the difference: which tokens are extra/missing, and is it adult-only?"""
    expected_tokens = expected_ft.split()
    actual_tokens = actual_ft.split()
    extra = [tok for tok in actual_tokens if tok not in expected_tokens]
    missing = [tok for tok in expected_tokens if tok not in actual_tokens]
    stripped_actual = [tok for tok in actual_tokens if not tok.startswith("__adult_")]
    stripped_expected = [tok for tok in expected_tokens if not tok.startswith("__adult_")]
    if not missing and stripped_actual == stripped_expected:
        return "adult_tokens_only", extra, missing
    return "other", extra, missing


def run_cli(tparse: Path, input_path: Path, out_path: Path) -> dict[int, dict]:
    with input_path.open("rb") as fin, out_path.open("wb") as fout:
        proc = subprocess.run([str(tparse)], stdin=fin, stdout=fout)
    if proc.returncode != 0:
        raise SystemExit(f"g1: tparse exited {proc.returncode}")
    produced: dict[int, dict] = {}
    with out_path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                row = json.loads(line)
                produced[int(row["id"])] = row
    return produced


def compare(selected: list[int], expected: dict[int, dict], produced: dict[int, dict],
            dump_path: Path, header: str) -> dict:
    """Exact-string comparison of every selected row's `ft`."""
    ft_identical = 0
    mismatched: list[dict] = []
    numeric_mismatches = 0
    kinds: collections.Counter = collections.Counter()
    extra_tokens: collections.Counter = collections.Counter()
    missing_tokens: collections.Counter = collections.Counter()

    for rid in selected:
        want = expected[rid]
        got = produced.get(rid)
        if got is None:
            mismatched.append({"id": rid, "reason": "no output line from the CLI"})
            kinds["no_output"] += 1
            continue
        if got.get("ft") == want.get("ft"):
            ft_identical += 1
        else:
            kind, extra, missing = classify_mismatch(want.get("ft", ""), got.get("ft", ""))
            kinds[kind] += 1
            for tok in extra:
                extra_tokens[tok] += 1
            for tok in missing:
                missing_tokens[tok] += 1
            mismatched.append(
                {
                    "id": rid,
                    "kind": kind,
                    "extra_tokens": extra,
                    "missing_tokens": missing,
                    "expected_ft": want.get("ft", ""),
                    "actual_ft": got.get("ft", ""),
                }
            )
        if any(got.get(field) != want.get(field) for field in NUMERIC_FIELDS):
            numeric_mismatches += 1

    with dump_path.open("w", encoding="utf-8") as dump:
        dump.write(f"# {header}: {len(mismatched)} of {len(selected)}\n")
        dump.write(f"# seed {ss.SEED}\n")
        for item in mismatched:
            dump.write(json.dumps({k: v for k, v in item.items() if not k.endswith("_ft")}) + "\n")
        dump.write("\n# first differing ft strings\n")
        for item in mismatched[:DUMP_ROWS]:
            dump.write(f"\nid {item['id']} ({item.get('kind')})\n")
            if "expected_ft" not in item:
                continue
            dump.write(f"  expected: {item['expected_ft']}\n")
            dump.write(f"  actual:   {item['actual_ft']}\n")

    return {
        "rows_compared": len(selected),
        "ft_identical": ft_identical,
        "ft_mismatched": len(mismatched),
        "numeric_field_mismatches": numeric_mismatches,
        "mismatch_kinds": dict(kinds),
        "extra_tokens": dict(extra_tokens.most_common(20)),
        "missing_tokens": dict(missing_tokens.most_common(20)),
        "mismatch_ids": [item["id"] for item in mismatched][:25],
        "mismatch_ids_note": "first 25 only; the complete list is in the dump",
        "dump": str(dump_path.relative_to(REPO)),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tparse", default=str(DEFAULT_TPARSE))
    parser.add_argument("--records", default=str(ss.DEFAULT_RECORDS))
    parser.add_argument("--features", default=str(ss.DEFAULT_FEATURES))
    parser.add_argument("--scored", default=str(ss.DEFAULT_SCORED))
    parser.add_argument("--total", type=int, default=ss.SAMPLE_TOTAL)
    parser.add_argument("--full-corpus", action="store_true",
                        help="also compare every record the two files share (diagnostic)")
    args = parser.parse_args()

    tparse = Path(args.tparse)
    if not tparse.is_file():
        print(f"g1: tparse CLI not found at {tparse}; run tools/cram-parity/build_tparse_cli.sh",
              file=sys.stderr)
        return 2

    RESULTS.mkdir(parents=True, exist_ok=True)
    records = Path(args.records)
    features = Path(args.features)

    print("g1: drawing the stratified sample ...", file=sys.stderr)
    selected, composition, expected = ss.build_sample(
        records, features, Path(args.scored), args.total
    )
    input_path = RESULTS / "g1_input.jsonl"
    cli_out_path = RESULTS / "g1_ft.jsonl"
    written = ss.emit_input(records, set(selected), input_path)
    print(f"g1: {written} records -> {input_path}", file=sys.stderr)
    produced = run_cli(tparse, input_path, cli_out_path)
    comparison = compare(selected, expected, produced, RESULTS / "g1_mismatches.dump",
                         "G1 ft mismatches")

    passed = comparison["ft_mismatched"] == 0 and comparison["ft_identical"] == len(selected) == args.total

    diagnostic = None
    if args.full_corpus:
        full_total = composition["intersection_records_and_features"]
        print(f"g1: full-corpus diagnostic over {full_total} records ...", file=sys.stderr)
        full_selected, _full_composition, full_expected = ss.build_sample(
            records, features, Path(args.scored), full_total
        )
        full_input = RESULTS / "g1_input_full.jsonl"
        full_out = RESULTS / "g1_ft_full.jsonl"
        ss.emit_input(records, set(full_selected), full_input)
        full_produced = run_cli(tparse, full_input, full_out)
        diagnostic = compare(full_selected, full_expected, full_produced,
                             RESULTS / "g1_full_mismatches.dump",
                             "G1 full-corpus ft mismatches")

    summary = load_summary()
    summary["g1"] = {
        "gate": "G1 feature-text parity",
        "criterion": 4,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "bar": f"{args.total}/{args.total} identical ft strings",
        "result": "PASS" if passed else "FAIL",
        "stop": not passed,
        "sample": composition,
        "cli": {
            "binary": str(tparse),
            "sha256": sha256_file(tparse),
            "sources": "app/src/main/cpp/dht_crawler-android/tparse/ + classifier-model native/tparse/cli.c",
        },
        "corpus": {
            "features": str(features),
            "features_sha256": sha256_file(features),
            "features_mtime": datetime.datetime.fromtimestamp(
                features.stat().st_mtime, datetime.timezone.utc
            ).isoformat(timespec="seconds"),
            "pre_stage3b_sibling": str(features) + ".pre_stage3b",
        },
        "comparison": comparison,
    }
    if diagnostic is not None:
        summary["g1"]["full_corpus_diagnostic"] = diagnostic
    write_summary(summary)

    print(
        f"G1 {'PASS' if passed else 'FAIL'}: {comparison['ft_identical']}/{len(selected)} identical ft "
        f"({comparison['ft_mismatched']} mismatched, {comparison['numeric_field_mismatches']} rows "
        f"with a differing numeric field)"
    )
    if comparison["ft_mismatched"]:
        print(f"G1 STOP: mismatch dump {RESULTS / 'g1_mismatches.dump'}")
        for item in comparison["mismatch_ids"][:5]:
            print(f"  mismatch id {item}")
    if diagnostic is not None:
        print(
            f"G1 full corpus (diagnostic): {diagnostic['ft_identical']}/{diagnostic['rows_compared']} "
            f"identical, {diagnostic['ft_mismatched']} mismatched, kinds "
            f"{diagnostic['mismatch_kinds']}, extra {diagnostic['extra_tokens']}"
        )
    print(
        "G1 sample: "
        + json.dumps({k: composition[k] for k in (
            "selected", "tv_anime_rows", "files_total_gt_50_rows",
            "intersection_records_and_features")})
    )
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
