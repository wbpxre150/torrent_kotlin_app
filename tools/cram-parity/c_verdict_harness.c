/*
 * c_verdict_harness.c -- the C leg of gate G2 (host verdict parity).
 *
 * Reads JSONL rows `{"id": <int>, "ft": "<tparse feature text>"}` on a file
 * argument, scores every row through the SHIPPING engine, and prints one JSON
 * line per row with the verdict plus the 48 state energies the verdict came
 * from:
 *
 *   {"id":..,"category":..,"kind":..,"adult":..,"adult_p":..,"confidence":..,
 *    "state":..,"nfeat":..,"truncated":..,"gap":..,"gap_rel":..,"e":[48 doubles]}
 *
 * `g2_verdict_parity.py` scores the same rows from the exported container with
 * `cram_export.py`'s validated reference and compares the two.
 *
 * Linkage contract: this harness links the PREPARED TREE's `src/cram.c` plus
 * `lib/cJSON/cJSON.c` and `tests/stub_log.c` -- never `src/classifier.c`, whose
 * real half only exists under `CLF_ENABLED` (design §9 S2 / plan assumption 3).
 * `clf_sha256_file()` lives in `classifier.c`, so the digest primitive is
 * supplied here instead: a self-contained SHA-256 that `cram_load()` calls to
 * verify both model files against `models/v15/manifest.json`.
 *
 * Usage: c_verdict_harness <model_dir> <rows.jsonl> [adult_threshold]
 * Exit:  0 all rows scored, 1 usage/load/IO failure (nothing partial is printed).
 *
 * The 48-state table is read straight out of `cram.bin`'s header + states block
 * (layout: `scripts/cram_export.py`'s container docstring, mirrored by
 * include/cram.h) because `cram_predict()` exposes only the verdict, and the
 * near-tie review needs the energies behind it.
 */

#if !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L /* getline() */
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <sys/types.h>

#include "cJSON.h"
#include "cram.h"

/* ------------------------------------------------------------------ */
/* SHA-256 (FIPS 180-4). Only used to feed `cram_load()`'s digest hook. */
/* ------------------------------------------------------------------ */

typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    unsigned char data[64];
    size_t datalen;
} sha256_ctx;

static const uint32_t SHA256_K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
};

static uint32_t rotr32(uint32_t x, unsigned n) { return (x >> n) | (x << (32u - n)); }

static void sha256_init(sha256_ctx *ctx) {
    ctx->datalen = 0;
    ctx->bitlen = 0;
    ctx->state[0] = 0x6a09e667u;
    ctx->state[1] = 0xbb67ae85u;
    ctx->state[2] = 0x3c6ef372u;
    ctx->state[3] = 0xa54ff53au;
    ctx->state[4] = 0x510e527fu;
    ctx->state[5] = 0x9b05688cu;
    ctx->state[6] = 0x1f83d9abu;
    ctx->state[7] = 0x5be0cd19u;
}

static void sha256_transform(sha256_ctx *ctx, const unsigned char *data) {
    uint32_t m[64];
    uint32_t a, b, c, d, e, f, g, h, t1, t2;
    int i;

    for (i = 0; i < 16; i++) {
        const unsigned char *p = data + 4 * i;
        m[i] = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
               ((uint32_t)p[2] << 8) | (uint32_t)p[3];
    }
    for (; i < 64; i++) {
        uint32_t s0 = rotr32(m[i - 15], 7) ^ rotr32(m[i - 15], 18) ^ (m[i - 15] >> 3);
        uint32_t s1 = rotr32(m[i - 2], 17) ^ rotr32(m[i - 2], 19) ^ (m[i - 2] >> 10);
        m[i] = m[i - 16] + s0 + m[i - 7] + s1;
    }

    a = ctx->state[0]; b = ctx->state[1]; c = ctx->state[2]; d = ctx->state[3];
    e = ctx->state[4]; f = ctx->state[5]; g = ctx->state[6]; h = ctx->state[7];

    for (i = 0; i < 64; i++) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        t1 = h + S1 + ch + SHA256_K[i] + m[i];
        t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }

    ctx->state[0] += a; ctx->state[1] += b; ctx->state[2] += c; ctx->state[3] += d;
    ctx->state[4] += e; ctx->state[5] += f; ctx->state[6] += g; ctx->state[7] += h;
}

