// Android's log on a PC (tools/npu/npurun.c builds the app's npu.c there): to stderr.
#ifndef HFD_ANDROID_LOG_SHIM_H
#define HFD_ANDROID_LOG_SHIM_H
#include <stdarg.h>
#include <stdio.h>
enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_vprint(int prio, const char *tag, const char *fmt, va_list ap) {
    fprintf(stderr, "%s %d: ", tag, prio);
    int n = vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    return n;
}
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = __android_log_vprint(prio, tag, fmt, ap);
    va_end(ap);
    return n;
}
#endif
