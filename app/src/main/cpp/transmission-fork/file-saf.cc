// transmission SAF storage fork — IPC client (Stage 05).
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <cstddef>
#include <fcntl.h>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>

#include "libtransmission/error.h"
#include "libtransmission/file.h"
#include "libtransmission/disk-timing.h"
#include "libtransmission/file-saf.h"


#include <android/log.h>

#define SAF_LOG_TAG "SafFileBackend"
#define saf_log_e(...) __android_log_print(ANDROID_LOG_ERROR, SAF_LOG_TAG, __VA_ARGS__)
#define saf_log_w(...) __android_log_print(ANDROID_LOG_WARN,  SAF_LOG_TAG, __VA_ARGS__)
namespace
{
constexpr char const* SAF_ROOT = "/saf/usb";

// Protocol op codes (must match SafIpcCodec in SafIpcCodec.kt).
constexpr uint8_t OP_OPEN = 1;
constexpr uint8_t OP_TRUNCATE = 2;
constexpr uint8_t OP_STAT = 3;
constexpr uint8_t OP_RENAME = 4;
constexpr uint8_t OP_REMOVE = 5;
constexpr uint8_t OP_MKDIRS = 6;
constexpr uint8_t OP_AVAIL = 7;
constexpr uint8_t OP_EXISTS = 8;

int g_sock = -1;
std::mutex g_mutex;

void set_err(tr_error* error, int e)
{
    if (error != nullptr)
    {
        error->set_from_errno(e);
    }
}

std::string strip_sentinel(std::string_view path)
{
    auto p = std::string{ path };
    if (p == SAF_ROOT)
    {
        return {};
    }
    // p starts with "/saf/usb/".
    return p.substr(std::strlen(SAF_ROOT) + 1);
}

void append_string(std::vector<uint8_t>& out, std::string const& s)
{
    uint16_t len = static_cast<uint16_t>(s.size());
    out.push_back(static_cast<uint8_t>(len & 0xFF));
    out.push_back(static_cast<uint8_t>((len >> 8) & 0xFF));
    out.insert(out.end(), s.begin(), s.end());
}

int ensure_sock()
{
    if (g_sock >= 0)
    {
        return g_sock;
    }
    char const* path = std::getenv("TORRENT_SAF_SOCKET");
    if (path == nullptr || path[0] == '\0')
    {
        saf_log_e("ensure_sock: TORRENT_SAF_SOCKET env not set; SAF bridge disabled");
        return -1;
    }
    int s = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0)
    {
        int e = errno;
        saf_log_e("ensure_sock: socket() failed errno=%d (%s)", e, std::strerror(e));
        return -1;
    }
    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    auto const path_len = std::strlen(path);
    if (path_len + 1 >= sizeof(addr.sun_path))
    {
        saf_log_e("ensure_sock: socket path too long (%zu)", path_len);
        ::close(s);
        return -1;
    }
    // Filesystem-namespace Unix socket: the path is the on-disk socket file
    // created by SafIpcServer (filesDir/saf-bridge.sock). No null prefix.
    std::memcpy(addr.sun_path, path, path_len + 1); // include trailing NUL
    socklen_t addr_len = static_cast<socklen_t>(
        offsetof(struct sockaddr_un, sun_path) + path_len);

    // Bounded retry: the server (SafIpcServer) binds synchronously in start(),
    // but a supervisor restart can race the child's first connect. Retry a few
    // times with a short sleep; log only on final failure to avoid log floods.
    bool connected = false;
    for (int attempt = 0; attempt < 5; ++attempt)
    {
        if (::connect(s, reinterpret_cast<struct sockaddr*>(&addr), addr_len) == 0)
        {
            connected = true;
            break;
        }
        if (attempt < 4)
        {
            ::usleep(200 * 1000);
        }
    }
    if (!connected)
    {
        int e = errno;
        saf_log_e("ensure_sock: connect(\"%s\") failed after retries errno=%d (%s)", path, e, std::strerror(e));
        ::close(s);
        return -1;
    }
    g_sock = s;
    return g_sock;
}

void drop_sock()
{
    if (g_sock >= 0)
    {
        ::close(g_sock);
        g_sock = -1;
    }
}

bool write_n(int s, void const* buf, size_t n)
{
    auto p = static_cast<char const*>(buf);
    size_t off = 0;
    while (off < n)
    {
        ssize_t w = ::write(s, p + off, n - off);
        if (w <= 0)
        {
            return false;
        }
        off += static_cast<size_t>(w);
    }
    return true;
}

bool read_n(int s, void* buf, size_t n)
{
    auto p = static_cast<char*>(buf);
    size_t off = 0;
    while (off < n)
    {
        ssize_t r = ::read(s, p + off, n - off);
        if (r <= 0)
        {
            return false;
        }
        off += static_cast<size_t>(r);
    }
    return true;
}

