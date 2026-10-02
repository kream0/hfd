# whisper.cpp for the Recite mode: speech recognition on the phone, with Tarteel's Qur'an model.
cmake_minimum_required(VERSION 3.22.1)
project(hfdwhisper C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Portable ARMv8 build (no -march=native when cross-compiling), and ggml's own thread pool.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.6.tar.gz
)
FetchContent_MakeAvailable(whisper)

add_library(hfdwhisper SHARED whisper_jni.c)
target_link_libraries(hfdwhisper PRIVATE whisper log m)
