#!/usr/bin/env python3
"""BEP51 `sample_infohashes` UDP probe (strict reply validation).

Sends the crawler's wire format for BEP51 `sample_infohashes` to a transmission
daemon's DHT port and validates the reply against the STRICT libtorrent consumer
bar (refs/libtorrent/src/kademlia/sample_infohashes.cpp:49-136), which is
stricter than this repo's own crawler parser.

The query bytes are EXACTLY the crawler's
(`dht_crawler/src/tree_protocol.c:320-360 build_sample_infohashes_query()`):

  d1:ad2:id20:<node_id>6:target20:<target>e1:q17:sample_infohashes1:t2:aa1:y1:qe

The strict bar a reply must clear:

  * a top-level bencode dict that consumes the whole datagram (no trailing bytes)
  * `y` == "r" (a reply, not an error) and the `t` transaction id echoed
  * `r` present and a dict
  * `id` a string of exactly 20 octets
  * `interval` an integer in [0, 21600]
  * `samples` ALWAYS present, a string, length % 20 == 0
  * `num` an integer >= 0
  * `nodes` (if present) a string with a 26-octet stride,
    `nodes6` (if present) with a 38-octet stride
  * the whole datagram shorter than dht.c's 2048-octet send buffer

Usage
-----
  python3 tools/bep51_probe.py HOST PORT [--count N] [--timeout S]

  # one query at the daemon's DHT port (51413 by default)
  python3 tools/bep51_probe.py 192.168.0.8 51413

  # five queries, 3 s each, against a daemon on the same LAN
  python3 tools/bep51_probe.py 192.168.0.8 51413 --count 5 --timeout 3

  # dead port: the expected signal for the disabled case
  python3 tools/bep51_probe.py 127.0.0.1 1 --timeout 1

UDP 51413 cannot be adb-forwarded, so the probe host and the phone must be on the
same LAN (or the probe must run on-device). A daemon with the feature disabled
drops the query silently, so it is indistinguishable from an unreachable node —
both read as "no reply".

Nothing here writes to the device and no third-party module is used (stdlib
socket only).

Exit codes: 0 = valid reply, 1 = timeout / no reply, 2 = invalid reply,
4 = bad arguments.
"""

import os
import socket
import sys

MAX_REPLY = 2048            # dht.c's send buffer
INTERVAL_MAX = 21600        # BEP51 spec maximum, clamped at the responder's read site
TID = b"aa"                 # crawler's transaction id
MAX_DEPTH = 32              # bencode nesting guard for untrusted input

EXIT_OK = 0
EXIT_TIMEOUT = 1
EXIT_INVALID = 2
EXIT_USAGE = 4


# ---------------------------------------------------------------- bencode

class BencodeError(Exception):
    """Raised when a datagram is not well-formed bencode."""


def _parse_int(data, pos):
    end = data.find(b"e", pos)
    if end < 0:
        raise BencodeError("unterminated integer")
    raw = data[pos:end]
    if not raw:
        raise BencodeError("empty integer")
    body = raw[1:] if raw[:1] == b"-" else raw
    if not body.isdigit() or (len(body) > 1 and body[:1] == b"0"):
        raise BencodeError("malformed integer %r" % raw)
    if raw == b"-0":
        raise BencodeError("negative zero")
    return int(raw), end + 1


def _parse_bytes(data, pos):
    colon = data.find(b":", pos)
    if colon < 0:
        raise BencodeError("unterminated string length")
    raw = data[pos:colon]
    if not raw.isdigit() or (len(raw) > 1 and raw[:1] == b"0"):
        raise BencodeError("malformed string length %r" % raw)
    start = colon + 1
    end = start + int(raw)
    if end > len(data):
        raise BencodeError("string runs past end of datagram")
    return data[start:end], end


def bdecode(data):
    """Decode one bencode value; the caller checks it consumed the whole input.

    Returns (value, end_pos). Bytes for strings, int for integers, dict for
    dictionaries (bytes keys) and list for lists.
    """
    value, pos = _decode(data, 0, 0)
    return value, pos


