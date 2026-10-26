# Stage 4: a real iterative round (chase closer nodes, inside the packet budget)

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Index/design: `plan/crawler-getpeers-throughput/README.md`.
> Depends on stage 3.

## Goal

Stage 2 removed the duplicate rounds but also removed the *only* thing the old rounds did that could
find new peers: nothing — they re-asked the same eight nodes. This stage adds the round the old code
was trying to be: after the first fan-out returns no peers, query the **closer nodes the responders
told us about**, and only while that is still making progress.

Two rules keep it inside the packet budget:

1. **Progress rule.** A candidate is worth querying only if it is XOR-closer to the infohash than the
   closest node that has already *responded*. Dead swarms answer with no peers and no closer nodes, so
   they never trigger a second round — they stay at one fan-out of packets.
2. **Hard cap.** `tree_get_peers_max_queries` (default 16) bounds total packets per infohash, and the
   per-infohash deadline still governs wall time.

Expected: yield **22 % → ≥25 %**, packets per lookup **≤14**, drain **not lower than stage 3**.

## Context (measured — do not re-derive)

- Stage 2's `tree_lookup_peers()` sends `tree->get_peers_fanout` queries to the 8 closest routing-table
  nodes, drains against `tree->get_peers_deadline_ms`, then returns. Baseline after stage 2 (fill in from
  the stage-2 probe): packets/lookup ≤10, mean lookup ≤2.5 s, yield ≥20 %.
- `tree_get_peers_response_t` (`include/tree_protocol.h:50`) already carries the closer nodes:
  `uint8_t nodes[256][20]` + `struct sockaddr_storage node_addrs[256]` + `node_count`. Today they are
  only fed to `tree_routing_add_node()` and never used as query targets — that is the whole gap.
- `xor_distance()` and `compare_distance()` exist but are **`static`** in `tree_routing.c:12/37`. They
  are not exported, and the XOR ordering must not be re-implemented in `thread_tree.c` (two copies of a
  distance rule will drift).
- `tree_get_peers_max_queries` exists as a config key after stage 3 and is already echoed in
  `/stats` `trees[].get_peers.max_queries`.
- The routing table holds ~1,500 nodes per tree and is refreshed from every response, so re-reading
  `tree_routing_get_closest()` cannot produce a new target set — the candidates must come from the
  responses themselves.

## Scope

### 1. Export the distance comparator (`include/tree_routing.h`, `src/tree_routing.c`)

```c
/* Compare XOR distances to `target`: returns <0 if `a` is closer than `b`, 0 if equal, >0 if farther.
 * memcmp semantics on the 20-byte XOR distance. */
int tree_routing_xor_compare(const uint8_t *target, const uint8_t *a, const uint8_t *b);
```

Implement it with the existing `xor_distance()` and make the current `static compare_distance()` a
one-line wrapper so there is exactly one implementation. No behaviour change to the routing table.

### 2. Candidate set in `tree_lookup_peers()` (`src/thread_tree.c`)

```c
#define GP_MAX_CANDIDATES 32
typedef struct {
    uint8_t id[20];
    struct sockaddr_storage addr;
    int queried;                 /* 1 once a get_peers has been sent to it */
} gp_candidate_t;
```

- Seed it with the initial `closest[8]` from `tree_routing_get_closest()` (`queried = 1` for the ones
  actually sent to in round 1, `0` otherwise).
- After each parsed response, merge `response.nodes[]` / `response.node_addrs[]` into the set:
  skip ids already present (memcmp on 20 bytes), skip ids already queried, keep the set sorted by
  `tree_routing_xor_compare(target=infohash, …)`, and drop the farthest when it exceeds
  `GP_MAX_CANDIDATES`.
- Track `best_responder_dist[20]`: the XOR distance of the closest node that **responded** this lookup
  (initialise to `0xFF…FF`). A node that times out must not improve it.

### 3. Round loop

Keep stage 2's structure (slot table, deadline, sweep-unregister) and wrap it in a loop:

```
queries_sent = 0;
deadline = now + tree->get_peers_deadline_ms;
for (;;) {
    pick up to fanout un-queried candidates that satisfy the progress rule, bounded by
        (tree->get_peers_max_queries - queries_sent);
    if (none) break;
    fan out those candidates (stage 2 slot code, queries_sent += sent);
    drain until all slots answered / per-slot expiry / deadline / shutdown;
    if (out->peer_count > 0) break;
    if (now >= deadline) break;
}
sweep-unregister everything outstanding;
return out->peer_count;
```

