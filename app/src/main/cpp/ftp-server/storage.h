/*
 * ftp-server storage abstraction.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 *
 * Backend-agnostic storage interface. relpath is a normalized path relative to
 * the served root: "" = root, no leading '/', no '.'/'..' segments, no trailing
 * '/'. Both the posix backend (this stage) and the SAF backend (stage 2)
 * implement this interface; keep it free of platform-specific types where
 * practical (a raw fd is acceptable — both backends produce fds).
 */
#ifndef FTPD_STORAGE_H
#define FTPD_STORAGE_H

typedef struct {
    int exists;
    int is_dir;
    unsigned long long size;
    long long mtime_ms; /* UTC milliseconds since epoch */
} storage_stat_t;

typedef struct {
    char name[512];
    int is_dir;
    unsigned long long size;
    long long mtime_ms; /* UTC milliseconds since epoch */
} storage_entry_t;

typedef struct storage_backend storage_backend_t;

typedef struct {
    /* Initialize the backend for the given root. 0 on success, -1 on error. */
    int (*init)(storage_backend_t *b, const char *root);
    void (*destroy)(storage_backend_t *b);

    /* 0 on success (out->exists == 0 when the path does not exist), -1 on I/O error. */
    int (*stat)(storage_backend_t *b, const char *relpath, storage_stat_t *out);

    /*
     * Directory listing batch starting at `offset` into the sorted entry list.
     * Returns the number of entries written (>= 0), -1 on error.
     * *total receives the total number of entries in the directory.
     */
    int (*list)(storage_backend_t *b, const char *relpath, int offset,
                storage_entry_t *out, int cap, int *total);

    /* Open a file for reading. Returns 0 and sets *fd_out, or -1 on error. */
    int (*open_read)(storage_backend_t *b, const char *relpath, int *fd_out);

    /* Remove a regular file. 0 on success, -1 on error. */
    int (*remove)(storage_backend_t *b, const char *relpath);

    /* Remove an empty directory. 0 on success, -1 on error. */
    int (*rmdir)(storage_backend_t *b, const char *relpath);
} storage_ops_t;

struct storage_backend {
    const storage_ops_t *ops;
    void *impl;
};

extern const storage_ops_t storage_posix_ops;
#if defined(FTPD_STORAGE_SAF)
extern const storage_ops_t storage_saf_ops;
#define FTPD_STORAGE_OPS (&storage_saf_ops)
#else
#define FTPD_STORAGE_OPS (&storage_posix_ops)
#endif

#endif /* FTPD_STORAGE_H */
