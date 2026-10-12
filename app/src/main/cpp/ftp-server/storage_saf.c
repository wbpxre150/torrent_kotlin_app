/*
 * ftp-server SAF storage backend.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 *
 * Serves the app's SAF USB tree to the FTP daemon (through the relpath prefix
 * stored from --root) via the Kotlin
 * SafIpcServer's read-only path (stage 2). Speaks the shared IPC wire
 * protocol (SafIpcCodec) over the filesystem Unix socket named by
 * --saf-socket (g_config.saf_socket):
 *
 *   Request  frame: [u8 op][u16 payloadLen][payload]
 *   Response frame: [i32 status][u16 payloadLen][payload]   (little-endian)
 *   Strings are [u16 len][utf-8 bytes].
 *
 * Ops: OP_RO_STAT (10), OP_RO_LIST (11, batched), OP_RO_OPEN (12, fd via
 * SCM_RIGHTS on success), OP_REMOVE (5, covers DELE and empty-dir RMD).
 *
 * Connection model: one ephemeral AF_UNIX connection per storage call
 * (connect, send, receive, close). FTP runs one thread per session, so a
 * shared persistent socket would need locking; a unix-domain connect costs
 * microseconds against the millisecond-scale SAF ContentResolver queries, so
 * per-call connections are the simpler, lock-free choice.
 *
 * The process NEVER exits on IPC failure: every transport error maps to -1
 * for that call (the session answers 450/550 and moves on).
 */
#define _GNU_SOURCE

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include "ftpd.h"
#include "storage.h"

#define SAF_RESP_MAX 65535u
#define SAF_REQ_MAX (2048u)
#define SAF_OP_REMOVE 5
#define SAF_OP_RO_STAT 10
#define SAF_OP_RO_LIST 11
#define SAF_OP_RO_OPEN 12
#define SAF_RO_LIST_END 0xFFFFFFFFu
#define SAF_CONN_RETRIES 5
#define SAF_CONN_RETRY_US 200000
/* IPC round-trip deadline (seconds), applied as SO_RCVTIMEO/SO_SNDTIMEO on
 * every per-call connection. Must exceed the Kotlin responder
 * (FtpSafIpcRequestHandler)'s 30s gateway semaphore cap so normal slow SAF
 * queries never trip it; a trip means the responder is genuinely stalled
 * or dead, in which case the call maps to -1 and the session answers
 * 450/550 — the correct FTP degradation instead of a hung session thread.
 * Overridable at runtime via the FTPD_SAF_IO_TIMEOUT_S env var (parsed in
 * saf_init) so tests can exercise a stalled responder in seconds. */
#define SAF_IO_TIMEOUT_S 35

typedef struct {
    char socket[4096];
    /* Normalized --root value as a relpath prefix under the SAF tree root;
     * empty string = serve the SAF tree root itself. */
    char root_prefix[4096];
    int io_timeout_s;
} saf_impl_t;

/* ---- little-endian (de)serialization, host-endianness independent ---- */

static void le_u16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
}

static uint16_t le_u16_get(const uint8_t *p) {
    return (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
}

static void le_u32(uint8_t *p, uint32_t v) {
    for (int i = 0; i < 4; i++)
        p[i] = (uint8_t)((v >> (8 * i)) & 0xff);
}

static uint32_t le_u32_get(const uint8_t *p) {
    uint32_t v = 0;
    for (int i = 0; i < 4; i++)
        v |= (uint32_t)p[i] << (8 * i);
    return v;
}

static uint64_t le_u64_get(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++)
        v |= (uint64_t)p[i] << (8 * i);
    return v;
}

/* ---- transport ---- */

