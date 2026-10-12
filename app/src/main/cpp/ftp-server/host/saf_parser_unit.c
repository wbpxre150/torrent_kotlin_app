/*
 * Host-only unit test for the SAF OP_RO_LIST batch parser
 * (append_batch_entries in storage_saf.c).
 *
 * Built by the host CMake as `saf-parser-unit`; never part of the Android
 * build. It includes storage_saf.c directly (with `static` lifted) so the
 * parser can be exercised in-memory without a socket.
 *
 * Wire format per SafIpcCodec.roListPayload (little-endian):
 *   [u32 nextIndex][u16 count]
 *   per entry: [u16 nameLen][name utf-8][u8 isDir][u64 size][i64 mtimeMs]
 */
/* storage_saf.c is included FIRST: its `#define _GNU_SOURCE` must precede
 * all system headers, and it brings stdio/stdlib/string/stdint itself. */
#define static
#include "../storage_saf.c"
#undef static

/* Referenced by saf_init in the included TU; unused by the parser. */
ftpd_config_t g_config;

#ifdef FTPD_SAF_PARSER_UNIT

static int failures = 0;

static void check(int cond, const char *what) {
    if (cond) {
        printf("ok: %s\n", what);
    } else {
        printf("FAIL: %s\n", what);
        failures++;
    }
}

/* Append one wire-format entry to p; returns the number of bytes written. */
static size_t put_entry(uint8_t *p, const char *name, int is_dir,
                        uint64_t size, long long mtime_ms) {
    size_t nlen = strlen(name);
    le_u16(p, (uint16_t)nlen);
    memcpy(p + 2, name, nlen);
    p[2 + nlen] = (uint8_t)(is_dir ? 1 : 0);
    for (int i = 0; i < 8; i++)
        p[3 + nlen + i] = (uint8_t)((size >> (8 * i)) & 0xff);
    uint64_t mt = (uint64_t)mtime_ms;
    for (int i = 0; i < 8; i++)
        p[11 + nlen + i] = (uint8_t)((mt >> (8 * i)) & 0xff);
    return 2 + nlen + 17;
}

static int run(void) {
    /* 1. Two-entry batch: names, flags, sizes, mtimes decode in order. */
    {
        uint8_t buf[512];
        size_t off = 0;
        le_u32(buf + off, 0xFFFFFFFFu); off += 4; /* nextIndex = END */
        le_u16(buf + off, 2); off += 2;           /* count */
        off += put_entry(buf + off, "sub dir", 1, 0, 1700000000000LL);
        off += put_entry(buf + off, "h\xc3\xa9llo w\xc3\xb6rld.txt", 0, 42,
                         1700000001234LL);

        storage_entry_t *arr = NULL;
        size_t n = 0, cap = 0;
        int rc = append_batch_entries(buf, off, &arr, &n, &cap);
        check(rc == 0, "two-entry batch parses");
        check(n == 2, "two entries decoded");
        if (n == 2) {
            check(strcmp(arr[0].name, "sub dir") == 0, "entry 0 name");
            check(arr[0].is_dir == 1, "entry 0 is_dir");
            check(arr[0].size == 0, "entry 0 size");
            check(arr[0].mtime_ms == 1700000000000LL, "entry 0 mtime");
            check(strcmp(arr[1].name, "h\xc3\xa9llo w\xc3\xb6rld.txt") == 0,
                  "entry 1 utf-8 name");
            check(arr[1].is_dir == 0, "entry 1 not a dir");
            check(arr[1].size == 42, "entry 1 size");
            check(arr[1].mtime_ms == 1700000001234LL, "entry 1 mtime");
        }
        free(arr);
    }

    /* 2. Overlong name: truncated to 511 + NUL, and the FOLLOWING entry
     *    still decodes correctly — the desync the P0 parser fix addresses. */
    {
        uint8_t buf[2048];
        char longname[600];
        memset(longname, 'a', sizeof longname - 1);
        longname[sizeof longname - 1] = '\0';
        size_t off = 0;
        le_u32(buf + off, 1); off += 4; /* nextIndex = 1 (not END) */
        le_u16(buf + off, 2); off += 2;
        off += put_entry(buf + off, longname, 0, 7, 5LL);
        off += put_entry(buf + off, "after", 1, 9, 6LL);

        storage_entry_t *arr = NULL;
        size_t n = 0, cap = 0;
        int rc = append_batch_entries(buf, off, &arr, &n, &cap);
        check(rc == 0, "overlong-name batch parses");
        check(n == 2, "overlong: two entries decoded");
        if (n == 2) {
            check(strlen(arr[0].name) == 511, "overlong name truncated to 511");
            check(arr[0].name[511] == '\0', "overlong name NUL-terminated");
            check(strcmp(arr[1].name, "after") == 0,
                  "entry after overlong name intact");
            check(arr[1].is_dir == 1 && arr[1].size == 9 &&
                      arr[1].mtime_ms == 6LL,
                  "entry after overlong: flags/size/mtime");
        }
        free(arr);
    }

    /* 3. Zero-entry batch. */
    {
        uint8_t buf[8];
        le_u32(buf, 0xFFFFFFFFu);
        le_u16(buf + 4, 0);
        storage_entry_t *arr = NULL;
        size_t n = 0, cap = 0;
        int rc = append_batch_entries(buf, sizeof buf, &arr, &n, &cap);
        check(rc == 0 && n == 0, "zero-entry batch");
        free(arr);
    }

    /* 4. Truncated trailing entry is rejected. */
    {
        uint8_t buf[64];
        size_t off = 0;
        le_u32(buf + off, 0xFFFFFFFFu); off += 4;
        le_u16(buf + off, 1); off += 2;
        off += put_entry(buf + off, "trunc", 0, 1, 1LL);
        off -= 3; /* chop 3 bytes off the mtime */
        storage_entry_t *arr = NULL;
        size_t n = 0, cap = 0;
        int rc = append_batch_entries(buf, off, &arr, &n, &cap);
        check(rc == -1, "truncated batch rejected");
        free(arr);
    }

    return failures;
}

int main(void) {
    int f = run();
    if (f != 0) {
        printf("saf parser unit: %d FAILURE(S)\n", f);
        return 1;
    }
    printf("saf parser unit: all checks passed\n");
    return 0;
}

#endif /* FTPD_SAF_PARSER_UNIT */
