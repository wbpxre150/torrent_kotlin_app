#!/usr/bin/env python3
"""Deterministic stratified corpus sample for gate G1 / G2.

The gate needs 10,000 corpus records joined on `id` between

  * `~/apps/classifier-model/data/samples/s120000_42.jsonl` -- the `cm sample`
    records (name + file list, already capped at 50 files in `file_index`
    order), and
  * `~/apps/classifier-model/data/feedback_category/corpus/features.jsonl` --
    the `ft` strings the model was trained on (270,390 rows).

Stratification (plan Stage 4 / design §10 G1): a fixed-seed, category-spread
draw with **>= 500 tv/anime rows** and **>= 200 rows with files_total > 50**.
Teacher labels come from `~/apps/cram/tvkind/data/clean_v3/scored_all.jsonl`
(the 200,000-id label file the export keys on) because `features.jsonl` only
carries the parser's own hints; a record whose id has no teacher row falls back
to its parser hint so the draw still covers it.

Everything here is read-only on the upstream workspaces, and the draw depends
only on (seed, the two input files), so re-running it reproduces the same ids.

    python3 tools/cram-parity/sample_select.py --out /tmp/sample.jsonl
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

SEED = 15
SAMPLE_TOTAL = 10_000
MIN_TV_ANIME = 500
MIN_BIG_FILES = 200
BIG_FILES_THRESHOLD = 50
FILE_CAP = 50  # sample.py FILE_LIMIT / design §3: the corpus caps file evidence

HOME = Path.home()
DEFAULT_RECORDS = HOME / "apps/classifier-model/data/samples/s120000_42.jsonl"
DEFAULT_FEATURES = HOME / "apps/classifier-model/data/feedback_category/corpus/features.jsonl"
DEFAULT_SCORED = HOME / "apps/cram/tvkind/data/clean_v3/scored_all.jsonl"


def _iter_jsonl(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def load_record_meta(path: Path) -> tuple[set[int], dict[int, int]]:
    """Ids in the sample file and their true (uncapped) file counts."""
    ids: set[int] = set()
    n_files: dict[int, int] = {}
    for rec in _iter_jsonl(path):
        rid = int(rec["id"])
        ids.add(rid)
        n_files[rid] = int(rec.get("n_files", len(rec.get("files") or ())))
    return ids, n_files


def load_teacher(path: Path, wanted: set[int]) -> dict[int, str]:
    """`id -> teacher_category` for the ids we can draw from."""
    teacher: dict[int, str] = {}
    if not path.exists():
        return teacher
    for rec in _iter_jsonl(path):
        rid = int(rec["id"])
        if rid in wanted:
            cat = rec.get("teacher_category")
            if cat:
                teacher[rid] = str(cat)
    return teacher


def load_expected_features(path: Path, wanted: set[int]) -> dict[int, dict]:
    """`id -> features.jsonl row` for the ids we can draw from."""
    rows: dict[int, dict] = {}
    for rec in _iter_jsonl(path):
        rid = int(rec["id"])
        if rid in wanted:
            rows[rid] = rec
    return rows


def _largest_remainder(counts: dict[str, int], total: int) -> dict[str, int]:
    """Proportional allocation that sums to exactly `total`."""
    population = sum(counts.values())
    if population == 0:
        return {key: 0 for key in counts}
    quotas = {key: counts[key] * total / population for key in counts}
    allocated = {key: int(value) for key, value in quotas.items()}
    remainder = total - sum(allocated.values())
    order = sorted(quotas, key=lambda key: (-(quotas[key] - allocated[key]), key))
    for key in order[:remainder]:
        allocated[key] += 1
    return allocated


def _pick(pool: list[int], count: int, salt: str) -> list[int]:
    if count <= 0:
        return []
    if count >= len(pool):
        return list(pool)
    rng = random.Random(f"{SEED}|{salt}")
    return rng.sample(pool, count)


def select(
    ids: set[int],
    n_files: dict[int, int],
    teacher: dict[int, str],
    hint: dict[int, int],
    total: int = SAMPLE_TOTAL,
) -> tuple[list[int], dict]:
    """The stratified draw. Returns (sorted ids, composition report)."""

    def stratum(rid: int) -> str:
        if rid in teacher:
            return teacher[rid]
        return f"hint_{hint.get(rid, -1)}"

    by_stratum: dict[str, list[int]] = {}
    for rid in sorted(ids):
        by_stratum.setdefault(stratum(rid), []).append(rid)

    quotas = _largest_remainder({k: len(v) for k, v in by_stratum.items()}, total)

    selected: list[int] = []
    for name in sorted(by_stratum):
        pool = by_stratum[name]
        quota = min(quotas.get(name, 0), len(pool))
        big = [rid for rid in pool if n_files.get(rid, 0) > BIG_FILES_THRESHOLD]
        small = [rid for rid in pool if n_files.get(rid, 0) <= BIG_FILES_THRESHOLD]
        # Split the stratum's quota between the two file-count bands in
        # proportion to their availability, so big packs are represented by
        # construction rather than by luck.
        if pool:
            big_quota = round(quota * len(big) / len(pool))
        else:
            big_quota = 0
        big_quota = min(big_quota, len(big))
        small_quota = min(quota - big_quota, len(small))
        chosen = _pick(big, big_quota, f"{name}|big") + _pick(small, small_quota, f"{name}|small")
        # A band that ran dry is backfilled from the other one.
        shortfall = quota - len(chosen)
        if shortfall > 0:
            taken = set(chosen)
            rest = [rid for rid in pool if rid not in taken]
            chosen += _pick(rest, shortfall, f"{name}|fill")
        selected.extend(chosen)

    selected = _repair(
        sorted(set(selected)),
        sorted(ids),
        predicate=lambda rid: n_files.get(rid, 0) > BIG_FILES_THRESHOLD,
        minimum=MIN_BIG_FILES,
        label="big_files",
    )
    selected = _repair(
        selected,
        sorted(ids),
        predicate=lambda rid: teacher.get(rid) in ("tv", "anime"),
        minimum=MIN_TV_ANIME,
        label="tv_anime",
    )
    selected = sorted(selected)

    teacher_counts: dict[str, int] = {}
    for rid in selected:
        key = teacher.get(rid, f"hint_{hint.get(rid, -1)}")
        teacher_counts[key] = teacher_counts.get(key, 0) + 1

    hint_counts: dict[str, int] = {}
    for rid in selected:
        key = str(hint.get(rid, -1))
        hint_counts[key] = hint_counts.get(key, 0) + 1

    composition = {
        "seed": SEED,
        "requested": total,
        "intersection_records_and_features": len(ids),
        "selected": len(selected),
        "strata": "teacher_category (fallback: parser hint)",
        "stratum_counts": dict(sorted(teacher_counts.items())),
        "parser_hint_counts": dict(sorted(hint_counts.items(), key=lambda kv: int(kv[0]))),
        "tv_anime_rows": sum(1 for rid in selected if teacher.get(rid) in ("tv", "anime")),
        "files_total_gt_50_rows": sum(
            1 for rid in selected if n_files.get(rid, 0) > BIG_FILES_THRESHOLD
        ),
        "min_tv_anime_required": MIN_TV_ANIME,
        "min_files_total_gt_50_required": MIN_BIG_FILES,
    }
    return selected, composition


def _repair(selected, universe, *, predicate, minimum, label):
    """Top up a stratum that came in under its bar (never lowers the bar)."""
    have = [rid for rid in selected if predicate(rid)]
    if len(have) >= minimum:
        return selected
    need = minimum - len(have)
    taken = set(selected)
    pool = [rid for rid in universe if predicate(rid) and rid not in taken]
    additions = _pick(pool, need, f"repair|{label}|add")
    if len(additions) < need:
        raise SystemExit(
            f"sample_select: cannot reach the {label} bar ({len(have)} of {minimum}, "
            f"{len(pool)} candidates available) -- report, do not lower the bar"
        )
    drop_pool = [rid for rid in selected if not predicate(rid)]
    drops = set(_pick(drop_pool, len(additions), f"repair|{label}|drop"))
    return [rid for rid in selected if rid not in drops] + additions


def emit_input(records_path: Path, selected: set[int], out_path: Path) -> int:
    """Write the tparse CLI's stdin: the record with its first 50 files."""
    written = 0
    with out_path.open("w", encoding="utf-8") as out:
        for rec in _iter_jsonl(records_path):
            rid = int(rec["id"])
            if rid not in selected:
                continue
            payload = {
                "id": rid,
                "name": rec.get("name", ""),
                "size_bytes": int(rec.get("size_bytes", 0)),
                "n_files": int(rec.get("n_files", 0)),
                "files": [
                    {"path": f.get("path", ""), "size": int(f.get("size", 0))}
                    for f in (rec.get("files") or ())[:FILE_CAP]
                ],
            }
            out.write(json.dumps(payload, ensure_ascii=False) + "\n")
            written += 1
    return written


