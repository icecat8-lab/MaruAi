// maru_engine.cpp — Maru Upscaler native engine (NCNN + Vulkan, GPU เท่านั้น)
// เลือกโมเดลอัตโนมัติ: classifier ตัดสินประเภทภาพ -> Kotlin เลือกโมเดล -> engine โหลดโมเดลนั้นตอนจำเป็น
// งานคำนวณของโครงข่ายทุกตัว (classifier + โมเดลอัปสเกล) รันบน GPU ไม่มีการสลับไปใช้ CPU
//
// ฝั่ง Kotlin (class MainActivity):
//   external fun initEngineNative(assetManager: AssetManager): Int
//       -2 = เครื่องไม่มี Vulkan GPU, -1 = ล้มเหลวอื่นๆ, ไม่งั้นเป็น bitmask: bit0 (1) = classifier พร้อม
//   external fun classifyBitmapNative(input: Bitmap): Float
//       0..1 = โอกาสเป็นอนิเมะ, -1 = ล้มเหลว/ไม่มี classifier
//   external fun hasModelNative(modelId: Int): Boolean
//       โหลดโมเดลนั้นถ้ายังไม่โหลด (0 = anime, 1 = photo) คืน true ถ้าใช้งานได้
//   external fun upscaleBitmapNative(input: Bitmap, output: Bitmap, modelId: Int, cb: MaruProgress?): Int
//       0 สำเร็จ, 1 โมเดลไม่พร้อม, 2 bitmap ไม่ถูกต้อง, 3 ถูกยกเลิก, 4 GPU อนุมานล้มเหลว, 5 โมเดลไม่ใช่ x4
//   external fun cancelNative()
//   external fun engineBackendNative(): Int      // 2 = GPU (Vulkan) หลังอัปสเกลสำเร็จ, 0 = ยังไม่เคย
//
// bitmap ต้องเป็น ARGB_8888 และ output ต้องกว้าง/สูง = input x 4 พอดี (ทุกโมเดลต้องเป็น x4)
// ชื่อ blob อินพุต/เอาต์พุตอ่านจากไฟล์ .param อัตโนมัติ (ตัวแรกของแต่ละฝั่ง)
// ต้องใช้ ncnn แบบ Vulkan (ncnn-<tag>-android-vulkan.zip)

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

#include "gpu.h"
#include "net.h"

#if !NCNN_VULKAN
#error "ต้องใช้ ncnn แบบ Vulkan (ncnn-<tag>-android-vulkan.zip)"
#endif

#define LOG_TAG "MaruEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kClsParam = "Maru_classifier_opt.param";
constexpr const char* kClsBin = "Maru_classifier_opt.bin";

constexpr int kModelAnime = 0;
constexpr int kModelPhoto = 1;
constexpr int kModelCount = 2;

constexpr int kScale = 4;
constexpr int kTilePad = 10;
constexpr int kMinTile = 32;

constexpr int kInitNoGpu = -2;
constexpr int kInitFail = -1;

// สมมติฐานของ classifier — ตรวจให้ตรงกับตอนเทรน
constexpr int kClsInputSize = 224;
constexpr int kClsAnimeIndex = 0;

enum Result { kOk = 0, kNotReady = 1, kBadBitmap = 2, kCancelled = 3, kInferFail = 4, kBadModel = 5 };

struct Model {
    const char* param;
    const char* bin;
    ncnn::Net net;
    std::string in, out;
    bool loaded = false;  // พร้อมใช้งาน
    bool tried = false;   // เคยพยายามโหลดแล้ว (กันโหลดซ้ำเมื่อไม่มีไฟล์)
    Model(const char* p, const char* b) : param(p), bin(b) {}
};

Model g_models[kModelCount] = {
    Model("Maru_anime-x4_opt.param", "Maru_anime-x4_opt.bin"),
    Model("Maru_photo-x4_opt.param", "Maru_photo-x4_opt.bin"),
};

ncnn::Net g_cls;
std::string g_clsIn, g_clsOut;
bool g_clsLoaded = false;
bool g_initialized = false;
bool g_gpuInstance = false;
bool g_gpuReady = false;
int g_gpuIndex = 0;
jobject g_assetRef = nullptr;
AAssetManager* g_mgr = nullptr;
std::mutex g_mutex;
std::atomic<bool> g_cancel{false};
std::atomic<int> g_lastBackend{0};

