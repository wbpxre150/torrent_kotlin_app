/*
 * ftp-server daemon: shared config and globals.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 */
#ifndef FTPD_FTPD_H
#define FTPD_FTPD_H

#include <netinet/in.h>
#include <stdatomic.h>
#include <stdint.h>

#include "storage.h"

#define FTPD_MAX_SESSIONS 8
#define FTPD_IDLE_TIMEOUT_S 600
#define FTPD_PASV_ACCEPT_TIMEOUT_S 30

typedef struct {
    char bind_ip[64];
    in_addr_t bind_addr;
    uint16_t port;
    char root[4096];
    uint16_t pasv_lo;
    uint16_t pasv_hi;
    double max_kibps;
    char saf_socket[4096]; /* used by the SAF backend (stage 2) */
} ftpd_config_t;

extern ftpd_config_t g_config;
extern storage_backend_t g_storage;
extern atomic_int g_active_sessions;

#endif /* FTPD_FTPD_H */
