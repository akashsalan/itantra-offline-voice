// Minimal ONNX Runtime bridge for the neural TTS pipeline.
//
// Deliberately dumb: create/destroy sessions, run one tensor in and one tensor
// out. Tokenisation, chunking, resampling and every policy decision stay in
// Kotlin so a mistake there cannot abort the process from native code.
//
// The runtime is the ONNX Runtime already shipped inside the sherpa-onnx AAR
// (libonnxruntime.so, 1.28.2, exporting the stable OrtGetApiBase C ABI). No
// second runtime is bundled, so nothing collides with sherpa or Moonshine.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

#define LOG_TAG "ItantraTts"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const OrtApi *g_ort = nullptr;
std::once_flag g_ort_once;

// Resolve the C API from the already-loaded libonnxruntime.so. Requesting a
// conservative API version means a future runtime upgrade degrades to "TTS
// unavailable" (and the caller falls back to eSpeak) instead of crashing.
void InitApi() {
    void *handle = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        LOGE("dlopen libonnxruntime.so failed: %s", dlerror());
        return;
    }
    auto get_base = reinterpret_cast<OrtApiBase *(*)()>(dlsym(handle, "OrtGetApiBase"));
    if (get_base == nullptr) {
        LOGE("OrtGetApiBase not found: %s", dlerror());
        return;
    }
    const OrtApiBase *base = get_base();
    if (base == nullptr) return;
    for (uint32_t version = ORT_API_VERSION; version >= 12; --version) {
        const OrtApi *api = base->GetApi(version);
        if (api != nullptr) {
            g_ort = api;
            return;
        }
    }
    LOGE("no compatible OrtApi version; runtime reports %s", base->GetVersionString());
}

const OrtApi *Api() {
    std::call_once(g_ort_once, InitApi);
    return g_ort;
}

struct Model {
    OrtEnv *env = nullptr;
    OrtSession *session = nullptr;
    OrtSessionOptions *options = nullptr;
    OrtMemoryInfo *memory = nullptr;
    std::string input_name;
    std::string output_name;
};

void Destroy(Model *model) {
    if (model == nullptr) return;
    const OrtApi *ort = Api();
    if (ort != nullptr) {
        if (model->memory != nullptr) ort->ReleaseMemoryInfo(model->memory);
        if (model->session != nullptr) ort->ReleaseSession(model->session);
        if (model->options != nullptr) ort->ReleaseSessionOptions(model->options);
        if (model->env != nullptr) ort->ReleaseEnv(model->env);
    }
    delete model;
}

void Throw(JNIEnv *env, const std::string &message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz != nullptr) env->ThrowNew(clazz, message.c_str());
}