void configure(ncnn::Net& net) {
    net.opt.use_vulkan_compute = true;
    net.opt.lightmode = true;
    net.opt.num_threads = 1;  // งานหนักอยู่บน GPU ทั้งหมด
    net.set_vulkan_device(g_gpuIndex);
}

bool assetExists(AAssetManager* mgr, const char* name) {
    AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_UNKNOWN);
    if (!a) return false;
    AAsset_close(a);
    return true;
}

bool loadNet(ncnn::Net& net, AAssetManager* mgr, const char* param, const char* bin,
             std::string& inName, std::string& outName) {
    net.clear();
    configure(net);
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
    LOGI("โหลด %s ok (GPU) in=%s out=%s", param, inName.c_str(), outName.c_str());
    return true;
}

// โหลดโมเดลตอนต้องใช้ (เรียกขณะถือ g_mutex)
bool ensureModel(int id) {
    if (id < 0 || id >= kModelCount || !g_gpuReady) return false;
    Model& m = g_models[id];
    if (m.loaded) return true;
    if (m.tried || !g_mgr) return false;
    m.tried = true;

    if (!assetExists(g_mgr, m.param) || !assetExists(g_mgr, m.bin)) {
        LOGI("ไม่พบไฟล์โมเดล %s (ข้าม)", m.param);
        return false;
    }
    m.loaded = loadNet(m.net, g_mgr, m.param, m.bin, m.in, m.out);
    return m.loaded;
}

