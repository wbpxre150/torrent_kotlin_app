/*
 * ftp-server session implementation.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 *
 * Per-session state, line reader, command dispatch, and handlers for:
 * USER PASS QUIT SYST TYPE NOOP FEAT OPTS PWD CWD CDUP PASV EPSV LIST NLST
 * SIZE MDTM MLST MLSD REST RETR ABOR DELE RMD.
 * PORT/EPRT/STOR/APPE/MKD/RNFR/RNTO reply 502; unknown commands 500.
 *
 * Paths: the argument is one raw byte string after the verb (spaces matter).
 * Everything is normalized inside the virtual root; login lands in the root.
 */
#define _GNU_SOURCE

#include <ctype.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include "ftpd.h"
#include "session.h"
#include "util.h"

#define SESSION_RBUF 8192
#define RETR_CHUNK (256 * 1024u)
#define LIST_BATCH 64

typedef struct session {
    int fd;
    int id;
    char cwd[1024]; /* normalized relpath; "" = root */
    char user[256];
    int have_user;
    int authenticated;
    unsigned mlst_mask; /* RFC 3659 facts enabled for MLST/MLSD (OPTS MLST) */
    long long rest; /* -1 = none; applies to the next RETR only */
    int data_listen_fd; /* -1 = no pending PASV/EPSV listener */
    int closed;
    char rbuf[SESSION_RBUF];
    size_t rlen;
    size_t roff;
} session_t;

static atomic_uint g_pasv_seq = 0;

/* ------------------------------------------------------------------ replies */

static int reply(session_t *s, const char *code, const char *fmt, ...) {
    char body[2048];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(body, sizeof body, fmt, ap);
    va_end(ap);
    if (n < 0)
        n = 0;
    size_t blen = (size_t)n;
    if (blen >= sizeof body)
        blen = sizeof body - 1;
    char line[2048 + 16];
    int m = snprintf(line, sizeof line, "%s %.*s\r\n", code, (int)blen, body);
    if (m < 0)
        return -1;
    return send_all(s->fd, line, (size_t)m) == (ssize_t)m ? 0 : -1;
}

/* --------------------------------------------------------------- line input */