static void sha256_update(sha256_ctx *ctx, const unsigned char *data, size_t len) {
    size_t i;
    for (i = 0; i < len; i++) {
        ctx->data[ctx->datalen++] = data[i];
        if (ctx->datalen == 64) {
            sha256_transform(ctx, ctx->data);
            ctx->bitlen += 512;
            ctx->datalen = 0;
        }
    }
}

static void sha256_final(sha256_ctx *ctx, unsigned char out[32]) {
    size_t i = ctx->datalen;
    if (ctx->datalen < 56) {
        ctx->data[i++] = 0x80;
        while (i < 56) ctx->data[i++] = 0x00;
    } else {
        ctx->data[i++] = 0x80;
        while (i < 64) ctx->data[i++] = 0x00;
        sha256_transform(ctx, ctx->data);
        memset(ctx->data, 0, 56);
    }
    ctx->bitlen += (uint64_t)ctx->datalen * 8u;
    ctx->data[63] = (unsigned char)(ctx->bitlen);
    ctx->data[62] = (unsigned char)(ctx->bitlen >> 8);
    ctx->data[61] = (unsigned char)(ctx->bitlen >> 16);
    ctx->data[60] = (unsigned char)(ctx->bitlen >> 24);
    ctx->data[59] = (unsigned char)(ctx->bitlen >> 32);
    ctx->data[58] = (unsigned char)(ctx->bitlen >> 40);
    ctx->data[57] = (unsigned char)(ctx->bitlen >> 48);
    ctx->data[56] = (unsigned char)(ctx->bitlen >> 56);
    sha256_transform(ctx, ctx->data);
    for (i = 0; i < 4; i++) {
        unsigned k;
        for (k = 0; k < 8; k++) {
            out[i + 4 * k] = (unsigned char)(ctx->state[k] >> (24 - 8 * i));
        }
    }
}

/* `cram_sha256_file_fn`: 0 on success with 64 lowercase hex + NUL, -1 else. */
static int harness_sha256_file(const char *path, char *out_hex) {
    static const char HEX[] = "0123456789abcdef";
    FILE *f;
    sha256_ctx ctx;
    unsigned char digest[32];
    unsigned char buf[64 * 1024];
    size_t got;
    int i;

    if (path == NULL || out_hex == NULL) return -1;
    f = fopen(path, "rb");
    if (f == NULL) return -1;
    sha256_init(&ctx);
    while ((got = fread(buf, 1, sizeof buf, f)) > 0) sha256_update(&ctx, buf, got);
    if (ferror(f)) {
        fclose(f);
        return -1;
    }
    fclose(f);
    sha256_final(&ctx, digest);
    for (i = 0; i < 32; i++) {
        out_hex[2 * i] = HEX[digest[i] >> 4];
        out_hex[2 * i + 1] = HEX[digest[i] & 0x0f];
    }
    out_hex[64] = '\0';
    return 0;
}

/* ------------------------------------------------------------------ */
/* small file helpers                                                  */
/* ------------------------------------------------------------------ */

static int read_whole_file(const char *path, unsigned char **out, size_t *out_len) {
    FILE *f = fopen(path, "rb");
    long size;
    unsigned char *buf;
    size_t got;

    if (f == NULL) return -1;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return -1; }
    size = ftell(f);
    if (size < 0) { fclose(f); return -1; }
    if (fseek(f, 0, SEEK_SET) != 0) { fclose(f); return -1; }
    buf = (unsigned char *)malloc((size_t)size + 1u);
    if (buf == NULL) { fclose(f); return -1; }
    got = fread(buf, 1, (size_t)size, f);
    fclose(f);
    if (got != (size_t)size) { free(buf); return -1; }
    buf[got] = '\0';
    *out = buf;
    *out_len = got;
    return 0;
}

static uint32_t le_u32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