bool Failed(JNIEnv *env, OrtStatus *status) {
    const OrtApi *ort = Api();
    if (status == nullptr) return false;
    std::string message = ort->GetErrorMessage(status);
    ort->ReleaseStatus(status);
    Throw(env, "ONNX Runtime: " + message);
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_itantra_app_tts_OnnxNative_available(JNIEnv *, jobject) {
    return Api() != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_org_itantra_app_tts_OnnxNative_runtimeVersion(JNIEnv *env, jobject) {
    void *handle = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) return env->NewStringUTF("");
    auto get_base = reinterpret_cast<OrtApiBase *(*)()>(dlsym(handle, "OrtGetApiBase"));
    if (get_base == nullptr) return env->NewStringUTF("");
    const OrtApiBase *base = get_base();
    return env->NewStringUTF(base == nullptr ? "" : base->GetVersionString());
}

/** Opens a single-input/single-output graph. Returns an opaque handle. */
JNIEXPORT jlong JNICALL
Java_org_itantra_app_tts_OnnxNative_open(JNIEnv *env, jobject, jstring path, jint threads) {
    const OrtApi *ort = Api();
    if (ort == nullptr) {
        Throw(env, "ONNX Runtime is unavailable on this device");
        return 0;
    }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    std::string model_path(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto *model = new Model();
    if (Failed(env, ort->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "itantra-tts", &model->env)) ||
        Failed(env, ort->CreateSessionOptions(&model->options))) {
        Destroy(model);
        return 0;
    }
    if (Failed(env, ort->SetIntraOpNumThreads(model->options, threads)) ||
        Failed(env, ort->SetInterOpNumThreads(model->options, 1)) ||
        Failed(env, ort->SetSessionGraphOptimizationLevel(model->options, ORT_ENABLE_ALL))) {
        Destroy(model);
        return 0;
    }
    if (Failed(env, ort->CreateSession(model->env, model_path.c_str(), model->options, &model->session)) ||
        Failed(env, ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &model->memory))) {
        Destroy(model);
        return 0;
    }

    OrtAllocator *allocator = nullptr;
    if (Failed(env, ort->GetAllocatorWithDefaultOptions(&allocator))) {
        Destroy(model);
        return 0;
    }
    size_t inputs = 0;
    size_t outputs = 0;
    if (Failed(env, ort->SessionGetInputCount(model->session, &inputs)) ||
        Failed(env, ort->SessionGetOutputCount(model->session, &outputs))) {
        Destroy(model);
        return 0;
    }
    // One input always. One output for fused acoustic, decoder and vocoder
    // graphs; two for the split encoder, which also returns per-token durations.
    if (inputs != 1 || outputs < 1 || outputs > 2) {
        Destroy(model);
        Throw(env, "Expected one graph input and one or two outputs");
        return 0;
    }
    char *input_name = nullptr;
    char *output_name = nullptr;
    if (Failed(env, ort->SessionGetInputName(model->session, 0, allocator, &input_name)) ||
        Failed(env, ort->SessionGetOutputName(model->session, 0, allocator, &output_name))) {
        Destroy(model);
        return 0;
    }
    model->input_name = input_name;
    model->output_name = output_name;
    // Names are copied above; releasing the allocator copies cannot fail fatally,
    // but the status is still consumed rather than discarded.
    if (OrtStatus *status = ort->AllocatorFree(allocator, input_name)) ort->ReleaseStatus(status);
    if (OrtStatus *status = ort->AllocatorFree(allocator, output_name)) ort->ReleaseStatus(status);
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_org_itantra_app_tts_OnnxNative_close(JNIEnv *, jobject, jlong handle) {
    Destroy(reinterpret_cast<Model *>(handle));
}

/** int64 tokens -> float mel, flattened. Shape is [1, length]. */
JNIEXPORT jfloatArray JNICALL
Java_org_itantra_app_tts_OnnxNative_runInt64(JNIEnv *env, jobject, jlong handle,
                                             jlongArray tokens, jintArray outShape) {
    const OrtApi *ort = Api();
    auto *model = reinterpret_cast<Model *>(handle);
    if (ort == nullptr || model == nullptr) {
        Throw(env, "TTS acoustic session is not open");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(tokens);
    if (count <= 0) {
        Throw(env, "No tokens supplied");
        return nullptr;
    }
    std::vector<int64_t> values(static_cast<size_t>(count));
    env->GetLongArrayRegion(tokens, 0, count, reinterpret_cast<jlong *>(values.data()));

    const int64_t shape[2] = {1, count};
    OrtValue *input = nullptr;
    if (Failed(env, ort->CreateTensorWithDataAsOrtValue(
                        model->memory, values.data(), values.size() * sizeof(int64_t), shape, 2,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &input))) {
        return nullptr;
    }
    const char *input_names[] = {model->input_name.c_str()};
    const char *output_names[] = {model->output_name.c_str()};
    OrtValue *output = nullptr;
    OrtStatus *status = ort->Run(model->session, nullptr, input_names,
                                 const_cast<const OrtValue *const *>(&input), 1,
                                 output_names, 1, &output);
    ort->ReleaseValue(input);
    if (Failed(env, status)) return nullptr;

    OrtTensorTypeAndShapeInfo *info = nullptr;
    size_t dims = 0;
    if (Failed(env, ort->GetTensorTypeAndShape(output, &info)) ||
        Failed(env, ort->GetDimensionsCount(info, &dims))) {
        if (info != nullptr) ort->ReleaseTensorTypeAndShapeInfo(info);
        ort->ReleaseValue(output);
        return nullptr;
    }
    std::vector<int64_t> out_dims(dims);
    size_t elements = 0;
    if (Failed(env, ort->GetDimensions(info, out_dims.data(), dims)) ||
        Failed(env, ort->GetTensorShapeElementCount(info, &elements))) {
        ort->ReleaseTensorTypeAndShapeInfo(info);
        ort->ReleaseValue(output);
        return nullptr;
    }
    ort->ReleaseTensorTypeAndShapeInfo(info);

    float *data = nullptr;
    if (Failed(env, ort->GetTensorMutableData(output, reinterpret_cast<void **>(&data)))) {
        ort->ReleaseValue(output);
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(elements));
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(elements), data);
    }
    if (outShape != nullptr && env->GetArrayLength(outShape) >= static_cast<jsize>(dims)) {
        std::vector<jint> shape_out(dims);
        for (size_t i = 0; i < dims; ++i) shape_out[i] = static_cast<jint>(out_dims[i]);
        env->SetIntArrayRegion(outShape, 0, static_cast<jsize>(dims), shape_out.data());
    }
    ort->ReleaseValue(output);
    return result;
}

