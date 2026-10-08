// ANDROID fork: TRANSMISSION_USB_SAF disk I/O worker.
#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "libtransmission/block-info.h"
#include "libtransmission/cache.h" // Cache::BlockData (no include cycle: cache.h does not include disk-io.h)
#include "libtransmission/crypto-utils.h" // tr_sha1_digest_t
#include "libtransmission/disk-queue.h"
#include "libtransmission/open-files.h"
#include "libtransmission/transmission.h"

struct tr_torrent;
struct tr_session;

// Everything the worker needs to find/open/create one file. Built on the session thread.
struct tr_disk_file_ref
{
    tr_torrent_id_t tor_id = {};
    tr_file_index_t file_index = {};
    uint64_t file_size = {};
    std::string subpath;                    // tor.file_subpath(i)
    std::vector<std::string> search_dirs;   // non-empty download_dir(), then incomplete_dir()
    std::string create_path;                // current_dir()/subpath[+PartialFileSuffix]; used only for writes
    tr_open_files::Preallocation prealloc = tr_open_files::Preallocation::None; // writes only
};

// One contiguous byte range inside one file. A job's segments are in torrent byte order and
// together cover the job's byte range exactly.
struct tr_disk_segment
{
    tr_disk_file_ref file;
    uint64_t file_offset = {};
    uint64_t length = {};
};

// Result of a tr_disk_io::write job, delivered to `done` on the session thread.
struct tr_disk_write_result
{
    int err = 0;
    std::string message;
};

// Bytes already in memory (from the Cache), relative to the start of the job's byte range.
struct tr_disk_overlay_chunk
{
    uint64_t offset = {};
    std::vector<uint8_t> data;
};

// Session thread only. No filesystem calls.
[[nodiscard]] std::vector<tr_disk_segment> tr_disk_make_segments(
    tr_torrent const& tor, tr_block_info::Location loc, uint64_t len, bool writable);

// Session thread only. Copies the parts of cached blocks that fall inside piece `piece`.
[[nodiscard]] std::vector<tr_disk_overlay_chunk> tr_disk_make_piece_overlay(tr_torrent const& tor, tr_piece_index_t piece);

class tr_disk_io
{
public:
    explicit tr_disk_io(tr_session& session);
    ~tr_disk_io(); // stop_and_discard

    // Result: 1 good, 0 mismatch, -1 read error. `done` runs on the session thread.
    void test_piece(
        tr_disk_queue::Prio prio,
        std::vector<tr_disk_segment> segments,
        std::vector<tr_disk_overlay_chunk> overlay,
        tr_sha1_digest_t expected,
        std::function<void(int result)> done);

    // Write `buffers` (concatenated, in order) at the byte range described by `segments`.
    // Low queue. `done` runs on the session thread.
    void write(
        std::vector<tr_disk_segment> segments,
        std::vector<std::shared_ptr<Cache::BlockData const>> buffers,
        std::function<void(tr_disk_write_result)> done);

    // Low queue job that does nothing. `done` runs on the session thread once every Low job
    // enqueued before it has finished.
    void barrier(std::function<void()> done);

    // Session thread. Blocks until a barrier passes or `timeout` elapses. Returns false on timeout.
    // TEMPORARY (removed in stage 4).
    bool wait_idle(std::chrono::milliseconds timeout);

    // Session thread. Backpressure with hysteresis (64 MiB / 32 MiB).
    [[nodiscard]] bool writes_backlogged();

    // Fire-and-forget: close the worker's fds (Low queue, so after earlier Low jobs).
    void close_torrent(tr_torrent_id_t tor_id);
    void close_file(tr_torrent_id_t tor_id, tr_file_index_t file);

    void shutdown(); // stop_and_discard; called from tr_session::closeImplPart1

    // Drain the Low queue until empty or `deadline`, then stop.
    void shutdown_drain(std::chrono::steady_clock::time_point deadline);

private:
    tr_session& session_;
    tr_open_files files_; // worker thread only
    std::atomic<uint64_t> queued_write_bytes_{0};
    bool backlogged_ = false; // session thread only
    tr_disk_queue queue_; // last member: its thread starts in the constructor

    static uint64_t const HighWater = 64U * 1024U * 1024U; // 64 MiB
    static uint64_t const LowWater = 32U * 1024U * 1024U; // 32 MiB
};