// เลือกขนาด tile ตามหน่วยความจำ GPU ที่ใช้ได้ (เกณฑ์เดียวกับ realesrgan-ncnn-vulkan)
int pickTileSize() {
    uint32_t budgetMb = 0;
    ncnn::VulkanDevice* dev = ncnn::get_gpu_device(g_gpuIndex);
    if (dev) budgetMb = dev->get_heap_budget();
    if (budgetMb > 1900) return 200;
    if (budgetMb > 550) return 100;
    if (budgetMb > 190) return 64;
    return kMinTile;
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

int inferTile(Model& m, const ncnn::Mat& tile, ncnn::Mat& res) {
    ncnn::Extractor ex = m.net.create_extractor();
    int r = ex.input(m.in.c_str(), tile);
    if (r != 0) return r;
    return ex.extract(m.out.c_str(), res);
}

struct PassCtx {
    JNIEnv* env;
    jobject cb;
    jmethodID cbMid;
    const unsigned char* inPix;
    int inStride;
    uint8_t* outPix;
    int outStride;
    int w, h;
};

// วิ่งทั้งภาพหนึ่งรอบด้วยขนาด tile ที่กำหนด
int runPass(Model& model, const PassCtx& c, int tileSize) {
    const int tilesX = (c.w + tileSize - 1) / tileSize;
    const int tilesY = (c.h + tileSize - 1) / tileSize;
    const int total = tilesX * tilesY;
    int done = 0;
    int lastPercent = -1;
    const float norm255[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};

    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            if (g_cancel.load()) return kCancelled;

            const int x0 = tx * tileSize, y0 = ty * tileSize;
            const int x1 = std::min(x0 + tileSize, c.w), y1 = std::min(y0 + tileSize, c.h);
            const int px0 = std::max(x0 - kTilePad, 0), py0 = std::max(y0 - kTilePad, 0);
            const int px1 = std::min(x1 + kTilePad, c.w), py1 = std::min(y1 + kTilePad, c.h);

            ncnn::Mat tile = ncnn::Mat::from_pixels_roi(c.inPix, ncnn::Mat::PIXEL_RGBA2RGB, c.w, c.h,
                                                        c.inStride, px0, py0, px1 - px0, py1 - py0);
            if (tile.empty()) return kInferFail;
            tile.substract_mean_normalize(nullptr, norm255);

            ncnn::Mat res;
            int r = inferTile(model, tile, res);
            if (r != 0) {
                LOGE("GPU อนุมานล้มเหลวที่ tile (%d,%d) code=%d tile=%d", tx, ty, r, tileSize);
                return kInferFail;
            }
            if (res.c < 3 || res.w != (px1 - px0) * kScale || res.h != (py1 - py0) * kScale) {
                LOGE("ขนาดผลลัพธ์ไม่ตรง (โมเดลต้องเป็น x4): %dx%dx%d", res.w, res.h, res.c);
                return kBadModel;
            }

            // ตัดขอบ pad ทิ้ง แล้วแปลง float -> 8 บิตลง bitmap ปลายทาง
            const int ox0 = (x0 - px0) * kScale, oy0 = (y0 - py0) * kScale;
            const int cw = (x1 - x0) * kScale, ch = (y1 - y0) * kScale;
            const int dstX = x0 * kScale, dstY = y0 * kScale;

            const ncnn::Mat rc = res.channel(0), gc = res.channel(1), bc = res.channel(2);
            for (int yy = 0; yy < ch; ++yy) {
                const float* rr = rc.row(oy0 + yy) + ox0;
                const float* gg = gc.row(oy0 + yy) + ox0;
                const float* bb = bc.row(oy0 + yy) + ox0;
                uint8_t* d = c.outPix + static_cast<size_t>(dstY + yy) * c.outStride + static_cast<size_t>(dstX) * 4;
                for (int xx = 0; xx < cw; ++xx) {
                    d[0] = toByte(rr[xx]);
                    d[1] = toByte(gg[xx]);
                    d[2] = toByte(bb[xx]);
                    d[3] = 255;
                    d += 4;
                }
            }

            ++done;
            if (c.cbMid) {
                int percent = done * 100 / total;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    c.env->CallVoidMethod(c.cb, c.cbMid, static_cast<jint>(percent));
                    if (c.env->ExceptionCheck()) c.env->ExceptionClear();
                }
            }
        }
    }
    return kOk;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_initEngineNative(JNIEnv* env, jobject /*thiz*/, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return g_clsLoaded ? 1 : 0;

    if (g_assetRef) {
        env->DeleteGlobalRef(g_assetRef);
        g_assetRef = nullptr;
        g_mgr = nullptr;
    }
    g_assetRef = env->NewGlobalRef(assetManager);
    g_mgr = AAssetManager_fromJava(env, g_assetRef);
    if (!g_mgr) {
        LOGE("AAssetManager_fromJava คืนค่า null");
        return kInitFail;
    }

    if (!g_gpuInstance) {
        if (ncnn::create_gpu_instance() != 0) {
            LOGE("สร้าง Vulkan instance ไม่สำเร็จ");
            return kInitNoGpu;
        }
        g_gpuInstance = true;
    }
    if (ncnn::get_gpu_count() <= 0) {
        LOGE("ไม่พบ Vulkan GPU");
        return kInitNoGpu;
    }
    g_gpuIndex = ncnn::get_default_gpu_index();
    g_gpuReady = true;

    g_clsLoaded = assetExists(g_mgr, kClsParam) && assetExists(g_mgr, kClsBin) &&
                  loadNet(g_cls, g_mgr, kClsParam, kClsBin, g_clsIn, g_clsOut);

    g_initialized = true;
    LOGI("initEngine gpu=%d classifier=%d", g_gpuIndex, g_clsLoaded ? 1 : 0);
    return g_clsLoaded ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_maru_upscaler_MainActivity_hasModelNative(JNIEnv* /*env*/, jobject /*thiz*/, jint modelId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return ensureModel(modelId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_maru_upscaler_MainActivity_engineBackendNative(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_lastBackend.load();
}

JNIEXPORT jfloat JNICALL
Java_com_maru_upscaler_MainActivity_classifyBitmapNative(JNIEnv* env, jobject /*thiz*/, jobject bitmap) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_clsLoaded) return -1.f;

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

    PassCtx ctx{env, progressCb, progressMid,
                static_cast<const unsigned char*>(in.pixels), static_cast<int>(in.info.stride),
                static_cast<uint8_t*>(out.pixels), static_cast<int>(out.info.stride), w, h};

    // GPU ล้มเหลว (เช่น หน่วยความจำ GPU ไม่พอ) -> ลดขนาด tile ครึ่งหนึ่งแล้วลองใหม่บน GPU เหมือนเดิม
    int tile = pickTileSize();
    int rc;
    for (;;) {
        rc = runPass(model, ctx, tile);
        if (rc != kInferFail || tile <= kMinTile) break;
        tile = std::max(kMinTile, tile / 2);
        LOGE("ลองใหม่ด้วย tile=%d", tile);
        if (!loadNet(model.net, g_mgr, model.param, model.bin, model.in, model.out)) {
            model.loaded = false;
            model.tried = false;
            return kInferFail;
        }
    }
    if (rc == kOk) g_lastBackend = 2;
    return rc;
}

}  // extern "C"
