# Host build for the native FTP daemon (no Android-specific code).
#
#   cmake -S app/src/main/cpp/ftp-server/host -B build/ftp-host
#   cmake --build build/ftp-host
#
# Backend selection (compile-time): -DFTPD_BACKEND=posix (default) | saf.
# The saf backend lands in stage 2.

cmake_minimum_required(VERSION 3.16)
project(ftpserver_host C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(FTPD_BACKEND "Storage backend: posix or saf" "posix")

set(FTPD_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)

set(FTPD_SOURCES
  ${FTPD_SRC_DIR}/ftpd.c
  ${FTPD_SRC_DIR}/session.c
  ${FTPD_SRC_DIR}/util.c
)

if(FTPD_BACKEND STREQUAL "saf")
  list(APPEND FTPD_SOURCES ${FTPD_SRC_DIR}/storage_saf.c)
  set(FTPD_BACKEND_DEF FTPD_STORAGE_SAF)
else()
  list(APPEND FTPD_SOURCES ${FTPD_SRC_DIR}/storage_posix.c)
  set(FTPD_BACKEND_DEF FTPD_STORAGE_POSIX)
endif()

add_executable(ftpserver-host ${FTPD_SOURCES})
target_compile_options(ftpserver-host PRIVATE -O2 -Wall -Wextra)
target_compile_definitions(ftpserver-host PRIVATE ${FTPD_BACKEND_DEF})
target_link_libraries(ftpserver-host PRIVATE pthread)
