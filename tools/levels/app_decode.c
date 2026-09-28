// The app's decoding (app/src/main/cpp/whisper_jni.c, nativeTranscribe) on WAV files, to compare
// with whisper-cli: same parameters, optionally without single_segment. Usage:
//   app_decode <model> <single_segment 0|1> <wav>...   (16-bit mono 16 kHz, 44-byte header)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

static float *read_wav(const char *path, int *n) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long size = ftell(f) - 44;
    fseek(f, 44, SEEK_SET);
    short *raw = malloc(size);
    fread(raw, 1, size, f);
    fclose(f);
    *n = (int) (size / 2);
    float *pcm = malloc(sizeof(float) * *n);
    for (int i = 0; i < *n; i++) pcm[i] = raw[i] / 32768.0f;
    free(raw);
    return pcm;
}

int main(int argc, char **argv) {
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    int single = atoi(argv[2]);
    for (int k = 3; k < argc; k++) {
        int n = 0;
        float *pcm = read_wav(argv[k], &n);
        struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads = 4;
        p.language = "ar";
        p.translate = false;
        p.no_timestamps = true;
        p.single_segment = single;
        p.no_context = true;
        p.print_realtime = false;
        p.print_progress = false;
        p.print_timestamps = false;
        p.print_special = false;
        p.suppress_blank = true;
        p.audio_ctx = 0;
        whisper_full(ctx, p, pcm, n);
        printf("%s\t", argv[k]);
        for (int i = 0; i < whisper_full_n_segments(ctx); i++) printf("%s ", whisper_full_get_segment_text(ctx, i));
        printf("\n");
        free(pcm);
    }
    whisper_free(ctx);
    return 0;
}