Progress rule, stated precisely: query candidate `c` only if
`tree_routing_xor_compare(infohash, c->id, best_responder_dist) < 0`.
With no responder yet (`best_responder_dist` all 0xFF), every candidate qualifies — that is the
"round 1 answered nothing, keep trying" case, which is correct but must be bounded by `max_queries`.

### 4. Counters

Reuse stage 1's counters; add exactly one:

- `gp_rounds_total` (per-tree + supervisor cumulative): increment per completed round after the first,
  so `/stats` shows how often the iterative path actually engages. Expect it to be **well below** the
  lookup count (only promising hashes chase); if it is close to 100 % of lookups, the progress rule is
  not working and packets/pop will blow out.

### 5. `/stats`

Add `rounds_total` and `mean_rounds_per_lookup` to the `get_peers` object (per tree + supervisor).

## Constraints

- `tree_lookup_peers()`'s signature and contract are unchanged (stage 2's).
- `tree_get_peers_max_queries` is a **hard** cap on packets for one infohash, including round 1. Never
  exceed it, even if the deadline still has time.
- Never exceed `tree_get_peers_deadline_ms` wall time, including the send-throttle time inside a round.
- Every TID registered must be unregistered before the next round starts and before returning — the
  sweep must run per round, not only at the end.
- Do not change `tree_send_get_peers()`, the dispatcher, `udp_rate_limit_pps`, or any existing key.
- Do not edit `dht_crawler/lib/` or `dht_crawler/patches/`.

## Pitfalls

1. **The progress rule is what protects the packet budget.** If you skip it (query any un-queried
   candidate), dead hashes will spend `max_queries` packets each and the drain will *fall* below stage 3
   — the opposite of this plan's goal. Implement the rule before optimising anything else.
2. **`best_responder_dist` must come from responders, not from the candidates you sent to.** A node that
   timed out tells you nothing; letting it tighten the bound aborts the search after one round.
3. **Merge-before-sort, dedupe by id.** Responses routinely repeat nodes (256-entry lists, heavy
   overlap). Without the id check the 32-slot set fills with duplicates of one node and the search
   stalls.
4. **A response can arrive for the previous round's TID.** The stage-2 sweep unregisters per round; if
   you move the sweep to the end of the function instead, round N+1's drain will consume round N's late
   responses (they fail the slot match and are skipped — correct but it burns queue capacity).
5. **Do not touch `tree_routing_add_node()` semantics.** It is called for every response node today and
   keeps the 1,500-node table fresh; the candidate set is an additional consumer, not a replacement.
6. **Stack budget.** `tree_get_peers_response_t` is ~37 KB (256 nodes × 128-byte sockaddrs). Keep exactly
   one instance per lookup and add the 32 × 148-byte candidate set beside it, not per round.

## Build / validate

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
cd /home/adam/apps/torrent_kotlin_app
(cd app/src/main/cpp/dht_crawler && make -j"$(nproc)")
./gradlew installDebug
./gradlew :app:testDebugUnitTest
```

## Exit criteria (implementer) — all must pass

1. Host `make`, `./gradlew installDebug`, `:app:testDebugUnitTest` clean.
2. `gp_probe.py --minutes 15` versus the stage-3 run:
   - yield **≥25 %** (from ~22 %);
   - packets per lookup **≤14** and **≥10** (if it is still ≤10 the iterative round never runs — the
     progress rule is too strict or the candidate merge is broken);
   - `mean_rounds_per_lookup` between **1.05 and 1.6**;
   - drain **not lower than stage 3**;
   - mean lookup still **≤3.0 s**.
3. UDP: `udp_sends_total` delta **<2,400 pps**; `udp_throttled_sends_total` growth **<2 %** of sends.
4. `queue_full_drops` delta 0; no crash; 30-minute soak with RSS/thread-count flat.
5. `torrents_last_hour` **≥ stage 3** (this stage trades a few packets for yield; it must not lose
   throughput).
6. `/search` and `/torrent/info` latencies unchanged.

**Revert condition (report, do not ship):** if yield does not rise by ≥2 pp, or the drain falls, the
iterative round is not paying for its packets. Revert to stage 3's single-round behaviour (keep the
config keys and the exported comparator) and record the measurement — a negative result here is a
legitimate outcome and closes cause 2 permanently.

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler commit -am "crawler: iterative get_peers round over response-supplied closer nodes"`
2. Bump the parent gitlink:
   `git add app/src/main/cpp/dht_crawler && git commit -m "app: crawler get_peers throughput stage 4 — iterative round"`