/* Manifest digests: models.cram.sha256 / models.cram_vocab.sha256 (design §7). */
static int manifest_digests(const char *dir, char *cram_hex, char *vocab_hex) {
    char path[CRAM_PATH_CAP * 2];
    unsigned char *blob = NULL;
    size_t len = 0;
    cJSON *root, *models, *node;
    int rc = -1;

    snprintf(path, sizeof path, "%s/manifest.json", dir);
    if (read_whole_file(path, &blob, &len) != 0) {
        fprintf(stderr, "harness: cannot read %s\n", path);
        return -1;
    }
    root = cJSON_Parse((const char *)blob);
    free(blob);
    if (root == NULL) {
        fprintf(stderr, "harness: %s is not valid JSON\n", path);
        return -1;
    }
    models = cJSON_GetObjectItemCaseSensitive(root, "models");
    node = cJSON_IsObject(models) ? cJSON_GetObjectItemCaseSensitive(models, "cram") : NULL;
    if (cJSON_IsObject(node)) {
        cJSON *sha = cJSON_GetObjectItemCaseSensitive(node, "sha256");
        if (cJSON_IsString(sha) && strlen(sha->valuestring) == 64) {
            memcpy(cram_hex, sha->valuestring, 65);
            rc = 0;
        }
    }
    if (rc == 0) {
        node = cJSON_GetObjectItemCaseSensitive(models, "cram_vocab");
        if (cJSON_IsObject(node)) {
            cJSON *sha = cJSON_GetObjectItemCaseSensitive(node, "sha256");
            if (cJSON_IsString(sha) && strlen(sha->valuestring) == 64) {
                memcpy(vocab_hex, sha->valuestring, 65);
            } else {
                rc = -1;
            }
        } else {
            rc = -1;
        }
    }
    if (rc != 0) fprintf(stderr, "harness: %s has no models.cram / models.cram_vocab sha256\n", path);
    cJSON_Delete(root);
    return rc;
}

/* The 48-state (cat, kind, adult) table lives in `cram.bin` at states_off. */
static int container_states(const char *path, unsigned char states[CRAM_N_STATES * 3]) {
    unsigned char *blob = NULL;
    size_t len = 0;
    uint32_t n_states, states_off;
    int rc = -1;

    if (read_whole_file(path, &blob, &len) != 0) return -1;
    if (len < 64 || memcmp(blob, CRAM_MAGIC, 4) != 0) goto done;
    n_states = le_u32(blob + 16);
    states_off = le_u32(blob + 48);
    if (n_states != CRAM_N_STATES) goto done;
    if ((size_t)states_off + 3u * CRAM_N_STATES > len) goto done;
    memcpy(states, blob + states_off, 3u * CRAM_N_STATES);
    rc = 0;
done:
    free(blob);
    return rc;
}

/* ------------------------------------------------------------------ */