def _decode(data, pos, depth):
    if depth > MAX_DEPTH:
        raise BencodeError("nesting too deep")
    if pos >= len(data):
        raise BencodeError("truncated datagram")
    ch = data[pos:pos + 1]
    if ch == b"i":
        return _parse_int(data, pos + 1)
    if ch == b"l":
        out = []
        pos += 1
        while True:
            if pos >= len(data):
                raise BencodeError("unterminated list")
            if data[pos:pos + 1] == b"e":
                return out, pos + 1
            item, pos = _decode(data, pos, depth + 1)
            out.append(item)
    if ch == b"d":
        out = {}
        pos += 1
        while True:
            if pos >= len(data):
                raise BencodeError("unterminated dictionary")
            if data[pos:pos + 1] == b"e":
                return out, pos + 1
            if not data[pos:pos + 1].isdigit():
                raise BencodeError("dictionary key is not a string")
            key, pos = _parse_bytes(data, pos)
            value, pos = _decode(data, pos, depth + 1)
            out[key] = value
    if ch.isdigit():
        return _parse_bytes(data, pos)
    raise BencodeError("unexpected byte %r at offset %d" % (ch, pos))


# ---------------------------------------------------------------- query

def build_query(node_id, target):
    """EXACTLY the crawler's wire format (tree_protocol.c:320-360)."""
    return (b"d1:ad2:id20:" + node_id +
            b"6:target20:" + target +
            b"e1:q17:sample_infohashes1:t2:aa1:y1:qe")


def check_reply(datagram):
    """Apply the strict libtorrent bar.

    Returns (fields, errors): `fields` carries num/samples/interval/nodes for
    reporting, `errors` is empty on a valid reply.
    """
    errors = []
    fields = {"num": None, "samples": None, "interval": None, "nodes": 0,
              "reply_len": len(datagram)}

    if not datagram:
        return fields, ["empty datagram"]
    if len(datagram) >= MAX_REPLY:
        return fields, ["reply is %d octets, not < %d" % (len(datagram), MAX_REPLY)]

    try:
        top, end = bdecode(datagram)
    except BencodeError as exc:
        return fields, ["not bencode: %s" % exc]
    if end != len(datagram):
        errors.append("%d trailing octet(s) after the top-level value"
                      % (len(datagram) - end))
    if not isinstance(top, dict):
        return fields, errors + ["top-level value is not a dictionary"]

    # y == "r": a reply, not an error
    if top.get(b"y") != b"r":
        errors.append("y is %r, expected b'r'" % (top.get(b"y"),))
    # tid echoed
    if top.get(b"t") != TID:
        errors.append("t is %r, expected the echoed tid %r" % (top.get(b"t"), TID))

    r = top.get(b"r")
    if not isinstance(r, dict):
        return fields, errors + ["r is missing or not a dictionary"]

    node = r.get(b"id")
    if not isinstance(node, bytes):
        errors.append("id is missing or not a string")
    elif len(node) != 20:
        errors.append("id is %d octets, expected exactly 20" % len(node))

    interval = r.get(b"interval")
    if not isinstance(interval, int) or isinstance(interval, bool):
        errors.append("interval is missing or not an integer")
    elif not 0 <= interval <= INTERVAL_MAX:
        errors.append("interval is %d, outside [0, %d]" % (interval, INTERVAL_MAX))
    else:
        fields["interval"] = interval

    num = r.get(b"num")
    if not isinstance(num, int) or isinstance(num, bool):
        errors.append("num is missing or not an integer")
    elif num < 0:
        errors.append("num is %d, negative" % num)
    else:
        fields["num"] = num

    samples = r.get(b"samples")
    if not isinstance(samples, bytes):
        errors.append("samples is missing or not a string")
    elif len(samples) % 20 != 0:
        errors.append("samples is %d octets, not a multiple of 20" % len(samples))
    else:
        fields["samples"] = len(samples) // 20

    nodes = r.get(b"nodes")
    if nodes is not None:
        if not isinstance(nodes, bytes):
            errors.append("nodes is not a string")
        elif len(nodes) % 26 != 0:
            errors.append("nodes is %d octets, not a 26-octet stride" % len(nodes))
        else:
            fields["nodes"] = len(nodes) // 26
    nodes6 = r.get(b"nodes6")
    if nodes6 is not None:
        if not isinstance(nodes6, bytes):
            errors.append("nodes6 is not a string")
        elif len(nodes6) % 38 != 0:
            errors.append("nodes6 is %d octets, not a 38-octet stride" % len(nodes6))

    return fields, errors


# ---------------------------------------------------------------- transport

