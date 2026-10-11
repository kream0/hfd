// Recite's decoder (whisper.cpp patched as the app's, whisper-encoder.patch) on an encoder output
// computed elsewhere: ONNX Runtime's, or the NPU's through npu.c on QNN's emulator. Prints the text
// it reads, to check a compiled encoder end to end (npu.yml). The audio is under 30 s (one window).
// Usage: decode <model> <audio.f32> <encoder output.raw: 1500 rows of 384, float32>
#include <stdio.h>
#include <stdlib.h>
#include "whisper.h"

static const char *encoded;

static bool from_file(const float *mel, int n_mel, int n_frames, float *out, int n_ctx, int n_state, void *user) {
    (void) mel; (void) n_mel; (void) n_frames; (void) user;
    FILE *f = fopen(encoded, "rb");
    if (f == NULL) return false;
    size_t n = (size_t) n_ctx * n_state, got = fread(out, sizeof(float), n, f);
    fclose(f);
    return got == n;
}

static void quiet(enum ggml_log_level level, const char *text, void *user) { (void) level; (void) text; (void) user; }

int main(int argc, char **argv) {
    if (argc != 4) { fprintf(stderr, "usage: decode <model> <audio.f32> <encoder.raw>\n"); return 2; }
    encoded = argv[3];
    whisper_log_set(quiet, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params_no_state(argv[1], cp);
    FILE *f = fopen(argv[2], "rb");
    if (ctx == NULL || f == NULL) { fprintf(stderr, "can't open the model or the audio\n"); return 1; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f) / (long) sizeof(float);
    fseek(f, 0, SEEK_SET);
    float *pcm = malloc((size_t) n * sizeof(float));
    if (fread(pcm, sizeof(float), (size_t) n, f) != (size_t) n) return 1;
    fclose(f);
    struct whisper_state *state = whisper_hfd_init_state(ctx, from_file, NULL);
    // As the app reads (whisper_jni.c), without the steering: what the encoder output alone gives.
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = 4;
    p.language = "ar";
    p.no_timestamps = true;
    p.single_segment = true;
    p.no_context = true;
    p.suppress_blank = true;
    p.print_progress = p.print_realtime = p.print_timestamps = p.print_special = false;
    if (whisper_full_with_state(ctx, state, p, pcm, (int) n) != 0) { fprintf(stderr, "whisper_full failed\n"); return 1; }
    for (int i = 0; i < whisper_full_n_segments_from_state(state); i++) printf("%s", whisper_full_get_segment_text_from_state(state, i));
    printf("\n");
    return 0;
}