bool send_request(uint8_t op, std::vector<uint8_t> const& payload)
{
    int s = ensure_sock();
    if (s < 0)
    {
        saf_log_e("send_request: op=%u no socket (see ensure_sock above)", static_cast<unsigned>(op));
        return false;
    }
    uint8_t hdr[3] = { op, static_cast<uint8_t>(payload.size() & 0xFF),
        static_cast<uint8_t>((payload.size() >> 8) & 0xFF) };
    if (!write_n(s, hdr, 3))
    {
        int e = errno;
        saf_log_e("send_request: op=%u write header failed errno=%d (%s)", static_cast<unsigned>(op), e, std::strerror(e));
        drop_sock();
        return false;
    }
    if (!payload.empty() && !write_n(s, payload.data(), payload.size()))
    {
        int e = errno;
        saf_log_e("send_request: op=%u write payload (%zu bytes) failed errno=%d (%s)",
            static_cast<unsigned>(op), payload.size(), e, std::strerror(e));
        drop_sock();
        return false;
    }
    return true;
}

bool recv_response(std::vector<uint8_t>& payload, int32_t& status)
{
    int s = g_sock;
    if (s < 0)
    {
        saf_log_e("recv_response: no socket");
        return false;
    }
    uint8_t hdr[6];
    if (!read_n(s, hdr, 6))
    {
        saf_log_e("recv_response: read header failed (peer closed?)");
        drop_sock();
        return false;
    }
    std::memcpy(&status, hdr, 4); // little-endian; arm64 is LE
    uint16_t len = static_cast<uint16_t>(hdr[4]) | static_cast<uint16_t>(hdr[5] << 8);
    payload.resize(len);
    if (len > 0 && !read_n(s, payload.data(), len))
    {
        saf_log_e("recv_response: read %u-byte payload failed", static_cast<unsigned>(len));
        drop_sock();
        return false;
    }
    return true;
}

// Receive a response that may carry one fd via SCM_RIGHTS (OP_OPEN success).
// Returns the fd (>=0) on success, or -1 on failure.
int recv_response_with_fd(int32_t& status)
{
    int s = g_sock;
    if (s < 0)
    {
        return -1;
    }
    struct msghdr msg{};
    char cbuf[CMSG_SPACE(sizeof(int))];
    std::memset(&cbuf, 0, sizeof(cbuf));
    uint8_t hdr[6];
    struct iovec iov{};
    iov.iov_base = hdr;
    iov.iov_len = sizeof(hdr);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    ssize_t r = ::recvmsg(s, &msg, MSG_WAITALL);
    if (r != static_cast<ssize_t>(sizeof(hdr)))
    {
        int e = errno;
        saf_log_e("recv_response_with_fd: recvmsg returned %zd (expected %zu) errno=%d (%s)",
            r, sizeof(hdr), e, std::strerror(e));
        drop_sock();
        return -1;
    }
    std::memcpy(&status, hdr, 4);
    uint16_t len = static_cast<uint16_t>(hdr[4]) | static_cast<uint16_t>(hdr[5] << 8);
    if (len > 0)
    {
        std::vector<uint8_t> tmp(len);
        if (!read_n(s, tmp.data(), len))
        {
            saf_log_e("recv_response_with_fd: read %u-byte payload failed", static_cast<unsigned>(len));
            drop_sock();
            return -1;
        }
    }
    int fd = -1;
    for (struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg != nullptr; cmsg = CMSG_NXTHDR(&msg, cmsg))
    {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS)
        {
            std::memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
            break;
        }
    }
    return fd;
}
} // namespace

bool tr_saf_is_path(std::string_view path) noexcept
{
    if (path == SAF_ROOT)
    {
        return true;
    }
    return path.rfind("/saf/usb/", 0) == 0;
}

tr_sys_file_t tr_saf_file_open(char const* path, int flags, int /*permissions*/, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-open", path };
    std::lock_guard<std::mutex> lk(g_mutex);
    std::string rel = strip_sentinel(path);
    std::vector<uint8_t> payload;
    payload.push_back((flags & TR_SYS_FILE_WRITE) != 0 ? 1 : 0); // MODE_RW / MODE_R
    append_string(payload, rel);
    if (!send_request(OP_OPEN, payload))
    {
        set_err(error, EIO);
        return TR_BAD_SYS_FILE;
    }
    int32_t status = 0;
    int fd = recv_response_with_fd(status);
    if (fd < 0 || status != 0)
    {
        if (fd >= 0)
        {
            ::close(fd);
        }
        set_err(error, EIO);
        return TR_BAD_SYS_FILE;
    }
    if ((flags & TR_SYS_FILE_TRUNCATE) != 0)
    {
        ::ftruncate(fd, 0);
    }
    return fd;
}

