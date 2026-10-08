// ANDROID fork: TRANSMISSION_USB_SAF disk I/O worker (stage 1).
#include <sys/prctl.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <future>
#include <utility>

#include <fmt/format.h>

#include "libtransmission/disk-io.h"

#include "libtransmission/cache.h"
#include "libtransmission/error.h"
#include "libtransmission/file.h"
#include "libtransmission/log.h"
#include "libtransmission/session.h"
#include "libtransmission/torrent.h"
#include "libtransmission/torrent-files.h"
#include "libtransmission/tr-strbuf.h" // tr_pathbuf
#include "libtransmission/utils.h" // _()
#include "libtransmission/disk-timing.h"

using namespace std::literals;

namespace
{

// Copied from read_entire_buf() in inout.cc (worker thread only).
bool read_entire_buf(tr_sys_file_t const fd, uint64_t file_offset, uint8_t* buf, uint64_t buflen, tr_error& error)
{
    while (buflen > 0U)
    {
        auto n_read = uint64_t{};

        if (!tr_sys_file_read_at(fd, buf, buflen, file_offset, &n_read, &error))
        {
            return false;
        }

        buf += n_read;
        buflen -= n_read;
        file_offset += n_read;
    }

    return true;
}

// Copied from write_entire_buf() in inout.cc (worker thread only).
bool write_entire_buf(tr_sys_file_t const fd, uint64_t file_offset, uint8_t const* buf, uint64_t buflen, tr_error& error)
{
    while (buflen > 0U)
    {
        auto n_written = uint64_t{};

        if (!tr_sys_file_write_at(fd, buf, buflen, file_offset, &n_written, &error))
        {
            return false;
        }

        buf += n_written;
        buflen -= n_written;
        file_offset += n_written;
    }

    return true;
}

// Worker thread only. The same logic as get_fd() in inout.cc, but using the
// self-contained tr_disk_file_ref built on the session thread.
// If `created` is non-null, it is set to true only when the final "create it"
// branch returns an fd (i.e. the file did not exist before).
std::optional<tr_sys_file_t> worker_get_fd(
    tr_open_files& files,
    tr_disk_file_ref const& file_ref,
    bool const writable,
    tr_error& error,
    bool* created = nullptr)
{
    // is the file already open in the fd pool?
    if (auto const fd = files.get(file_ref.tor_id, file_ref.file_index, writable); fd)
    {
        return fd;
    }

    // does the file exist in one of the search dirs?
    for (auto const& dir : file_ref.search_dirs)
    {
        for (auto const& path : { tr_pathbuf{ dir, '/', file_ref.subpath }, tr_pathbuf{ dir, '/', file_ref.subpath,
                                                                                  tr_torrent_files::PartialFileSuffix } })
        {
            if (tr_sys_path_get_info(path))
            {
                return files.get(file_ref.tor_id, file_ref.file_index, writable, path, file_ref.prealloc, file_ref.file_size);
            }
        }
    }

    // do we want to create it?
    if (writable)
    {
        if (auto const fd = files.get(
                file_ref.tor_id, file_ref.file_index, writable, file_ref.create_path, file_ref.prealloc, file_ref.file_size);
            fd)
        {
            if (created != nullptr)
            {
                *created = true;
            }
            return fd;
        }
    }

    error.set_from_errno(ENOENT);
    return {};
}

// Worker thread only. Reads [rel_offset, rel_offset + len) of the job's byte range, which the
// segments cover exactly, into `out`.
bool read_range(
    tr_open_files& files,
    std::vector<tr_disk_segment> const& segments,
    uint64_t rel_offset,
    uint64_t len,
    uint8_t* out,
    tr_error& error)
{
    auto const end = rel_offset + len;
    auto abs_pos = uint64_t{};
    auto remaining = len;

    for (auto const& seg : segments)
    {
        auto const seg_begin = abs_pos;
        auto const seg_end = abs_pos + seg.length;
        abs_pos = seg_end;

        if (seg_end <= rel_offset || seg_begin >= end || remaining == 0U)
        {
            continue;
        }

        auto const in_seg_offset = std::max(seg_begin, rel_offset) - seg_begin;
        auto const n = std::min(remaining, seg.length - in_seg_offset);
        auto const fd = worker_get_fd(files, seg.file, false, error);
        if (!fd)
        {
            return false;
        }

        {
            tr_disk_timer const timer{ "pread", seg.file.subpath, seg.file_offset + in_seg_offset, n };
            if (!read_entire_buf(*fd, seg.file_offset + in_seg_offset, out, n, error))
            {
                return false;
            }
        }

        out += n;
        remaining -= n;
    }

    return true;
}

// Worker thread only. Writes the concatenated `buffers` (whose combined size equals the sum of
// the segments' lengths) into the byte range the segments cover exactly, in chunks of at most
// the scratch buffer size. Stops at the first error, leaving it in `error` and `*failed_seg`
// pointing at the segment that failed (nullptr on success).
bool write_range(
    tr_open_files& files,
    std::vector<tr_disk_segment> const& segments,
    std::vector<std::shared_ptr<Cache::BlockData const>> const& buffers,
    size_t& files_created,
    tr_disk_segment const*& failed_seg,
    tr_error& error)
{
    auto scratch = std::vector<uint8_t>(1024U * 1024U); // reused across the job
    auto buffer_index = size_t{};
    auto buffer_offset = size_t{};
    auto pos = uint64_t{};

    for (auto const& seg : segments)
    {
        auto const seg_begin = pos;
        auto const seg_end = pos + seg.length;
        while (pos < seg_end)
        {
            auto const n = std::min<uint64_t>(seg_end - pos, std::size(scratch));

            // Fill scratch from the concatenated buffers.
            for (auto i = uint64_t{}; i < n; )
            {
                auto const& buf = *buffers[buffer_index];
                auto const buf_size = size_t{ std::size(buf) };
                auto const chunk = std::min<uint64_t>(n - i, buf_size - buffer_offset);
                std::copy(std::begin(buf) + buffer_offset, std::begin(buf) + buffer_offset + chunk, std::data(scratch) + i);
                buffer_offset += size_t{ chunk };
                i += chunk;
                if (buffer_offset == buf_size)
                {
                    ++buffer_index;
                    buffer_offset = 0U;
                }
            }

            auto file_created = bool{};
            auto const fd = worker_get_fd(files, seg.file, true, error, &file_created);
            if (!fd)
            {
                failed_seg = &seg;
                return false;
            }
            if (file_created)
            {
                ++files_created;
            }

            auto const off = seg.file_offset + (pos - seg_begin);
            {
                tr_disk_timer const t{ "pwrite", seg.file.subpath, off, n };
                if (!write_entire_buf(*fd, off, std::data(scratch), n, error))
                {
                    failed_seg = &seg;
                    return false;
                }
            }

            pos += n;
        }
    }

    return true;
}

} // namespace