/* 0 = line ready, -1 = control closed, -2 = idle timeout (600s). */
static int read_line(session_t *s, char *out, size_t outsz) {
    for (;;) {
        for (size_t i = s->roff; i < s->rlen; i++) {
            if (s->rbuf[i] == '\n') {
                size_t n = i - s->roff;
                if (n + 1 >= outsz)
                    n = outsz - 1;
                memcpy(out, s->rbuf + s->roff, n);
                out[n] = '\0';
                if (n > 0 && out[n - 1] == '\r')
                    out[n - 1] = '\0';
                s->roff = i + 1;
                return 0;
            }
        }
        memmove(s->rbuf, s->rbuf + s->roff, s->rlen - s->roff);
        s->rlen -= s->roff;
        s->roff = 0;
        if (s->rlen + 1 >= sizeof s->rbuf)
            return -1; /* line too long */
        struct pollfd pfd;
        pfd.fd = s->fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int rv = poll(&pfd, 1, FTPD_IDLE_TIMEOUT_S * 1000);
        if (rv < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (rv == 0)
            return -2;
        ssize_t n = read(s->fd, s->rbuf + s->rlen, sizeof s->rbuf - 1 - s->rlen);
        if (n <= 0)
            return -1;
        s->rlen += (size_t)n;
    }
}

/*
 * Non-blocking line read for use while a data transfer is in flight (control
 * socket is known readable via poll). Returns bytes copied, 0 = no complete
 * line available yet, -1 = control closed.
 */
static int read_line_noblock(session_t *s, char *out, size_t outsz) {
    int did_read = 0;
    for (;;) {
        for (size_t i = s->roff; i < s->rlen; i++) {
            if (s->rbuf[i] == '\n') {
                size_t n = i - s->roff;
                if (n + 1 >= outsz)
                    n = outsz - 1;
                memcpy(out, s->rbuf + s->roff, n);
                out[n] = '\0';
                if (n > 0 && out[n - 1] == '\r')
                    out[n - 1] = '\0';
                s->roff = i + 1;
                return (int)n;
            }
        }
        if (did_read)
            return 0;
        did_read = 1;
        memmove(s->rbuf, s->rbuf + s->roff, s->rlen - s->roff);
        s->rlen -= s->roff;
        s->roff = 0;
        if (s->rlen + 1 >= sizeof s->rbuf)
            return -1;
        ssize_t n = read(s->fd, s->rbuf + s->rlen, sizeof s->rbuf - 1 - s->rlen);
        if (n <= 0)
            return -1;
        s->rlen += (size_t)n;
    }
}

/* ------------------------------------------------------------------ parsing */

static void parse_command(const char *line, char *verb, size_t vsize,
                          const char **arg) {
    size_t i = 0;
    while (line[i] != '\0' && line[i] != ' ' && line[i] != '\t')
        i++;
    size_t n = i;
    if (n >= vsize)
        n = vsize - 1;
    memcpy(verb, line, n);
    verb[n] = '\0';
    for (size_t c = 0; c < n; c++)
        verb[c] = (char)toupper((unsigned char)verb[c]);
    while (line[i] == ' ' || line[i] == '\t')
        i++;
    *arg = line + i;
}

static const char *path_basename(const char *p) {
    const char *slash = strrchr(p, '/');
    return (slash != NULL) ? slash + 1 : p;
}

static void display_path(const char *cwd, char *out, size_t outsz) {
    if (cwd[0] == '\0')
        snprintf(out, outsz, "/");
    else
        snprintf(out, outsz, "/%s", cwd);
}

/* -------------------------------------------------------------------- data */

/* Open a passive data listener on the bound IP within the configured range. */
static int data_listener_open(session_t *s, unsigned short *port_out) {
    if (s->data_listen_fd >= 0) {
        close(s->data_listen_fd);
        s->data_listen_fd = -1;
    }
    unsigned int range = (unsigned int)(g_config.pasv_hi - g_config.pasv_lo + 1);
    for (unsigned int i = 0; i < range; i++) {
        unsigned int pick = atomic_fetch_add(&g_pasv_seq, 1u) % range;
        unsigned short port = (unsigned short)(g_config.pasv_lo + (unsigned int)pick);
        int fd = -1;
        if (net_make_listener(g_config.bind_addr, port, &fd) == 0) {
            s->data_listen_fd = fd;
            *port_out = port;
            return 0;
        }
    }
    return -1;
}

/* Accept the passive data connection (30s timeout). 0 on success (dfd out). */
static int data_accept(session_t *s, int *dfd_out) {
    if (s->data_listen_fd < 0) {
        reply(s, "425", "Use PASV or EPSV first.");
        return -1;
    }
    struct pollfd pfd;
    pfd.fd = s->data_listen_fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int rv = poll(&pfd, 1, FTPD_PASV_ACCEPT_TIMEOUT_S * 1000);
    if (rv <= 0) {
        close(s->data_listen_fd);
        s->data_listen_fd = -1;
        reply(s, "425", "Data connection timed out.");
        return -1;
    }
    int dfd = accept(s->data_listen_fd, NULL, NULL);
    close(s->data_listen_fd);
    s->data_listen_fd = -1;
    if (dfd < 0) {
        reply(s, "425", "Data connection failed.");
        return -1;
    }
    net_set_nodelay(dfd);
    struct sockaddr_in ca;
    struct sockaddr_in da;
    socklen_t cl = sizeof ca;
    socklen_t dl = sizeof da;
    if (getpeername(s->fd, (struct sockaddr *)&ca, &cl) == 0 &&
        getpeername(dfd, (struct sockaddr *)&da, &dl) == 0 &&
        ca.sin_addr.s_addr != da.sin_addr.s_addr) {
        close(dfd);
        reply(s, "426", "Data connection from unexpected peer; closed.");
        return -1;
    }
    *dfd_out = dfd;
    return 0;
}

/*
 * Send a chunk on the data socket, watching the control socket for ABOR/QUIT.
 * Returns 0 = all sent, 1 = ABOR, 2 = data client closed / error, 3 = QUIT.
 */
static int data_send_chunk(session_t *s, int dfd, const char *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        struct pollfd pfds[2];
        pfds[0].fd = dfd;
        pfds[0].events = POLLOUT;
        pfds[0].revents = 0;
        pfds[1].fd = s->fd;
        pfds[1].events = POLLIN;
        pfds[1].revents = 0;
        int rv = poll(pfds, 2, 1000);
        if (rv < 0) {
            if (errno == EINTR)
                continue;
            return 2;
        }
        if (pfds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            char line[SESSION_RBUF];
            int n = read_line_noblock(s, line, sizeof line);
            if (n < 0)
                return 2; /* control connection gone */
            if (n > 0) {
                char verb[16];
                const char *arg;
                parse_command(line, verb, sizeof verb, &arg);
                if (strcmp(verb, "ABOR") == 0)
                    return 1;
                if (strcmp(verb, "QUIT") == 0)
                    return 3;
                /* other commands during transfer: ignored */
            }
        }
        if (pfds[0].revents & (POLLOUT | POLLHUP | POLLERR)) {
            ssize_t w = send(dfd, buf + off, len - off, MSG_NOSIGNAL);
            if (w > 0) {
                off += (size_t)w;
                continue;
            }
            if (w < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
                continue;
            return 2; /* EPIPE / ECONNRESET / other: client closed */
        }
    }
    return 0;
}

/* Close the data socket and send the reply sequence for the transfer result. */
static void finish_data(session_t *s, int dfd, int result) {
    close(dfd);
    if (result == 0) {
        reply(s, "226", "Transfer complete.");
    } else if (result == 1) {
        reply(s, "426", "Data connection closed; transfer aborted.");
        reply(s, "226", "Transfer complete.");
    } else if (result == 2) {
        reply(s, "426", "Data connection closed; transfer aborted.");
    } else {
        reply(s, "426", "Data connection closed; transfer aborted.");
        reply(s, "221", "Goodbye.");
        s->closed = 1;
    }
}

/* ----------------------------------------------------------------- commands */

static void cmd_user(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "USER requires a user name.");
        return;
    }
    snprintf(s->user, sizeof s->user, "%s", arg);
    s->have_user = 1;
    s->authenticated = 0;
    reply(s, "331", "User name okay, need password.");
}

