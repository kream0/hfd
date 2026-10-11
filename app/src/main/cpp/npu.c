// Recite's encoder on the NPU through QNN's C API (npu.h). The steps are QNN's for a context
// binary compiled ahead of time: the HTP backend and a device, the binary's graph and tensors (read
// with QNN System), the context, then one execution per reading. The Hexagon's part of QNN (its
// "skel") is loaded by the DSP from a file: ADSP_LIBRARY_PATH names the app's library folder.
#include "npu.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

void hfd_npu_compare(const float *cpu, const float *npu, int n_ctx, int n_state, char *out, size_t cap) {
    double dot = 0, dot_t = 0, cc = 0, nn = 0, cpu_abs = 0, npu_abs = 0, top = 0;
    size_t nan = 0, inf = 0, zero = 0;
    for (int c = 0; c < n_ctx; c++) {
        for (int s = 0; s < n_state; s++) {
            double x = cpu[(size_t) c * n_state + s], y = npu[(size_t) c * n_state + s], t = npu[(size_t) s * n_ctx + c];
            if (isnan(y)) { nan++; continue; }
            if (isinf(y)) { inf++; continue; }
            if (y == 0) zero++;
            if (!isfinite(t)) t = 0;
            dot += x * y;
            dot_t += x * t;
            cc += x * x;
            nn += y * y;
            cpu_abs += fabs(x);
            npu_abs += fabs(y);
            if (fabs(y) > top) top = fabs(y);
        }
    }
    double n = (double) n_ctx * n_state, norm = sqrt(cc) * sqrt(nn);
    snprintf(out, cap, "cosine %.5f (read transposed %.5f), mean |x| npu %.4f cpu %.4f, max %.2f, nan %zu, inf %zu, zero %zu",
             norm > 0 ? dot / norm : 0, norm > 0 ? dot_t / norm : 0, npu_abs / n, cpu_abs / n, top, nan, inf, zero);
}

#ifndef HFD_QNN

struct hfd_npu { int unused; };

hfd_npu *hfd_npu_new(const char *binary, const char *lib_dir, char *err, size_t err_cap) {
    (void) binary; (void) lib_dir;
    snprintf(err, err_cap, "built without QNN");
    return NULL;
}
void hfd_npu_free(hfd_npu *n) { (void) n; }
bool hfd_npu_encode(const float *mel, int n_mel, int n_frames, float *out, int n_ctx, int n_state, void *user) {
    (void) mel; (void) n_mel; (void) n_frames; (void) out; (void) n_ctx; (void) n_state; (void) user;
    return false;
}
int64_t hfd_npu_last_us(const hfd_npu *n) { (void) n; return 0; }
uint64_t hfd_npu_last_error(const hfd_npu *n) { (void) n; return 0; }
const char *hfd_npu_info(const hfd_npu *n) { (void) n; return ""; }

#else

#include <dlfcn.h>
#include <stdarg.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

#include "QnnInterface.h"
#include "QnnTypes.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "System/QnnSystemInterface.h"
#include "System/QnnSystemContext.h"
#include "HTP/QnnHtpSystemContext.h"

#define TAG "hfd-npu"

typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t ***, uint32_t *);

// How a tensor holds its values: floats of 32 or 16 bits, or unsigned integers of 8 or 16 bits
// with a scale and offset (a quantized graph's: value = scale · (q + offset)). The SM7750's NPU
// runs no half floats: its encoder is quantized.
enum { IO_F32, IO_F16, IO_U8, IO_U16 };
typedef struct { int kind; float scale; int32_t offset; } hfd_io;

static int io_of(const Qnn_Tensor_t *t, hfd_io *io) {
    io->scale = 1;
    io->offset = 0;
    switch (t->v1.dataType) {
        case QNN_DATATYPE_FLOAT_32: io->kind = IO_F32; return 1;
        case QNN_DATATYPE_FLOAT_16: io->kind = IO_F16; return 1;
        case QNN_DATATYPE_UFIXED_POINT_8: io->kind = IO_U8; break;
        case QNN_DATATYPE_UFIXED_POINT_16: io->kind = IO_U16; break;
        default: return 0;
    }
    const Qnn_QuantizeParams_t *q = &t->v1.quantizeParams;
    if (q->encodingDefinition != QNN_DEFINITION_DEFINED || q->quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET ||
        !(q->scaleOffsetEncoding.scale > 0)) return 0;
    io->scale = q->scaleOffsetEncoding.scale;
    io->offset = q->scaleOffsetEncoding.offset;
    return 1;
}