static int write_full(int fd, const void *buf, size_t n) {
    const char *p = buf;
    size_t off = 0;
    while (off < n) {
        ssize_t w = write(fd, p + off, n - off);
        if (w < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        off += (size_t)w;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t n) {
    char *p = buf;
    size_t off = 0;
    while (off < n) {
        ssize_t r = read(fd, p + off, n - off);
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (r == 0)
            return -1;
        off += (size_t)r;
    }
    return 0;
}

static int saf_connect(const saf_impl_t *impl) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    /* Bound the round trip: a stalled/dead responder must degrade to a
     * transport error (the next read/write fails, saf_call maps it to -1,
     * the session answers 450/550), never hang the session thread. The
     * deadline sits above the responder's 30s gateway cap, so a healthy
     * but slow SAF query can never trip it (see SAF_IO_TIMEOUT_S). */
    struct timeval tv;
    tv.tv_sec = impl->io_timeout_s;
    tv.tv_usec = 0;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv) != 0 ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv) != 0) {
        close(fd);
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    size_t plen = strlen(impl->socket);
    if (plen + 1 > sizeof addr.sun_path) {
        close(fd);
        return -1;
    }
    memcpy(addr.sun_path, impl->socket, plen);
    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + plen);
    /* Bounded retry: the app (SafIpcServer) binds at service start, so a
     * daemon restart can race the socket file appearing. */
    for (int attempt = 0; attempt < SAF_CONN_RETRIES; attempt++) {
        if (connect(fd, (struct sockaddr *)&addr, alen) == 0)
            return fd;
        if (errno != ECONNREFUSED && errno != ENOENT)
            break; /* not a startup race: fail fast */
        if (attempt + 1 < SAF_CONN_RETRIES)
            usleep(SAF_CONN_RETRY_US);
    }
    close(fd);
    return -1;
}

static int saf_send(int fd, int op, const uint8_t *payload, size_t len) {
    uint8_t hdr[3];
    hdr[0] = (uint8_t)op;
    le_u16(hdr + 1, (uint16_t)len);
    if (write_full(fd, hdr, sizeof hdr) != 0)
        return -1;
    if (len > 0 && write_full(fd, payload, len) != 0)
        return -1;
    return 0;
}

/* Receive a response frame. Returns 0 and sets *status and *plen; -1 on error. */
static int saf_recv(int fd, int32_t *status, uint8_t *payload, size_t cap,
                    size_t *plen) {
    uint8_t hdr[6];
    if (read_full(fd, hdr, sizeof hdr) != 0)
        return -1;
    *status = (int32_t)le_u32_get(hdr);
    size_t len = le_u16_get(hdr + 4);
    if (len > cap)
        return -1;
    if (len > 0 && read_full(fd, payload, len) != 0)
        return -1;
    *plen = len;
    return 0;
}

/* Receive a response frame that may carry one fd via SCM_RIGHTS. */
static int saf_recv_with_fd(int fd, int32_t *status, int *fd_out) {
    uint8_t hdr[6];
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct msghdr msg;
    memset(&msg, 0, sizeof msg);
    memset(cbuf, 0, sizeof cbuf);
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof cbuf;
    /* The 6-byte header may arrive in pieces on the stream socket, so loop
     * like read_full (EINTR continues, peer close is an error). A fd, if any,
     * rides as SCM_RIGHTS ancillary data on the FIRST datagram, so cbuf is
     * captured on the first recvmsg and only the remaining header bytes are
     * read on later iterations. */
    size_t off = 0;
    while (off < sizeof hdr) {
        struct iovec io;
        io.iov_base = hdr + off;
        io.iov_len = sizeof hdr - off;
        msg.msg_iov = &io;
        msg.msg_iovlen = 1;
        ssize_t r = recvmsg(fd, &msg, 0);
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (r == 0)
            return -1; /* peer closed mid-header */
        off += (size_t)r;
    }
    *status = (int32_t)le_u32_get(hdr);
    size_t len = le_u16_get(hdr + 4);
    if (len > 0) {
        /* OP_RO_OPEN carries no payload bytes; drain defensively. */
        uint8_t drain[512];
        size_t doff = 0;
        while (doff < len) {
            size_t chunk = len - doff > sizeof drain ? sizeof drain : len - doff;
            if (read_full(fd, drain, chunk) != 0)
                return -1;
            doff += chunk;
        }
    }
    *fd_out = -1;
    for (struct cmsghdr *cm = CMSG_FIRSTHDR(&msg); cm != NULL;
         cm = CMSG_NXTHDR(&msg, cm)) {
        if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS &&
            cm->cmsg_len >= (socklen_t)CMSG_LEN(sizeof(int))) {
            memcpy(fd_out, CMSG_DATA(cm), sizeof(int));
            break;
        }
    }
    return 0;
}

