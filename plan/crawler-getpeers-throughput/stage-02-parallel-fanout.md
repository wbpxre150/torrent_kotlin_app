# Stage 2: parallel fan-out with one per-infohash deadline

> Implement via `/dev` (dev-workflow) with this file as the task prompt.
> **Target repo: `~/apps/torrent_kotlin_app`**, submodule `app/src/main/cpp/dht_crawler`.
> Index/design: `plan/crawler-getpeers-throughput/README.md`.
> Depends on stage 1 (its counters and probe are the exit criteria).

## Goal

Replace the sequential send-then-wait-per-node loop in `tree_lookup_peers()` with a **fan-out**: send
the round's queries back-to-back with distinct TIDs, then drain responses against **one deadline**.
Delete the two duplicate rounds.

Measured baseline to beat (per pop, on the phone):

| | today | target |
|---|---|---|
| worker time per pop | **10.9 s** | **≤2.5 s** |
| packets per pop | **~25** | **≤10** |
| drain per tree | 1.66 /s | **≥4 /s** |
| yield | 22 % | **≥20 %** (must not regress) |

## Context (measured — do not re-derive)

- `tree_lookup_peers()` (`thread_tree.c:644-722`) is the only sender of get_peers packets. Two call
  sites: the get_peers worker (`thread_tree.c:773`) and the metadata worker's fresh-peer retry
  (`tree_metadata.c:1486`, ~87 k calls/h).
- Cost structure today: iteration 0 sends to the 8 closest routing-table nodes **one at a time**, each
  with a `tree_response_queue_pop(…, 1500 ms)`. Iterations 1 and 2 then **re-query the same 8 nodes**
  (line 717 refills `closest` from the routing table; response nodes are only inserted into the table at
  line 704, comment: `/* Could track these for next iteration, but keeping it simple */`). Worst case
  8 × 3 × 1.5 s = 36 s; measured mean 10.9 s.
- `queries_this_round` (line 669/703) is dead code: assigned `8` or `0`, read in a `ni < 8` guard,
  never incremented. Delete it.
- The dispatcher already routes per TID (`tree_dispatcher.c`: `tree_protocol_extract_tid()` →
  uthash → `tree_response_queue_try_push()`), so N outstanding TIDs on one queue works today.
  `tree_protocol_extract_tid()` (`include/tree_protocol.h:207`) is the matcher.
- `tree_protocol_gen_tid()` is a process-wide atomic counter → distinct TIDs for a batch are free.
- `tree_response_t` (`include/tree_response_queue.h:18`) carries `data`/`len`/`from` but **no TID** —
  match by extracting the TID from `data`.
- `tree_response_queue_try_push()` **drops silently when full**; the queue is created with capacity
  **10** in both callers (`thread_tree.c:807`, `tree_metadata.c:1482`).
- `tree_socket_send()` calls `udp_rate_limit_acquire()` **before** taking the process-wide
  `send_lock` (`tree_socket.c:156-160`); under throttling a send can sleep up to 50 ms.
- The shared dispatcher deletes TID registrations older than **10 s**
  (`tree_dispatcher.c`, `TID_TIMEOUT`), so the deadline must stay below that.

## Scope

All in `src/thread_tree.c` unless stated. Change nothing else.

### 1. Constants

```c
/* Stage 2: hardcoded; stage 3 promotes these to config keys. */
#define GP_FANOUT            8     /* concurrent get_peers queries per lookup */
#define GP_DEADLINE_MS    3000     /* hard cap on one lookup, must stay < 10000 */
```

In `include/tree_response_queue.h` add `#define TREE_GP_RESPONSE_QUEUE_CAP 16` (≥ `GP_FANOUT` + slack)
and use it for **both** queue creations:
`thread_tree.c:807` (get_peers worker) and `tree_metadata.c:1482` (fresh-peer retry).

### 2. Rewrite the body of `tree_lookup_peers()`

Keep the signature, the `out` initialisation (`memset`, `infohash` copy) and the
`tree_routing_get_closest(rt, infohash, closest, 8)` / `n == 0 → return 0` prologue. Then:

```
purge stale responses: while (tree_response_queue_count(q) > 0) pop(q, &pkt, 0);
deadline = now_ms() + GP_DEADLINE_MS;          // starts BEFORE the sends (see pitfall 3)

struct { uint8_t tid[4]; int tid_len; int sent; time_t sent_at; } slot[GP_FANOUT];
int outstanding = 0;

/* fan out: one query per closest node, up to GP_FANOUT */
for (i = 0; i < n && i < GP_FANOUT; i++) {
    slot[i].tid_len = tree_protocol_gen_tid(slot[i].tid);
    tree_dispatcher_register_tid(dispatcher, slot[i].tid, slot[i].tid_len, q);
    gp_packets_sent++;
    if (tree_send_get_peers(tree, sock, slot[i].tid, slot[i].tid_len, infohash, &closest[i].addr) == 0) {
        slot[i].sent = 1; slot[i].sent_at = now_ms(); outstanding++;
    } else {
        tree_dispatcher_unregister_tid(dispatcher, slot[i].tid, slot[i].tid_len);
        slot[i].tid_len = 0;
    }
}

/* drain against the deadline */
while (outstanding > 0 && out->peer_count < MAX_PEERS_PER_ENTRY) {
    int remaining = deadline - now_ms();
    if (remaining <= 0 || atomic_load(&tree->shutdown_requested)) break;

    int slice = remaining < 250 ? remaining : 250;
    if (slice > tree->get_peers_timeout_ms) slice = tree->get_peers_timeout_ms;

    tree_response_t pkt;
    if (tree_response_queue_pop(q, &pkt, slice) != 0) continue;   /* timeout slice */

    uint8_t rtid[4]; int rtid_len;
    if (tree_protocol_extract_tid(pkt.data, pkt.len, rtid, &rtid_len) != 0) continue;

    int s = find_slot(slot, outstanding_slots, rtid, rtid_len);   /* -1 if not ours */
    if (s < 0 || !slot[s].sent) continue;                          /* stale / duplicate */

    slot[s].sent = 0; outstanding--;
    gp_answers++;
    tree_dispatcher_unregister_tid(dispatcher, slot[s].tid, slot[s].tid_len);
    slot[s].tid_len = 0;

    if (tree_handle_get_peers_response(tree, pkt.data, pkt.len, &pkt.from, &response) == 0) {
        collect peers into out->peers (respecting MAX_PEERS_PER_ENTRY);
        for (each node) tree_routing_add_node(rt, ...);            /* keep: this is how the table stays fresh */
    }
}

/* sweep: unregister everything still outstanding, on EVERY exit path */
for (i = 0; i < sent_slots; i++)
    if (slot[i].tid_len > 0) tree_dispatcher_unregister_tid(dispatcher, slot[i].tid, slot[i].tid_len);
return out->peer_count;
```

Notes the implementer must honour:

- **The deadline is the only expiry rule.** A slot stays eligible until the deadline; a slow node
  therefore gets the full ~3 s, which is the same total chance it has today (round 3 lands at ~3.2 s)
  but at ~1/3 the wall-clock cost. `tree_get_peers_timeout_ms` keeps a real job as the drain-slice cap
  (`min(remaining, 250 ms, tree_get_peers_timeout_ms)`), not as a per-node chance.
- **Do not implement the iterative round here.** Stage 4 does that with a proper candidate set. Stage 2
  queries exactly the 8 closest routing-table nodes, once each.
- Keep the `MAX_PEERS_PER_ENTRY` early exit: peers found → stop draining, sweep, return.

### 3. Callers

No signature change, so `thread_tree.c:773` and `tree_metadata.c:1486` keep calling it as-is. Only the
two queue capacities change (see §1). `tree_metadata.c`'s queue is still created per failure — hoisting
it is stage 3, not here.

## Constraints

- `tree_lookup_peers()` keeps its contract: returns `out->peer_count`, fills `out->infohash`/`peers`,
  pushes nothing, changes no phase, no new parameters.
- Never block longer than `GP_DEADLINE_MS` on one infohash, including under UDP throttling.
- Every `tree_dispatcher_register_tid()` must be matched by exactly one
  `tree_dispatcher_unregister_tid()` on **every** path (answered, send-failed, deadline, shutdown).
