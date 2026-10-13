// maru_engine.cpp — Maru Upscaler native engine (NCNN + Vulkan GPU, fallback CPU)
//
// ฝั่ง Kotlin (class MainActivity):
//   external fun initEngineNative(assetManager: AssetManager): Int
//       bit0 (1) = classifier พร้อม, bit1 (2) = โมเดลอัปสเกลพร้อม, bit2 (4) = ใช้ GPU อยู่
//   external fun classifyBitmapNative(input: Bitmap): Float
//       0..1 = โอกาสเป็นอนิเมะ, -1 = ล้มเหลว
//   external fun upscaleBitmapNative(input: Bitmap, output: Bitmap, cb: MaruProgress?): Int
//       0 สำเร็จ, 1 โมเดลไม่พร้อม, 2 bitmap ไม่ถูกต้อง, 3 ถูกยกเลิก, 4 อนุมานล้มเหลว
//   external fun cancelNative()
//   external fun engineBackendNative(): Int      // 0 ไม่พร้อม, 1 CPU, 2 GPU (Vulkan)
//
// bitmap ต้องเป็น ARGB_8888 และ output ต้องกว้าง/สูง = input x 4 พอดี
// ชื่อ blob อินพุต/เอาต์พุตอ่านจากไฟล์ .param อัตโนมัติ (ตัวแรกของแต่ละฝั่ง)

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>

#include "cpu.h"
#include "gpu.h"
#include "net.h"

#define LOG_TAG "MaruEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kClsParam = "Maru_classifier_opt.param";
constexpr const char* kClsBin = "Maru_classifier_opt.bin";
constexpr const char* kAnimeParam = "Maru_anime-x4_opt.param";
constexpr const char* kAnimeBin = "Maru_anime-x4_opt.bin";

constexpr int kScale = 4;
constexpr int kTileGpu = 160;
constexpr int kTileCpu = 128;
constexpr int kTilePad = 10;

// สมมติฐานของ classifier — ตรวจให้ตรงกับตอนเทรน
constexpr int kClsInputSize = 224;
constexpr int kClsAnimeIndex = 0;

enum Result { kOk = 0, kNotReady = 1, kBadBitmap = 2, kCancelled = 3, kInferFail = 4 };

ncnn::Net g_cls;
ncnn::Net g_anime;
std::string g_clsIn, g_clsOut, g_animeIn, g_animeOut;
int g_flags = 0;
std::atomic<int> g_backend{0};
bool g_gpuInstance = false;
jobject g_assetRef = nullptr;
AAssetManager* g_mgr = nullptr;
std::mutex g_mutex;
std::atomic<bool> g_cancel{false};

void configure(ncnn::Net& net, bool useGpu) {
    net.opt.lightmode = true;
    int big = ncnn::get_big_cpu_count();
    net.opt.num_threads = big > 0 ? std::min(big, 4) : 4;
#if NCNN_VULKAN
    net.opt.use_vulkan_compute = useGpu;
    if (useGpu) net.set_vulkan_device(0);
#else
    (void)useGpu;
#endif
}

bool loadNet(ncnn::Net& net, AAssetManager* mgr, const char* param, const char* bin, bool useGpu,
             std::string& inName, std::string& outName) {
    net.clear();
    configure(net, useGpu);
    if (net.load_param(mgr, param) != 0) {
        LOGE("load_param ล้มเหลว: %s (ถ้าขึ้นต้นด้วย pnnx.* แปลว่ายังไม่ใช่ฟอร์แมต ncnn)", param);
        net.clear();
        return false;
    }
    if (net.load_model(mgr, bin) != 0) {
        LOGE("load_model ล้มเหลว: %s", bin);
        net.clear();
        return false;
    }
    const auto& ins = net.input_names();
    const auto& outs = net.output_names();
    if (ins.empty() || outs.empty()) {
        LOGE("ไม่พบ blob อินพุต/เอาต์พุตใน %s", param);
        net.clear();
        return false;
    }
    inName = ins[0];
    outName = outs[0];
    LOGI("โหลด %s ok (%s) in=%s out=%s", param, useGpu ? "GPU" : "CPU", inName.c_str(), outName.c_str());
    return true;
}

