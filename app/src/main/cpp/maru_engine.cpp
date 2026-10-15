#jni/cpp
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

#include "../../core/src/engine.h"

#define LOG_TAG "MaruEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kModelCount = 2;
constexpr int kScale = 4;

constexpr int kInitNoGpu = -2;
constexpr int kInitFail = -1;

enum Result { kOk = 0, kNotReady = 1, kBadBitmap = 2, kCancelled = 3, kInferFail = 4, kBadModel = 5 };

struct Model {
    const char* filename;
    vva::Engine engine;
    bool loaded = false;
    bool tried = false;
    Model(const char* f) : filename(f) {}
};

Model g_models[kModelCount] = {
    Model("maru_anime_x4.vva"),
    Model("maru_photo_x4.vva"),
};

bool g_initialized = false;
jobject g_assetRef = nullptr;
AAssetManager* g_mgr = nullptr;
std::mutex g_mutex;
std::atomic<bool> g_cancel{false};

bool ensureModel(int id) {
    if (id < 0 || id >= kModelCount) return false;
    Model& m = g_models[id];
    if (m.loaded) return true;
    if (m.tried || !g_mgr) return false;
    m.tried = true;

    m.loaded = m.engine.loadModel(g_mgr, m.filename);
    return m.loaded;
}

struct LockedBitmap {
    JNIEnv* env = nullptr;
    jobject bmp = nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    bool ok = false;

    LockedBitmap(JNIEnv* e, jobject b) : env(e), bmp(b) {
        if (!b) return;
        if (AndroidBitmap_getInfo(e, b, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        if (AndroidBitmap_lockPixels(e, b, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        ok = pixels != nullptr;
    }
    ~LockedBitmap() {
        if (ok) AndroidBitmap_unlockPixels(env, bmp);
    }
    LockedBitmap(const LockedBitmap&) = delete;
    LockedBitmap& operator=(const LockedBitmap&) = delete;
};

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_initEngineNative(JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return 1;

    if (g_assetRef) {
        env->DeleteGlobalRef(g_assetRef);
        g_assetRef = nullptr;
        g_mgr = nullptr;
    }
    g_assetRef = env->NewGlobalRef(assetManager);
    g_mgr = AAssetManager_fromJava(env, g_assetRef);
    if (!g_mgr) {
        LOGE("AAssetManager_fromJava returned null");
        return kInitFail;
    }

    g_initialized = true;
    LOGI("VVA Engine initialized successfully");
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_com_maru_upscaler_MainActivity_hasModelNative(JNIEnv* /*env*/, jobject /*thiz*/, jint modelId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return ensureModel(modelId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_maru_upscaler_MainActivity_classifyBitmapNative(JNIEnv* /*env*/, jobject /*thiz*/, jobject /*bitmap*/) {
    return 1.0f;
}

JNIEXPORT void JNICALL
Java_com_maru_upscaler_MainActivity_cancelNative(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_upscaleBitmapNative(JNIEnv* env, jobject /*thiz*/,
                                                       jobject inBitmap, jobject outBitmap,
                                                       jint modelId, jobject progressCb) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(false);
    if (!ensureModel(modelId)) return kNotReady;
    Model& model = g_models[modelId];

    LockedBitmap in(env, inBitmap);
    LockedBitmap out(env, outBitmap);
    if (!in.ok || !out.ok) return kBadBitmap;

    const int w = static_cast<int>(in.info.width);
    const int h = static_cast<int>(in.info.height);
    if (w <= 0 || h <= 0) return kBadBitmap;
    if (static_cast<int>(out.info.width) != w * kScale || static_cast<int>(out.info.height) != h * kScale)
        return kBadBitmap;

    jmethodID progressMid = nullptr;
    if (progressCb) {
        jclass cls = env->GetObjectClass(progressCb);
        progressMid = env->GetMethodID(cls, "onProgress", "(I)V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            progressMid = nullptr;
        }
        env->DeleteLocalRef(cls);
    }

    bool success = model.engine.process(in.pixels, out.pixels, w, h, [&](int percent) {
        if (progressCb && progressMid) {
            env->CallVoidMethod(progressCb, progressMid, static_cast<jint>(percent));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    });

    if (g_cancel.load()) return kCancelled;
    return success ? kOk : kInferFail;
}

}  // extern "C"