int main(int argc, char **argv) {
    const char *dir;
    const char *rows_path;
    double threshold = 0.375;
    char cram_hex[CRAM_SHA_CAP] = {0};
    char vocab_hex[CRAM_SHA_CAP] = {0};
    char path[CRAM_PATH_CAP * 2];
    cram_digests_t digests;
    cram_model_t *model = NULL;
    cram_container_t *container = NULL;
    cram_status_t status = CRAM_OK;
    unsigned char states[CRAM_N_STATES * 3];
    FILE *rows;
    char *line = NULL;
    size_t line_cap = 0;
    ssize_t line_len;
    long long n_rows = 0, n_failed = 0, n_truncated = 0;

    if (argc < 3) {
        fprintf(stderr, "usage: %s <model_dir> <rows.jsonl> [adult_threshold]\n", argv[0]);
        return 1;
    }
    dir = argv[1];
    rows_path = argv[2];
    if (argc > 3) {
        char *end = NULL;
        double parsed = strtod(argv[3], &end);
        if (end == argv[3] || !(parsed > 0.0)) {
            fprintf(stderr, "harness: bad threshold %s\n", argv[3]);
            return 1;
        }
        threshold = parsed;
    }

    if (manifest_digests(dir, cram_hex, vocab_hex) != 0) return 1;

    snprintf(path, sizeof path, "%s/cram.bin", dir);
    digests.sha256_file = harness_sha256_file;
    digests.cram_sha256 = cram_hex;
    digests.vocab_sha256 = vocab_hex;
    model = cram_load(dir, &digests, &status);
    if (model == NULL) {
        fprintf(stderr, "harness: cram_load(%s) failed: %s (%d)\n", dir,
                cram_status_str(status), (int)status);
        return 1;
    }
    container = cram_container_load(path, &status);
    if (container == NULL) {
        fprintf(stderr, "harness: cram_container_load(%s) failed: %s (%d)\n", path,
                cram_status_str(status), (int)status);
        cram_free(model);
        return 1;
    }
    if (container_states(path, states) != 0) {
        fprintf(stderr, "harness: cannot read the 48-state table from %s\n", path);
        cram_container_free(container);
        cram_free(model);
        return 1;
    }

    fprintf(stderr, "harness: model %s verified (cram sha %s, vocab sha %s), threshold %.17g\n",
            dir, cram_hex, vocab_hex, threshold);

    rows = fopen(rows_path, "r");
    if (rows == NULL) {
        fprintf(stderr, "harness: cannot open %s\n", rows_path);
        cram_container_free(container);
        cram_free(model);
        return 1;
    }

    while ((line_len = getline(&line, &line_cap, rows)) > 0) {
        cJSON *obj, *id_node, *ft_node;
        const char *ft = "";
        long long id = 0;
        cram_row_t row;
        cram_verdict_t verdict;
        double unary[CRAM_N_OUTPUTS];
        double energy[CRAM_N_STATES];
        double best_e, second_e, gap, gap_rel;
        int best = 0, s;

        while (line_len > 0 && (line[line_len - 1] == '\n' || line[line_len - 1] == '\r')) {
            line[--line_len] = '\0';
        }
        if (line_len == 0) continue;

        obj = cJSON_Parse(line);
        if (obj == NULL) {
            fprintf(stderr, "harness: skipping a malformed row\n");
            n_failed++;
            continue;
        }
        id_node = cJSON_GetObjectItemCaseSensitive(obj, "id");
        ft_node = cJSON_GetObjectItemCaseSensitive(obj, "ft");
        if (cJSON_IsNumber(id_node)) id = (long long)id_node->valuedouble;
        if (cJSON_IsString(ft_node)) ft = ft_node->valuestring;

        memset(&row, 0, sizeof row);
        memset(&verdict, 0, sizeof verdict);
        if (cram_features(model, ft, &row) != CRAM_OK ||
            cram_predict(model, &row, threshold, &verdict) != CRAM_OK) {
            fprintf(stderr, "harness: row %lld failed to score\n", id);
            n_failed++;
            cJSON_Delete(obj);
            continue;
        }
        if (cram_container_unary(container, &row, unary) != CRAM_OK) {
            fprintf(stderr, "harness: row %lld has no unary\n", id);
            n_failed++;
            cJSON_Delete(obj);
            continue;
        }

        for (s = 0; s < (int)CRAM_N_STATES; s++) {
            unsigned int sc = states[3 * s + 0];
            unsigned int sk = states[3 * s + 1];
            unsigned int sa = states[3 * s + 2];
            energy[s] = unary[sc] + unary[CRAM_KIND_UNARY_BASE + sk] +
                        unary[CRAM_ADULT_UNARY_BASE + sa];
        }
        best = 0;
        for (s = 1; s < (int)CRAM_N_STATES; s++) {
            if (energy[s] < energy[best]) best = s;
        }
        best_e = energy[best];
        /* Second-smallest, so the near-tie review sees the gap the "first
         * minimum wins" rule had to decide between. */
        second_e = 1e300;
        for (s = 0; s < (int)CRAM_N_STATES; s++) {
            if (s == best) continue;
            if (energy[s] < second_e) second_e = energy[s];
        }
        gap = second_e - best_e;
        {
            double scale = best_e < 0.0 ? -best_e : best_e;
            double other = second_e < 0.0 ? -second_e : second_e;
            if (other > scale) scale = other;
            gap_rel = (scale > 0.0) ? gap / scale : gap;
        }

        if (row.truncated) n_truncated++;
        n_rows++;

        printf("{\"id\":%lld,\"category\":%d,\"kind\":%d,\"adult\":%d,\"adult_p\":%.17g,"
               "\"confidence\":%.17g,\"state\":%d,\"nfeat\":%d,\"truncated\":%d,"
               "\"gap\":%.17g,\"gap_rel\":%.17g,\"e\":[",
               id, verdict.category, verdict.kind, verdict.adult, verdict.adult_p,
               verdict.confidence, best, row.count, row.truncated, gap, gap_rel);
        for (s = 0; s < (int)CRAM_N_STATES; s++) {
            printf("%s%.17g", s ? "," : "", energy[s]);
        }
        printf("]}\n");

        cJSON_Delete(obj);
    }
    free(line);
    fclose(rows);

    fprintf(stderr, "harness: scored %lld rows (%lld failed, %lld feature-cap truncated)\n",
            n_rows, n_failed, n_truncated);

    cram_container_free(container);
    cram_free(model);
    return (n_failed == 0 && n_rows > 0) ? 0 : 1;
}