def query_once(sock, host, port, timeout, index):
    """Send one query and classify the outcome: "valid", "timeout" or "invalid"."""
    node_id = os.urandom(20)
    target = os.urandom(20)
    packet = build_query(node_id, target)

    try:
        sock.settimeout(timeout)
        sock.sendto(packet, (host, port))
    except OSError as exc:
        print("attempt %d: SEND FAILED (%s)" % (index, exc))
        return "timeout"

    while True:
        try:
            datagram, addr = sock.recvfrom(MAX_REPLY * 4)
        except (socket.timeout, TimeoutError, BlockingIOError):
            print("attempt %d: no reply within %gs -> FAIL (timeout)" % (index, timeout))
            return "timeout"
        except OSError as exc:
            # ICMP port-unreachable surfaces here on some stacks; either way there
            # is no reply to validate.
            print("attempt %d: no reply (%s) -> FAIL (timeout)" % (index, exc))
            return "timeout"

        if addr[0] != host or addr[1] != port:
            print("attempt %d: ignoring %d-octet datagram from %s:%d"
                  % (index, len(datagram), addr[0], addr[1]))
            continue

        fields, errors = check_reply(datagram)
        if errors:
            print("attempt %d: %d-octet reply from %s:%d -> FAIL (invalid)"
                  % (index, len(datagram), addr[0], addr[1]))
            for err in errors:
                print("    %s" % err)
            return "invalid"

        print("attempt %d: num=%d samples=%d (%d B) interval=%d nodes=%d "
              "reply=%d B -> PASS"
              % (index, fields["num"], fields["samples"],
                 fields["samples"] * 20, fields["interval"], fields["nodes"],
                 fields["reply_len"]))
        return "valid"


def usage(message=None):
    if message:
        print("bep51_probe: %s" % message, file=sys.stderr)
    print("Usage: %s HOST PORT [--count N] [--timeout S]" % sys.argv[0], file=sys.stderr)
    print("  HOST:         phone LAN address (UDP 51413 cannot be adb-forwarded)",
          file=sys.stderr)
    print("  PORT:         the daemon's DHT port (transmission default 51413)",
          file=sys.stderr)
    print("  --count N:    number of queries to send (default 1)", file=sys.stderr)
    print("  --timeout S:  per-query reply timeout in seconds (default 2.0)",
          file=sys.stderr)
    print("Exit codes: 0 valid reply, 1 timeout, 2 invalid reply, 4 bad arguments",
          file=sys.stderr)
    return EXIT_USAGE


def main(argv):
    positional = []
    count = 1
    timeout = 2.0
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg in ("-h", "--help"):
            usage()
            return EXIT_OK
        if arg in ("--count", "--timeout"):
            if i + 1 >= len(argv):
                return usage("%s needs a value" % arg)
            try:
                value = float(argv[i + 1])
            except ValueError:
                return usage("%s needs a number, got %r" % (arg, argv[i + 1]))
            if arg == "--count":
                if value < 1 or value != int(value):
                    return usage("--count needs a positive integer")
                count = int(value)
            else:
                if value < 0:
                    return usage("--timeout cannot be negative")
                timeout = value
            i += 2
            continue
        if arg.startswith("--"):
            return usage("unknown option %r" % arg)
        positional.append(arg)
        i += 1

    if len(positional) != 2:
        return usage("expected HOST and PORT")
    host = positional[0]
    try:
        port = int(positional[1])
    except ValueError:
        return usage("PORT must be a number, got %r" % positional[1])
    if not 0 < port < 65536:
        return usage("PORT must be in 1..65535")

    try:
        family, _, _, _, sockaddr = socket.getaddrinfo(
            host, port, 0, socket.SOCK_DGRAM)[0]
    except socket.gaierror as exc:
        print("bep51_probe: cannot resolve %s: %s" % (host, exc), file=sys.stderr)
        return EXIT_TIMEOUT
    host, port = sockaddr[0], sockaddr[1]

    print("bep51_probe: sample_infohashes -> %s:%d (count=%d timeout=%gs)"
          % (host, port, count, timeout))

    sock = socket.socket(family, socket.SOCK_DGRAM)
    try:
        outcomes = [query_once(sock, host, port, timeout, n + 1) for n in range(count)]
    finally:
        sock.close()

    valid = outcomes.count("valid")
    timed_out = outcomes.count("timeout")
    invalid = outcomes.count("invalid")
    verdict = "PASS" if invalid == 0 and timed_out == 0 else "FAIL"
    print("summary: %d/%d valid, %d timeout, %d invalid -> %s"
          % (valid, count, timed_out, invalid, verdict))

    if invalid:
        return EXIT_INVALID
    if timed_out:
        return EXIT_TIMEOUT
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