static void cmd_pass(session_t *s, const char *arg) {
    if (!s->have_user) {
        reply(s, "503", "Login with USER first.");
        return;
    }
    const char *eu = getenv("FTP_USERNAME");
    const char *ep = getenv("FTP_PASSWORD");
    int ok = (eu != NULL && ep != NULL && arg != NULL && eu[0] != '\0' &&
              strcmp(s->user, eu) == 0 && strcmp(arg, ep) == 0);
    if (!ok) {
        sleep(1);
        reply(s, "530", "Login incorrect.");
        return;
    }
    s->authenticated = 1;
    reply(s, "230", "User %s logged in.", s->user);
}

static void cmd_quit(session_t *s) {
    reply(s, "221", "Goodbye.");
    s->closed = 1;
}

static void cmd_type(session_t *s, const char *arg) {
    if (arg != NULL &&
        (arg[0] == 'A' || arg[0] == 'I' || arg[0] == 'a' || arg[0] == 'i')) {
        char t = (char)toupper((unsigned char)arg[0]);
        reply(s, "200", "Type set to %c.", t);
    } else {
        reply(s, "504", "Type not supported.");
    }
}

static void cmd_feat(session_t *s) {
    char mlst[64];
    if (format_mlst_feat(mlst, sizeof mlst, s->mlst_mask) != 0)
        return;
    char msg[512];
    int n = snprintf(msg, sizeof msg, "211-Features:\r\n"
                                 "%s\r\n"
                                 "MLSD\r\n"
                                 "UTF8\r\n"
                                 "SIZE\r\n"
                                 "MDTM\r\n"
                                 "REST STREAM\r\n"
                                 "211 End\r\n",
                     mlst);
    if (n < 0)
        return;
    send_all(s->fd, msg, (size_t)n);
}

