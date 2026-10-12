/*
 * ftp-server utilities implementation.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 */
#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "util.h"

ssize_t send_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *)buf;
    size_t off = 0;
    while (off < len) {
        ssize_t w = send(fd, p + off, len - off, MSG_NOSIGNAL);
        if (w < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        off += (size_t)w;
    }
    return (ssize_t)off;
}

int net_make_listener(in_addr_t addr, unsigned short port, int *fd_out) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = addr;
    sa.sin_port = htons(port);
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) != 0) {
        close(fd);
        return -1;
    }
    if (listen(fd, 32) != 0) {
        close(fd);
        return -1;
    }
    *fd_out = fd;
    return 0;
}

int net_set_nodelay(int fd) {
    int one = 1;
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one) != 0)
        return -1;
    return 0;
}

int addr_to_str(const struct sockaddr_in *sa, char *buf, size_t sz) {
    char ip[INET_ADDRSTRLEN];
    if (inet_ntop(AF_INET, &sa->sin_addr, ip, (socklen_t)sizeof ip) == NULL) {
        snprintf(buf, sz, "unknown");
        return 0;
    }
    snprintf(buf, sz, "%s:%u", ip, (unsigned)ntohs(sa->sin_port));
    return 0;
}

static const char *const FTPD_MONTHS[12] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

void format_list_time(time_t mtime, char *out, size_t sz) {
    struct tm tmv;
    struct tm *t = localtime_r(&mtime, &tmv);
    if (t == NULL) {
        snprintf(out, sz, "Jan 01 00:00");
        return;
    }
    time_t now = time(NULL);
    if (now - mtime < 15552000) { /* 180 days */
        snprintf(out, sz, "%s %02d %02d:%02d", FTPD_MONTHS[t->tm_mon], t->tm_mday,
                 t->tm_hour, t->tm_min);
    } else {
        snprintf(out, sz, "%s %02d  %04d", FTPD_MONTHS[t->tm_mon], t->tm_mday,
                 1900 + t->tm_year);
    }
}

void format_mdtm_utc(time_t mtime, char *out, size_t sz) {
    struct tm tmv;
    struct tm *t = gmtime_r(&mtime, &tmv);
    if (t == NULL) {
        snprintf(out, sz, "19700101000000");
        return;
    }
    snprintf(out, sz, "%04d%02d%02d%02d%02d%02d", 1900 + t->tm_year, t->tm_mon + 1,
             t->tm_mday, t->tm_hour, t->tm_min, t->tm_sec);
}

int normalize_relpath(const char *in, char *out, size_t outsz) {
    if (outsz == 0)
        return -1;
    out[0] = '\0';
    if (in == NULL)
        return 0;
    const char *p = in;
    while (*p == '/')
        p++;
    size_t n = 0;
    while (*p != '\0') {
        const char *seg = p;
        while (*p != '\0' && *p != '/')
            p++;
        size_t len = (size_t)(p - seg);
        if (len == 0)
            continue; /* "a//b" */
        if (len == 1 && seg[0] == '.')
            continue;
        if (len == 2 && seg[0] == '.' && seg[1] == '.') {
            if (n > 0) {
                size_t i = n;
                while (i > 0 && out[i - 1] != '/')
                    i--;
                n = i;
            }
            continue; /* '..' at root clamps to root */
        }
        if (n + len + 1 >= outsz)
            return -1;
        if (n > 0)
            out[n++] = '/';
        memcpy(out + n, seg, len);
        n += len;
    }
    out[n] = '\0';
    return 0;
}

int join_relpath(const char *cwd, const char *arg, char *out, size_t outsz) {
    if (arg == NULL || arg[0] == '\0') {
        if (cwd == NULL)
            cwd = "";
        if (snprintf(out, outsz, "%s", cwd) < 0)
            return -1;
        return 0; /* cwd is already normalized */
    }
    char tmp[4096];
    int n;
    if (cwd != NULL && cwd[0] != '\0')
        n = snprintf(tmp, sizeof tmp, "%s/%s", cwd, arg);
    else
        n = snprintf(tmp, sizeof tmp, "%s", arg);
    if (n < 0 || (size_t)n >= sizeof tmp)
        return -1;
    return normalize_relpath(tmp, out, outsz);
}

