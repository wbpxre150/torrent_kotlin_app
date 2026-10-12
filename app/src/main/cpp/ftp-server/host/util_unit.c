/*
 * Host-only unit test for the relpath helpers in util.c
 * (normalize_relpath, join_relpath) and the RFC 3659 MLST/MLSD fact
 * formatters / OPTS parser.
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

/* Assert format_mlsx_facts(...) returns `want_rc` and, on success, writes
 * exactly `want`. */
static void check_mlsx(int is_dir, uint64_t size, int64_t mtime_ms,
                       unsigned mask, int want_rc, const char *want) {
    char out[128];
    memset(out, 0xff, sizeof out);
    out[0] = '\0';
    int rc = format_mlsx_facts(out, sizeof out, is_dir, size, mtime_ms, mask);
    check(rc == want_rc, "mlsx facts return code");
    if (rc == 0 && strcmp(out, want) != 0) {
        printf("FAIL: mlsx facts output (got '%s', want '%s')\n", out, want);
        failures++;
    }
}

/* Assert a facts/feat formatter returned 0 and wrote exactly `want`. */
static void check_mlst_str(int rc, const char *out, const char *want,
                           const char *what) {
    check(rc == 0, what);
    if (rc == 0 && strcmp(out, want) != 0) {
        printf("FAIL: %s (got '%s', want '%s')\n", what, out, want);
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
    check_join("a", "", 0, "a");
    check_join(NULL, "x", 0, "x");
    check_join("a", NULL, 0, "a");
    check_join("a/b", "../c", 0, "a/c");
    check_join("a", "b//./c", 0, "a/b/c");

    /* Absolute arguments ignore cwd and are normalized alone. */
    check_join("a", "/b", 0, "b");
    check_join("a/b", "/", 0, "");
    check_join("a/b", "/x/y", 0, "x/y");
    check_join("a", "b", 0, "a/b");
    check_join("a", "../c", 0, "c");
    check_join("", "/[x] y", 0, "[x] y");

    /* 8. join_relpath: overflow is rejected. */
    {
        char big[4200];
        memset(big, 'x', sizeof big - 1);
        big[sizeof big - 1] = '\0';
        char out[512];
        int rc = join_relpath("a", big, out, sizeof out);
        check(rc == -1, "join overflow rejected");
    }

    /* 9. RFC 3659 MLST/MLSD facts. 1700000000 s == 2023-11-14 22:13:20 UTC. */
    check_mlsx(0, 1234, 1700000000000LL, MLSX_FACT_ALL, 0,
               "type=file;size=1234;modify=20231114221320;perm=rd;");
    check_mlsx(1, 999, 1700000000000LL, MLSX_FACT_ALL, 0,
               "type=dir;modify=20231114221320;perm=elpd;");
    check_mlsx(1, 999, 1700000000000LL, MLSX_FACT_TYPE | MLSX_FACT_SIZE, 0,
               "type=dir;");
    check_mlsx(0, 1234, 1700000000000LL, 0, 0, "");
    check_mlsx(0, 0, 0LL, MLSX_FACT_TYPE, 0, "type=file;");
    check_mlsx(1, 0, 0LL, MLSX_FACT_PERM, 0, "perm=elpd;");
    check_mlsx(0, 0, 0LL, MLSX_FACT_MODIFY, 0, "modify=19700101000000;");

    /* 10. mlsx facts: truncation is rejected atomically. */
    {
        char small[5] = {0, 0, 0, 0, 0};
        int rc = format_mlsx_facts(small, sizeof small, 0, 1234,
                                   1700000000000LL, MLSX_FACT_ALL);
        check(rc == -1, "mlsx facts truncation rejected");
        check(small[0] == '\0', "mlsx facts output untouched on truncation");
        /* 66 chars + NUL is the worst case (20-digit size). */
        char tight[67];
        check(format_mlsx_facts(tight, sizeof tight, 0, UINT64_MAX,
                                1700000000000LL, MLSX_FACT_ALL) == 0,
              "mlsx facts fit exactly");
        char tight2[66];
        check(format_mlsx_facts(tight2, sizeof tight2, 0, UINT64_MAX,
                                1700000000000LL, MLSX_FACT_ALL) == -1,
              "mlsx facts one byte short rejected");
    }

    /* 11. parse_mlst_opts. */
    check(parse_mlst_opts("type;size;") == (MLSX_FACT_TYPE | MLSX_FACT_SIZE),
          "opts parse type;size;");
    check(parse_mlst_opts("Type;Modify") == (MLSX_FACT_TYPE | MLSX_FACT_MODIFY),
          "opts parse mixed case");
    check(parse_mlst_opts("type;size;modify;perm") == MLSX_FACT_ALL,
          "opts parse all without trailing ';'");
    check(parse_mlst_opts("type;foo;size;") == (MLSX_FACT_TYPE | MLSX_FACT_SIZE),
          "opts parse unknown facts ignored");
    check(parse_mlst_opts("") == 0, "opts parse empty");
    check(parse_mlst_opts(NULL) == 0, "opts parse NULL");
    check(parse_mlst_opts(";;") == 0, "opts parse only separators");
    check(parse_mlst_opts("siz") == 0, "opts parse near-miss short");
    check(parse_mlst_opts("typetype") == 0, "opts parse near-miss long");

    /* 12. format_mlst_feat and format_mlst_opts_list. */
    {
        char feat[64];
        int rc = format_mlst_feat(feat, sizeof feat, MLSX_FACT_ALL);
        check_mlst_str(rc, feat, "MLST type*;size*;modify*;perm*;",
                       "feat line full mask");
        rc = format_mlst_feat(feat, sizeof feat, MLSX_FACT_TYPE | MLSX_FACT_SIZE);
        check_mlst_str(rc, feat, "MLST type*;size*;modify;perm;",
                       "feat line partial mask");
        rc = format_mlst_feat(feat, sizeof feat, 0);
        check_mlst_str(rc, feat, "MLST type;size;modify;perm;",
                       "feat line no mask");
        char feat_small[4];
        check(format_mlst_feat(feat_small, sizeof feat_small, MLSX_FACT_ALL) == -1,
              "feat line truncation rejected");

        char list[32];
        rc = format_mlst_opts_list(list, sizeof list,
                                   MLSX_FACT_TYPE | MLSX_FACT_SIZE);
        check_mlst_str(rc, list, "type;size;", "opts list enabled facts");
        rc = format_mlst_opts_list(list, sizeof list, MLSX_FACT_ALL);
        check_mlst_str(rc, list, "type;size;modify;perm;", "opts list all facts");
        rc = format_mlst_opts_list(list, sizeof list, 0);
        check_mlst_str(rc, list, "", "opts list empty mask");
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