bool tr_saf_path_exists(char const* path, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-exists", path };
    std::lock_guard<std::mutex> lk(g_mutex);
    std::vector<uint8_t> payload;
    append_string(payload, strip_sentinel(path));
    if (!send_request(OP_EXISTS, payload))
    {
        set_err(error, EIO);
        return false;
    }
    int32_t status = 0;
    std::vector<uint8_t> resp;
    if (!recv_response(resp, status))
    {
        set_err(error, EIO);
        return false;
    }
    return status == 1;
}

std::optional<tr_sys_path_info> tr_saf_path_get_info(std::string_view path, int /*flags*/, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-stat", path };
    std::lock_guard<std::mutex> lk(g_mutex);
    std::vector<uint8_t> payload;
    append_string(payload, strip_sentinel(path));
    if (!send_request(OP_STAT, payload))
    {
        set_err(error, EIO);
        return {};
    }
    int32_t status = 0;
    std::vector<uint8_t> resp;
    if (!recv_response(resp, status))
    {
        set_err(error, EIO);
        return {};
    }
    if (status != 0 || resp.size() < 9)
    {
        set_err(error, ENOENT);
        return {};
    }
    tr_sys_path_info info{};
    info.type = (resp[0] == 1) ? TR_SYS_PATH_IS_DIRECTORY : TR_SYS_PATH_IS_FILE;
    int64_t size = 0;
    std::memcpy(&size, resp.data() + 1, 8);
    info.size = static_cast<uint64_t>(size);
    int64_t mtime = 0;
    if (resp.size() >= 17)
    {
        std::memcpy(&mtime, resp.data() + 9, 8);
    }
    info.last_modified_at = static_cast<time_t>(mtime);
    return info;
}

bool tr_saf_path_rename(char const* src_path, char const* dst_path, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-rename", src_path };
    std::lock_guard<std::mutex> lk(g_mutex);
    std::string dst(dst_path);
    auto pos = dst.find_last_of('/');
    std::string newName = (pos == std::string::npos) ? dst : dst.substr(pos + 1);
    std::vector<uint8_t> payload;
    append_string(payload, strip_sentinel(src_path));
    append_string(payload, newName);
    if (!send_request(OP_RENAME, payload))
    {
        set_err(error, EIO);
        return false;
    }
    int32_t status = 0;
    std::vector<uint8_t> resp;
    if (!recv_response(resp, status))
    {
        set_err(error, EIO);
        return false;
    }
    if (status != 0)
    {
        set_err(error, EIO);
        return false;
    }
    return true;
}

bool tr_saf_path_is_same(char const* path1, char const* path2, tr_error* /*error*/)
{
    bool const saf1 = tr_saf_is_path(path1);
    bool const saf2 = tr_saf_is_path(path2);
    if (!saf1 || !saf2)
    {
        // One is SAF, the other isn't — they can't be the same document.
        return false;
    }
    auto const rel1 = strip_sentinel(path1);
    auto const rel2 = strip_sentinel(path2);
    return rel1 == rel2;
}

bool tr_saf_path_remove(char const* path, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-remove", path };
    std::lock_guard<std::mutex> lk(g_mutex);
    std::vector<uint8_t> payload;
    append_string(payload, strip_sentinel(path));
    if (!send_request(OP_REMOVE, payload))
    {
        set_err(error, EIO);
        return false;
    }
    int32_t status = 0;
    std::vector<uint8_t> resp;
    if (!recv_response(resp, status))
    {
        set_err(error, EIO);
        return false;
    }
    return status == 0;
}

bool tr_saf_dir_create(char const* path, int /*flags*/, int /*permissions*/, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-mkdir", path };
    std::lock_guard<std::mutex> lk(g_mutex);
    std::vector<uint8_t> payload;
    append_string(payload, strip_sentinel(path));
    if (!send_request(OP_MKDIRS, payload))
    {
        set_err(error, EIO);
        return false;
    }
    int32_t status = 0;
    std::vector<uint8_t> resp;
    if (!recv_response(resp, status))
    {
        set_err(error, EIO);
        return false;
    }
    return status == 0;
}

std::optional<tr_sys_path_capacity> tr_saf_path_get_capacity(std::string_view path, tr_error* error)
{
    // ANDROID fork: stage 0 timing. Timer before the lock_guard so lock wait counts.
    tr_disk_timer const disk_timer{ "saf-capacity", path };
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!send_request(OP_AVAIL, {}))
    {
        set_err(error, EIO);
        return {};
    }
    int32_t status = 0;
    std::vector<uint8_t> resp;
    if (!recv_response(resp, status))
    {
        set_err(error, EIO);
        return {};
    }
    if (status != 0 || resp.size() < 8)
    {
        set_err(error, EIO);
        return {};
    }
    int64_t bytes = 0;
    std::memcpy(&bytes, resp.data(), 8);
    tr_sys_path_capacity cap{};
    cap.free = bytes;
    cap.total = -1; // unknown
    return cap;
}