- Keep all existing routing-table updates (`tree_routing_add_node` for every node in every response).
- Do not change `tree_send_get_peers()`, the dispatcher, `udp_rate_limit_pps`, or any config.
- Do not edit `dht_crawler/lib/` or `dht_crawler/patches/`.

## Pitfalls

1. **Unregistering is the whole risk.** A missed unregister leaves the shared dispatcher holding a
   pointer into a worker's queue; when that worker exits (respawn) the dispatcher writes into freed
   memory. Write the sweep **before** the main loop, then verify by inspection that every `return`/
   `break` passes through it.
2. **`dropped_responses` WILL rise, and that is correct.** After we unregister on early exit, late
   responses for those TIDs are dropped by the dispatcher by design. Do not "fix" it, and do not use it
   as a failure signal. `queue_full_drops` is the counter that must stay flat.
3. **Start the deadline before the sends.** `tree_send_get_peers()` → `tree_socket_send()` →
   `udp_rate_limit_acquire()` can sleep up to 50 ms per packet under throttling; 8 sends can burn
   400 ms. If the deadline starts after the batch, a throttled lookup silently exceeds its cap.
4. **Purge stale responses on entry.** An early exit leaves up to 7 unconsumed responses in the worker's
   queue. Without the purge they are consumed by the *next* lookup (harmless but they occupy capacity
   against the next batch of 8 in a 16-slot queue).
5. **Response queue capacity is shared with the metadata retry path.** `tree_metadata.c:1482` creates a
   capacity-10 queue per failure; if you resize only the worker queue, the fresh-peer retry path starts
   dropping responses and its 2.4 % rescue rate will silently fall to ~1 %. Resize both.
6. **`pop` returns -1 for both timeout and shutdown.** Distinguish with
   `atomic_load(&tree->shutdown_requested)`; do not treat a shutdown as "all nodes dead".
7. **TID collisions are not a concern here** (`tree_protocol_gen_tid` is a global atomic), but do not
   reuse a slot's `tid` buffer after unregistering it — the dispatcher may still be comparing against
   that memory in another thread. Unregister first, then zero `tid_len`.

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
2. `gp_probe.py --minutes 10` versus the stage-1 baseline:
   - mean lookup **≤2.5 s** (from ~10.9 s)
   - packets per lookup **≤10** (from ~25)
   - drain **≥4 /s per tree** (from 1.66), sustained for the whole run
   - yield **≥20 %** (baseline 22 %; a small drop is acceptable, a collapse is not)
3. UDP stays inside budget: `udp_sends_total` delta **<2,200 pps**, `udp_throttled_sends_total` grows by
   **<1 %** of sends.
4. Dispatcher: `queue_full_drops` delta **0**; `dropped_responses` may rise (document the rate).
5. The stage stops being the limiter: `peers_queue_size` max across trees **>100** and
   `get_peers_paused` observed true at least once (it is false in the baseline) — this proves the peers
   queue finally backs up and the metadata stage becomes the constraint.
6. Throughput: `torrents_last_hour` **≥2×** the stage-1 value; `aggregate.metadata_success_rate_percent`
   within ±3 pp of baseline (a collapse means slow-but-alive nodes are being dropped — raise the
   deadline before shipping).
7. 30-minute soak: RSS and thread count flat (`dumpsys meminfo`, task count), no FD growth
   (`ls /proc/<pid>/fd | wc -l`), no crash, no `TID`-related WARN/ERROR in `logs/dht_crawler.log`.
8. `/search` and `/torrent/info` latencies unchanged (this stage must not touch the DB/HTTP path).

If criterion 2's yield or criterion 6's success rate regresses: do **not** ship — report, and the next
attempt is stage 3's `tree_get_peers_deadline_ms` at 5000–8000 before anything else.

## Commits

1. Inside the submodule:
   `git -C app/src/main/cpp/dht_crawler commit -am "crawler: fan out get_peers queries against one per-infohash deadline"`
2. Bump the parent gitlink:
   `git add app/src/main/cpp/dht_crawler && git commit -m "app: crawler get_peers throughput stage 2 — parallel fan-out"`
