#!/bin/sh
set -e
cd "$(dirname "$0")"
g++ -std=c++17 -O1 -pthread -I.. disk_queue_test.cc -o /tmp/disk_queue_test && /tmp/disk_queue_test