static size_t io_size(const hfd_io *io) { return io->kind == IO_F32 ? 4 : io->kind == IO_U8 ? 1 : 2; }

static const char *io_name(const hfd_io *io) {
    static const char *names[] = { "f32", "f16", "u8", "u16" };
    return names[io->kind];
}

static void io_put(const hfd_io *io, void *buf, size_t i, float x) {
    switch (io->kind) {
        case IO_F32: ((float *) buf)[i] = x; break;
        case IO_F16: ((_Float16 *) buf)[i] = (_Float16) x; break;
        default: {
            float top = io->kind == IO_U8 ? 255.f : 65535.f, q = roundf(x / io->scale) - (float) io->offset;
            q = q > top ? top : q >= 0 ? q : 0;
            if (io->kind == IO_U8) ((uint8_t *) buf)[i] = (uint8_t) q;
            else ((uint16_t *) buf)[i] = (uint16_t) q;
        }
    }
}

static float io_get(const hfd_io *io, const void *buf, size_t i) {
    switch (io->kind) {
        case IO_F32: return ((const float *) buf)[i];
        case IO_F16: return (float) ((const _Float16 *) buf)[i];
        case IO_U8: return io->scale * ((float) ((const uint8_t *) buf)[i] + (float) io->offset);
        default: return io->scale * ((float) ((const uint16_t *) buf)[i] + (float) io->offset);
    }
}
typedef Qnn_ErrorHandle_t (*GetSystemProvidersFn)(const QnnSystemInterface_t ***, uint32_t *);

struct hfd_npu {
    void *lib, *sys_lib;
    QNN_INTERFACE_VER_TYPE qnn;
    QNN_SYSTEM_INTERFACE_VER_TYPE sys;
    Qnn_LogHandle_t log;
    Qnn_BackendHandle_t backend;
    Qnn_DeviceHandle_t device;
    QnnSystemContext_Handle_t sys_ctx;
    Qnn_ContextHandle_t context;
    Qnn_GraphHandle_t graph;
    uint32_t power_id;
    QnnHtpDevice_PerfInfrastructure_t perf;
    void *binary;
    // The graph's one input (log-mel) and one output (encoder states), with their buffers.
    Qnn_Tensor_t in, out;
    void *in_buf, *out_buf;
    size_t in_count, out_count;
    hfd_io in_io, out_io;
    int64_t last_us;
    uint64_t last_error;
    // The NPU (0s if QNN doesn't say) and what the binary was compiled for, for the diagnostics.
    char info[256];
};

static void qnn_log(const char *fmt, QnnLog_Level_t level, uint64_t timestamp, va_list args) {
    (void) timestamp;
    __android_log_vprint(level <= QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, fmt, args);
}

static int64_t now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000 + t.tv_nsec / 1000;
}

static size_t elements(const Qnn_Tensor_t *t) {
    size_t n = 1;
    for (uint32_t i = 0; i < t->v1.rank; i++) n *= t->v1.dimensions[i];
    return n;
}

// The tensor's last two dimensions (after leading 1s), or 0s.
static void last_two(const Qnn_Tensor_t *t, uint32_t *a, uint32_t *b) {
    *a = *b = 0;
    if (t->v1.rank >= 2) {
        *a = t->v1.dimensions[t->v1.rank - 2];
        *b = t->v1.dimensions[t->v1.rank - 1];
    }
}

