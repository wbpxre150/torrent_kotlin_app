#!/usr/bin/env python3
"""Gate G1 -- feature-text parity (plan exit criterion 4, design §10).

Compares the `ft` string the APP'S VENDORED tparse produces against the `ft`
that `~/apps/classifier-model/data/feedback_category/corpus/features.jsonl`
carries for the same corpus records (name + the first 50 files in file order,
which is what `cm sample` emitted and what the device will classify).

Bar (Revision 1, operator-authorised): **10,000 / 10,000 identical**. The
original bar was MISSED at 9,710/10,000 -- every mismatch adult-token-only --
because the corpus vintage carries `__adult_jav/site/set` on only 20 of 270,390
rows while the vendored parser emits them unconditionally. The resolution is the
serving-side canonicalisation (Stage 7): `cram_strip_adult_tokens()` in
`src/cram.c`, called by `src/classifier.c`, mirrored here by
`sample_select.strip_adult_tokens()`. This harness applies that mirror to the CLI
output before comparing, exactly as the daemon does.

The bar stays exact because the seed-15 sample contains none of the corpus rows
that carry the markers: this script derives that 20-id set from `features.jsonl`
itself, asserts `sample & set == {}`, and in `--full-corpus` mode asserts the
**mismatch id set equals the corpus adult-token id set, exactly** (a
self-checking set equality, not a percentage). Any mismatch outside that set, in
either direction, is a STOP.

`--full-corpus` additionally repeats the same comparison over every record the
two files share (the whole 120,000-row join). The gate itself is always the
10,000-row stratified sample.

`--adult-impact` (and every plain run) scores exactly the 20 corpus rows that
carry the markers twice -- `c_verdict_harness` with and without `--raw` -- and
records the per-field verdict changes and the max |d adult_p|, which is the
bounded witness for that population.

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
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import sample_select as ss  # noqa: E402
import g2_verdict_parity as g2  # noqa: E402  (its compiled C harness is the verdict leg)

RESULTS = HERE / "results"
DEFAULT_TPARSE = HERE / "build" / "tparse"
SUMMARY = RESULTS / "summary.json"
DUMP_ROWS = 20
NUMERIC_FIELDS = ("cat", "tv", "sl", "sh", "el", "eh", "res", "nv", "na", "ni", "nz", "nx", "nd", "fl")
#: Corpus rows whose `features.jsonl` `ft` carries a `__adult_*` token. Measured:
#: exactly 20 of 270,390 (0.0074 %). A different count means the corpus moved and
#: the revision's premise has to be re-established -- STOP, do not adapt.
ADULT_TOKEN_ID_COUNT = 20


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
        # The CLI text is canonicalised exactly as the daemon does it (Stage 7);
        # the corpus side is the reference and is never rewritten.
        got_ft = ss.strip_adult_tokens(got.get("ft", ""))
        if got_ft == want.get("ft"):
            ft_identical += 1
        else:
            kind, extra, missing = classify_mismatch(want.get("ft", ""), got_ft)
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
                    "actual_ft": got_ft,
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
        "mismatch_ids_note": "first 25 only; the complete list is mismatch_id_set",
        "mismatch_id_set": sorted(item["id"] for item in mismatched),
        "dump": str(dump_path.relative_to(REPO)),
    }


def corpus_adult_token_ids(features: Path) -> dict[int, list[str]]:
    """Ids whose corpus `ft` carries a `__adult_*` token, with the tokens found."""
    found: dict[int, list[str]] = {}
    with features.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            tokens = [
                token
                for token in str(row.get("ft", "")).split(" ")
                if token.startswith(ss.ADULT_TOKEN_PREFIX)
            ]
            if tokens:
                found[int(row["id"])] = sorted(set(tokens))
    return found


def adult_token_impact(features: Path, ids: set[int], cc: str) -> dict:
    """Score exactly the corpus rows that carry the markers, `--raw` vs stripped.

    This is the bounded witness for the 20-row population the Revision-1 bar
    carves out: the markers cannot be compared there (the corpus carries them and
    serving drops them), so their *effect* is measured instead. Both legs run the
    same compiled harness over the same text; only `--raw` differs.

    The text is the corpus `ft` verbatim, not a CLI rendering: these ids are not in
    the 120,000-record `s120000_42.jsonl`, so there is no name/file record to
    re-render, and the corpus text is what the frozen model was trained on.
    """
    rows_path = RESULTS / "g1_adult_rows.jsonl"
    with_corpus_tokens = 0
    with features.open("r", encoding="utf-8") as handle, rows_path.open(
        "w", encoding="utf-8"
    ) as out:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            rid = int(row["id"])
            if rid not in ids:
                continue
            ft = str(row.get("ft", ""))
            if any(token.startswith(ss.ADULT_TOKEN_PREFIX) for token in ft.split(" ")):
                with_corpus_tokens += 1
            out.write(json.dumps({"id": rid, "ft": ft}, ensure_ascii=False) + "\n")

    g2.ensure_harness(cc)
    verdicts: dict[str, dict[int, dict]] = {}
    for label, extra in (("stripped", []), ("raw", ["--raw"])):
        proc = subprocess.run(
            [str(g2.HARNESS), str(g2.MODEL_DIR), str(rows_path),
             repr(g2.ADULT_THRESHOLD), *extra],
            capture_output=True,
            text=True,
            check=False,
        )
        if proc.returncode != 0:
            raise SystemExit(
                f"g1: the impact harness ({label}) exited {proc.returncode}: "
                f"{proc.stderr.strip()}"
            )
        verdicts[label] = {
            int(json.loads(line)["id"]): json.loads(line)
            for line in proc.stdout.splitlines()
            if line.strip()
        }

    changes: collections.Counter = collections.Counter()
    changed_ids: list[int] = []
    max_delta = 0.0
    max_delta_id = None
    for rid in sorted(set(verdicts["stripped"]) & set(verdicts["raw"])):
        plain = verdicts["stripped"][rid]
        raw = verdicts["raw"][rid]
        for field in ("category", "kind", "adult"):
            if plain[field] != raw[field]:
                changes[field] += 1
        delta = abs(raw["adult_p"] - plain["adult_p"])
        if delta > max_delta:
            max_delta = delta
            max_delta_id = rid
        if any(plain[field] != raw[field] for field in ("category", "kind", "adult")):
            changed_ids.append(rid)

    return {
        "role": "opposite-direction witness -- the corpus CARRIES `__adult_*` on these rows "
                "and serving strips them. G1's full-corpus leg cannot exercise that "
                "direction: the ids appear in none of the local record files, so they are "
                "outside the 120,000-record join. This measurement stands in for it.",
        "rows": len(verdicts["stripped"]),
        "rows_carrying_markers": with_corpus_tokens,
        "text": "features.jsonl `ft` verbatim (these ids have no s120000_42.jsonl record)",
        "threshold": g2.ADULT_THRESHOLD,
        "verdict_changes": {field: changes[field] for field in ("category", "kind", "adult")},
        "changed_ids": changed_ids,
        "max_adult_p_delta": max_delta,
        "max_adult_p_delta_id": max_delta_id,
        "rows_file": str(rows_path.relative_to(REPO)),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tparse", default=str(DEFAULT_TPARSE))
    parser.add_argument("--records", default=str(ss.DEFAULT_RECORDS))
    parser.add_argument("--features", default=str(ss.DEFAULT_FEATURES))
    parser.add_argument("--scored", default=str(ss.DEFAULT_SCORED))
    parser.add_argument("--total", type=int, default=ss.SAMPLE_TOTAL)
    parser.add_argument("--full-corpus", action="store_true",
                        help="also compare every record the two files share, with the "
                             "mismatch-id-set == corpus-adult-token-id-set assertion")
    parser.add_argument("--adult-impact", action="store_true",
                        help="only re-measure the 20 adult-token corpus rows")
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    args = parser.parse_args()

    tparse = Path(args.tparse)
    if not tparse.is_file():
        print(f"g1: tparse CLI not found at {tparse}; run tools/cram-parity/build_tparse_cli.sh",
              file=sys.stderr)
        return 2

    RESULTS.mkdir(parents=True, exist_ok=True)
    records = Path(args.records)
    features = Path(args.features)

    print("g1: deriving the corpus adult-token id set from features.jsonl ...",
          file=sys.stderr)
    adult_ids = corpus_adult_token_ids(features)
    if len(adult_ids) != ADULT_TOKEN_ID_COUNT:
        print(
            f"g1: STOP -- features.jsonl carries {len(adult_ids)} rows with a "
            f"`__adult_` token, expected exactly {ADULT_TOKEN_ID_COUNT}: {sorted(adult_ids)}",
            file=sys.stderr,
        )
        return 1
    print(f"g1: corpus adult-token ids ({len(adult_ids)}): {sorted(adult_ids)}",
          file=sys.stderr)

    if args.adult_impact:
        impact = adult_token_impact(features, set(adult_ids), args.cc)
        summary = load_summary()
        summary.setdefault("g1", {})["adult_token_impact"] = impact
        write_summary(summary)
        print(
            f"G1 adult-token impact: {impact['rows']} rows "
            f"({impact['rows_carrying_markers']} carry the markers), "
            f"verdict changes {impact['verdict_changes']}, "
            f"max |d adult_p| {impact['max_adult_p_delta']:.6g}"
        )
        return 0

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

    overlap = sorted(set(selected) & set(adult_ids))
    print(f"g1: sample & corpus adult-token ids = {len(overlap)} {overlap}", file=sys.stderr)
    sample_ok = (
        comparison["ft_mismatched"] == 0
        and comparison["ft_identical"] == len(selected) == args.total
        and comparison["numeric_field_mismatches"] == 0
    )
    disjoint_ok = not overlap
    if not disjoint_ok:
        print(
            f"g1: STOP -- the sample contains corpus adult-token rows {overlap}; the exact "
            f"bar only holds on a sample disjoint from that set",
            file=sys.stderr,
        )

    diagnostic = None
    full_set_equal = None
    full_ok = True
    adult_ids_in_join = None
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
        # The gate's STOP condition is "no mismatch outside the corpus adult-token
        # id set", and the only rows that can still differ after the serving-side
        # strip are those whose CORPUS text carries the markers -- i.e. the
        # intersection of that set with the join. Set equality against the
        # intersected set keeps a single unexpected mismatch fatal; it is not a
        # count or a percentage.
        join_ids = set(full_selected)
        adult_ids_in_join = sorted(set(adult_ids) & join_ids)
        expected_mismatch_set = adult_ids_in_join
        full_set_equal = diagnostic["mismatch_id_set"] == expected_mismatch_set
        full_ok = full_set_equal and diagnostic["numeric_field_mismatches"] == 0
        print(
            f"g1: corpus adult-token ids inside the join: {len(adult_ids_in_join)} "
            f"{adult_ids_in_join}",
            file=sys.stderr,
        )
        if not full_set_equal:
            unexpected = sorted(set(diagnostic["mismatch_id_set"]) - set(expected_mismatch_set))
            expected_but_identical = sorted(
                set(expected_mismatch_set) - set(diagnostic["mismatch_id_set"])
            )
            print(
                f"g1: STOP -- the full-corpus mismatch id set is not the corpus adult-token "
                f"id set intersected with the join: {len(unexpected)} unexpected "
                f"{unexpected[:10]}, {len(expected_but_identical)} expected-but-identical "
                f"{expected_but_identical[:10]}",
                file=sys.stderr,
            )

    impact = adult_token_impact(features, set(adult_ids), args.cc)
    passed = sample_ok and disjoint_ok and full_ok

    summary = load_summary()
    summary["g1"] = {
        "gate": "G1 feature-text parity",
        "criterion": 4,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "bar": f"{args.total}/{args.total} identical ft strings after the serving-side "
               f"`__adult_*` canonicalisation (Revision 1, operator-authorised)",
        "bar_revision": {
            "original_bar": "10,000/10,000 identical ft (no canonicalisation)",
            "original_result": "FAIL 9,710/10,000, every mismatch adult-token-only",
            "resolution": "strip `__adult_*` on the serving path (Stage 7); corpus "
                          "regeneration and refit remain forbidden",
            "plan_full_corpus_expectation": "119,980/120,000 with the mismatch id set equal "
                                            "to the 20-id corpus adult-token set (plan "
                                            "assumption 13)",
            "plan_full_corpus_expectation_does_not_hold": "planner inference resting on a "
                                                          "false premise: the 20 corpus "
                                                          "adult-token ids appear in none of "
                                                          "the local record files, so the "
                                                          "intersected set is empty and after "
                                                          "the serving-side strip nothing "
                                                          "mismatches. Measured: "
                                                          "120,000/120,000 identical with "
                                                          "mismatch_id_set = [] -- strictly "
                                                          "stronger. The assertion is "
                                                          "evaluated as mismatch_id_set == "
                                                          "sorted(corpus_adult_ids & join_ids) "
                                                          "(set equality, never a count), so "
                                                          "one unexpected mismatch still fails "
                                                          "the gate.",
        },
        "result": "PASS" if passed else "FAIL",
        "stop": not passed,
        "sample": composition,
        "adult_token_canonicalisation": {
            "rule": "remove every token beginning `__adult_` plus one adjacent separator "
                    "(cram_strip_adult_tokens, src/cram.c); sample_select.strip_adult_tokens "
                    "is the byte-exact mirror and is applied to the CLI text here",
            "corpus_rows_with_markers": len(adult_ids),
            "corpus_ids": sorted(adult_ids),
            "corpus_tokens": {str(rid): toks for rid, toks in sorted(adult_ids.items())},
            "sample_intersection_empty": disjoint_ok,
            "sample_intersection": overlap,
            "corpus_adult_ids_in_join": (
                len(adult_ids_in_join) if adult_ids_in_join is not None else None
            ),
            "corpus_adult_ids_in_join_list": adult_ids_in_join,
            "full_corpus_mismatch_ids_equal_intersected_set": full_set_equal,
        },
        "adult_token_impact": impact,
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
    print(
        f"G1 sample & corpus adult-token ids: {len(overlap)} (bar: 0) -- "
        f"{'OK' if disjoint_ok else 'STOP'}"
    )
    if diagnostic is not None:
        print(
            f"G1 full corpus: {diagnostic['ft_identical']}/{diagnostic['rows_compared']} "
            f"identical, {diagnostic['ft_mismatched']} mismatched, kinds "
            f"{diagnostic['mismatch_kinds']}"
        )
        print(
            f"G1 full-corpus mismatch id set == corpus adult-token ids inside the join: "
            f"{full_set_equal} (mismatched {len(diagnostic['mismatch_id_set'])}, "
            f"expected {len(adult_ids_in_join or [])} of the {len(adult_ids)} corpus "
            f"adult-token ids; {len(adult_ids) - len(adult_ids_in_join or [])} are outside "
            f"the join)"
        )
    print(
        f"G1 adult-token impact: {impact['rows']} rows "
        f"({impact['rows_carrying_markers']} carry the markers), "
        f"verdict changes {impact['verdict_changes']}, "
        f"max |d adult_p| {impact['max_adult_p_delta']:.6g}"
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
