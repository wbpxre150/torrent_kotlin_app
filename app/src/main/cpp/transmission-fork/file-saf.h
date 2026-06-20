// This file is part of the transmission SAF storage fork (Stage 05).
// Routes path-based tr_sys_* calls under the /saf/usb sentinel to the app-side SAF
// gateway over a Unix socket; fds cross the process boundary via SCM_RIGHTS.
#pragma once

#include <optional>
#include <string_view>

#include "libtransmission/file.h"

// True if [path] is the SAF sentinel root ("/saf/usb") or a descendant.
[[nodiscard]] bool tr_saf_is_path(std::string_view path) noexcept;

// SAF-backed replacements for the path-based tr_sys_* wrappers. Each is a complete
// replacement invoked when tr_saf_is_path(path) is true.
[[nodiscard]] tr_sys_file_t tr_saf_file_open(char const* path, int flags, int permissions, tr_error* error);
[[nodiscard]] bool tr_saf_path_exists(char const* path, tr_error* error);
[[nodiscard]] std::optional<tr_sys_path_info> tr_saf_path_get_info(std::string_view path, int flags, tr_error* error);
[[nodiscard]] bool tr_saf_path_rename(char const* src_path, char const* dst_path, tr_error* error);
[[nodiscard]] bool tr_saf_path_remove(char const* path, tr_error* error);
[[nodiscard]] bool tr_saf_dir_create(char const* path, int flags, int permissions, tr_error* error);
[[nodiscard]] std::optional<tr_sys_path_capacity> tr_saf_path_get_capacity(std::string_view path, tr_error* error);