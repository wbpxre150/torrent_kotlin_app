#!/usr/bin/env bash
# Build the host tparse CLI that gates G1 and G2 use.
#
# It is the APP'S VENDORED parser (app/src/main/cpp/dht_crawler-android/tparse/)
# compiled together with classifier-model's host driver `native/tparse/cli.c`,
# which the vendored copy does not carry (the crawler never runs the CLI). That
# is exactly the combination the plan specifies for G1.
#
# The "shared sources are byte-identical" claim is PROVEN here, not assumed: every
# file the two trees have in common is compared before compiling, and a
# difference aborts the build. The compile then uses only the vendored copies
# (plus cli.c), so the binary cannot silently be the upstream one.
#
#   bash tools/cram-parity/build_tparse_cli.sh
#
# Output: tools/cram-parity/build/tparse (gitignored), with the sha256 of every
# input and of the binary printed for the gate record.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
VENDORED="$REPO_ROOT/app/src/main/cpp/dht_crawler-android/tparse"
UPSTREAM="${TPARSE_UPSTREAM:-$HOME/apps/classifier-model/native/tparse}"
OUT_DIR="$REPO_ROOT/tools/cram-parity/build"
OUT="$OUT_DIR/tparse"
CC="${CC:-cc}"

# Files the two copies share. cli.c, the build files and tests are upstream-only.
SHARED=(tparse.c name.c tparse.h tparse_internal.h taxonomy.h adult_terms.h)

die() { echo "build_tparse_cli: $*" >&2; exit 1; }

[ -d "$VENDORED" ] || die "vendored tparse sources not found: $VENDORED"
[ -f "$UPSTREAM/cli.c" ] || die "upstream cli.c not found: $UPSTREAM/cli.c (set TPARSE_UPSTREAM)"

echo "build_tparse_cli: vendored sources   $VENDORED"
echo "build_tparse_cli: upstream cli.c     $UPSTREAM/cli.c"

for file in "${SHARED[@]}"; do
    [ -f "$VENDORED/$file" ] || die "vendored copy is missing $file"
    [ -f "$UPSTREAM/$file" ] || die "upstream copy is missing $file"
    if ! cmp -s "$VENDORED/$file" "$UPSTREAM/$file"; then
        die "$file differs between the vendored and upstream copies -- G1's premise is broken, report it"
    fi
    printf 'build_tparse_cli: identical  %-18s %s\n' "$file" "$(sha256sum "$VENDORED/$file" | cut -d' ' -f1)"
done
printf 'build_tparse_cli: upstream   %-18s %s\n' "cli.c" "$(sha256sum "$UPSTREAM/cli.c" | cut -d' ' -f1)"

mkdir -p "$OUT_DIR"
# Same flags the upstream CMake Release build uses (-Wall -Wextra -Werror there;
# -Werror is left off here so a compiler-version nit cannot block a gate).
"$CC" -O2 -std=c11 -Wall -Wextra \
    -I "$VENDORED" \
    -o "$OUT" \
    "$UPSTREAM/cli.c" \
    "$VENDORED/tparse.c" \
    "$VENDORED/name.c"

echo "build_tparse_cli: built $OUT"
echo "build_tparse_cli: binary sha256 $(sha256sum "$OUT" | cut -d' ' -f1)"
echo "build_tparse_cli: smoke  echo '{\"id\":1,\"name\":\"Some.Movie.2019.1080p.BluRay.x264\",\"size_bytes\":1,\"n_files\":1,\"files\":[{\"path\":\"Some.Movie.2019.1080p.BluRay.x264.mkv\",\"size\":1}]}' | $OUT"
