// The app's decoding (app/src/main/cpp/whisper_jni.c, nativeTranscribe) as a process, for the
// Recite bench (core/src/test/.../ReciteBench.kt): loads the model once, then for each request on
// stdin (int32 little-endian sample count, float32 samples at 16 kHz mono; then int32 byte count
// and UTF-8 text of the prompt, the text before: 0 for none; then int32 byte count and UTF-8 text
// of what the reciter is expected to say, one continuation per line: 0 to decode freely) writes
// the text heard on one line of stdout. Usage: decoder <model> [threads]. Environment (to compare
// settings on the bench): HFD_BIAS_BONUS (nats), HFD_BEAM (beam size for steered decoding; greedy
// by default, as the app).
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "bias.h"

static void quiet(enum ggml_log_level level, const char *text, void *user) {}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: decoder <model> [threads]\n"); return 2; }
    whisper_log_set(quiet, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (ctx == NULL) { fprintf(stderr, "could not load %s\n", argv[1]); return 1; }
    int threads = argc > 2 ? atoi(argv[2]) : 4;
    for (;;) {
        int32_t n;
        if (fread(&n, 4, 1, stdin) != 1 || n < 0) break;
        float *pcm = malloc(sizeof(float) * (n > 0 ? n : 1));
        if (fread(pcm, 4, n, stdin) != (size_t) n) { free(pcm); break; }
        int32_t np;
        if (fread(&np, 4, 1, stdin) != 1 || np < 0) { free(pcm); break; }
        char *prompt = malloc(np + 1);
        if (fread(prompt, 1, np, stdin) != (size_t) np) { free(pcm); free(prompt); break; }
        prompt[np] = 0;
        int32_t ne;
        if (fread(&ne, 4, 1, stdin) != 1 || ne < 0) { free(pcm); free(prompt); break; }
        char *expected = malloc(ne + 1);
        if (fread(expected, 1, ne, stdin) != (size_t) ne) { free(pcm); free(prompt); free(expected); break; }
        expected[ne] = 0;
        const char *bonus = getenv("HFD_BIAS_BONUS");
        hfd_bias *bias = ne > 0 ? hfd_bias_new(expected, bonus ? (float) atof(bonus) : HFD_BIAS_BONUS) : NULL;
        const char *beam_env = getenv("HFD_BEAM");
        int beam = bias && beam_env ? atoi(beam_env) : 0;

        struct whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
        if (beam > 1) p.beam_search.beam_size = beam;
        p.n_threads = threads;
        p.language = "ar";
        p.translate = false;
        p.no_timestamps = true;
        p.single_segment = true;
        p.no_context = true;
        p.print_realtime = false;
        p.print_progress = false;
        p.print_timestamps = false;
        p.print_special = false;
        p.suppress_blank = true;
        p.audio_ctx = 0;
        if (np > 0) p.initial_prompt = prompt;
        if (bias) {
            p.logits_filter_callback = hfd_bias_filter;
            p.logits_filter_callback_user_data = bias;
        }
        if (whisper_full(ctx, p, pcm, n) == 0) {
            for (int i = 0; i < whisper_full_n_segments(ctx); i++) {
                const char *s = whisper_full_get_segment_text(ctx, i);
                for (; *s; s++) putchar(*s == '\n' || *s == '\r' ? ' ' : *s);
                putchar(' ');
            }
        }
        putchar('\n');
        fflush(stdout);
        free(pcm);
        free(prompt);
        free(expected);
        hfd_bias_free(bias);
    }
    whisper_free(ctx);
    return 0;
}
