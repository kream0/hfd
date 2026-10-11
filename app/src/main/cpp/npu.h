// Recite's encoder on the phone's NPU (a Snapdragon's Hexagon, through Qualcomm's QNN): Tarteel's
// tiny encoder compiled ahead of time for the phone's Hexagon (.github/workflows/npu.yml), run in
// place of whisper.cpp's own (whisper-encoder.patch: whisper_hfd_init_state). Built only with
// QNN's headers (HFD_QNN, CMakeLists.txt); without them hfd_npu_new says so.
#ifndef HFD_NPU_H
#define HFD_NPU_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct hfd_npu hfd_npu;

// Loads QNN's HTP backend from the app's libraries ([lib_dir], where the Hexagon's own part is
// read from too) and the compiled encoder at [binary]. NULL on failure, with why in [err].
hfd_npu *hfd_npu_new(const char *binary, const char *lib_dir, char *err, size_t err_cap);

void hfd_npu_free(hfd_npu *n);

// whisper_hfd_encoder_callback: the encoder's output for a log-mel input, on the NPU.
bool hfd_npu_encode(const float *mel, int n_mel, int n_frames, float *out, int n_ctx, int n_state, void *user);

// The last encoding's time on the NPU (µs), and its QNN error if it failed (0 if not).
int64_t hfd_npu_last_us(const hfd_npu *n);
uint64_t hfd_npu_last_error(const hfd_npu *n);

#endif
