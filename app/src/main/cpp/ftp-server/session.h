/*
 * ftp-server session layer: one detached pthread per control connection,
 * command dispatcher and per-command handlers.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 */
#ifndef FTPD_SESSION_H
#define FTPD_SESSION_H

/* Spawn a detached session thread for an accepted control connection. */
void session_start(int fd, int id);

#endif /* FTPD_SESSION_H */