/**
 * Two-output encoder: int64 tokens -> float features (returned, flattened) plus
 * int64 durations (written into outDurations). Used by the split acoustic
 * topology, where the duration expansion happens in Kotlin between the graphs.
 */
JNIEXPORT jfloatArray JNICALL
Java_org_itantra_app_tts_OnnxNative_runEncoder(JNIEnv *env, jobject, jlong handle,
                                               jlongArray tokens, jintArray outShape,
                                               jintArray outDurations) {
    const OrtApi *ort = Api();
    auto *model = reinterpret_cast<Model *>(handle);
    if (ort == nullptr || model == nullptr) {
        Throw(env, "TTS encoder session is not open");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(tokens);
    if (count <= 0) {
        Throw(env, "No tokens supplied");
        return nullptr;
    }
    std::vector<int64_t> values(static_cast<size_t>(count));
    env->GetLongArrayRegion(tokens, 0, count, reinterpret_cast<jlong *>(values.data()));

    const int64_t shape[2] = {1, count};
    OrtValue *input = nullptr;
    if (Failed(env, ort->CreateTensorWithDataAsOrtValue(
                        model->memory, values.data(), values.size() * sizeof(int64_t), shape, 2,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &input))) {
        return nullptr;
    }

    // The encoder graph declares two outputs; Model only caches the first name.
    OrtAllocator *allocator = nullptr;
    if (Failed(env, ort->GetAllocatorWithDefaultOptions(&allocator))) {
        ort->ReleaseValue(input);
        return nullptr;
    }
    char *second_name = nullptr;
    if (Failed(env, ort->SessionGetOutputName(model->session, 1, allocator, &second_name))) {
        ort->ReleaseValue(input);
        return nullptr;
    }
    const char *input_names[] = {model->input_name.c_str()};
    const char *output_names[] = {model->output_name.c_str(), second_name};
    OrtValue *outputs[2] = {nullptr, nullptr};
    OrtStatus *status = ort->Run(model->session, nullptr, input_names,
                                const_cast<const OrtValue *const *>(&input), 1,
                                output_names, 2, outputs);
    if (OrtStatus *freed = ort->AllocatorFree(allocator, second_name)) ort->ReleaseStatus(freed);
    ort->ReleaseValue(input);
    if (Failed(env, status)) return nullptr;

    jfloatArray result = nullptr;
    do {
        OrtTensorTypeAndShapeInfo *info = nullptr;
        size_t dims = 0;
        size_t elements = 0;
        if (Failed(env, ort->GetTensorTypeAndShape(outputs[0], &info)) ||
            Failed(env, ort->GetDimensionsCount(info, &dims)) ||
            Failed(env, ort->GetTensorShapeElementCount(info, &elements))) {
            if (info != nullptr) ort->ReleaseTensorTypeAndShapeInfo(info);
            break;
        }
        std::vector<int64_t> feature_dims(dims);
        if (Failed(env, ort->GetDimensions(info, feature_dims.data(), dims))) {
            ort->ReleaseTensorTypeAndShapeInfo(info);
            break;
        }
        ort->ReleaseTensorTypeAndShapeInfo(info);

        float *features = nullptr;
        if (Failed(env, ort->GetTensorMutableData(outputs[0],
                                                  reinterpret_cast<void **>(&features)))) {
            break;
        }
        result = env->NewFloatArray(static_cast<jsize>(elements));
        if (result != nullptr) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(elements), features);
        }
        if (outShape != nullptr && env->GetArrayLength(outShape) >= static_cast<jsize>(dims)) {
            std::vector<jint> shape_out(dims);
            for (size_t i = 0; i < dims; ++i) shape_out[i] = static_cast<jint>(feature_dims[i]);
            env->SetIntArrayRegion(outShape, 0, static_cast<jsize>(dims), shape_out.data());
        }

        int64_t *durations = nullptr;
        size_t duration_count = 0;
        OrtTensorTypeAndShapeInfo *duration_info = nullptr;
        if (Failed(env, ort->GetTensorTypeAndShape(outputs[1], &duration_info)) ||
            Failed(env, ort->GetTensorShapeElementCount(duration_info, &duration_count))) {
            if (duration_info != nullptr) ort->ReleaseTensorTypeAndShapeInfo(duration_info);
            break;
        }
        ort->ReleaseTensorTypeAndShapeInfo(duration_info);
        if (Failed(env, ort->GetTensorMutableData(outputs[1],
                                                  reinterpret_cast<void **>(&durations)))) {
            break;
        }
        const jsize capacity = outDurations == nullptr ? 0 : env->GetArrayLength(outDurations);
        const size_t writable = std::min(static_cast<size_t>(capacity), duration_count);
        if (writable > 0) {
            std::vector<jint> narrowed(writable);
            for (size_t i = 0; i < writable; ++i) {
                narrowed[i] = static_cast<jint>(durations[i]);
            }
            env->SetIntArrayRegion(outDurations, 0, static_cast<jsize>(writable), narrowed.data());
        }
    } while (false);

    for (OrtValue *value : outputs) {
        if (value != nullptr) ort->ReleaseValue(value);
    }
    return result;
}

