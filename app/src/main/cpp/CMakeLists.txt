# whisper.cpp for the Recite mode: speech recognition on the phone, with Tarteel's Qur'an model.
cmake_minimum_required(VERSION 3.22.1)
project(hfdwhisper C CXX)

include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# ggml's CPU code in several builds, one per family of ARM cores (plain ARMv8; ARMv8.2 with dot
# products and half floats; ARMv8.6 with int8 matrix multiplies too), the phone's best loaded at
# start (whisper_jni.c): the right instructions on any 64-bit ARM phone. Against plain ARMv8 a
# reading takes 2.2 times less with ARMv8.2 (539 vs 1178 ms), and 13 % less again with ARMv8.6's
# int8 matrix multiplies (461 vs 530 ms; armspeed.yml, GitHub's Neoverse N2, 2 and 10 Oct).
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.6.tar.gz
)
FetchContent_MakeAvailable(whisper)

add_library(hfdwhisper SHARED whisper_jni.c)
target_link_libraries(hfdwhisper PRIVATE whisper log m)