// The HTP at full speed while Recite runs (the default power mode can cost several times the
// latency): DCVS off, performance mode, the highest voltage corners, short RPC latency.
static void burst(hfd_npu *n) {
    QnnDevice_Infrastructure_t infra = NULL;
    if (n->qnn.deviceGetInfrastructure == NULL || n->qnn.deviceGetInfrastructure(&infra) != QNN_SUCCESS || infra == NULL) return;
    QnnHtpDevice_Infrastructure_t *htp = (QnnHtpDevice_Infrastructure_t *) infra;
    if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) return;
    n->perf = htp->perfInfra;
    if (n->perf.createPowerConfigId(0, 0, &n->power_id) != QNN_SUCCESS) { n->perf.setPowerConfig = NULL; return; }
    QnnHtpPerfInfrastructure_PowerConfig_t dcvs;
    memset(&dcvs, 0, sizeof(dcvs));
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvs.dcvsV3Config.contextId = n->power_id;
    dcvs.dcvsV3Config.setDcvsEnable = 1;
    dcvs.dcvsV3Config.dcvsEnable = 0;
    dcvs.dcvsV3Config.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    dcvs.dcvsV3Config.setSleepLatency = 1;
    dcvs.dcvsV3Config.sleepLatency = 40;
    dcvs.dcvsV3Config.setSleepDisable = 1;
    dcvs.dcvsV3Config.sleepDisable = 1;
    dcvs.dcvsV3Config.setBusParams = 1;
    dcvs.dcvsV3Config.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.setCoreParams = 1;
    dcvs.dcvsV3Config.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    QnnHtpPerfInfrastructure_PowerConfig_t rpc;
    memset(&rpc, 0, sizeof(rpc));
    rpc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
    rpc.rpcControlLatencyConfig = 100;
    QnnHtpPerfInfrastructure_PowerConfig_t poll;
    memset(&poll, 0, sizeof(poll));
    poll.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    poll.rpcPollingTimeConfig = 9999;
    const QnnHtpPerfInfrastructure_PowerConfig_t *configs[] = { &dcvs, &rpc, &poll, NULL };
    Qnn_ErrorHandle_t e = n->perf.setPowerConfig(n->power_id, configs);
    if (e != QNN_SUCCESS) __android_log_print(ANDROID_LOG_WARN, TAG, "setPowerConfig: %llu", (unsigned long long) e);
}

// The NPU's Hexagon version, SoC (Qnn_SocModel_t) and VTCM (MB), as QNN reports it; 0s if it doesn't.
static void platform(hfd_npu *n, uint32_t *arch, uint32_t *soc, size_t *vtcm) {
    *arch = *soc = 0;
    *vtcm = 0;
#ifdef HFD_NPU_EMULATE_SOC
    return;  // the emulator says v68 whatever SoC it plays
#endif
    const QnnDevice_PlatformInfo_t *info = NULL;
    if (n->qnn.deviceGetPlatformInfo == NULL || n->qnn.deviceGetPlatformInfo(n->log, &info) != QNN_SUCCESS || info == NULL) return;
    if (info->version == QNN_DEVICE_PLATFORM_INFO_VERSION_1 && info->v1.numHwDevices > 0 && info->v1.hwDevices != NULL) {
        const QnnDevice_HardwareDeviceInfo_t *hw = &info->v1.hwDevices[0];
        const QnnHtpDevice_DeviceInfoExtension_t *ext = hw->version == QNN_DEVICE_HARDWARE_DEVICE_INFO_VERSION_1 ? hw->v1.deviceInfoExtension : NULL;
        if (ext != NULL && ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) {
            *arch = (uint32_t) ext->onChipDevice.arch;
            *soc = ext->onChipDevice.socModel;
            *vtcm = ext->onChipDevice.vtcmSize;
        }
    }
    if (n->qnn.deviceFreePlatformInfo != NULL) n->qnn.deviceFreePlatformInfo(n->log, info);
}

// The Hexagon version a binary was compiled for (QNN System's version 3 of its info), or 0.
static uint32_t binary_arch(const QnnSystemContext_BinaryInfo_t *info) {
    if (info->version != QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) return 0;
    const QnnHtpSystemContext_ContextBlobInfo_t *meta = info->contextBinaryInfoV3.contextMetadata;
    if (meta == NULL || info->contextBinaryInfoV3.contextMetadataSize < sizeof(QnnHtpSystemContext_ContextBlobInfo_t)) return 0;
    return meta->version == QNN_SYSTEM_CONTEXT_HTP_CONTEXT_INFO_BLOB_VERSION_V1 ? meta->contextBinaryContextBlobInfoV1.dspArch : 0;
}

static void *read_file(const char *path, size_t *size) {
    FILE *f = fopen(path, "rb");
    if (f == NULL) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *buf = n > 0 ? malloc((size_t) n) : NULL;
    if (buf != NULL && fread(buf, 1, (size_t) n, f) != (size_t) n) { free(buf); buf = NULL; }
    fclose(f);
    *size = buf != NULL ? (size_t) n : 0;
    return buf;
}

