// The app's decoding of one WAV (16 kHz mono 16-bit), freely or steered toward expected text
// (bias.h), for .github/workflows/experiment.yml.
// Usage: biascli <model> <wav> <free|hard|margin> [expected.txt (one continuation per line)]
// Prints: <steered tokens> <ms> <mean log-probability of the tokens, the model's own> <text>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "whisper.h"
#include "bias.h"

static void quiet(enum ggml_log_level level, const char *text, void *user) { (void) level; (void) text; (void) user; }

static float *read_wav(const char *path, int *n) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(size);
    if (fread(b, 1, size, f) != (size_t) size) { fclose(f); free(b); return NULL; }
    fclose(f);
    long at = 12;
    while (at + 8 <= size) {
        int len = b[at + 4] | b[at + 5] << 8 | b[at + 6] << 16 | b[at + 7] << 24;
        if (memcmp(b + at, "data", 4) == 0) {
            long bytes = len < size - at - 8 ? len : size - at - 8;
            *n = (int) (bytes / 2);
            float *pcm = malloc(sizeof(float) * (*n > 0 ? *n : 1));
            for (int i = 0; i < *n; i++) pcm[i] = (short) (b[at + 8 + 2 * i] | b[at + 9 + 2 * i] << 8) / 32768.0f;
            free(b);
            return pcm;
        }
        at += 8 + len + (len & 1);
    }
    free(b);
    return NULL;
}

static char *read_text(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *s = malloc(size + 1);
    size_t got = fread(s, 1, size, f);
    s[got] = 0;
    fclose(f);
    return s;
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: biascli <model> <wav> <free|hard|margin> [expected.txt]\n"); return 2; }
    whisper_log_set(quiet, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) { fprintf(stderr, "could not load %s\n", argv[1]); return 1; }
    int n = 0;
    float *pcm = read_wav(argv[2], &n);
    if (!pcm) { fprintf(stderr, "could not read %s\n", argv[2]); return 1; }
    int free_run = strcmp(argv[3], "free") == 0;
    hfd_bias *bias = NULL;
    if (!free_run && argc > 4) {
        char *expected = read_text(argv[4]);
        int hard = strcmp(argv[3], "hard") == 0;
        bias = hfd_bias_new(expected, hard ? 0.0f : (float) atof(argv[3]), hard);
        free(expected);
    }

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = 4;
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
    if (bias) {
        // Steered decoding takes less likely tokens on purpose: no fallback to sampling.
        p.temperature_inc = 0.0f;
        p.logits_filter_callback = hfd_bias_filter;
        p.logits_filter_callback_user_data = bias;
    }
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int rc = whisper_full(ctx, p, pcm, n);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    long ms = (t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000;
    double lp = 0;
    int taken = 0;
    if (bias) {
        lp = bias->logprob;
        taken = bias->taken;
    } else if (rc == 0) {
        const whisper_token eot = whisper_token_eot(ctx);
        for (int i = 0; i < whisper_full_n_segments(ctx); i++) {
            for (int j = 0; j < whisper_full_n_tokens(ctx, i); j++) {
                whisper_token_data d = whisper_full_get_token_data(ctx, i, j);
                if (d.id > eot) continue;
                lp += d.plog;
                taken++;
            }
        }
    }
    printf("%d %ld %.2f ", bias ? bias->steered : 0, ms, taken ? lp / taken : 0.0);
    if (rc == 0) {
        for (int i = 0; i < whisper_full_n_segments(ctx); i++) {
            const char *s = whisper_full_get_segment_text(ctx, i);
            for (; *s; s++) putchar(*s == '\n' || *s == '\r' ? ' ' : *s);
        }
    }
    putchar('\n');
    hfd_bias_free(bias);
    free(pcm);
    whisper_free(ctx);
    return 0;
}
