// The app's npu.c on a PC, against QNN's HTP emulator (the SDK's x86 libQnnHtp.so): it loads the
// compiled encoder as the phone does and encodes a log-mel in whisper.cpp's layout (80 rows of
// 3000 frames, float32), writing the output in whisper.cpp's (1500 rows of 384, float32) for
// npu.yml to compare with ONNX Runtime's. Usage: npurun <binary> <mel.raw> <out.raw>
#include <stdio.h>
#include <stdlib.h>
#include "npu.h"

int main(int argc, char **argv) {
    if (argc != 4) { fprintf(stderr, "usage: npurun <binary> <mel.raw> <out.raw>\n"); return 2; }
    char err[512] = "";
    hfd_npu *n = hfd_npu_new(argv[1], ".", err, sizeof(err));
    if (n == NULL) { printf("npu.c: %s\n", err); return 1; }
    printf("npu.c: %s\n", hfd_npu_info(n));
    size_t mel_n = 80 * 3000, out_n = 1500 * 384;
    float *mel = malloc(mel_n * sizeof(float)), *out = calloc(out_n, sizeof(float));
    FILE *f = fopen(argv[2], "rb");
    if (f == NULL || fread(mel, sizeof(float), mel_n, f) != mel_n) { printf("can't read %s\n", argv[2]); return 1; }
    fclose(f);
    bool ok = hfd_npu_encode(mel, 80, 3000, out, 1500, 384, n);
    printf("npu.c: encode %s in %.1f s, error %llu\n", ok ? "ok" : "failed", hfd_npu_last_us(n) / 1e6, (unsigned long long) hfd_npu_last_error(n));
    f = fopen(argv[3], "wb");
    fwrite(out, sizeof(float), out_n, f);
    fclose(f);
    hfd_npu_free(n);
    return ok ? 0 : 1;
}