// The graph's tensors as the binary describes them (QNN System), versions 1 to 3.
static int graph_info(const QnnSystemContext_BinaryInfo_t *info, const char **name, Qnn_Tensor_t **in, uint32_t *n_in,
                      Qnn_Tensor_t **out, uint32_t *n_out) {
    uint32_t graphs = 0;
    QnnSystemContext_GraphInfo_t *g = NULL;
    switch (info->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1: graphs = info->contextBinaryInfoV1.numGraphs; g = info->contextBinaryInfoV1.graphs; break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2: graphs = info->contextBinaryInfoV2.numGraphs; g = info->contextBinaryInfoV2.graphs; break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3: graphs = info->contextBinaryInfoV3.numGraphs; g = info->contextBinaryInfoV3.graphs; break;
        default: return 0;
    }
    if (graphs < 1 || g == NULL) return 0;
    // Versions 1 to 3 begin alike: name, inputs, outputs.
    switch (g[0].version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3: break;
        default: return 0;
    }
    *name = g[0].graphInfoV1.graphName;
    *in = g[0].graphInfoV1.graphInputs;
    *n_in = g[0].graphInfoV1.numGraphInputs;
    *out = g[0].graphInfoV1.graphOutputs;
    *n_out = g[0].graphInfoV1.numGraphOutputs;
    return 1;
}

#define FAIL(...) do { snprintf(err, err_cap, __VA_ARGS__); hfd_npu_free(n); return NULL; } while (0)