/** float mel [1, bins, frames] -> float waveform, flattened. */
JNIEXPORT jfloatArray JNICALL
Java_org_itantra_app_tts_OnnxNative_runFloat(JNIEnv *env, jobject, jlong handle,
                                             jfloatArray values, jint bins, jint frames) {
    const OrtApi *ort = Api();
    auto *model = reinterpret_cast<Model *>(handle);
    if (ort == nullptr || model == nullptr) {
        Throw(env, "TTS vocoder session is not open");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(values);
    if (bins <= 0 || frames <= 0 || count != bins * frames) {
        Throw(env, "Mel tensor shape does not match its data");
        return nullptr;
    }
    std::vector<float> data(static_cast<size_t>(count));
    env->GetFloatArrayRegion(values, 0, count, data.data());

    const int64_t shape[3] = {1, bins, frames};
    OrtValue *input = nullptr;
    if (Failed(env, ort->CreateTensorWithDataAsOrtValue(
                        model->memory, data.data(), data.size() * sizeof(float), shape, 3,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input))) {
        return nullptr;
    }
    const char *input_names[] = {model->input_name.c_str()};
    const char *output_names[] = {model->output_name.c_str()};
    OrtValue *output = nullptr;
    OrtStatus *status = ort->Run(model->session, nullptr, input_names,
                                 const_cast<const OrtValue *const *>(&input), 1,
                                 output_names, 1, &output);
    ort->ReleaseValue(input);
    if (Failed(env, status)) return nullptr;

    OrtTensorTypeAndShapeInfo *info = nullptr;
    size_t elements = 0;
    if (Failed(env, ort->GetTensorTypeAndShape(output, &info)) ||
        Failed(env, ort->GetTensorShapeElementCount(info, &elements))) {
        if (info != nullptr) ort->ReleaseTensorTypeAndShapeInfo(info);
        ort->ReleaseValue(output);
        return nullptr;
    }
    ort->ReleaseTensorTypeAndShapeInfo(info);

    float *samples = nullptr;
    if (Failed(env, ort->GetTensorMutableData(output, reinterpret_cast<void **>(&samples)))) {
        ort->ReleaseValue(output);
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(elements));
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(elements), samples);
    }
    ort->ReleaseValue(output);
    return result;
}

}  // extern "C"