static void cmd_opts(session_t *s, const char *arg) {
    if (arg != NULL && strncasecmp(arg, "MLST", 4) == 0) {
        const char *facts = arg + 4;
        if (facts[0] != '\0' && facts[0] != ' ' && facts[0] != '\t') {
            reply(s, "502", "Command not implemented.");
            return;
        }
        while (*facts == ' ' || *facts == '\t')
            facts++;
        s->mlst_mask = parse_mlst_opts(facts);
        char list[32];
        if (format_mlst_opts_list(list, sizeof list, s->mlst_mask) != 0)
            list[0] = '\0';
        reply(s, "200", "MLST OPTS %s", list);
        return;
    }
    if (arg != NULL && strncasecmp(arg, "UTF8", 4) == 0)
        reply(s, "200", "UTF8 enabled");
    else
        reply(s, "502", "Command not implemented.");
}

static void cmd_pwd(session_t *s) {
    char disp[1200];
    display_path(s->cwd, disp, sizeof disp);
    char quoted[1300];
    ftpd_quote_path(disp, quoted, sizeof quoted);
    reply(s, "257", "%s is the current directory", quoted);
}

static void cmd_cwd(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "CWD requires a directory name.");
        return;
    }
    char path[1024];
    if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
        reply(s, "550", "Path too long.");
        return;
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "%s: No such file or directory.", arg);
        return;
    }
    if (!st.is_dir) {
        reply(s, "550", "%s: Not a directory.", arg);
        return;
    }
    snprintf(s->cwd, sizeof s->cwd, "%s", path);
    char disp[1200];
    display_path(path, disp, sizeof disp);
    reply(s, "250", "Directory changed to %s.", disp);
}

static void cmd_cdup(session_t *s) {
    size_t n = strlen(s->cwd);
    while (n > 0 && s->cwd[n - 1] == '/')
        n--;
    while (n > 0 && s->cwd[n - 1] != '/')
        n--;
    s->cwd[n] = '\0';
    char disp[1200];
    display_path(s->cwd, disp, sizeof disp);
    reply(s, "250", "Directory changed to %s.", disp);
}

static void cmd_pasv(session_t *s) {
    unsigned short port = 0;
    if (data_listener_open(s, &port) != 0) {
        reply(s, "425", "Cannot open passive connection.");
        return;
    }
    const unsigned char *b = (const unsigned char *)&g_config.bind_addr;
    reply(s, "227", "Entering Passive Mode (%u,%u,%u,%u,%u,%u)", (unsigned)b[0],
          (unsigned)b[1], (unsigned)b[2], (unsigned)b[3], (unsigned)(port >> 8),
          (unsigned)(port & 0xffu));
}

static void cmd_epsv(session_t *s) {
    unsigned short port = 0;
    if (data_listener_open(s, &port) != 0) {
        reply(s, "425", "Cannot open passive connection.");
        return;
    }
    reply(s, "229", "Entering Extended Passive Mode (|||%u|)", (unsigned)port);
}

