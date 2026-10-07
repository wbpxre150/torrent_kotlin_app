// ANDROID fork: slow disk-operation logging (TRANSMISSION_USB_SAF stage 0).
#pragma once

#include <sys/prctl.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>

#include <fmt/format.h>

#include "libtransmission/log.h"

class tr_disk_timer
{
public:
    tr_disk_timer(char const* op, std::string_view path, uint64_t offset = 0, uint64_t len = 0)
        : op_{ op }
        , path_{ path }
        , offset_{ offset }
        , len_{ len }
        , start_{ std::chrono::steady_clock::now() }
    {
    }

    tr_disk_timer(tr_disk_timer const&) = delete;
    tr_disk_timer& operator=(tr_disk_timer const&) = delete;

    ~tr_disk_timer()
    {
        auto const ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start_)
                            .count();
        char name[16] = {};
        prctl(PR_GET_NAME, name, 0, 0, 0);
        auto const on_session = std::strcmp(name, "tr-session") == 0;
        auto const threshold = on_session ? SessionThresholdMs : OtherThresholdMs;
        if (ms >= threshold)
        {
            tr_logAddWarn(fmt::format(
                "[DISKSLOW] thread={} op={} path={} offset={} len={} ms={}",
                name,
                op_,
                path_,
                offset_,
                len_,
                ms));
        }
    }

    static constexpr long long SessionThresholdMs = 100;
    static constexpr long long OtherThresholdMs = 100; // stage 5 raises this

private:
    char const* op_;
    std::string path_;
    uint64_t offset_;
    uint64_t len_;
    std::chrono::steady_clock::time_point start_;
};
