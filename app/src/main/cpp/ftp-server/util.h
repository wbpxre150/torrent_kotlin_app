/*
 * ftp-server utilities: networking helpers, LIST line and time formatting,
 * path normalization, logging, global rate bucket.
 *
 * Architecture derived from sunaku/simple-ftp (https://github.com/sunaku/simple-ftp);
 * protocol and storage reimplemented.
 */
#ifndef FTPD_UTIL_H
#define FTPD_UTIL_H

#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
#include <time.h>

#include "storage.h"

/* Write all bytes; MSG_NOSIGNAL. Returns bytes written or -1. */
ssize_t send_all(int fd, const void *buf, size_t len);

/* Create, bind (SO_REUSEADDR) and listen on an IPv4 TCP socket. 0 on success. */
int net_make_listener(in_addr_t addr, unsigned short port, int *fd_out);

int net_set_nodelay(int fd);

/* "ip:port" into buf. Returns 0 on success. */
int addr_to_str(const struct sockaddr_in *sa, char *buf, size_t sz);

/* "Mon dd HH:MM" (within 6 months) or "Mon dd  YYYY"; hardcoded English months. */
void format_list_time(time_t mtime, char *out, size_t sz);

/* "YYYYMMDDHHMMSS" in UTC. */
void format_mdtm_utc(time_t mtime, char *out, size_t sz);

/* RFC 3659 MLST/MLSD fact mask bits. */
#define MLSX_FACT_TYPE 1
#define MLSX_FACT_SIZE 2
#define MLSX_FACT_MODIFY 4
#define MLSX_FACT_PERM 8
#define MLSX_FACT_ALL 15

/*
 * RFC 3659 MLST/MLSD facts string: type, size, modify, perm in that order,
 * each terminated by ';', only facts set in mask (size is omitted for
 * directories even when requested). No name and no trailing space; the
 * caller appends " name". modify is "YYYYMMDDHHMMSS" in UTC from
 * mtime_ms/1000. mask 0 yields "". Returns 0 on success, -1 if out is too
 * small (in which case out is left unmodified).
 */
int format_mlsx_facts(char *out, size_t cap, int is_dir, uint64_t size,
                      int64_t mtime_ms, unsigned mask);

/*
 * Parse an OPTS MLST argument (e.g. "type;size;" or "Type;Modify") into a
 * fact mask: case-insensitive, ';' separated, unknown facts ignored.
 * NULL or empty yields 0.
 */
unsigned parse_mlst_opts(const char *arg);

/*
 * RFC 3659 FEAT line content: "MLST type*;size*;modify*;perm*" where '*'
 * marks facts currently enabled in mask. Returns 0 on success, -1 if too
 * small.
 */
int format_mlst_feat(char *out, size_t cap, unsigned mask);

/*
 * Enabled-facts list ("type;size;") for OPTS MLST 200 replies. Returns 0 on
 * success, -1 if too small.
 */
int format_mlst_opts_list(char *out, size_t cap, unsigned mask);

/*
 * Normalize a raw path argument into a virtual-root-relative path:
 * strips leading '/', collapses '.', '..', '//' segments; '..' at the root
 * clamps to the root. out is "" for the root. 0 on success, -1 if too long.
 */
int normalize_relpath(const char *in, char *out, size_t outsz);

/* Join a cwd with a raw argument and normalize. 0 on success, -1 if too long. */
int join_relpath(const char *cwd, const char *arg, char *out, size_t outsz);

/*
 * Strip leading "-xyz" option tokens (e.g. "-la", "-l -a") from a LIST/NLST
 * argument. Returns the remaining path argument, or NULL if none.
 */
const char *strip_list_opts(const char *arg);

/* Quote a path for PWD-style replies: wraps in quotes, doubles inner quotes. */
void ftpd_quote_path(const char *in, char *out, size_t outsz);

/*
 * One LIST line, byte-for-byte baseline format, CRLF terminated:
 * "dr-x------   1 torrent torrent <12-wide size> <Mon dd HH:MM|Mon dd  YYYY> name"
 * (directories show size 0).
 */
void format_list_line(const storage_entry_t *e, char *out, size_t outsz);

/* Timestamped line-buffered log to stdout. */
void log_msg(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void log_session(int id, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

/*
 * Global token-bucket rate limit. rate_limit_init(kibps) with 0 = unlimited.
 * rate_limit(bytes) sleeps when the bucket is exhausted (mutex-protected).
 */
void rate_limit_init(double kibps);
void rate_limit(int64_t bytes);

#endif /* FTPD_UTIL_H */
