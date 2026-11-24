#!/bin/sh
# ANDROID fork: bep51 -- host harness runner for the BEP51 sample_infohashes
# responder that apply_fork.py adds to third-party/dht/dht.c.
#
# Modeled on run.sh: set -e, cd to this directory, compile into /tmp, run.
# The code under test is the PATCHED dht.c from the extracted transmission tree,
# so the tree must have been extracted and patched first (buildTransmission or a
# manual apply_fork.py run).
set -e
cd "$(dirname "$0")"

APP_DIR=$(cd ../../../../.. && pwd)
DHT_DIR="$APP_DIR/build/native/src/transmission-4.1.4/third-party/dht"

if ! grep -q "ANDROID fork: bep51" "$DHT_DIR/dht.c" 2>/dev/null; then
    echo "run_dht_bep51.sh: no BEP51 hunks in $DHT_DIR/dht.c" >&2
    echo "  expected marker 'ANDROID fork: bep51' -- the harness needs the patched tree." >&2
    echo "  run: ./gradlew :app:buildTransmission" >&2
    echo "  or:  python3 app/src/main/cpp/transmission-fork/apply_fork.py \"$APP_DIR/build/native/src/transmission-4.1.4\"" >&2
    exit 1
fi

gcc -std=gnu99 -O1 -g -D_GNU_SOURCE -I"$DHT_DIR" \
    "$DHT_DIR/dht.c" dht_bep51_test.c -o /tmp/dht_bep51_test
/tmp/dht_bep51_test