/*
 * One round trip on a fresh connection. On success returns 0 and hands the
 * caller a malloc'd response payload (freed by the caller); on any transport
 * failure returns -1.
 */
static int saf_call(const saf_impl_t *impl, int op, const uint8_t *req,
                    size_t reqlen, int32_t *status, uint8_t **payload_out,
                    size_t *plen_out) {
    *payload_out = NULL;
    *plen_out = 0;
    int fd = saf_connect(impl);
    if (fd < 0)
        return -1;
    uint8_t *payload = malloc(SAF_RESP_MAX);
    int rc = -1;
    if (payload != NULL && saf_send(fd, op, req, reqlen) == 0 &&
        saf_recv(fd, status, payload, SAF_RESP_MAX, plen_out) == 0) {
        *payload_out = payload;
        rc = 0;
    } else {
        free(payload);
    }
    close(fd);
    return rc;
}

/* Build a [u16 len][bytes] string payload; 0 on overflow. */
static size_t build_string_payload(uint8_t *out, size_t cap, const char *s) {
    size_t len = strlen(s);
    if (2 + len > cap)
        return 0;
    le_u16(out, (uint16_t)len);
    memcpy(out + 2, s, len);
    return 2 + len;
}

/* ---- root prefix ---- */

/*
 * Normalize --root into a relpath PREFIX under the SAF tree root: strip
 * leading/trailing '/' and collapse '//' runs. Empty input (or all slashes)
 * yields "" = serve the SAF tree root. 0 on success, -1 on overflow.
 */
static int normalize_prefix(const char *in, char *out, size_t outsz) {
    size_t n = 0;
    const char *p = in;
    while (*p == '/')
        p++;
    while (*p != '\0') {
        if (*p == '/') {
            p++;
            while (*p == '/')
                p++; /* a trailing run is simply dropped */
        } else {
            if (n + 1 >= outsz)
                return -1;
            out[n++] = *p++;
        }
    }
    out[n] = '\0';
    return 0;
}

/* Join the root prefix with a normalized relpath into `out`:
 * "<prefix>/<rel>" (rel non-empty), "<prefix>" (rel empty), no trailing '/'. */
static int saf_join(const saf_impl_t *impl, const char *rel, char *out, size_t outsz) {
    int n;
    if (impl->root_prefix[0] == '\0')
        n = snprintf(out, outsz, "%s", rel);
    else if (rel[0] == '\0')
        n = snprintf(out, outsz, "%s", impl->root_prefix);
    else
        n = snprintf(out, outsz, "%s/%s", impl->root_prefix, rel);
    return (n < 0 || (size_t)n >= outsz) ? -1 : 0;
}
/* ---- backend vtable ---- */

static int saf_init(storage_backend_t *b, const char *root) {
    if (g_config.saf_socket[0] == '\0')
        return -1;
    saf_impl_t *impl = calloc(1, sizeof *impl);
    if (impl == NULL)
        return -1;
    /* IPC deadline: SAF_IO_TIMEOUT_S default, overridable via the
     * FTPD_SAF_IO_TIMEOUT_S env var so tests can exercise a stalled
     * responder in seconds (1..3600; invalid values keep the default). */
    impl->io_timeout_s = SAF_IO_TIMEOUT_S;
    const char *ovr = getenv("FTPD_SAF_IO_TIMEOUT_S");
    if (ovr != NULL) {
        char *end = NULL;
        long v = strtol(ovr, &end, 10);
        if (end != ovr && *end == '\0' && v >= 1 && v <= 3600)
            impl->io_timeout_s = (int)v;
    }
    if (snprintf(impl->socket, sizeof impl->socket, "%s", g_config.saf_socket)
        >= (int)sizeof impl->socket) {
        free(impl);
        return -1;
    }
    /* The app's FTP root is a relpath under the SAF tree root (the shared data
     * dir, e.g. "torrent_data"); "" serves the tree root itself. */
    if (normalize_prefix(root, impl->root_prefix, sizeof impl->root_prefix) != 0) {
        free(impl);
        return -1;
    }
    b->impl = impl;
    return 0;
}

