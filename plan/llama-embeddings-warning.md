# The llama.cpp "embeddings required … -> overriding" log flood

**Status: fixed** — 2026-11-26, dht_crawler commits `4057a72` ("crawler: mark every movie-kind token
as an output instead of letting the allocr override", fix 1 below) and `7da3204` ("crawler: hold all
of llama.cpp's log at DEBUG, not just its DEBUG lines", see *Log levels* below). Originally recorded
2026-11-26 from the live dashboard on `b078fce` (versionCode 5 / 0.4.1) as a code reading plus one
observation.

## Symptom

The DHT Crawler card's log tail is saturated with identical lines, one per classified item:

```
init: embeddings required but some input tokens were not marked as outputs -> overriding
```

Seen on the dashboard render that produced `docs/screenshots/web-dashboard.png`, where ~10
consecutive lines filled the visible tail while `CRAM v15 … 1651 predicted` and
`movie_kind generation 13 / model_ready true` were both healthy.

Note the `init:` prefix: that is llama.cpp's default log callback writing to stderr with no
component tag, not one of the crawler's own `log_msg` lines.

## Where it comes from

Upstream llama.cpp at the pinned commit `18ab62a8`:

- `src/llama-batch.cpp:28` — `llama_batch_allocr::init(..., bool output_all)`
- `src/llama-batch.cpp:226-232` — when `output_all` is true and any token has `output == 0`, it
  warns once and then `std::fill(output.begin(), output.end(), 1)`

`output_all` is true because the movie-kind context is created with embeddings on
(`dht_crawler/src/movie_kind.c:550`, `context_params.embeddings = true`).

The batch is built with the convenience helper
(`dht_crawler/src/movie_kind.c:643`):

```c
struct llama_batch batch = llama_batch_get_one(tokens, n_tokens);
```

and `llama_batch_get_one` (`src/llama-batch.cpp:1108`) hard-codes `logits = nullptr` — meaning "no
token is explicitly flagged as an output". So every encode/decode the movie-kind classifier
performs takes the warn-and-override path. That is why the flood is proportional to classification
volume rather than to anything going wrong.

## Impact

- **Log volume only.** llama.cpp immediately overrides to all-token outputs, which is exactly what
  the shipped pooling path needs (`LLAMA_POOLING_TYPE_LAST` + `llama_get_embeddings_seq(ctx, 0)` at
  `movie_kind.c:664`). The pooled vector is unchanged, so **no verdict is affected**.
- It drowns the crawler's own log lines in the dashboard tail and in `files/logs/dht_crawler.log`,
  which makes both useless for reading real errors — the actual cost, and the reason to fix it.
- It is *not* the CRAM path: CRAM is pure C and never touches llama.cpp.

**Not verified:** that the override is bit-identical to flagging every token as an output
explicitly. That is what the upstream code does, but the mask-parity style check (Gate P) was not
re-run for this specific change of batch construction. (The fix above settles it at the code level —
the override *is* `std::fill(output, 1)`; an on-device `--dump-masks` diff against pre-change output
is still the empirical check.)

## Fix applied (candidate 1)

`dht_crawler/src/movie_kind.c` (`mk_llama_embed`) no longer uses `llama_batch_get_one`. It builds
the batch explicitly and states the intent:

```c
struct llama_batch batch = llama_batch_init(n_tokens, 0, 1);
memcpy(batch.token, tokens, (size_t)n_tokens * sizeof(llama_token));
batch.n_tokens = n_tokens;
for (int32_t i = 0; i < n_tokens; i++) {
    batch.pos[i]       = i;
    batch.n_seq_id[i]  = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i]    = 1;
}
...
free(tokens);
llama_batch_free(batch);
```

Why this is equivalent rather than merely quieter, at the pin `18ab62a8`:

- `llama_batch_compat::init` (`src/llama-batch.cpp:1472`) reads a NULL `logits` array as
  `t.output = (i == n_tokens - 1)` — *only the last token is an output*. That is what made
  `output_all` warn.
- The override it then performs is `std::fill(output.begin(), output.end(), 1)` — *every* token an
  output, which is exactly what the loop above requests. Same `output` vector, same graph.
- The hand-written `pos`/`n_seq_id`/`seq_id` values are the ones the automatic paths produce: the
  compat layer auto-generates `pos` from `llama_memory_seq_pos_max(mem, s) + 1`, and the decode
  branch clears the memory immediately before `llama_decode`, so both sequences start at 0.

`llama_batch_init` mallocs `pos`/`n_seq_id`/`seq_id`/`logits` uninitialised, so they must be written
here — passing the mallocs through untouched would feed garbage positions to the backend.
`llama_batch_free` frees the copied token array, hence the separate `free(tokens)`.

Build/test evidence: `make tests-movie-kind tests-movie-kind-render tests-movie-kind-filter` pass
(the host build has no `MK_ENABLED`, so it never compiles this block), and the change is
warning-clean under `-Wall -Wextra` in the real `MK_ENABLED` arm64 target
(`./gradlew :app:buildDhtCrawler`).

## Log levels: the rest of the noise (`7da3204`)

Fix 1 removed the per-item line, but the log tail was still dominated by llama.cpp: one model load
emitted ~500 `llama_graph_n_input_tensors` lines (llama.cpp *DEBUG*) plus the GGUF metadata dump,
`print_info`, `load_tensors` and `llama_context`/`llama_kv_cache` blocks (llama.cpp *INFO*) — ~230
more. None had a timestamp or a level, because llama.cpp's default callback
(`src/llama-impl.cpp:84` at the pin) **ignores the `ggml_log_level` it is handed**:

```c
void llama_log_callback_default(ggml_log_level level, const char * text, void * user_data) {
    (void) level; (void) user_data;
    fputs(text, stderr);
    fflush(stderr);
}
```

So llama.cpp's own level never applied to the crawler's — the `log_level` setting had no effect on it
at all.

`movie_kind.c` now installs `llama_log_set(mk_llama_log, NULL)` before the first model load (i.e.
before `llama_init_from_model`, where the graph dump happens) and maps the levels onto the crawler's:
**DEBUG and INFO → `LOG_DEBUG`**, WARN → `LOG_WARN`, ERROR → `LOG_ERROR`, `NONE` dropped, `CONT`
reusing the previous message's level.

llama.cpp's INFO is load diagnostics, and the crawler already prints the one load line worth reading
at INFO — `MovieKind: model loaded (generation 13, 16 genres, 4 threads)`. So the whole of
llama.cpp's output sits at DEBUG, and its WARN/ERROR (a failed encode, an unsupported op) still
surface at the shipped `log_level = INFO`.

This is candidate 2 below done the other way round: it demotes llama.cpp's output by level rather
than filtering one message string, so no warning is hidden.

Measured on `b078fce` at `log_level = INFO`: 1783 lines with 511 graph lines → **22 lines, 0
llama.cpp lines**. The same build at `log_level = DEBUG` produced 446k lines, every one prefixed
`[DEBUG]` — demoted, not deleted.

## Candidate fixes, cheapest first

1. **Silence it at the source of truth** — build the batch explicitly instead of using
   `llama_batch_get_one`: `llama_batch_init(n_tokens, 0, 1)` with every `batch.logits[i] = 1`, then
   `llama_batch_free`. No warning, and the intent ("all tokens are outputs") is stated rather than
   inferred. **Applied — see above.**
2. **Install a log callback** (`llama_log_set`) that drops this specific message. Cheap, but hides
   the signal for every other llama.cpp warning too — only worth it as a stop-gap. **Superseded:**
   `7da3204` installs a callback that maps levels instead (see *Log levels* above), so nothing is
   dropped by message text and WARN/ERROR still surface.
3. **Upstream it**: the override is correct, so the warning arguably belongs at debug level when
   `output_all` is set. That helps everyone but does not fix this device until the pin moves.

## How to verify a fix

- `files/logs/dht_crawler.log` contains **0** occurrences of `were not marked as outputs`.
- It also contains **0** `llama_*`/`print_info`/`load_tensors`/`sched_reserve` lines at
`log_level = INFO`, and the same dump prefixed `[DEBUG]` at `log_level = DEBUG` (that is the level
mapping working, not a deletion).
- `movie_kind` rows still increase (`/stats movie_kind.rows_predicted`) and `thermal_pauses` stays
  0, so classification is still running.
- Re-run the Gate P mask-parity spot check against the pre-change `--dump-masks` output; it must be
  byte-identical. If fix 1 changes any mask, the override was not equivalent and the change is
  wrong.
