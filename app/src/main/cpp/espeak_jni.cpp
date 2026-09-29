#include <jni.h>
#include <espeak-ng/speak_lib.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <vector>

namespace {
std::mutex engineMutex;
std::atomic<bool> cancelled{false};
std::vector<short> pcm;
std::chrono::steady_clock::time_point synthStart;
long long firstPcmNs = -1;
bool initialized = false;
bool overflowed = false;
int callback(short *samples, int count, espeak_EVENT *) {
    if (cancelled.load()) return 1;
    if (samples && count > 0) {
        if (firstPcmNs < 0) firstPcmNs = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - synthStart).count();
        if (pcm.size() + count > 22050 * 120) { overflowed = true; return 1; }
        pcm.insert(pcm.end(), samples, samples + count);
    }
    return 0;
}
void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_org_itantra_app_tts_EspeakNative_initialize(JNIEnv *env, jobject, jstring parentPath) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (initialized) return 22050;
    const char *path = env->GetStringUTFChars(parentPath, nullptr);
    // Synchronous retrieval: the callback supplies PCM, no native audio device.
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 20, path, espeakINITIALIZE_DONT_EXIT);
    env->ReleaseStringUTFChars(parentPath, path);
    if (rate <= 0) { fail(env, "eSpeak voice data initialization failed"); return 0; }
    espeak_SetSynthCallback(callback);
    initialized = true;
    return rate;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_org_itantra_app_tts_EspeakNative_synthesize(JNIEnv *env, jobject, jbyteArray utf8, jstring voice, jint rate, jint pitch) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!initialized) { fail(env, "eSpeak is not initialized"); return nullptr; }
    const char *voiceName = env->GetStringUTFChars(voice, nullptr);
    auto selected = espeak_SetVoiceByName(voiceName);
    env->ReleaseStringUTFChars(voice, voiceName);
    if (selected != EE_OK) { fail(env, "Requested eSpeak voice is unavailable"); return nullptr; }
    espeak_SetParameter(espeakRATE, rate, 0);
    espeak_SetParameter(espeakPITCH, pitch, 0);
    const auto length = env->GetArrayLength(utf8);
    std::vector<char> text(length + 1, 0);
    env->GetByteArrayRegion(utf8, 0, length, reinterpret_cast<jbyte *>(text.data()));
    pcm.clear();
    firstPcmNs = -1;
    overflowed = false;
    cancelled.store(false);
    synthStart = std::chrono::steady_clock::now();
    const auto result = espeak_Synth(text.data(), text.size(), 0, POS_CHARACTER, 0, espeakCHARS_UTF8 | espeakENDPAUSE, nullptr, nullptr);
    if (result != EE_OK) { fail(env, "eSpeak synthesis failed"); return nullptr; }
    if (overflowed) { pcm.clear(); fail(env, "Speech exceeds the two-minute synthesis safety limit"); return nullptr; }
    if (cancelled.load()) { pcm.clear(); }
    auto output = env->NewShortArray(static_cast<jsize>(pcm.size()));
    if (output && !pcm.empty()) env->SetShortArrayRegion(output, 0, static_cast<jsize>(pcm.size()), pcm.data());
    pcm.clear();
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_itantra_app_tts_EspeakNative_firstPcmLatencyNs(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(engineMutex);
    return firstPcmNs;
}
extern "C" JNIEXPORT void JNICALL
Java_org_itantra_app_tts_EspeakNative_stop(JNIEnv *, jobject) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_org_itantra_app_tts_EspeakNative_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (initialized) espeak_Terminate();
    initialized = false;
}
