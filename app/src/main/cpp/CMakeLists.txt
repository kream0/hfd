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
    # Its encoder may run elsewhere (the NPU, npu.c): whisper_hfd_init_state.
    PATCH_COMMAND patch -p1 -i ${CMAKE_CURRENT_SOURCE_DIR}/whisper-encoder.patch
)
FetchContent_MakeAvailable(whisper)

add_library(hfdwhisper SHARED whisper_jni.c npu.c)
target_link_libraries(hfdwhisper PRIVATE whisper log m)
# QNN's headers (Qualcomm's AI Runtime SDK; build.yml fetches them, they aren't in the repository):
# without them the NPU is left out and npu.c says so.
if (DEFINED HFD_QNN_INCLUDE AND EXISTS "${HFD_QNN_INCLUDE}/QnnInterface.h")
    target_include_directories(hfdwhisper PRIVATE ${HFD_QNN_INCLUDE})
    target_compile_definitions(hfdwhisper PRIVATE HFD_QNN)
    message(STATUS "NPU: QNN headers in ${HFD_QNN_INCLUDE}")
else()
    message(WARNING "NPU left out: no QNN headers (HFD_QNN_INCLUDE)")
endif()