const char *strip_list_opts(const char *arg) {
    if (arg == NULL)
        return NULL;
    const char *p = arg;
    for (;;) {
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p != '-')
            break;
        while (*p != '\0' && *p != ' ' && *p != '\t')
            p++;
    }
    while (*p == ' ' || *p == '\t')
        p++;
    return (*p != '\0') ? p : NULL;
}

void ftpd_quote_path(const char *in, char *out, size_t outsz) {
    if (outsz < 3) {
        if (outsz > 0)
            out[0] = '\0';
        return;
    }
    size_t o = 0;
    out[o++] = '"';
    for (const char *p = in; *p != '\0'; p++) {
        if (*p == '"') {
            if (o + 3 >= outsz)
                break;
            out[o++] = '"';
        }
        if (o + 2 >= outsz)
            break;
        out[o++] = *p;
    }
    out[o++] = '"';
    out[o] = '\0';
}

void format_list_line(const storage_entry_t *e, char *out, size_t outsz) {
    const char *mode = e->is_dir ? "dr-x------" : "-r--------";
    unsigned long long size = e->is_dir ? 0 : e->size;
    char tb[32];
    format_list_time((time_t)(e->mtime_ms / 1000LL), tb, sizeof tb);
    snprintf(out, outsz, "%s   1 torrent torrent %12llu %s %s\r\n", mode, size, tb,
             e->name);
}

void log_msg(const char *fmt, ...) {
    time_t now = time(NULL);
    struct tm tmv;
    struct tm *t = localtime_r(&now, &tmv);
    char ts[80] = "unknown";
    if (t != NULL)
        snprintf(ts, sizeof ts, "%04d-%02d-%02d %02d:%02d:%02d", 1900 + t->tm_year,
                 t->tm_mon + 1, t->tm_mday, t->tm_hour, t->tm_min, t->tm_sec);
    va_list ap;
    va_start(ap, fmt);
    fprintf(stdout, "[%s] ", ts);
    vfprintf(stdout, fmt, ap);
    fputc('\n', stdout);
    va_end(ap);
}

void log_session(int id, const char *fmt, ...) {
    char inner[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(inner, sizeof inner, fmt, ap);
    va_end(ap);
    if (n < 0)
        n = 0;
    log_msg("S%d %.*s", id, n, inner);
}

static pthread_mutex_t g_rate_mu = PTHREAD_MUTEX_INITIALIZER;
static double g_rate_kibps = 0.0;
static double g_rate_tokens = 0.0;
static struct timespec g_rate_last;

void rate_limit_init(double kibps) {
    pthread_mutex_lock(&g_rate_mu);
    g_rate_kibps = kibps;
    g_rate_tokens = kibps;
    clock_gettime(CLOCK_MONOTONIC, &g_rate_last);
    pthread_mutex_unlock(&g_rate_mu);
}

void rate_limit(int64_t bytes) {
    pthread_mutex_lock(&g_rate_mu);
    if (g_rate_kibps <= 0.0) {
        pthread_mutex_unlock(&g_rate_mu);
        return;
    }
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    double dt = (double)(now.tv_sec - g_rate_last.tv_sec) +
                (double)(now.tv_nsec - g_rate_last.tv_nsec) / 1e9;
    if (dt < 0.0)
        dt = 0.0;
    g_rate_tokens += dt * g_rate_kibps;
    if (g_rate_tokens > g_rate_kibps)
        g_rate_tokens = g_rate_kibps; /* burst capped at one second of budget */
    g_rate_last = now;
    double need = (double)bytes / 1024.0;
    double deficit = need - g_rate_tokens;
    if (deficit > 0.0) {
        g_rate_tokens = 0.0;
        pthread_mutex_unlock(&g_rate_mu);
        double secs = deficit / g_rate_kibps;
        struct timespec ts;
        ts.tv_sec = (time_t)secs;
        ts.tv_nsec = (long)((secs - (double)ts.tv_sec) * 1e9);
        nanosleep(&ts, NULL);
        return;
    }
    g_rate_tokens -= need;
    pthread_mutex_unlock(&g_rate_mu);
}
