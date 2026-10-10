// Option 4 of the speed work (10 Oct), parked: whisper.cpp v1.7.6 patched (whisper-prefix.patch)
// to take the start of a reading as already read, decoded in one batch after the task tokens.
// Times a reading free and with each given prefix (best of 3; T = threads):
//   patch -p1 -d whisper.cpp < whisper-prefix.patch; cmake ... --target whisper
//   gcc -O2 -o prefixtime prefixtime.c -Iwhisper.cpp/include -Iwhisper.cpp/ggml/include libwhisper.so
//   ./prefixtime model.bin clip.f32 " قُلْ هُوَ اللَّهُ أَحَدٌ" ...   (clip: float32 16 kHz mono)
// On 4× al-Ikhlāṣ (12.9 s, x86, 4 threads): free 584 ms (58 tokens); a 36-token prefix costs
// ~21 ms against ~50 ms read token by token; the encoder is ~480 ms of it. Per token: 3.8 ms on
// 1 thread, 2.5 on 2, 0.9 on 4. The phone (1.14.3): ~560 ms + ~22 ms per second of audio (about
// 4 ms a token). Worth it once the encoder is fast (NPU), not before: ~15 % of a long reading.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "whisper.h"
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
static void run(struct whisper_context *ctx, float *pcm, int n, const whisper_token *pre, int npre, const char *label) {
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = getenv("T") ? atoi(getenv("T")) : 4; p.language = "ar"; p.no_timestamps = 1; p.single_segment = 1; p.no_context = 1;
    p.print_progress = 0; p.print_realtime = 0; p.print_timestamps = 0; p.suppress_blank = 1;
    p.hfd_prefix_tokens = pre; p.hfd_prefix_n_tokens = npre;
    double best = 1e9; char out[4096] = "";
    for (int r = 0; r < 3; r++) {
        double t = now();
        whisper_full(ctx, p, pcm, n);
        t = now() - t; if (t < best) best = t;
        out[0] = 0;
        for (int i = 0; i < whisper_full_n_segments(ctx); i++) strncat(out, whisper_full_get_segment_text(ctx, i), sizeof(out) - strlen(out) - 1);
    }
    int ntok = 0; for (int i = 0; i < whisper_full_n_segments(ctx); i++) ntok += whisper_full_n_tokens(ctx, i);
    printf("%-10s %4.0f ms  prefix %2d tokens, %2d generated: %s\n", label, best * 1000, npre, ntok, out);
}
int main(int argc, char **argv) {
    struct whisper_context_params cp = whisper_context_default_params(); cp.use_gpu = 0;
    struct whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    FILE *f = fopen(argv[2], "rb"); fseek(f, 0, SEEK_END); int n = ftell(f) / 4; fseek(f, 0, SEEK_SET);
    float *pcm = malloc(n * 4);
    if (fread(pcm, 4, n, f) != (size_t) n) return 1;
    fclose(f);
    run(ctx, pcm, n, NULL, 0, "free");
    for (int a = 3; a < argc; a++) {
        whisper_token toks[512];
        int k = whisper_tokenize(ctx, argv[a], toks, 512);
        run(ctx, pcm, n, toks, k, "prefixed");
    }
    return 0;
}
