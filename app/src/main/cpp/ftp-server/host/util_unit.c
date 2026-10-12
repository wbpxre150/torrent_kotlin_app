/*
 * Host-only unit test for the relpath helpers in util.c
 * (normalize_relpath, join_relpath).
 *
 * Built by the host CMake as `util-unit`; never part of the Android
 * build. It includes util.c directly (with `static` lifted) so the
 * helpers can be exercised in-memory without a socket.
 */
/* util.c is included FIRST: its `#define _GNU_SOURCE` must precede
 * all system headers, and it brings stdio/stdlib/string/stdint itself. */
#define static
#include "../util.c"
#undef static

#ifdef FTPD_UTIL_UNIT

static int failures = 0;

static void check(int cond, const char *what) {
    if (cond) {
        printf("ok: %s\n", what);
    } else {
        printf("FAIL: %s\n", what);
        failures++;
    }
}

/* Assert normalize_relpath(in, out, sz) returns `want_rc` and, on
 * success, writes exactly `want`. */
static void check_norm(const char *in, size_t sz, int want_rc, const char *want) {
    char out[512];
    memset(out, 0xff, sizeof out);
    out[0] = '\0';
    int rc = normalize_relpath(in, out, sz);
    check(rc == want_rc, "normalize return code");
    if (rc == 0 && strcmp(out, want) != 0) {
        printf("FAIL: normalize output (got '%s', want '%s')\n", out, want);
        failures++;
    }
}

/* Assert join_relpath(cwd, arg, out, sz) returns `want_rc` and, on
 * success, writes exactly `want`. */
static void check_join(const char *cwd, const char *arg, int want_rc,
                       const char *want) {
    char out[512];
    memset(out, 0xff, sizeof out);
    out[0] = '\0';
    int rc = join_relpath(cwd, arg, out, sizeof out);
    check(rc == want_rc, "join return code");
    if (rc == 0 && strcmp(out, want) != 0) {
        printf("FAIL: join output (got '%s', want '%s')\n", out, want);
        failures++;
    }
}

static int run(void) {
    /* 1. normalize_relpath: plain and empty inputs. */
    check_norm("", 512, 0, "");
    check_norm("a", 512, 0, "a");
    check_norm("a/b", 512, 0, "a/b");

    /* 2. Separator handling: the busy-loop regression. */
    check_norm("a//b", 512, 0, "a/b");
    check_norm("/a/b/", 512, 0, "a/b");
    check_norm("a/b///c", 512, 0, "a/b/c");
    check_norm("////", 512, 0, "");

    /* 3. '.' segments are skipped. */
    check_norm(".", 512, 0, "");
    check_norm("a/./b", 512, 0, "a/b");
    check_norm("./a", 512, 0, "a");

    /* 4. '..' clamps to the root. */
    check_norm("/../x", 512, 0, "x");
    check_norm("a/b/../..", 512, 0, "");
    check_norm("a/b/../../c", 512, 0, "c");

    /* 5. Overflow is rejected (-1), including outsz == 0. */
    check_norm("a/bcdefghij", 4, -1, NULL);
    check_norm("a", 0, -1, NULL);

    /* 6. NULL input is the root. */
    check_norm(NULL, 512, 0, "");

    /* 7. join_relpath: cwd/arg edge cases. */
    check_join("", "x", 0, "x");
    check_join("a", "/b", 0, "a/b");
    check_join("a", "", 0, "a");
    check_join(NULL, "x", 0, "x");
    check_join("a", NULL, 0, "a");
    check_join("a/b", "../c", 0, "a/c");
    check_join("a", "b//./c", 0, "a/b/c");

    /* 8. join_relpath: overflow is rejected. */
    {
        char big[4200];
        memset(big, 'x', sizeof big - 1);
        big[sizeof big - 1] = '\0';
        char out[512];
        int rc = join_relpath("a", big, out, sizeof out);
        check(rc == -1, "join overflow rejected");
    }

    return failures;
}

int main(void) {
    int f = run();
    if (f != 0) {
        printf("util unit: %d FAILURE(S)\n", f);
        return 1;
    }
    printf("util unit: all checks passed\n");
    return 0;
}

#endif /* FTPD_UTIL_UNIT */
