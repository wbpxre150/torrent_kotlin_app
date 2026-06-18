#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <time.h>

/* Pattern byte for the sparse random-access test. */
#define PATTERN 0x5A

/*
 * Validate that [fd] is a seekable fd usable for torrent-style random-access I/O.
 * Steps at [offset] (a >4GB offset):
 *   1. lseek(fd, offset, SEEK_SET)  -> must return offset (seekable).
 *   2. pwrite(fd, PATTERN, 1, offset) -> must write 1 byte.
 *   3. pread (fd, buf, 1, offset)     -> must read PATTERN back.
 *   4. ftruncate(fd, offset + 1)      -> grow (sparse) to offset+1.
 *   5. ftruncate(fd, 0)               -> shrink back to 0.
 *   6. fsync(fd).
 * Returns 0 on success, -errno (negative) on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_torrent_app_storage_SafBridgeTest_nativeValidateFd(
    JNIEnv *env, jclass cls, jint fd, jlong offset) {
    (void)env; (void)cls;
    if (fd < 0) return (jlong)(-EINVAL);

    /* 1. seekable? */
    off_t pos = lseek((int)fd, (off_t)offset, SEEK_SET);
    if (pos == (off_t)-1) return (jlong)(-errno);
    if (pos != (off_t)offset) return (jlong)(-EIO);  /* not actually seekable */

    /* 2. pwrite one byte at the >4GB offset */
    unsigned char b = PATTERN;
    ssize_t w = pwrite((int)fd, &b, 1, (off_t)offset);
    if (w != 1) return (jlong)(-errno);

    /* 3. pread it back */
    unsigned char r = 0;
    ssize_t rd = pread((int)fd, &r, 1, (off_t)offset);
    if (rd != 1) return (jlong)(-errno);
    if (r != PATTERN) return (jlong)(-EIO);

    /* 4. grow via ftruncate (sparse) */
    if (ftruncate((int)fd, (off_t)(offset + 1)) != 0) return (jlong)(-errno);

    /* 5. shrink back to 0 */
    if (ftruncate((int)fd, 0) != 0) return (jlong)(-errno);

    /* 6. fsync */
    if (fsync((int)fd) != 0) {
        /* fsync may be unsupported over FUSE; treat as non-fatal: do NOT fail. */
    }

    return 0;
}

/*
 * Measure sequential write throughput to [fd]. Writes [bytes] in 1 MiB chunks via
 * pwrite at increasing offsets (starting at 0). Returns elapsed nanoseconds, or
 * -errno (negative) on the first failed write.
 */
JNIEXPORT jlong JNICALL
Java_com_torrent_app_storage_SafBridgeTest_nativeThroughput(
    JNIEnv *env, jclass cls, jint fd, jlong bytes) {
    (void)env; (void)cls;
    if (fd < 0 || bytes <= 0) return (jlong)(-EINVAL);

    static unsigned char buf[1024 * 1024];
    memset(buf, PATTERN, sizeof(buf));

    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);

    off_t off = 0;
    jlong remaining = bytes;
    while (remaining > 0) {
        size_t chunk = sizeof(buf);
        if ((jlong)chunk > remaining) chunk = (size_t)remaining;
        ssize_t w = pwrite((int)fd, buf, chunk, off);
        if (w != (ssize_t)chunk) return (jlong)(-errno);
        off += w;
        remaining -= w;
    }

    fsync((int)fd);  /* best-effort */
    clock_gettime(CLOCK_MONOTONIC, &t1);

    jlong ns = (jlong)(t1.tv_sec - t0.tv_sec) * 1000000000LL +
               (jlong)(t1.tv_nsec - t0.tv_nsec);
    return ns < 0 ? 0 : ns;
}
