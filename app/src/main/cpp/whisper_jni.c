// JNI bridge to whisper.cpp for app.hfd.recite.Whisper: load a model, transcribe a chunk of
// 16 kHz mono audio (steered toward the text expected, bias.h), free the model. One model is
// used from one thread at a time. Its encoder may run on the NPU (npu.h): a second state of the
// same model, the CPU's staying for a fallback.
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <sched.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"
#include "ggml-backend.h"
#include "bias.h"
#include "npu.h"

#define TAG "hfd-whisper"

// ggml's CPU code, in one build per family of ARM cores (CMakeLists.txt), best first: ggml loads
// a build only if this CPU runs its instructions (ggml_backend_score). By name, so from the APK.
static const char *CPU_BUILDS[] = {
    "libggml-cpu-android_armv8.6_1.so",  // + int8 matrix multiplies
    "libggml-cpu-android_armv8.2_2.so",  // + dot products and half floats
    "libggml-cpu-android_armv8.2_1.so",  // + dot products
    "libggml-cpu-android_armv8.0_1.so",  // any 64-bit ARM
};

// Loads the best CPU build once; returns which one and what it uses (NEON, DOTPROD, MATMUL_INT8…),
// or null if none loaded (recognition can't run then).
JNIEXPORT jstring JNICALL
Java_app_hfd_recite_Whisper_nativeBackends(JNIEnv *env, jclass clazz) {
    static const char *chosen = NULL;
    for (size_t i = 0; chosen == NULL && i < sizeof(CPU_BUILDS) / sizeof(CPU_BUILDS[0]); i++) {
        if (ggml_backend_load(CPU_BUILDS[i]) != NULL) chosen = CPU_BUILDS[i];
    }
    if (chosen == NULL || ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "no CPU build of ggml loaded");
        return NULL;
    }
    char info[1024];
    snprintf(info, sizeof(info), "%s %s", chosen, whisper_print_system_info());
    return (*env)->NewStringUTF(env, info);
}

// Keeps the calling thread, and the threads ggml starts from it for each computation (they inherit
// it), on the cores in [mask] (bit i: core i). Returns 0, or the error.
JNIEXPORT jint JNICALL
Java_app_hfd_recite_Whisper_nativePin(JNIEnv *env, jclass clazz, jlong mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; i++) if (((unsigned long long) mask >> i) & 1) CPU_SET(i, &set);
    return sched_setaffinity(0, sizeof(set), &set) == 0 ? 0 : errno;
}

// A loaded model: whisper.cpp's context, its state on the CPU, and on the NPU if set up.
typedef struct {
    struct whisper_context *ctx;
    struct whisper_state *cpu;
    struct whisper_state *npu_state;
    hfd_npu *npu;
    int last_npu; // the last reading's encoder ran on the NPU
} hfd_model;

JNIEXPORT jlong JNICALL
Java_app_hfd_recite_Whisper_nativeInit(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params_no_state(p, params);
    (*env)->ReleaseStringUTFChars(env, path, p);
    struct whisper_state *cpu = ctx != NULL ? whisper_init_state(ctx) : NULL;
    hfd_model *m = cpu != NULL ? calloc(1, sizeof(hfd_model)) : NULL;
    if (m == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "could not load the model");
        if (cpu != NULL) whisper_free_state(cpu);
        if (ctx != NULL) whisper_free(ctx);
        return 0;
    }
    m->ctx = ctx;
    m->cpu = cpu;
    return (jlong) (intptr_t) m;
}

// The encoder on the NPU: QNN with the compiled encoder at [binary] (QNN's libraries in
// [libDir]), and a second state of the model reading through it. Null if all went well, else why.
JNIEXPORT jstring JNICALL
Java_app_hfd_recite_Whisper_nativeNpu(JNIEnv *env, jclass clazz, jlong handle, jstring binary, jstring libDir) {
    hfd_model *m = (hfd_model *) (intptr_t) handle;
    if (m == NULL) return (*env)->NewStringUTF(env, "no model");
    if (m->npu_state != NULL) return NULL;
    const char *b = (*env)->GetStringUTFChars(env, binary, NULL);
    const char *l = (*env)->GetStringUTFChars(env, libDir, NULL);
    char err[512] = "";
    hfd_npu *npu = hfd_npu_new(b, l, err, sizeof(err));
    (*env)->ReleaseStringUTFChars(env, binary, b);
    (*env)->ReleaseStringUTFChars(env, libDir, l);
    if (npu == NULL) return (*env)->NewStringUTF(env, err);
    struct whisper_state *state = whisper_hfd_init_state(m->ctx, hfd_npu_encode, npu);
    if (state == NULL) {
        hfd_npu_free(npu);
        return (*env)->NewStringUTF(env, "whisper state");
    }
    m->npu = npu;
    m->npu_state = state;
    return NULL;
}

// The last reading: whether its encoder ran on the NPU, its time there (µs), and the NPU's error
// if it failed (the reading then came from the CPU).
JNIEXPORT jlongArray JNICALL
Java_app_hfd_recite_Whisper_nativeNpuLast(JNIEnv *env, jclass clazz, jlong handle) {
    hfd_model *m = (hfd_model *) (intptr_t) handle;
    jlong v[3] = { m != NULL && m->last_npu, m != NULL ? hfd_npu_last_us(m->npu) : 0, m != NULL ? (jlong) hfd_npu_last_error(m->npu) : 0 };
    jlongArray out = (*env)->NewLongArray(env, 3);
    if (out != NULL) (*env)->SetLongArrayRegion(env, out, 0, 3, v);
    return out;
}