static void cmd_list(session_t *s, const char *arg) {
    const char *target = strip_list_opts(arg);
    if (s->data_listen_fd < 0) {
        reply(s, "425", "Use PASV or EPSV first.");
        return;
    }
    s->rest = -1; /* REST is cleared by LIST */
    char path[1024];
    if (target != NULL) {
        if (join_relpath(s->cwd, target, path, sizeof path) != 0) {
            reply(s, "550", "Path too long.");
            return;
        }
    } else {
        snprintf(path, sizeof path, "%s", s->cwd);
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "No such file or directory.");
        return;
    }
    reply(s, "150", "Opening BINARY mode data connection for file list.");
    int dfd = -1;
    if (data_accept(s, &dfd) != 0)
        return;
    int result = 0;
    if (st.is_dir) {
        int offset = 0;
        for (;;) {
            storage_entry_t batch[LIST_BATCH];
            int total = 0;
            int n = g_storage.ops->list(&g_storage, path, offset, batch,
                                        LIST_BATCH, &total);
            if (n < 0) {
                result = 2;
                break;
            }
            for (int i = 0; i < n; i++) {
                char line[2048];
                format_list_line(&batch[i], line, sizeof line);
                int rv = data_send_chunk(s, dfd, line, strlen(line));
                if (rv != 0) {
                    result = rv;
                    break;
                }
            }
            if (result != 0)
                break;
            offset += n;
            if (n == 0 || offset >= total)
                break;
        }
    } else {
        storage_entry_t e;
        memset(&e, 0, sizeof e);
        const char *base = path_basename(path);
        size_t blen = strlen(base);
        if (blen >= sizeof e.name)
            blen = sizeof e.name - 1;
        memcpy(e.name, base, blen);
        e.name[blen] = '\0';
        e.is_dir = 0;
        e.size = st.size;
        e.mtime_ms = st.mtime_ms;
        char line[2048];
        format_list_line(&e, line, sizeof line);
        result = data_send_chunk(s, dfd, line, strlen(line));
    }
    finish_data(s, dfd, result);
}

static void cmd_nlst(session_t *s, const char *arg) {
    const char *target = strip_list_opts(arg);
    if (s->data_listen_fd < 0) {
        reply(s, "425", "Use PASV or EPSV first.");
        return;
    }
    s->rest = -1;
    char path[1024];
    if (target != NULL) {
        if (join_relpath(s->cwd, target, path, sizeof path) != 0) {
            reply(s, "550", "Path too long.");
            return;
        }
    } else {
        snprintf(path, sizeof path, "%s", s->cwd);
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "No such file or directory.");
        return;
    }
    reply(s, "150", "Opening BINARY mode data connection for name list.");
    int dfd = -1;
    if (data_accept(s, &dfd) != 0)
        return;
    int result = 0;
    if (st.is_dir) {
        int offset = 0;
        for (;;) {
            storage_entry_t batch[LIST_BATCH];
            int total = 0;
            int n = g_storage.ops->list(&g_storage, path, offset, batch,
                                        LIST_BATCH, &total);
            if (n < 0) {
                result = 2;
                break;
            }
            for (int i = 0; i < n; i++) {
                char line[560];
                int m = snprintf(line, sizeof line, "%s\r\n", batch[i].name);
                if (m < 0)
                    m = 0;
                int rv = data_send_chunk(s, dfd, line, (size_t)m);
                if (rv != 0) {
                    result = rv;
                    break;
                }
            }
            if (result != 0)
                break;
            offset += n;
            if (n == 0 || offset >= total)
                break;
        }
    } else {
        char line[560];
        int m = snprintf(line, sizeof line, "%s\r\n", path_basename(path));
        if (m < 0)
            m = 0;
        result = data_send_chunk(s, dfd, line, (size_t)m);
    }
    finish_data(s, dfd, result);
}

static void cmd_size(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "SIZE requires a file name.");
        return;
    }
    char path[1024];
    if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
        reply(s, "550", "Path too long.");
        return;
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "No such file or directory.");
        return;
    }
    if (st.is_dir) {
        reply(s, "550", "Is a directory.");
        return;
    }
    reply(s, "213", "%llu", st.size);
}

static void cmd_mdtm(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "MDTM requires a file name.");
        return;
    }
    char path[1024];
    if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
        reply(s, "550", "Path too long.");
        return;
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "No such file or directory.");
        return;
    }
    char tb[32];
    format_mdtm_utc((time_t)(st.mtime_ms / 1000LL), tb, sizeof tb);
    reply(s, "213", "%s", tb);
}

/*
 * MLST: facts for a single path. The argument is a literal path (spaces and
 * glob characters included, never glob-interpreted); empty means the cwd.
 * Entry line is " SP facts SP pathname CRLF"; with mask 0 the facts string is
 * empty, so the line begins with two spaces before the name.
 */