static void saf_destroy(storage_backend_t *b) {
    free(b->impl);
    b->impl = NULL;
}

static int saf_stat(storage_backend_t *b, const char *rel, storage_stat_t *out) {
    const saf_impl_t *impl = b->impl;
    out->exists = 0;
    out->is_dir = 0;
    out->size = 0;
    out->mtime_ms = 0;

    char path[4096 + 512];
    if (saf_join(impl, rel, path, sizeof path) != 0)
        return -1;
    uint8_t req[SAF_REQ_MAX];
    size_t reqlen = build_string_payload(req, sizeof req, path);
    if (reqlen == 0)
        return -1;
    int32_t status = 0;
    uint8_t *payload = NULL;
    size_t plen = 0;
    int rc = -1;
    if (saf_call(impl, SAF_OP_RO_STAT, req, reqlen, &status, &payload, &plen) == 0) {
        if (status == -2)
            rc = 0; /* not exists */
        else if (status == 0 && plen >= 17) {
            out->exists = 1;
            out->is_dir = payload[0] == 1 ? 1 : 0;
            out->size = le_u64_get(payload + 1);
            out->mtime_ms = (long long)le_u64_get(payload + 9);
            rc = 0;
        }
        /* status -11/-19/-5/other -> -1 (error) */
    }
    free(payload);
    return rc;
}

/*
 * Append the entries of one OP_RO_LIST batch payload to the realloc-grown
 * array (*arr / *n / *caparr). Per entry: [u16 nameLen LE][name utf-8]
 * [u8 isDir][u64 size LE][i64 mtimeMs LE]. 0 on success; -1 on a malformed
 * batch (the array is left in a valid, freeable state).
 */
static int append_batch_entries(const uint8_t *payload, size_t plen,
                                storage_entry_t **arr, size_t *n,
                                size_t *caparr) {
    if (plen < 6)
        return -1;
    uint16_t count = le_u16_get(payload + 4);
    const uint8_t *p = payload + 6;
    size_t rem = plen - 6;
    for (uint16_t i = 0; i < count; i++) {
        if (rem < 2)
            return -1;
        uint16_t nlen = le_u16_get(p);
        p += 2;
        rem -= 2;
        if (rem < (size_t)nlen + 17)
            return -1;
        if (*n == *caparr) {
            *caparr = *caparr ? *caparr * 2 : 64;
            storage_entry_t *na = realloc(*arr, *caparr * sizeof *na);
            if (na == NULL)
                return -1;
            *arr = na;
        }
        storage_entry_t *e = &(*arr)[*n];
        memset(e, 0, sizeof *e);
        size_t copy = nlen < sizeof e->name ? nlen : sizeof e->name - 1;
        memcpy(e->name, p, copy);
        e->name[copy] = '\0';
        p += nlen;
        rem -= nlen;
        e->is_dir = *p == 1 ? 1 : 0;
        p += 1;
        e->size = le_u64_get(p);
        p += 8;
        e->mtime_ms = (long long)le_u64_get(p);
        p += 8;
        rem -= 17;
        (*n)++;
    }
    return 0;
}

/*
 * Fetch the full sorted listing (all OP_RO_LIST batches) into a local array,
 * then hand back the window [offset, offset+cap) — the pagination contract
 * session.c consumes.
 */