// The NPU and the compiled encoder (npu.c), or null if it isn't set up.
JNIEXPORT jstring JNICALL
Java_app_hfd_recite_Whisper_nativeNpuInfo(JNIEnv *env, jclass clazz, jlong handle) {
    hfd_model *m = (hfd_model *) (intptr_t) handle;
    return m != NULL && m->npu != NULL ? (*env)->NewStringUTF(env, hfd_npu_info(m->npu)) : NULL;
}

// The NPU's encoder output from its last reading against the CPU's from its own (of the same
// audio, for this to mean anything), or null if either is missing.
JNIEXPORT jstring JNICALL
Java_app_hfd_recite_Whisper_nativeNpuCompare(JNIEnv *env, jclass clazz, jlong handle) {
    hfd_model *m = (hfd_model *) (intptr_t) handle;
    if (m == NULL || m->npu_state == NULL) return NULL;
    int c1 = 0, s1 = 0, c2 = 0, s2 = 0;
    const float *cpu = whisper_hfd_encoder_output(m->cpu, &c1, &s1);
    const float *npu = whisper_hfd_encoder_output(m->npu_state, &c2, &s2);
    if (cpu == NULL || npu == NULL || c1 != c2 || s1 != s2) return NULL;
    char out[256];
    hfd_npu_compare(cpu, npu, c1, s1, out, sizeof(out));
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
Java_app_hfd_recite_Whisper_nativeFree(JNIEnv *env, jclass clazz, jlong handle) {
    hfd_model *m = (hfd_model *) (intptr_t) handle;
    if (m == NULL) return;
    if (m->npu_state != NULL) whisper_free_state(m->npu_state);
    hfd_npu_free(m->npu);
    whisper_free_state(m->cpu);
    whisper_free(m->ctx);
    free(m);
}

// Returns the text as UTF-8 bytes (decoded leniently on the Kotlin side), or null on failure.
// With [npu], the encoder runs on the NPU if it is set up; if that fails, the CPU reads it.
JNIEXPORT jbyteArray JNICALL
Java_app_hfd_recite_Whisper_nativeTranscribe(JNIEnv *env, jclass clazz, jlong handle, jfloatArray samples,
                                             jint threads, jint audioCtx, jbyteArray expected, jboolean npu) {
    hfd_model *m = (hfd_model *) (intptr_t) handle;
    if (m == NULL) return NULL;
    struct whisper_context *ctx = m->ctx;
    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "ar";
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = true;
    params.no_context = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.audio_ctx = audioCtx;

    // What the reciter is expected to say (UTF-8, one continuation per line): the recogniser
    // prefers it among what it nearly hears. Or "?\n<text>": a mistake checked, the reading held
    // to that text, with what each of its words cost after it (bias.h, hfd_check).
    hfd_bias *bias = NULL;
    hfd_check *check = NULL;
    if (expected != NULL) {
        jsize len = (*env)->GetArrayLength(env, expected);
        char *text = malloc(len + 1);
        if (text != NULL) {
            (*env)->GetByteArrayRegion(env, expected, 0, len, (jbyte *) text);
            text[len] = 0;
            if (len >= 2 && text[0] == '?' && text[1] == '\n') check = hfd_check_new(text + 2);
            else bias = hfd_bias_new(text, HFD_BIAS_BONUS);
            free(text);
        }
    }
    if (bias != NULL) {
        params.logits_filter_callback = hfd_bias_filter;
        params.logits_filter_callback_user_data = bias;
    }
    if (check != NULL) {
        params.logits_filter_callback = hfd_check_filter;
        params.logits_filter_callback_user_data = check;
    }

    struct whisper_state *state = npu && m->npu_state != NULL ? m->npu_state : m->cpu;
    int rc = whisper_full_with_state(ctx, state, params, pcm, n);
    m->last_npu = state == m->npu_state && rc == 0;
    if (rc != 0 && state != m->cpu) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "NPU reading failed (%d): on the CPU", rc);
        // A check read again from the start: its costs too.
        if (check != NULL) { memset(check->cost, 0, sizeof(float) * check->words); check->stopped = 0; }
        state = m->cpu;
        rc = whisper_full_with_state(ctx, state, params, pcm, n);
    }
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    hfd_bias_free(bias);
    if (rc != 0) {
        hfd_check_free(check);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return NULL;
    }

    size_t len = 0;
    int segments = whisper_full_n_segments_from_state(state);
    for (int i = 0; i < segments; i++) len += strlen(whisper_full_get_segment_text_from_state(state, i)) + 1;
    size_t costs = check != NULL ? (size_t) check->words * 12 + 16 : 0;
    char *text = malloc(len + costs + 1);
    if (text == NULL) { hfd_check_free(check); return NULL; }
    size_t at = 0;
    for (int i = 0; i < segments; i++) {
        const char *s = whisper_full_get_segment_text_from_state(state, i);
        size_t l = strlen(s);
        memcpy(text + at, s, l);
        at += l;
        text[at++] = ' ';
    }
    if (check != NULL) {
        text[at++] = '\t';
        hfd_check_costs(check, text + at, costs);
        at += strlen(text + at);
        hfd_check_free(check);
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) at);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) at, (const jbyte *) text);
    free(text);
    return out;
}
