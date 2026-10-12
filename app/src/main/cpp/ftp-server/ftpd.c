/*
 * ftpserver-host: native C FTP daemon (foreground, argv-driven).
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 *
 * Usage:
 *   ftpserver-host --bind <ip> --port <n> --root <dir> --pasv <lo-hi> \
 *                  --max-kibps <n> --saf-socket <path>
 *
 * Exit codes: 0 clean stop, 2 usage error, 3 bind failure, 4 storage init failure.
 * SIGTERM/SIGINT set a stop flag; the accept loop (poll, 1s timeout) notices and
 * exits well within the supervisor's 5s grace. SIGPIPE is ignored globally and all
 * sends use MSG_NOSIGNAL.
 */
#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include "ftpd.h"
#include "session.h"
#include "util.h"

ftpd_config_t g_config;
storage_backend_t g_storage;
atomic_int g_active_sessions;

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

static void usage(const char *prog) {
    fprintf(stderr,
            "usage: %s --bind <ip> --port <n> --root <dir> --pasv <lo-hi> "
            "--max-kibps <n> --saf-socket <path>\n",
            prog);
}

static int parse_u16(const char *s, uint16_t *out) {
    char *end = NULL;
    errno = 0;
    long v = strtol(s, &end, 10);
    if (errno != 0 || end == s || *end != '\0' || v < 1 || v > 65535)
        return -1;
    *out = (uint16_t)v;
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (setpriority(PRIO_PROCESS, 0, 10) != 0)
        log_msg("warning: setpriority(10) failed: %s", strerror(errno));

    const char *a_bind = NULL;
    const char *a_port = NULL;
    const char *a_root = NULL;
    const char *a_pasv = NULL;
    const char *a_kibps = NULL;
    const char *a_saf = NULL;
    for (int i = 1; i < argc; i++) {
        const char *flag = argv[i];
        const char **dest = NULL;
        if (strcmp(flag, "--bind") == 0)
            dest = &a_bind;
        else if (strcmp(flag, "--port") == 0)
            dest = &a_port;
        else if (strcmp(flag, "--root") == 0)
            dest = &a_root;
        else if (strcmp(flag, "--pasv") == 0)
            dest = &a_pasv;
        else if (strcmp(flag, "--max-kibps") == 0)
            dest = &a_kibps;
        else if (strcmp(flag, "--saf-socket") == 0)
            dest = &a_saf;
        if (dest == NULL || i + 1 >= argc) {
            usage(argv[0]);
            return 2;
        }
        *dest = argv[++i];
    }
    if (a_bind == NULL || a_port == NULL || a_root == NULL || a_pasv == NULL ||
        a_kibps == NULL || a_saf == NULL) {
        usage(argv[0]);
        return 2;
    }

    struct in_addr ina;
    if (inet_pton(AF_INET, a_bind, &ina) != 1) {
        usage(argv[0]);
        return 2;
    }
    if (parse_u16(a_port, &g_config.port) != 0) {
        usage(argv[0]);
        return 2;
    }
    unsigned int lo = 0;
    unsigned int hi = 0;
    if (sscanf(a_pasv, "%u-%u", &lo, &hi) != 2 || lo < 1 || hi > 65535 || lo > hi) {
        usage(argv[0]);
        return 2;
    }
    g_config.pasv_lo = (uint16_t)lo;
    g_config.pasv_hi = (uint16_t)hi;
    char *kend = NULL;
    g_config.max_kibps = strtod(a_kibps, &kend);
    if (kend == a_kibps || *kend != '\0' || g_config.max_kibps < 0.0) {
        usage(argv[0]);
        return 2;
    }

    snprintf(g_config.bind_ip, sizeof g_config.bind_ip, "%s", a_bind);
    g_config.bind_addr = ina.s_addr;
    if (snprintf(g_config.root, sizeof g_config.root, "%s", a_root) >=
        (int)sizeof g_config.root ||
        snprintf(g_config.saf_socket, sizeof g_config.saf_socket, "%s", a_saf) >=
            (int)sizeof g_config.saf_socket) {
        usage(argv[0]);
        return 2;
    }

    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    g_storage.ops = FTPD_STORAGE_OPS;
    if (g_storage.ops->init(&g_storage, g_config.root) != 0) {
        log_msg("error: storage init failed for root '%s'", g_config.root);
        return 4;
    }
    rate_limit_init(g_config.max_kibps);
    atomic_init(&g_active_sessions, 0);

    int listener = -1;
    if (net_make_listener(g_config.bind_addr, g_config.port, &listener) != 0) {
        log_msg("error: bind %s:%u failed: %s", g_config.bind_ip,
                (unsigned)g_config.port, strerror(errno));
        return 3;
    }
    int fl = fcntl(listener, F_GETFL, 0);
    if (fl >= 0)
        fcntl(listener, F_SETFL, fl | O_NONBLOCK);

    log_msg("listening on %s:%u root='%s' pasv=%u-%u max_kibps=%.0f (pid %d)",
            g_config.bind_ip, (unsigned)g_config.port, g_config.root,
            (unsigned)g_config.pasv_lo, (unsigned)g_config.pasv_hi, g_config.max_kibps,
            (int)getpid());

    pid_t init_ppid = getppid();
    int next_session_id = 0;
    while (!g_stop) {
        struct pollfd pfd;
        pfd.fd = listener;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int rv = poll(&pfd, 1, 1000);
        if (rv > 0 && (pfd.revents & (POLLIN | POLLHUP))) {
            for (;;) {
                int cfd = accept(listener, NULL, NULL);
                if (cfd < 0)
                    break; /* backlog drained (EAGAIN) or error */
                if (atomic_load(&g_active_sessions) >= FTPD_MAX_SESSIONS) {
                    log_msg("session cap (%d) reached; rejecting %d", FTPD_MAX_SESSIONS,
                            cfd);
                    const char *msg = "421 Too many sessions; goodbye.\r\n";
                    send_all(cfd, msg, strlen(msg));
                    close(cfd);
                    continue;
                }
                next_session_id++;
                session_start(cfd, next_session_id);
            }
        }
        if (getppid() != init_ppid) {
            log_msg("orphaned (parent pid %d gone); exiting", (int)init_ppid);
            break;
        }
    }
    close(listener);
    if (g_storage.ops != NULL && g_storage.ops->destroy != NULL)
        g_storage.ops->destroy(&g_storage);
    log_msg("stopped (active sessions: %d)", (int)atomic_load(&g_active_sessions));
    return 0;
}