def build_sample(
    records: Path = DEFAULT_RECORDS,
    features: Path = DEFAULT_FEATURES,
    scored: Path = DEFAULT_SCORED,
    total: int = SAMPLE_TOTAL,
):
    """(selected ids, composition, expected features rows) -- the whole draw."""
    ids, n_files = load_record_meta(records)
    teacher = load_teacher(scored, ids)
    expected = load_expected_features(features, ids)
    drawable = sorted(ids & set(expected))
    hint = {rid: int(expected[rid].get("cat", -1)) for rid in drawable}
    selected, composition = select(set(drawable), n_files, teacher, hint, total=total)
    composition["records"] = str(records)
    composition["features"] = str(features)
    composition["scored_all"] = str(scored)
    composition["records_with_teacher_labels"] = len(teacher)
    return selected, composition, {rid: expected[rid] for rid in selected}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, help="write the CLI input JSONL here")
    parser.add_argument("--records", default=str(DEFAULT_RECORDS))
    parser.add_argument("--features", default=str(DEFAULT_FEATURES))
    parser.add_argument("--scored", default=str(DEFAULT_SCORED))
    parser.add_argument("--total", type=int, default=SAMPLE_TOTAL)
    args = parser.parse_args()

    selected, composition, _ = build_sample(
        Path(args.records), Path(args.features), Path(args.scored), args.total
    )
    written = emit_input(Path(args.records), set(selected), Path(args.out))
    composition["written"] = written
    print(json.dumps(composition, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