static void cmd_mlst(session_t *s, const char *arg) {
    char path[1024];
    if (arg != NULL && arg[0] != '\0') {
        if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
            reply(s, "550", "Path too long.");
            return;
        }
    } else {
        snprintf(path, sizeof path, "%s", s->cwd);
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "No such file or directory.");
        return;
    }
    char facts[80];
    if (format_mlsx_facts(facts, sizeof facts, st.is_dir, st.size, st.mtime_ms,
                          s->mlst_mask) != 0) {
        reply(s, "550", "Internal error.");
        return;
    }
    char disp[1200];
    display_path(path, disp, sizeof disp);
    char line[2048];
    int m = snprintf(line, sizeof line, "250-Listing %s\r\n %s %s\r\n250 End\r\n",
                     disp, facts, disp);
    if (m < 0)
        return;
    send_all(s->fd, line, (size_t)m);
}

/* MLSD: MLST-format listing of a directory; the argument is a literal path. */
static void cmd_mlsd(session_t *s, const char *arg) {
    if (s->data_listen_fd < 0) {
        reply(s, "425", "Use PASV or EPSV first.");
        return;
    }
    s->rest = -1; /* REST is cleared by MLSD */
    char path[1024];
    if (arg != NULL && arg[0] != '\0') {
        if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
            reply(s, "550", "Path too long.");
            return;
        }
    } else {
        snprintf(path, sizeof path, "%s", s->cwd);
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "No such file or directory.");
        return;
    }
    if (!st.is_dir) {
        reply(s, "550", "Not a directory.");
        return;
    }
    reply(s, "150", "Opening BINARY mode data connection for MLSD listing.");
    int dfd = -1;
    if (data_accept(s, &dfd) != 0)
        return;
    int result = 0;
    int offset = 0;
    for (;;) {
        storage_entry_t batch[LIST_BATCH];
        int total = 0;
        int n = g_storage.ops->list(&g_storage, path, offset, batch, LIST_BATCH,
                                    &total);
        if (n < 0) {
            result = 2;
            break;
        }
        for (int i = 0; i < n; i++) {
            char facts[80];
            if (format_mlsx_facts(facts, sizeof facts, batch[i].is_dir,
                                  batch[i].size, batch[i].mtime_ms,
                                  s->mlst_mask) != 0) {
                result = 2;
                break;
            }
            char line[2048];
            int m = snprintf(line, sizeof line, "%s %s\r\n", facts,
                             batch[i].name);
            if (m < 0) {
                result = 2;
                break;
            }
            int rv = data_send_chunk(s, dfd, line, (size_t)m);
            if (rv != 0) {
                result = rv;
                break;
            }
        }
        if (result != 0)
            break;
        offset += n;
        if (n == 0 || offset >= total)
            break;
    }
    finish_data(s, dfd, result);
}

static void cmd_rest(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "REST requires an offset.");
        return;
    }
    errno = 0;
    char *end = NULL;
    long long v = strtoll(arg, &end, 10);
    if (end == arg || *end != '\0' || errno != 0 || v < 0) {
        reply(s, "501", "REST offset syntax error.");
        return;
    }
    s->rest = v;
    reply(s, "350", "Restarting at %lld.", v);
}