bool reloadAnimeOnCpu() {
    if (!g_mgr) return false;
    if (!loadNet(g_anime, g_mgr, kAnimeParam, kAnimeBin, false, g_animeIn, g_animeOut)) {
        g_flags &= ~2;
        g_backend = 0;
        return false;
    }
    g_backend = 1;
    return true;
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

inline uint8_t toByte(float v) {
    float x = v * 255.f + 0.5f;
    if (x < 0.f) x = 0.f;
    if (x > 255.f) x = 255.f;
    return static_cast<uint8_t>(x);
}

int inferTile(const ncnn::Mat& tile, ncnn::Mat& res) {
    ncnn::Extractor ex = g_anime.create_extractor();
    int r = ex.input(g_animeIn.c_str(), tile);
    if (r != 0) return r;
    return ex.extract(g_animeOut.c_str(), res);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_initEngineNative(JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_flags & 2) return g_flags | (g_backend.load() == 2 ? 4 : 0);

    if (g_assetRef) {
        env->DeleteGlobalRef(g_assetRef);
        g_assetRef = nullptr;
        g_mgr = nullptr;
    }
    g_assetRef = env->NewGlobalRef(assetManager);
    g_mgr = AAssetManager_fromJava(env, g_assetRef);
    if (!g_mgr) {
        LOGE("AAssetManager_fromJava คืนค่า null");
        return 0;
    }

    ncnn::set_cpu_powersave(2);

    bool gpuOk = false;
#if NCNN_VULKAN
    if (!g_gpuInstance && ncnn::create_gpu_instance() == 0) g_gpuInstance = true;
    gpuOk = g_gpuInstance && ncnn::get_gpu_count() > 0;
#endif

    int flags = 0;
    if (loadNet(g_cls, g_mgr, kClsParam, kClsBin, false, g_clsIn, g_clsOut)) flags |= 1;

    int backend = 0;
    if (gpuOk && loadNet(g_anime, g_mgr, kAnimeParam, kAnimeBin, true, g_animeIn, g_animeOut)) {
        backend = 2;
    } else if (loadNet(g_anime, g_mgr, kAnimeParam, kAnimeBin, false, g_animeIn, g_animeOut)) {
        backend = 1;
    }
    if (backend != 0) flags |= 2;

    g_flags = flags;
    g_backend = backend;
    LOGI("initEngine flags=%d backend=%d", flags, backend);
    return flags | (backend == 2 ? 4 : 0);
}

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_engineBackendNative(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_backend.load();
}

JNIEXPORT jfloat JNICALL
Java_com_maru_upscaler_MainActivity_classifyBitmapNative(JNIEnv* env, jobject /*thiz*/, jobject bitmap) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!(g_flags & 1)) return -1.f;

    LockedBitmap in(env, bitmap);
    if (!in.ok) return -1.f;

    ncnn::Mat m = ncnn::Mat::from_pixels_resize(
        static_cast<const unsigned char*>(in.pixels), ncnn::Mat::PIXEL_RGBA2RGB,
        static_cast<int>(in.info.width), static_cast<int>(in.info.height),
        static_cast<int>(in.info.stride), kClsInputSize, kClsInputSize);
    if (m.empty()) return -1.f;

    const float mean[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm[3] = {1.f / (0.229f * 255.f), 1.f / (0.224f * 255.f), 1.f / (0.225f * 255.f)};
    m.substract_mean_normalize(mean, norm);

    ncnn::Extractor ex = g_cls.create_extractor();
    if (ex.input(g_clsIn.c_str(), m) != 0) return -1.f;
    ncnn::Mat out;
    if (ex.extract(g_clsOut.c_str(), out) != 0 || out.w < 2) return -1.f;

    const float* p = out;
    float mx = std::max(p[0], p[1]);
    float e0 = std::exp(p[0] - mx);
    float e1 = std::exp(p[1] - mx);
    float prob[2] = {e0 / (e0 + e1), e1 / (e0 + e1)};
    return prob[kClsAnimeIndex];
}

