/* App-side OpenSSL compatibility shim for dht_crawler (Stage 09).
 *
 * dht_crawler only uses OpenSSL for one-shot SHA1 (src/tree_metadata.c)
 * and the incremental SHA256 API (src/bep51_cache.c). This header maps
 * those OpenSSL APIs onto mbedTLS so the crawler builds without linking
 * OpenSSL. The compat include dir is listed FIRST in the CMake include
 * path, shadowing any real <openssl/sha.h>.
 */
#ifndef DHT_CRAWLER_COMPAT_OPENSSL_SHA_H
#define DHT_CRAWLER_COMPAT_OPENSSL_SHA_H

#include <stddef.h>

#include <mbedtls/sha1.h>
#include <mbedtls/sha256.h>

#ifndef SHA_DIGEST_LENGTH
#define SHA_DIGEST_LENGTH 20
#endif

#ifndef SHA256_DIGEST_LENGTH
#define SHA256_DIGEST_LENGTH 32
#endif

/* --- SHA1 (one-shot) -------------------------------------------------- */

/* Matches OpenSSL's:
 *   unsigned char *SHA1(const unsigned char *d, size_t n, unsigned char *md);
 */
static inline unsigned char *SHA1(const unsigned char *d, size_t n, unsigned char *md)
{
    (void)mbedtls_sha1(d, n, md);
    return md;
}

/* --- SHA256 (incremental + one-shot) ---------------------------------- */

typedef mbedtls_sha256_context SHA256_CTX;

/* OpenSSL returns 1 on success, 0 on failure; mbedTLS returns 0 on success. */
static inline int SHA256_Init(SHA256_CTX *ctx)
{
    mbedtls_sha256_init(ctx);
    return mbedtls_sha256_starts(ctx, 0) == 0;
}

static inline int SHA256_Update(SHA256_CTX *ctx, const void *data, size_t len)
{
    return mbedtls_sha256_update(ctx, (const unsigned char *)data, len) == 0;
}

static inline int SHA256_Final(unsigned char *md, SHA256_CTX *ctx)
{
    int ok = mbedtls_sha256_finish(ctx, md) == 0;
    mbedtls_sha256_free(ctx);
    return ok;
}

/* Matches OpenSSL's:
 *   unsigned char *SHA256(const unsigned char *d, size_t n, unsigned char *md);
 */
static inline unsigned char *SHA256(const unsigned char *d, size_t n, unsigned char *md)
{
    (void)mbedtls_sha256(d, n, md, 0);
    return md;
}

#endif /* DHT_CRAWLER_COMPAT_OPENSSL_SHA_H */
