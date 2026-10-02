// JNI bridge to whisper.cpp for app.hfd.recite.Whisper: load a model, transcribe a chunk of
// 16 kHz mono audio (steered toward the text expected, bias.h), free the model. One context is
// used from one thread at a time.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"
#include "bias.h"

#define TAG "hfd-whisper"

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
    // prefers it among what it nearly hears.
    hfd_bias *bias = NULL;
    if (expected != NULL) {
        jsize len = (*env)->GetArrayLength(env, expected);
        char *text = malloc(len + 1);
        if (text != NULL) {
            (*env)->GetByteArrayRegion(env, expected, 0, len, (jbyte *) text);
            text[len] = 0;
            bias = hfd_bias_new(text, HFD_BIAS_MARGIN, 0);
            free(text);
        }
    }
    if (bias != NULL) {
        params.logits_filter_callback = hfd_bias_filter;
        params.logits_filter_callback_user_data = bias;
    }

    int rc = whisper_full(ctx, params, pcm, n);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    hfd_bias_free(bias);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return NULL;
    }

    size_t len = 0;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) len += strlen(whisper_full_get_segment_text(ctx, i)) + 1;
    char *text = malloc(len + 1);
    if (text == NULL) return NULL;
    size_t at = 0;
    for (int i = 0; i < segments; i++) {
        const char *s = whisper_full_get_segment_text(ctx, i);
        size_t l = strlen(s);
        memcpy(text + at, s, l);
        at += l;
        text[at++] = ' ';
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) at);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) at, (const jbyte *) text);
    free(text);
    return out;
}