JNIEXPORT void JNICALL
Java_com_maru_upscaler_MainActivity_cancelNative(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_upscaleBitmapNative(JNIEnv* env, jobject /*thiz*/,
                                                       jobject inBitmap, jobject outBitmap,
                                                       jobject progressCb) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(false);
    if (!(g_flags & 2)) return kNotReady;

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

    const auto* inPix = static_cast<const unsigned char*>(in.pixels);
    auto* outPix = static_cast<uint8_t*>(out.pixels);
    const int outStride = static_cast<int>(out.info.stride);

    const int tileSize = (g_backend.load() == 2) ? kTileGpu : kTileCpu;
    const int tilesX = (w + tileSize - 1) / tileSize;
    const int tilesY = (h + tileSize - 1) / tileSize;
    const int total = tilesX * tilesY;
    int done = 0;
    int lastPercent = -1;

    const float norm255[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};

    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            if (g_cancel.load()) return kCancelled;

            const int x0 = tx * tileSize, y0 = ty * tileSize;
            const int x1 = std::min(x0 + tileSize, w), y1 = std::min(y0 + tileSize, h);
            const int px0 = std::max(x0 - kTilePad, 0), py0 = std::max(y0 - kTilePad, 0);
            const int px1 = std::min(x1 + kTilePad, w), py1 = std::min(y1 + kTilePad, h);

            ncnn::Mat tile = ncnn::Mat::from_pixels_roi(inPix, ncnn::Mat::PIXEL_RGBA2RGB, w, h,
                                                        static_cast<int>(in.info.stride),
                                                        px0, py0, px1 - px0, py1 - py0);
            if (tile.empty()) return kInferFail;
            tile.substract_mean_normalize(nullptr, norm255);

            ncnn::Mat res;
            int r = inferTile(tile, res);
            if (r != 0 && g_backend.load() == 2) {
                LOGE("GPU ล้มเหลว (code %d) ที่ tile (%d,%d) -> สลับไปใช้ CPU", r, tx, ty);
                if (!reloadAnimeOnCpu()) return kInferFail;
                res = ncnn::Mat();
                r = inferTile(tile, res);
            }
            if (r != 0) {
                LOGE("อนุมานล้มเหลวที่ tile (%d,%d) code=%d", tx, ty, r);
                return kInferFail;
            }
            if (res.c < 3 || res.w != (px1 - px0) * kScale || res.h != (py1 - py0) * kScale) {
                LOGE("ขนาดผลลัพธ์ไม่ตรง: %dx%dx%d", res.w, res.h, res.c);
                return kInferFail;
            }

            const int ox0 = (x0 - px0) * kScale, oy0 = (y0 - py0) * kScale;
            const int cw = (x1 - x0) * kScale, ch = (y1 - y0) * kScale;
            const int dstX = x0 * kScale, dstY = y0 * kScale;

            const ncnn::Mat rc = res.channel(0), gc = res.channel(1), bc = res.channel(2);
            for (int yy = 0; yy < ch; ++yy) {
                const float* rr = rc.row(oy0 + yy) + ox0;
                const float* gg = gc.row(oy0 + yy) + ox0;
                const float* bb = bc.row(oy0 + yy) + ox0;
                uint8_t* d = outPix + static_cast<size_t>(dstY + yy) * outStride + static_cast<size_t>(dstX) * 4;
                for (int xx = 0; xx < cw; ++xx) {
                    d[0] = toByte(rr[xx]);
                    d[1] = toByte(gg[xx]);
                    d[2] = toByte(bb[xx]);
                    d[3] = 255;
                    d += 4;
                }
            }

            ++done;
            if (progressMid) {
                int percent = done * 100 / total;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    env->CallVoidMethod(progressCb, progressMid, static_cast<jint>(percent));
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            }
        }
    }
    return kOk;
}

}  // extern "C"
