// maru_engine.cpp — Maru Upscaler native engine (NCNN + JNI)
//
// สัญญากับฝั่ง Kotlin (ใน class MainActivity):
//
//   fun interface MaruProgress { fun onProgress(percent: Int) }
//
//   external fun initEngineNative(assetManager: android.content.res.AssetManager): Int
//       -> bitmask: bit0 (1) = classifier โหลดได้, bit1 (2) = anime upscaler โหลดได้, 0 = ล้มเหลวทั้งหมด
//   external fun classifyBitmapNative(input: Bitmap): Float
//       -> ความน่าจะเป็นว่าเป็นภาพอนิเมะ 0..1, หรือ -1 ถ้าล้มเหลว
//   external fun upscaleBitmapNative(input: Bitmap, output: Bitmap, cb: MaruProgress?): Int
//       -> 0 สำเร็จ, 1 โมเดลไม่พร้อม, 2 bitmap ไม่ถูกต้อง, 3 ถูกยกเลิก, 4 อนุมานล้มเหลว
//   external fun cancelNative()
//
// ข้อกำหนด: bitmap ทั้งสองต้องเป็น ARGB_8888 และ output ต้องกว้าง/สูง = input x 4 พอดี
// การ decode/encode ไฟล์ (JPEG/PNG, EXIF) ทำฝั่ง Kotlin ด้วย BitmapFactory

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>

#include "cpu.h"
#include "net.h"

#define LOG_TAG "MaruEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ชื่อไฟล์ใน assets และชื่อ blob (ค่าเริ่มต้นของ pnnx -> ncnn คือ in0 / out0)
constexpr const char* kClsParam = "Maru_classifier_opt.param";
constexpr const char* kClsBin = "Maru_classifier_opt.bin";
constexpr const char* kAnimeParam = "Maru_anime-x4_opt.param";
constexpr const char* kAnimeBin = "Maru_anime-x4_opt.bin";
constexpr const char* kInBlob = "in0";
constexpr const char* kOutBlob = "out0";

// อัปสเกล x4 แบบแบ่ง tile: แกนกลาง 128px + ขอบ 10px กันรอยต่อ
constexpr int kScale = 4;
constexpr int kTileSize = 128;
constexpr int kTilePad = 10;

// classifier: ขนาดอินพุตและ index ของคลาส "anime"
// !! สมมติฐานที่ยังไม่ได้ยืนยันจากไฟล์โมเดล — ตรวจให้ตรงกับตอนเทรน !!
constexpr int kClsInputSize = 224;
constexpr int kClsAnimeIndex = 0;

enum Result { kOk = 0, kNotReady = 1, kBadBitmap = 2, kCancelled = 3, kInferFail = 4 };

ncnn::Net g_cls;
ncnn::Net g_anime;
int g_flags = 0;
std::mutex g_mutex;
std::atomic<bool> g_cancel{false};

void configure(ncnn::Net& net) {
    net.opt.use_vulkan_compute = false;
    net.opt.lightmode = true;
    int big = ncnn::get_big_cpu_count();
    net.opt.num_threads = big > 0 ? std::min(big, 4) : 4;
}

bool loadNet(ncnn::Net& net, AAssetManager* mgr, const char* param, const char* bin) {
    net.clear();
    configure(net);
    if (net.load_param(mgr, param) != 0) {
        LOGE("load_param ล้มเหลว: %s (ถ้าไฟล์ขึ้นต้นด้วย pnnx.* แปลว่ายังไม่ใช่ฟอร์แมต ncnn)", param);
        net.clear();
        return false;
    }
    if (net.load_model(mgr, bin) != 0) {
        LOGE("load_model ล้มเหลว: %s", bin);
        net.clear();
        return false;
    }
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

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_initEngineNative(JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_flags == 3) return g_flags;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("AAssetManager_fromJava คืนค่า null");
        return 0;
    }

    ncnn::set_cpu_powersave(2);  // ใช้ big cores

    int flags = 0;
    if (loadNet(g_cls, mgr, kClsParam, kClsBin)) flags |= 1;
    if (loadNet(g_anime, mgr, kAnimeParam, kAnimeBin)) flags |= 2;
    g_flags = flags;
    LOGI("initEngine flags=%d", flags);
    return flags;
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

    // ImageNet mean/std (สมมติฐาน — ปรับให้ตรงกับตอนเทรน)
    const float mean[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm[3] = {1.f / (0.229f * 255.f), 1.f / (0.224f * 255.f), 1.f / (0.225f * 255.f)};
    m.substract_mean_normalize(mean, norm);

    ncnn::Extractor ex = g_cls.create_extractor();
    if (ex.input(kInBlob, m) != 0) return -1.f;
    ncnn::Mat out;
    if (ex.extract(kOutBlob, out) != 0 || out.w < 2) return -1.f;

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

    // callback (ไม่บังคับ)
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

    const int tilesX = (w + kTileSize - 1) / kTileSize;
    const int tilesY = (h + kTileSize - 1) / kTileSize;
    const int total = tilesX * tilesY;
    int done = 0;
    int lastPercent = -1;

    const float norm255[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};

    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            if (g_cancel.load()) return kCancelled;

            const int x0 = tx * kTileSize, y0 = ty * kTileSize;
            const int x1 = std::min(x0 + kTileSize, w), y1 = std::min(y0 + kTileSize, h);
            const int px0 = std::max(x0 - kTilePad, 0), py0 = std::max(y0 - kTilePad, 0);
            const int px1 = std::min(x1 + kTilePad, w), py1 = std::min(y1 + kTilePad, h);

            ncnn::Mat tile = ncnn::Mat::from_pixels_roi(inPix, ncnn::Mat::PIXEL_RGBA2RGB, w, h,
                                                        static_cast<int>(in.info.stride),
                                                        px0, py0, px1 - px0, py1 - py0);
            if (tile.empty()) return kInferFail;
            tile.substract_mean_normalize(nullptr, norm255);

            ncnn::Mat res;
            {
                ncnn::Extractor ex = g_anime.create_extractor();
                if (ex.input(kInBlob, tile) != 0 || ex.extract(kOutBlob, res) != 0) {
                    LOGE("อนุมานล้มเหลวที่ tile (%d,%d)", tx, ty);
                    return kInferFail;
                }
            }
            if (res.c < 3 || res.w != (px1 - px0) * kScale || res.h != (py1 - py0) * kScale) {
                LOGE("ขนาดผลลัพธ์ไม่ตรง: %dx%dx%d", res.w, res.h, res.c);
                return kInferFail;
            }

            // ตัดขอบ pad ทิ้ง แล้วเขียนลง bitmap ปลายทาง
            const int ox0 = (x0 - px0) * kScale, oy0 = (y0 - py0) * kScale;
            const int cw = (x1 - x0) * kScale, ch = (y1 - y0) * kScale;
            const int dstX = x0 * kScale, dstY = y0 * kScale;

            const ncnn::Mat rc = res.channel(0), gc = res.channel(1), bc = res.channel(2);
            for (int yy = 0; yy < ch; ++yy) {
                const float* r = rc.row(oy0 + yy) + ox0;
                const float* g = gc.row(oy0 + yy) + ox0;
                const float* b = bc.row(oy0 + yy) + ox0;
                uint8_t* d = outPix + static_cast<size_t>(dstY + yy) * outStride + static_cast<size_t>(dstX) * 4;
                for (int xx = 0; xx < cw; ++xx) {
                    d[0] = toByte(r[xx]);
                    d[1] = toByte(g[xx]);
                    d[2] = toByte(b[xx]);
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