static void cmd_retr(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "RETR requires a file name.");
        return;
    }
    if (s->data_listen_fd < 0) {
        reply(s, "425", "Use PASV or EPSV first.");
        return;
    }
    char path[1024];
    if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
        reply(s, "550", "Path too long.");
        return;
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "%s: No such file or directory.", arg);
        return;
    }
    if (st.is_dir) {
        reply(s, "550", "%s: Is a directory.", arg);
        return;
    }
    long long offset = s->rest;
    s->rest = -1; /* REST applies to this RETR only */
    if (offset < 0)
        offset = 0;
    if (offset > (long long)st.size)
        offset = (long long)st.size;
    reply(s, "150", "Opening BINARY mode data connection for %s (%llu bytes).", arg,
          st.size);
    int dfd = -1;
    if (data_accept(s, &dfd) != 0)
        return;
    int ffd = -1;
    if (g_storage.ops->open_read(&g_storage, path, &ffd) != 0 || ffd < 0) {
        close(dfd);
        reply(s, "550", "%s: Could not open file.", arg);
        return;
    }
    int result = 0;
    if (offset < (long long)st.size) {
        char *buf = malloc(RETR_CHUNK);
        if (buf == NULL) {
            close(ffd);
            close(dfd);
            reply(s, "451", "Out of memory.");
            return;
        }
        off_t off = (off_t)offset;
        for (;;) {
            ssize_t r = pread(ffd, buf, RETR_CHUNK, off);
            if (r < 0) {
                if (errno == EINTR)
                    continue;
                result = 2;
                break;
            }
            if (r == 0)
                break;
            off += r;
            rate_limit((int64_t)r);
            int rv = data_send_chunk(s, dfd, buf, (size_t)r);
            if (rv != 0) {
                result = rv;
                break;
            }
            if (r < (ssize_t)RETR_CHUNK)
                break;
        }
        free(buf);
    }
    close(ffd);
    finish_data(s, dfd, result);
}

static void cmd_dele(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "DELE requires a file name.");
        return;
    }
    char path[1024];
    if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
        reply(s, "550", "Path too long.");
        return;
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "%s: No such file or directory.", arg);
        return;
    }
    if (st.is_dir) {
        reply(s, "550", "%s: Is a directory.", arg);
        return;
    }
    if (g_storage.ops->remove(&g_storage, path) != 0) {
        reply(s, "550", "%s: Could not delete file.", arg);
        return;
    }
    reply(s, "250", "Delete operation successful.");
}

static void cmd_rmd(session_t *s, const char *arg) {
    if (arg == NULL || arg[0] == '\0') {
        reply(s, "501", "RMD requires a directory name.");
        return;
    }
    char path[1024];
    if (join_relpath(s->cwd, arg, path, sizeof path) != 0) {
        reply(s, "550", "Path too long.");
        return;
    }
    storage_stat_t st;
    if (g_storage.ops->stat(&g_storage, path, &st) != 0 || !st.exists) {
        reply(s, "550", "%s: No such file or directory.", arg);
        return;
    }
    if (!st.is_dir) {
        reply(s, "550", "%s: Not a directory.", arg);
        return;
    }
    if (g_storage.ops->rmdir(&g_storage, path) != 0) {
        reply(s, "550", "%s: Could not remove directory (not empty?).", arg);
        return;
    }
    reply(s, "250", "Remove directory operation successful.");
}

static void cmd_abor_idle(session_t *s) {
    reply(s, "503", "No transfer in progress.");
}