hfd_npu *hfd_npu_new(const char *binary, const char *lib_dir, char *err, size_t err_cap) {
    hfd_npu *n = calloc(1, sizeof(hfd_npu));
    if (n == NULL) { snprintf(err, err_cap, "memory"); return NULL; }
    // Where the DSP looks for QNN's Hexagon part (libQnnHtpV73Skel.so): the app's libraries first.
    char path[1024];
    snprintf(path, sizeof(path), "%s;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", lib_dir);
    setenv("ADSP_LIBRARY_PATH", path, 1);

    n->lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (n->lib == NULL) FAIL("dlopen libQnnHtp.so: %s", dlerror());
    GetProvidersFn providers = (GetProvidersFn) dlsym(n->lib, "QnnInterface_getProviders");
    const QnnInterface_t **list = NULL;
    uint32_t count = 0;
    if (providers == NULL || providers(&list, &count) != QNN_SUCCESS || count == 0) FAIL("QnnInterface_getProviders");
    int found = 0;
    for (uint32_t i = 0; i < count && !found; i++) {
        if (list[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR && list[i]->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
            n->qnn = list[i]->QNN_INTERFACE_VER_NAME;
            found = 1;
        }
    }
    if (!found) FAIL("no QNN %d.%d interface (%u providers, first %u.%u)", QNN_API_VERSION_MAJOR, QNN_API_VERSION_MINOR, count,
                     list[0]->apiVersion.coreApiVersion.major, list[0]->apiVersion.coreApiVersion.minor);

    n->sys_lib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    if (n->sys_lib == NULL) FAIL("dlopen libQnnSystem.so: %s", dlerror());
    GetSystemProvidersFn sys_providers = (GetSystemProvidersFn) dlsym(n->sys_lib, "QnnSystemInterface_getProviders");
    const QnnSystemInterface_t **sys_list = NULL;
    uint32_t sys_count = 0;
    if (sys_providers == NULL || sys_providers(&sys_list, &sys_count) != QNN_SUCCESS || sys_count == 0) FAIL("QnnSystemInterface_getProviders");
    found = 0;
    for (uint32_t i = 0; i < sys_count && !found; i++) {
        if (sys_list[i]->systemApiVersion.major == QNN_SYSTEM_API_VERSION_MAJOR && sys_list[i]->systemApiVersion.minor >= QNN_SYSTEM_API_VERSION_MINOR) {
            n->sys = sys_list[i]->QNN_SYSTEM_INTERFACE_VER_NAME;
            found = 1;
        }
    }
    if (!found) FAIL("no QNN System %d.%d interface", QNN_SYSTEM_API_VERSION_MAJOR, QNN_SYSTEM_API_VERSION_MINOR);

    Qnn_ErrorHandle_t e;
    if (n->qnn.logCreate != NULL) n->qnn.logCreate(qnn_log, QNN_LOG_LEVEL_WARN, &n->log);
    if ((e = n->qnn.backendCreate(n->log, NULL, &n->backend)) != QNN_SUCCESS) FAIL("backendCreate %llu", (unsigned long long) e);
    const QnnDevice_Config_t **device_config = NULL;
#ifdef HFD_NPU_EMULATE_SOC
    // tools/npu/npurun.c: QNN's emulator on a PC, as the phone's SoC (a v68 otherwise).
    QnnHtpDevice_CustomConfig_t soc_config;
    memset(&soc_config, 0, sizeof(soc_config));
    soc_config.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
    soc_config.socModel = HFD_NPU_EMULATE_SOC;
    QnnDevice_Config_t soc_device;
    soc_device.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    soc_device.customConfig = &soc_config;
    const QnnDevice_Config_t *emulated[] = { &soc_device, NULL };
    device_config = emulated;
#endif
    if (n->qnn.deviceCreate != NULL && (e = n->qnn.deviceCreate(n->log, device_config, &n->device)) != QNN_SUCCESS) FAIL("deviceCreate %llu", (unsigned long long) e);
    burst(n);
    uint32_t arch, soc;
    size_t vtcm;
    platform(n, &arch, &soc, &vtcm);

    size_t size = 0;
    n->binary = read_file(binary, &size);
    if (n->binary == NULL) FAIL("read %s", binary);
    if ((e = n->sys.systemContextCreate(&n->sys_ctx)) != QNN_SUCCESS) FAIL("systemContextCreate %llu", (unsigned long long) e);
    const QnnSystemContext_BinaryInfo_t *info = NULL;
    Qnn_ContextBinarySize_t info_size = 0;
    if ((e = n->sys.systemContextGetBinaryInfo(n->sys_ctx, n->binary, size, &info, &info_size)) != QNN_SUCCESS || info == NULL)
        FAIL("systemContextGetBinaryInfo %llu", (unsigned long long) e);
    const char *name = NULL;
    Qnn_Tensor_t *ins = NULL, *outs = NULL;
    uint32_t n_in = 0, n_out = 0;
    if (!graph_info(info, &name, &ins, &n_in, &outs, &n_out) || n_in != 1 || n_out != 1)
        FAIL("graph info (version %d): %u inputs, %u outputs", (int) info->version, n_in, n_out);
    // A binary compiled for another Hexagon may load and run, and give garbage (1.15.1: one for a
    // v68 on the phone's v73).
    uint32_t compiled = binary_arch(info);
    if (arch != 0 && compiled != 0 && arch != compiled) FAIL("compiled for a v%u, this NPU is a v%u (SoC %u)", compiled, arch, soc);

    if ((e = n->qnn.contextCreateFromBinary(n->backend, n->device, NULL, n->binary, size, &n->context, NULL)) != QNN_SUCCESS)
        FAIL("contextCreateFromBinary %llu", (unsigned long long) e);
    if ((e = n->qnn.graphRetrieve(n->context, name, &n->graph)) != QNN_SUCCESS) FAIL("graphRetrieve %s: %llu", name, (unsigned long long) e);

    // The tensors as described, with the app's buffers (the descriptions stay with QNN System's context).
    n->in = ins[0];
    n->out = outs[0];
    if (!io_of(&n->in, &n->in_io) || !io_of(&n->out, &n->out_io))
        FAIL("tensor types 0x%x 0x%x", (unsigned) n->in.v1.dataType, (unsigned) n->out.v1.dataType);
    n->in_count = elements(&n->in);
    n->out_count = elements(&n->out);
    size_t in_bytes = n->in_count * io_size(&n->in_io), out_bytes = n->out_count * io_size(&n->out_io);
    n->in_buf = malloc(in_bytes);
    n->out_buf = malloc(out_bytes);
    if (n->in_buf == NULL || n->out_buf == NULL) FAIL("memory");
    n->in.v1.memType = QNN_TENSORMEMTYPE_RAW;
    n->in.v1.clientBuf.data = n->in_buf;
    n->in.v1.clientBuf.dataSize = (uint32_t) in_bytes;
    n->out.v1.memType = QNN_TENSORMEMTYPE_RAW;
    n->out.v1.clientBuf.data = n->out_buf;
    n->out.v1.clientBuf.dataSize = (uint32_t) out_bytes;
    uint32_t a, b, c, d;
    last_two(&n->in, &a, &b);
    last_two(&n->out, &c, &d);
    snprintf(n->info, sizeof(n->info), "npu v%u soc %u vtcm %zu MB; binary v%u soc %u; %s %ux%u %s, %s %ux%u %s", arch, soc, vtcm, compiled,
             info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3 ? info->contextBinaryInfoV3.socModel : 0,
             n->in.v1.name, a, b, io_name(&n->in_io), n->out.v1.name, c, d, io_name(&n->out_io));
    __android_log_print(ANDROID_LOG_INFO, TAG, "graph %s: %s", name, n->info);
    return n;
}

void hfd_npu_free(hfd_npu *n) {
    if (n == NULL) return;
    if (n->context != NULL) n->qnn.contextFree(n->context, NULL);
    if (n->perf.destroyPowerConfigId != NULL && n->perf.setPowerConfig != NULL) n->perf.destroyPowerConfigId(n->power_id);
    if (n->device != NULL && n->qnn.deviceFree != NULL) n->qnn.deviceFree(n->device);
    if (n->backend != NULL) n->qnn.backendFree(n->backend);
    if (n->log != NULL && n->qnn.logFree != NULL) n->qnn.logFree(n->log);
    if (n->sys_ctx != NULL) n->sys.systemContextFree(n->sys_ctx);
    free(n->binary);
    free(n->in_buf);
    free(n->out_buf);
    if (n->sys_lib != NULL) dlclose(n->sys_lib);
    if (n->lib != NULL) dlclose(n->lib);
    free(n);
}

bool hfd_npu_encode(const float *mel, int n_mel, int n_frames, float *out, int n_ctx, int n_state, void *user) {
    hfd_npu *n = (hfd_npu *) user;
    if ((size_t) n_mel * n_frames != n->in_count || (size_t) n_ctx * n_state != n->out_count) {
        n->last_error = 1;
        __android_log_print(ANDROID_LOG_ERROR, TAG, "sizes: mel %d×%d for %zu, out %d×%d for %zu", n_mel, n_frames, n->in_count, n_ctx, n_state, n->out_count);
        return false;
    }
    // The input as the graph holds it: [n_mel][n_frames] (whisper.cpp's), or transposed.
    uint32_t a, b;
    last_two(&n->in, &a, &b);
    int in_t = a == (uint32_t) n_frames && b == (uint32_t) n_mel;
    for (int j = 0; j < n_mel; j++) {
        for (int i = 0; i < n_frames; i++) {
            size_t at = in_t ? (size_t) i * n_mel + j : (size_t) j * n_frames + i;
            io_put(&n->in_io, n->in_buf, at, mel[(size_t) j * n_frames + i]);
        }
    }
    int64_t t = now_us();
    Qnn_ErrorHandle_t e = n->qnn.graphExecute(n->graph, &n->in, 1, &n->out, 1, NULL, NULL);
    n->last_us = now_us() - t;
    n->last_error = e;
    if (e != QNN_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "graphExecute: %llu", (unsigned long long) e);
        return false;
    }
    // The output into whisper.cpp's [n_ctx][n_state], from the graph's (or transposed).
    last_two(&n->out, &a, &b);
    int out_t = a == (uint32_t) n_state && b == (uint32_t) n_ctx;
    for (int c = 0; c < n_ctx; c++) {
        for (int s = 0; s < n_state; s++) {
            size_t at = out_t ? (size_t) s * n_ctx + c : (size_t) c * n_state + s;
            out[(size_t) c * n_state + s] = io_get(&n->out_io, n->out_buf, at);
        }
    }
    return true;
}

int64_t hfd_npu_last_us(const hfd_npu *n) { return n != NULL ? n->last_us : 0; }
uint64_t hfd_npu_last_error(const hfd_npu *n) { return n != NULL ? n->last_error : 0; }
const char *hfd_npu_info(const hfd_npu *n) { return n != NULL ? n->info : ""; }

#endif
