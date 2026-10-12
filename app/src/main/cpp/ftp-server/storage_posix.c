/*
 * ftp-server posix storage backend.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 *
 * Serves a plain directory tree on a Linux host. relpath values are normalized
 * (no leading '/', no '.'/'..' segments), so joining with the root cannot
 * escape it.
 */
#define _GNU_SOURCE

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "storage.h"

typedef struct {
    char root[4096];
} posix_impl_t;

static int join_path(const posix_impl_t *impl, const char *rel, char *out, size_t outsz) {
    int n;
    if (rel == NULL || rel[0] == '\0')
        n = snprintf(out, outsz, "%s", impl->root);
    else
        n = snprintf(out, outsz, "%s/%s", impl->root, rel);
    return (n < 0 || (size_t)n >= outsz) ? -1 : 0;
}

static int posix_init(storage_backend_t *b, const char *root) {
    posix_impl_t *impl = calloc(1, sizeof *impl);
    if (impl == NULL)
        return -1;
    if (snprintf(impl->root, sizeof impl->root, "%s", root) >= (int)sizeof impl->root) {
        free(impl);
        return -1;
    }
    struct stat st;
    if (stat(impl->root, &st) != 0 || !S_ISDIR(st.st_mode)) {
        free(impl);
        return -1;
    }
    b->impl = impl;
    return 0;
}

static void posix_destroy(storage_backend_t *b) {
    free(b->impl);
    b->impl = NULL;
}

static int posix_stat(storage_backend_t *b, const char *rel, storage_stat_t *out) {
    const posix_impl_t *impl = b->impl;
    char path[8192];
    out->exists = 0;
    out->is_dir = 0;
    out->size = 0;
    out->mtime_ms = 0;
    if (join_path(impl, rel, path, sizeof path) != 0)
        return -1;
    struct stat st;
    if (stat(path, &st) != 0)
        return 0; /* not found */
    out->exists = 1;
    out->is_dir = S_ISDIR(st.st_mode) ? 1 : 0;
    out->size = (unsigned long long)st.st_size;
    out->mtime_ms = (long long)st.st_mtime * 1000LL;
    return 0;
}

static int cmp_entries(const void *a, const void *b) {
    const storage_entry_t *ea = a;
    const storage_entry_t *eb = b;
    return strcmp(ea->name, eb->name);
}

static int posix_list(storage_backend_t *b, const char *rel, int offset,
                      storage_entry_t *out, int cap, int *total) {
    const posix_impl_t *impl = b->impl;
    char path[8192];
    if (join_path(impl, rel, path, sizeof path) != 0)
        return -1;
    if (offset < 0)
        offset = 0;
    if (cap < 0)
        cap = 0;
    if (total != NULL)
        *total = 0;

    DIR *d = opendir(path);
    if (d == NULL)
        return -1;

    storage_entry_t *arr = NULL;
    size_t n = 0;
    size_t caparr = 0;
    struct dirent *de;
    int rc = 0;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0)
            continue;
        if (n == caparr) {
            caparr = caparr ? caparr * 2 : 64;
            storage_entry_t *na = realloc(arr, caparr * sizeof *na);
            if (na == NULL) {
                rc = -1;
                break;
            }
            arr = na;
        }
        storage_entry_t *e = &arr[n];
        snprintf(e->name, sizeof e->name, "%s", de->d_name);
        e->is_dir = 0;
        e->size = 0;
        e->mtime_ms = 0;
        char fpath[8192 + 512];
        if (snprintf(fpath, sizeof fpath, "%s/%s", path, de->d_name) < (int)sizeof fpath) {
            struct stat st;
            if (stat(fpath, &st) == 0) {
                e->is_dir = S_ISDIR(st.st_mode) ? 1 : 0;
                e->size = (unsigned long long)st.st_size;
                e->mtime_ms = (long long)st.st_mtime * 1000LL;
            }
        }
        n++;
    }
    closedir(d);
    if (rc != 0) {
        free(arr);
        return -1;
    }
    if (total != NULL)
        *total = (int)n;
    if (n > 0)
        qsort(arr, n, sizeof *arr, cmp_entries);
    int written = 0;
    for (size_t i = (size_t)offset; i < n && written < cap; i++)
        out[written++] = arr[i];
    free(arr);
    return written;
}

static int posix_open_read(storage_backend_t *b, const char *rel, int *fd_out) {
    const posix_impl_t *impl = b->impl;
    char path[8192];
    if (join_path(impl, rel, path, sizeof path) != 0)
        return -1;
    int fd = open(path, O_RDONLY);
    if (fd < 0)
        return -1;
    *fd_out = fd;
    return 0;
}

static int posix_remove(storage_backend_t *b, const char *rel) {
    const posix_impl_t *impl = b->impl;
    char path[8192];
    if (join_path(impl, rel, path, sizeof path) != 0)
        return -1;
    return unlink(path) == 0 ? 0 : -1;
}

static int posix_rmdir(storage_backend_t *b, const char *rel) {
    const posix_impl_t *impl = b->impl;
    char path[8192];
    if (join_path(impl, rel, path, sizeof path) != 0)
        return -1;
    return rmdir(path) == 0 ? 0 : -1;
}

const storage_ops_t storage_posix_ops = {
    .init = posix_init,
    .destroy = posix_destroy,
    .stat = posix_stat,
    .list = posix_list,
    .open_read = posix_open_read,
    .remove = posix_remove,
    .rmdir = posix_rmdir,
};