static void dispatch(session_t *s, const char *verb, const char *arg) {
    if (strcmp(verb, "USER") == 0) {
        cmd_user(s, arg);
        return;
    }
    if (strcmp(verb, "PASS") == 0) {
        cmd_pass(s, arg);
        return;
    }
    if (strcmp(verb, "QUIT") == 0) {
        cmd_quit(s);
        return;
    }
    if (strcmp(verb, "NOOP") == 0) {
        reply(s, "200", "OK.");
        return;
    }
    if (strcmp(verb, "SYST") == 0) {
        reply(s, "215", "UNIX Type: L8");
        return;
    }
    if (strcmp(verb, "FEAT") == 0) {
        cmd_feat(s);
        return;
    }
    if (strcmp(verb, "TYPE") == 0) {
        cmd_type(s, arg);
        return;
    }
    if (strcmp(verb, "OPTS") == 0) {
        cmd_opts(s, arg);
        return;
    }
    if (strcmp(verb, "PORT") == 0 || strcmp(verb, "EPRT") == 0 ||
        strcmp(verb, "STOR") == 0 || strcmp(verb, "APPE") == 0 ||
        strcmp(verb, "MKD") == 0 || strcmp(verb, "RNFR") == 0 ||
        strcmp(verb, "RNTO") == 0) {
        reply(s, "502", "Command not implemented.");
        return;
    }
    if (!s->authenticated) {
        reply(s, "530", "Please login with USER and PASS.");
        return;
    }
    if (strcmp(verb, "PWD") == 0) {
        cmd_pwd(s);
        return;
    }
    if (strcmp(verb, "CWD") == 0) {
        cmd_cwd(s, arg);
        return;
    }
    if (strcmp(verb, "CDUP") == 0) {
        cmd_cdup(s);
        return;
    }
    if (strcmp(verb, "PASV") == 0) {
        cmd_pasv(s);
        return;
    }
    if (strcmp(verb, "EPSV") == 0) {
        cmd_epsv(s);
        return;
    }
    if (strcmp(verb, "LIST") == 0) {
        cmd_list(s, arg);
        return;
    }
    if (strcmp(verb, "NLST") == 0) {
        cmd_nlst(s, arg);
        return;
    }
    if (strcmp(verb, "SIZE") == 0) {
        cmd_size(s, arg);
        return;
    }
    if (strcmp(verb, "MDTM") == 0) {
        cmd_mdtm(s, arg);
        return;
    }
    if (strcmp(verb, "MLST") == 0) {
        cmd_mlst(s, arg);
        return;
    }
    if (strcmp(verb, "MLSD") == 0) {
        cmd_mlsd(s, arg);
        return;
    }
    if (strcmp(verb, "REST") == 0) {
        cmd_rest(s, arg);
        return;
    }
    if (strcmp(verb, "RETR") == 0) {
        cmd_retr(s, arg);
        return;
    }
    if (strcmp(verb, "ABOR") == 0) {
        cmd_abor_idle(s);
        return;
    }
    if (strcmp(verb, "DELE") == 0) {
        cmd_dele(s, arg);
        return;
    }
    if (strcmp(verb, "RMD") == 0) {
        cmd_rmd(s, arg);
        return;
    }
    reply(s, "500", "Unknown command.");
}

/* ------------------------------------------------------------------- thread */

static void log_command(const session_t *s, const char *verb, const char *arg) {
    if (strcmp(verb, "PASS") == 0)
        log_session(s->id, "cmd PASS ****");
    else if (arg != NULL && arg[0] != '\0')
        log_session(s->id, "cmd %s %s", verb, arg);
    else
        log_session(s->id, "cmd %s", verb);
}

static void *session_main(void *arg) {
    session_t *s = arg;
    struct sockaddr_in sa;
    socklen_t sl = sizeof sa;
    if (getpeername(s->fd, (struct sockaddr *)&sa, &sl) == 0) {
        char peer[64];
        addr_to_str(&sa, peer, sizeof peer);
        log_session(s->id, "connected from %s", peer);
    } else {
        log_session(s->id, "connected");
    }
    reply(s, "220", "ftpserver-host ready.");
    for (;;) {
        char line[SESSION_RBUF];
        int rv = read_line(s, line, sizeof line);
        if (rv == -2) {
            log_session(s->id, "idle timeout");
            reply(s, "421", "Idle timeout; closing connection.");
            break;
        }
        if (rv == -1)
            break;
        if (line[0] == '\0')
            continue;
        char verb[16];
        const char *arg;
        parse_command(line, verb, sizeof verb, &arg);
        log_command(s, verb, arg);
        dispatch(s, verb, arg);
        if (s->closed)
            break;
    }
    if (s->data_listen_fd >= 0)
        close(s->data_listen_fd);
    close(s->fd);
    log_session(s->id, "disconnected");
    atomic_fetch_sub(&g_active_sessions, 1);
    free(s);
    return NULL;
}

void session_start(int fd, int id) {
    session_t *s = calloc(1, sizeof *s);
    if (s == NULL) {
        close(fd);
        return;
    }
    s->fd = fd;
    s->id = id;
    s->rest = -1;
    s->mlst_mask = MLSX_FACT_ALL;
    s->data_listen_fd = -1;
    net_set_nodelay(fd);
    atomic_fetch_add(&g_active_sessions, 1);
    pthread_t th;
    if (pthread_create(&th, NULL, session_main, s) != 0) {
        atomic_fetch_sub(&g_active_sessions, 1);
        close(fd);
        free(s);
        return;
    }
    pthread_detach(th);
}
