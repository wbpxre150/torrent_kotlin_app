# The llama.cpp "embeddings required … -> overriding" log flood

**Status: open, cosmetic, unfixed.** Recorded 2026-11-26 from the live dashboard on `b078fce`
(versionCode 5 / 0.4.1). Nothing here was diagnosed on-device; it is a code reading plus one
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
re-run for this specific change of batch construction.

## Candidate fixes, cheapest first

1. **Silence it at the source of truth** — build the batch explicitly instead of using
   `llama_batch_get_one`: `llama_batch_init(n_tokens, 0, 1)` with every `batch.logits[i] = 1`, then
   `llama_batch_free`. No warning, and the intent ("all tokens are outputs") is stated rather than
   inferred.
2. **Install a log callback** (`llama_log_set`) that drops this specific message. Cheap, but hides
   the signal for every other llama.cpp warning too — only worth it as a stop-gap.
3. **Upstream it**: the override is correct, so the warning arguably belongs at debug level when
   `output_all` is set. That helps everyone but does not fix this device until the pin moves.

## How to verify a fix

- `files/logs/dht_crawler.log` contains **0** occurrences of `were not marked as outputs`.
- `movie_kind` rows still increase (`/stats movie_kind.rows_predicted`) and `thermal_pauses` stays
  0, so classification is still running.
- Re-run the Gate P mask-parity spot check against the pre-change `--dump-masks` output; it must be
  byte-identical. If fix 1 changes any mask, the override was not equivalent and the change is
  wrong.