tr_disk_io::tr_disk_io(tr_session& session)
    : session_{ session }
    , queue_{ []
              {
                  prctl(PR_SET_NAME, "tr-diskio", 0, 0, 0);
              } }
{
}

tr_disk_io::~tr_disk_io()
{
    queue_.stop_and_discard();
}

void tr_disk_io::test_piece(
    tr_disk_queue::Prio const prio,
    std::vector<tr_disk_segment> segments,
    std::vector<tr_disk_overlay_chunk> overlay,
    tr_sha1_digest_t const expected,
    std::function<void(int result)> done)
{
    queue_.push(
        prio,
        [this,
         session = &session_,
         segments = std::move(segments),
         overlay = std::move(overlay),
         expected,
         done = std::move(done)]() mutable
        {
            // Worker part. Hash the job's whole range, using the overlay for the cached parts.
            auto result = int{};
            {
                auto total = uint64_t{};
                for (auto const& seg : segments)
                {
                    total += seg.length;
                }

                tr_disk_timer const timer{ "testpiece", std::empty(segments) ? "" : segments.front().file.subpath, 0, total };

                auto sha = tr_sha1{};
                auto const buffer_size = 256U * 1024U; // reused per job
                auto buffer = std::vector<uint8_t>(buffer_size);
                auto pos = uint64_t{};
                auto overlay_iter = std::begin(overlay);
                auto read_failed = false;

                while (pos < total)
                {
                    if (overlay_iter != std::end(overlay) && overlay_iter->offset == pos)
                    {
                        sha.add(std::data(overlay_iter->data), std::size(overlay_iter->data));
                        pos += std::size(overlay_iter->data);
                        ++overlay_iter;
                        continue;
                    }

                    auto const next_chunk_offset = overlay_iter != std::end(overlay) ? overlay_iter->offset : total;
                    auto const n = std::min({ total - pos, next_chunk_offset - pos, uint64_t{ buffer_size } });
                    auto read_error = tr_error{};
                    if (!read_range(files_, segments, pos, n, std::data(buffer), read_error))
                    {
                        read_failed = true;
                        break;
                    }

                    sha.add(std::data(buffer), size_t{ n });
                    pos += n;
                }

                result = read_failed ? -1 : (sha.finish() == expected ? 1 : 0);
            }

            // Session part. Must not capture `this`.
            session->run_in_session_thread([done = std::move(done), result]()
                                           {
                                               done(result);
                                           });
        });
}