static int saf_list(storage_backend_t *b, const char *rel, int offset,
                    storage_entry_t *out, int cap, int *total) {
    const saf_impl_t *impl = b->impl;
    if (offset < 0)
        offset = 0;
    if (cap < 0)
        cap = 0;
    if (total != NULL)
        *total = 0;

    char path[4096 + 512];
    if (saf_join(impl, rel, path, sizeof path) != 0)
        return -1;
    size_t rellen = strlen(path);
    if (2 + rellen + 4 > SAF_REQ_MAX)
        return -1;
    uint8_t *req = malloc(SAF_REQ_MAX);
    if (req == NULL)
        return -1;
    le_u16(req, (uint16_t)rellen);
    memcpy(req + 2, path, rellen);

    storage_entry_t *arr = NULL;
    size_t n = 0, caparr = 0;
    int rc = -1;
    for (uint32_t start = 0; ; ) {
        le_u32(req + 2 + rellen, start);
        int32_t status = 0;
        uint8_t *payload = NULL;
        size_t plen = 0;
        if (saf_call(impl, SAF_OP_RO_LIST, req, 2 + rellen + 4, &status,
                     &payload, &plen) != 0)
            goto done;
        /* payload: [u32 nextIndex][u16 count][entries...] */
        if (status != 0 || plen < 6) {
            free(payload);
            goto done;
        }
        uint32_t next = le_u32_get(payload);
        if (append_batch_entries(payload, plen, &arr, &n, &caparr) != 0) {
            free(payload);
            goto done;
        }
        free(payload);
        if (next == SAF_RO_LIST_END)
            break;
        if (next <= start)
            break; /* protocol anomaly: avoid an infinite loop */
        start = next;
    }
    if (total != NULL)
        *total = (int)n;
    int written = 0;
    for (size_t i = (size_t)offset; i < n && written < cap; i++)
        out[written++] = arr[i];
    rc = written;
done:
    free(req);
    free(arr);
    return rc;
}

static int saf_open_read(storage_backend_t *b, const char *rel, int *fd_out) {
    const saf_impl_t *impl = b->impl;
    *fd_out = -1;
    char path[4096 + 512];
    if (saf_join(impl, rel, path, sizeof path) != 0)
        return -1;
    uint8_t req[SAF_REQ_MAX];
    size_t reqlen = build_string_payload(req, sizeof req, path);
    if (reqlen == 0)
        return -1;
    int fd = saf_connect(impl);
    if (fd < 0)
        return -1;
    int rc = -1;
    if (saf_send(fd, SAF_OP_RO_OPEN, req, reqlen) == 0) {
        int32_t status = 0;
        int rfd = -1;
        if (saf_recv_with_fd(fd, &status, &rfd) == 0) {
            if (status == 0 && rfd >= 0) {
                *fd_out = rfd;
                rc = 0;
            } else if (rfd >= 0) {
                close(rfd); /* error status: never leak the fd */
            }
            /* status -2 (ENOENT) / -5 (EIO) / -11 / -19 -> -1 */
        }
    }
    close(fd);
    return rc;
}

static int saf_remove(storage_backend_t *b, const char *rel) {
    const saf_impl_t *impl = b->impl;
    char path[4096 + 512];
    if (saf_join(impl, rel, path, sizeof path) != 0)
        return -1;
    uint8_t req[SAF_REQ_MAX];
    size_t reqlen = build_string_payload(req, sizeof req, path);
    if (reqlen == 0)
        return -1;
    int32_t status = 0;
    uint8_t *payload = NULL;
    size_t plen = 0;
    int rc = -1;
    if (saf_call(impl, SAF_OP_REMOVE, req, reqlen, &status, &payload, &plen) == 0)
        rc = (status == 0) ? 0 : -1;
    free(payload);
    return rc;
}

static int saf_rmdir(storage_backend_t *b, const char *rel) {
    /* The stage-2 handler routes both DELE and RMD through OP_REMOVE; SAF
     * deleteDocument removes an empty directory. */
    return saf_remove(b, rel);
}

const storage_ops_t storage_saf_ops = {
    .init = saf_init,
    .destroy = saf_destroy,
    .stat = saf_stat,
    .list = saf_list,
    .open_read = saf_open_read,
    .remove = saf_remove,
    .rmdir = saf_rmdir,
};
