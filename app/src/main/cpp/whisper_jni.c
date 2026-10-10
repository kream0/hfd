// JNI bridge to whisper.cpp for app.hfd.recite.Whisper: load a model, transcribe a chunk of
// 16 kHz mono audio (steered toward the text expected, bias.h), free the model. One context is
// used from one thread at a time.
#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"
#include "ggml-backend.h"
#include "bias.h"

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

JNIEXPORT jlong JNICALL
Java_app_hfd_recite_Whisper_nativeInit(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, params);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (ctx == NULL) __android_log_print(ANDROID_LOG_ERROR, TAG, "could not load the model");
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_app_hfd_recite_Whisper_nativeFree(JNIEnv *env, jclass clazz, jlong ctx) {
    if (ctx != 0) whisper_free((struct whisper_context *) (intptr_t) ctx);
}

// Returns the text as UTF-8 bytes (decoded leniently on the Kotlin side), or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_app_hfd_recite_Whisper_nativeTranscribe(JNIEnv *env, jclass clazz, jlong ctxPtr, jfloatArray samples,
                                             jint threads, jint audioCtx, jbyteArray expected) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ctxPtr;
    if (ctx == NULL) return NULL;
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

    int rc = whisper_full(ctx, params, pcm, n);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    hfd_bias_free(bias);
    if (rc != 0) {
        hfd_check_free(check);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return NULL;
    }

    size_t len = 0;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) len += strlen(whisper_full_get_segment_text(ctx, i)) + 1;
    size_t costs = check != NULL ? (size_t) check->words * 12 + 16 : 0;
    char *text = malloc(len + costs + 1);
    if (text == NULL) { hfd_check_free(check); return NULL; }
    size_t at = 0;
    for (int i = 0; i < segments; i++) {
        const char *s = whisper_full_get_segment_text(ctx, i);
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