void tr_disk_io::read(
    std::vector<tr_disk_segment> segments,
    std::function<void(tr_disk_read_result)> done)
{
    // Session thread: count the read as in-flight before the worker starts.
    ++*reads_in_flight_;

    queue_.push(
        tr_disk_queue::Prio::High,
        [this,
         session = &session_,
         reads_in_flight = reads_in_flight_,
         segments = std::move(segments),
         done = std::move(done)]() mutable
        {
            // Worker part.
            auto result = tr_disk_read_result{};
            {
                auto total = uint64_t{};
                for (auto const& seg : segments)
                {
                    total += seg.length;
                }

                tr_disk_timer const timer{ "pread", std::empty(segments) ? "" : segments.front().file.subpath, 0, total };

                result.data.resize(total);

                auto error = tr_error{};
                if (!read_range(files_, segments, 0, total, std::data(result.data), error))
                {
                    result.err = error.code() != 0 ? error.code() : EIO;
                    result.data.clear();
                }
            }

            // Session part. Must not capture `this` (and must be const-callable:
            // run_in_session_thread wraps it in a std::function). `result` is held
            // in a shared_ptr so the data is moved, not copied, into `done`.
            session->run_in_session_thread(
                [reads_in_flight, done = std::move(done), result = std::make_shared<tr_disk_read_result>(std::move(result))]()
                {
                    // Decrement before `done` so the count is right even when the peer is gone.
                    --*reads_in_flight;
                    done(std::move(*result));
                });
        });
}

void tr_disk_io::write(
    std::vector<tr_disk_segment> segments,
    std::vector<std::shared_ptr<Cache::BlockData const>> buffers,
    std::function<void(tr_disk_write_result)> done)
{
    // Session thread: account for the bytes before the worker starts.
    auto total = uint64_t{};
    for (auto const& seg : segments)
    {
        total += seg.length;
    }
    queued_write_bytes_ += total;

    queue_.push(
        tr_disk_queue::Prio::Low,
        [this,
         session = &session_,
         segments = std::move(segments),
         buffers = std::move(buffers),
         done = std::move(done),
         total]()
        {
            // Worker part.
            auto result = tr_disk_write_result{};
            auto files_created = size_t{};
            auto const* failed_seg = static_cast<tr_disk_segment const*>(nullptr);
            auto error = tr_error{};

            write_range(files_, segments, buffers, files_created, failed_seg, error);
            if (error)
            {
                result.err = error.code();
                result.message = fmt::format(
                    fmt::runtime(_("Couldn't save '{path}': {error} ({error_code})")),
                    fmt::arg("path", failed_seg != nullptr ? failed_seg->file.subpath : ""s),
                    fmt::arg("error", error.message()),
                    fmt::arg("error_code", error.code()));
            }

            // Always subtract, whether the job succeeded or failed.
            queued_write_bytes_ -= total;

            // Session part. Must not capture `this`.
            session->run_in_session_thread([session, files_created, done = std::move(done), result = std::move(result)]()
                                           {
                                               for (auto i = size_t{}; i < files_created; ++i)
                                               {
                                                   session->add_file_created();
                                               }
                                               done(result);
                                           });
        });
}

void tr_disk_io::barrier(std::function<void()> done)
{
    queue_.push(
        tr_disk_queue::Prio::Low,
        [session = &session_, done = std::move(done)]()
        {
            // Session part. Must not capture `this`.
            session->run_in_session_thread([done = std::move(done)]()
                                           {
                                               done();
                                           });
        });
}

bool tr_disk_io::wait_idle(std::chrono::milliseconds const timeout)
{
    auto p = std::make_shared<std::promise<void>>();
    auto f = p->get_future();
    queue_.push(tr_disk_queue::Prio::Low, [p]
                {
                    p->set_value();
                });
    return f.wait_for(timeout) == std::future_status::ready;
}

bool tr_disk_io::writes_backlogged()
{
    auto const q = queued_write_bytes_.load();
    if (!backlogged_ && q >= HighWater)
    {
        backlogged_ = true;
        tr_logAddInfo("Disk write backlog: pausing block requests");
    }
    else if (backlogged_ && q <= LowWater)
    {
        backlogged_ = false;
        tr_logAddInfo("Disk write backlog: resuming block requests");
    }
    return backlogged_;
}

void tr_disk_io::close_torrent(tr_torrent_id_t const tor_id)
{
    queue_.push(tr_disk_queue::Prio::Low, [this, tor_id]
                {
                    files_.close_torrent(tor_id);
                });
}

void tr_disk_io::close_file(tr_torrent_id_t const tor_id, tr_file_index_t const file)
{
    queue_.push(tr_disk_queue::Prio::Low, [this, tor_id, file]
                {
                    files_.close_file(tor_id, file);
                });
}

void tr_disk_io::shutdown()
{
    queue_.stop_and_discard();
}

void tr_disk_io::shutdown_drain(std::chrono::steady_clock::time_point const deadline)
{
    queue_.stop_and_drain(deadline);
}

std::vector<tr_disk_segment> tr_disk_make_segments(
    tr_torrent const& tor, tr_block_info::Location loc, uint64_t len, bool writable)
{
    TR_ASSERT(tor.session->am_in_session_thread());

    auto const& session = *tor.session;
    auto const suffix = session.isIncompleteFileNamingEnabled() ? tr_torrent_files::PartialFileSuffix : ""sv;

    auto segments = std::vector<tr_disk_segment>{};
    auto [file_index, file_offset] = tor.file_offset(loc);
    while (len > 0U)
    {
        auto const file_size = tor.file_size(file_index);
        auto const bytes_this_pass = std::min(len, file_size - file_offset);
        if (bytes_this_pass > 0U)
        {
            // Zero-size files are skipped (inout.cc returns early for them) but file_index
            // still advances below.
            auto seg = tr_disk_segment{};
            seg.file.tor_id = tor.id();
            seg.file.file_index = file_index;
            seg.file.file_size = file_size;
            seg.file.subpath = tor.file_subpath(file_index);
            if (auto const& dir = tor.download_dir(); !std::empty(dir))
            {
                seg.file.search_dirs.emplace_back(dir.sv());
            }
            if (auto const& dir = tor.incomplete_dir(); !std::empty(dir))
            {
                seg.file.search_dirs.emplace_back(dir.sv());
            }
            seg.file.create_path = std::string{ tr_pathbuf{ tor.current_dir(), '/', tor.file_subpath(file_index), suffix }.sv() };
            seg.file.prealloc = writable && tor.file_is_wanted(file_index) ? session.preallocationMode() :
                                                                              tr_open_files::Preallocation::None;
            seg.file_offset = file_offset;
            seg.length = bytes_this_pass;
            segments.push_back(std::move(seg));
        }
        len -= bytes_this_pass;
        ++file_index;
        file_offset = 0U;
    }
    return segments;
}

std::vector<tr_disk_overlay_chunk> tr_disk_make_piece_overlay(tr_torrent const& tor, tr_piece_index_t piece)
{
    TR_ASSERT(tor.session->am_in_session_thread());

    auto const [piece_begin, piece_end] = tor.block_info().byte_span_for_piece(piece);
    auto const [block_begin, block_end] = tor.block_span_for_piece(piece);
    auto chunks = std::vector<tr_disk_overlay_chunk>{};

    for (auto block = block_begin; block < block_end; ++block)
    {
        auto const data = tor.session->cache->copy_cached_block(tor.id(), block);
        if (!data)
        {
            continue;
        }

        auto const loc = tor.block_loc(block);
        auto const block_begin_byte = uint64_t{ loc.byte };
        auto const block_end_byte = block_begin_byte + uint64_t{ tor.block_size(block) };
        auto const inter_begin = std::max(uint64_t{ piece_begin }, block_begin_byte);
        auto const inter_end = std::min(uint64_t{ piece_end }, block_end_byte);
        if (inter_begin >= inter_end || std::size(*data) < inter_end - block_begin_byte)
        {
            continue;
        }

        auto chunk = tr_disk_overlay_chunk{};
        chunk.offset = inter_begin - uint64_t{ piece_begin };
        auto const begin_in_block = inter_begin - block_begin_byte;
        auto const end_in_block = inter_end - block_begin_byte;
        chunk.data.assign(std::begin(*data) + begin_in_block, std::begin(*data) + end_in_block);
        chunks.push_back(std::move(chunk));
    }
    return chunks;
}
