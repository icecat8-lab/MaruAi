package com.maru.upscaler

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.maru.upscaler.ui.IosButton
import com.maru.upscaler.ui.IosEasing
import com.maru.upscaler.ui.IosIconButton
import com.maru.upscaler.ui.MaruSettingsScreen
import com.maru.upscaler.ui.iosSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/** callback ที่ฝั่ง C++ เรียกกลับ (ชื่อเมธอด onProgress ต้องไม่เปลี่ยน) */
fun interface MaruProgress {
    fun onProgress(percent: Int)
}

class ResultData(
    val full: Bitmap,
    val before: Bitmap,
    val previewAfter: Bitmap,
    val seconds: Double
)

sealed class PipelineResult {
    class Success(val data: ResultData) : PipelineResult()
    class Error(val message: String) : PipelineResult()
    object Cancelled : PipelineResult()
}

private enum class Phase { Idle, Processing, Result, Error }

private val MaruShapes = Shapes(extraSmall = RoundedCornerShape(14.dp))

private val MaruDark = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    surfaceTint = Color.Transparent,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF8E8E93),
    error = Color(0xFFFF6B6B)
)

private val MaruLight = lightColorScheme(
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5E5EA),
    surfaceTint = Color.Transparent,
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF6C6C70),
    error = Color(0xFFD32F2F)
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MaruUpscaler"
        private const val MAX_INPUT_EDGE = 1024   // ด้านยาวสุดของภาพเข้า (ผลลัพธ์ = x4)
        private const val PREVIEW_EDGE = 2048     // ขนาดภาพพรีวิวบนจอ

        // รหัสโมเดล (ต้องตรงกับลำดับใน g_models ของ maru_engine.cpp)
        private const val MODEL_ANIME = 0
        private const val MODEL_PHOTO = 1

        @Volatile
        private var nativeLoaded = false

        init {
            try {
                System.loadLibrary("maru_upscaler")
                nativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "โหลดไลบรารี native ไม่สำเร็จ", e)
            }
        }
    }

    external fun initEngineNative(assetManager: AssetManager): Int
    external fun classifyBitmapNative(input: Bitmap): Float
    external fun hasModelNative(modelId: Int): Boolean
    external fun upscaleBitmapNative(input: Bitmap, output: Bitmap, modelId: Int, cb: MaruProgress?): Int
    external fun cancelNative()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMaxRefreshRate()

        val prefs = getSharedPreferences("maru_prefs", MODE_PRIVATE)
        val savedTheme = prefs.getInt("theme_mode", 2)
        val savedLang = prefs.getString("app_lang", "th") ?: "th"

        setContent {
            var themeMode by remember { mutableStateOf(savedTheme) }
            var currentLang by remember { mutableStateOf(savedLang) }

            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

            MaterialTheme(
                colorScheme = if (darkTheme) MaruDark else MaruLight,
                shapes = MaruShapes
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        themeMode = themeMode,
                        onThemeChange = { newTheme ->
                            themeMode = newTheme
                            prefs.edit().putInt("theme_mode", newTheme).apply()
                        },
                        lang = currentLang,
                        onLangChange = { newLang ->
                            currentLang = newLang
                            prefs.edit().putString("app_lang", newLang).apply()
                        },
                        onUpscale = { uri, lang, onProgress -> runPipeline(uri, lang, onProgress) },
                        onCancel = { if (nativeLoaded) cancelNative() },
                        onSave = { bitmap -> saveToGallery(bitmap) }
                    )
                }
            }
        }
    }

    /** ขอให้จอทำงานที่รีเฟรชเรตสูงสุดของเครื่อง (ความละเอียดเดิม) อัตโนมัติ */
    private fun requestMaxRefreshRate() {
        try {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val current = display.mode
            val best = display.supportedModes
                .filter {
                    it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight
                }
                .maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
        } catch (e: Exception) {
            Log.w(TAG, "ตั้งค่า refresh rate สูงสุดไม่สำเร็จ", e)
        }
    }

    // ---------------------------------------------------------------- pipeline

    private suspend fun runPipeline(
        uri: Uri,
        lang: String,
        onProgress: (Int, String) -> Unit
    ): PipelineResult = withContext(Dispatchers.Default) {
        fun tr(th: String, en: String) = if (lang == "th") th else en
        val started = SystemClock.elapsedRealtime()

        if (!nativeLoaded) {
            return@withContext PipelineResult.Error(
                tr("โหลดไลบรารี native ไม่สำเร็จ", "Failed to load the native library")
            )
        }

        onProgress(2, tr("กำลังเริ่มระบบ AI...", "Starting AI engine..."))
        val initCode = initEngineNative(assets)
        if (initCode == -2) {
            return@withContext PipelineResult.Error(
                tr(
                    "เครื่องนี้ไม่รองรับ Vulkan GPU ซึ่งแอปต้องใช้ในการอัปสเกล",
                    "This device has no Vulkan GPU, which the app requires for upscaling"
                )
            )
        }
        if (initCode < 0) {
            return@withContext PipelineResult.Error(
                tr("เริ่มระบบ AI ไม่สำเร็จ", "Failed to start the AI engine")
            )
        }

        onProgress(5, tr("กำลังอ่านภาพ...", "Reading image..."))
        val input = decodeInput(uri)
            ?: return@withContext PipelineResult.Error(tr("อ่านภาพไม่สำเร็จ", "Cannot read the image"))

        // ---- เลือกโมเดลอัตโนมัติจากผล classifier (ทำงานเงียบๆ ไม่แสดงบนจอ) ----
        onProgress(8, tr("กำลังวิเคราะห์ภาพ...", "Analyzing image..."))
        val prob = classifyBitmapNative(input)   // โอกาสเป็นอนิเมะ, -1 = ไม่มี classifier

        var modelId = if (prob >= 0f && prob < 0.5f) MODEL_PHOTO else MODEL_ANIME
        // ยังไม่มีโมเดล Photo -> ใช้โมเดล Anime แทน
        if (modelId == MODEL_PHOTO && !hasModelNative(MODEL_PHOTO)) {
            modelId = MODEL_ANIME
        }
        if (!hasModelNative(modelId)) {
            return@withContext PipelineResult.Error(
                tr(
                    "โหลดโมเดลอัปสเกลไม่สำเร็จ — ตรวจว่าไฟล์โมเดลใน assets เป็นฟอร์แมต NCNN",
                    "Failed to load the upscale model — make sure the model files in assets are NCNN format"
                )
            )
        }

        val upscalingText = tr("กำลังอัปสเกล...", "Upscaling...")
        onProgress(10, upscalingText)

        val out = try {
            Bitmap.createBitmap(input.width * 4, input.height * 4, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return@withContext PipelineResult.Error(tr("หน่วยความจำไม่พอ", "Out of memory"))
        }

        val code = upscaleBitmapNative(input, out, modelId, MaruProgress { p ->
            onProgress(10 + p * 85 / 100, upscalingText)
        })
        when (code) {
            0 -> Unit
            3 -> return@withContext PipelineResult.Cancelled
            1 -> return@withContext PipelineResult.Error(tr("โมเดลยังไม่พร้อม", "Model is not ready"))
            2 -> return@withContext PipelineResult.Error(tr("ภาพไม่ถูกต้อง", "Invalid bitmap"))
            5 -> return@withContext PipelineResult.Error(
                tr(
                    "ขนาดผลลัพธ์ของโมเดลไม่ใช่ x4 — ตรวจโมเดลที่ export",
                    "The model output is not x4 — check the exported model"
                )
            )
            else -> return@withContext PipelineResult.Error(
                tr("GPU อัปสเกลล้มเหลว", "GPU upscaling failed")
            )
        }

        val longOut = max(out.width, out.height)
        val previewAfter = if (longOut > PREVIEW_EDGE) {
            val s = PREVIEW_EDGE.toFloat() / longOut
            Bitmap.createScaledBitmap(
                out,
                (out.width * s).toInt().coerceAtLeast(1),
                (out.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else out

        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        onProgress(100, tr("เสร็จสิ้น", "Done"))
        PipelineResult.Success(ResultData(out, input, previewAfter, seconds))
    }

    /** อ่านภาพ: ลดขนาด, หมุนตาม EXIF, ทำให้เป็น ARGB_8888 ทึบแสง */
    private fun decodeInput(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_INPUT_EDGE) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    m.postRotate(90f)
                    m.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    m.postRotate(-90f)
                    m.postScale(-1f, 1f)
                }
                else -> Unit
            }
            val longEdge = max(bmp.width, bmp.height)
            if (longEdge > MAX_INPUT_EDGE) {
                val s = MAX_INPUT_EDGE.toFloat() / longEdge
                m.postScale(s, s)
            }
            if (!m.isIdentity) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            if (bmp.config != Bitmap.Config.ARGB_8888) {
                bmp = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            if (bmp.hasAlpha()) {
                // โมเดลไม่รองรับ alpha: วางบนพื้นขาวก่อน
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                AndroidCanvas(flat).apply {
                    drawColor(AndroidColor.WHITE)
                    drawBitmap(bmp, 0f, 0f, null)
                }
                bmp = flat
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "decodeInput ล้มเหลว", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "decodeInput หน่วยความจำไม่พอ", e)
            null
        }
    }

    private suspend fun saveToGallery(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        var created: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "MaruUpscale_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MaruUpscaler")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val target = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            created = target

            val ok = contentResolver.openOutputStream(target)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } ?: false
            if (!ok) {
                contentResolver.delete(target, null, null)
                return@withContext false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(target, done, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "บันทึกภาพล้มเหลว", e)
            created?.let {
                try {
                    contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            false
        }
    }
}

// ------------------------------------------------------------------------ UI

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    lang: String,
    onLangChange: (String) -> Unit,
    onUpscale: suspend (Uri, String, (Int, String) -> Unit) -> PipelineResult,
    onCancel: () -> Unit,
    onSave: suspend (Bitmap) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var result by remember { mutableStateOf<ResultData?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    var etaSeconds by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0.5f) }
    var userTouched by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val runCounter = remember { intArrayOf(0) }
    val etaBase = remember { longArrayOf(0L, 0L) }   // [0] = เวลาเริ่มวัด, [1] = progress ตอนเริ่มวัด
    val lastData = remember { mutableStateOf<ResultData?>(null) }

    val phase = when {
        isProcessing -> Phase.Processing
        result != null -> Phase.Result
        errorText != null -> Phase.Error
        else -> Phase.Idle
    }
    val hasContent = phase != Phase.Idle
    val shown = result ?: lastData.value

    fun tr(th: String, en: String) = if (lang == "th") th else en

    // เก็บผลลัพธ์ล่าสุดไว้ชั่วคราว ให้แอนิเมชันตอนออกจากหน้าผลลัพธ์ยังมีภาพให้แสดง
    LaunchedEffect(result) {
        if (result != null) lastData.value = result
    }
    LaunchedEffect(phase) {
        if (phase != Phase.Result) {
            delay(500)
            lastData.value = null
        }
    }

    // เปิดหน้าผลลัพธ์: เส้นแบ่งไหลมาที่กลางภาพอย่างนุ่มนวล (หยุดทันทีถ้าผู้ใช้แตะ)
    LaunchedEffect(result) {
        if (result != null) {
            userTouched = false
            sliderPosition = 1f
            animate(
                initialValue = 1f,
                targetValue = 0.5f,
                animationSpec = spring(dampingRatio = 1f, stiffness = 70f)
            ) { v, _ -> if (!userTouched) sliderPosition = v }
        }
    }

    fun resetAll() {
        if (isProcessing) {
            job?.cancel()
            onCancel()
        }
        runCounter[0]++
        isProcessing = false
        result = null
        errorText = null
        progress = 0
        statusText = ""
        etaSeconds = null
        sliderPosition = 0.5f
    }

    fun startUpscale(uri: Uri) {
        if (isProcessing) {
            job?.cancel()
            onCancel()
        }
        val runId = ++runCounter[0]
        result = null
        errorText = null
        progress = 0
        etaSeconds = null
        etaBase[0] = 0L
        etaBase[1] = 0L
        sliderPosition = 0.5f
        statusText = tr("กำลังเตรียม...", "Preparing...")
        isProcessing = true
        job = scope.launch {
            val r = onUpscale(uri, lang) { p, s ->
                if (runId == runCounter[0]) {
                    progress = p
                    statusText = s
                    // ประมาณเวลาที่เหลือจากความเร็วของ tile ที่เสร็จแล้ว (ช่วง 10..95%)
                    if (p in 11..94) {
                        val now = SystemClock.elapsedRealtime()
                        if (etaBase[0] == 0L) {
                            etaBase[0] = now
                            etaBase[1] = p.toLong()
                        } else if (p - etaBase[1] >= 4) {
                            val perUnit = (now - etaBase[0]).toDouble() / (p - etaBase[1])
                            val raw = (perUnit * (95 - p) / 1000.0 + 0.5).toInt()
                            val prev = etaSeconds
                            etaSeconds = if (prev == null) raw else (prev * 2 + raw + 1) / 3
                        }
                    }
                }
            }
            if (runId == runCounter[0]) {
                when (r) {
                    is PipelineResult.Success -> result = r.data
                    is PipelineResult.Error -> errorText = r.message
                    PipelineResult.Cancelled -> Unit
                }
                isProcessing = false
            }
        }
    }

    fun doSave() {
        val data = result ?: return
        if (isSaving) return
        isSaving = true
        scope.launch {
            val ok = onSave(data.full)
            isSaving = false
            Toast.makeText(
                context,
                if (ok) tr("บันทึกรูปภาพเรียบร้อย!", "Image saved successfully!")
                else tr("บันทึกไม่สำเร็จ", "Failed to save"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doSave()
        else Toast.makeText(
            context,
            tr("ต้องอนุญาตการเข้าถึงที่เก็บข้อมูลเพื่อบันทึกภาพ", "Storage permission is required to save"),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun requestSave() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else doSave()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) startUpscale(uri)
    }

    BackHandler(enabled = showSettings || hasContent) {
        if (showSettings) showSettings = false else resetAll()
    }

    // เปลี่ยนหน้าหลัก <-> ตั้งค่า แบบ push ของ iOS (หน้าเดิมเลื่อนถอยหลังเล็กน้อย)
    AnimatedContent(
        targetState = showSettings,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(animationSpec = iosSpring(400f)) { it }) togetherWith
                    (slideOutHorizontally(animationSpec = iosSpring(400f)) { -it / 4 } +
                        fadeOut(animationSpec = iosSpring(400f)))
            } else {
                ((slideInHorizontally(animationSpec = iosSpring(400f)) { -it / 4 } +
                    fadeIn(animationSpec = iosSpring(400f))) togetherWith
                    (slideOutHorizontally(animationSpec = iosSpring(400f)) { it })).apply {
                    targetContentZIndex = -1f
                }
            }
        },
        label = "screen"
    ) { settingsVisible ->
        if (settingsVisible) {
            MaruSettingsScreen(
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                lang = lang,
                onLangChange = onLangChange,
                onBack = { showSettings = false }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(cs.background)
            ) {
                // ---- แถบบน: ซ้าย = กากบาท (ระหว่างประมวลผล) / ย้อนกลับ, ขวา = เมนู ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val leftMode = when {
                        isProcessing -> 1
                        hasContent -> 2
                        else -> 0
                    }
                    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = leftMode,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(220)) +
                                    scaleIn(initialScale = 0.6f, animationSpec = tween(220, easing = IosEasing))) togetherWith
                                    (fadeOut(animationSpec = tween(120)) +
                                        scaleOut(targetScale = 0.6f, animationSpec = tween(120)))
                            },
                            label = "leftIcon"
                        ) { mode ->
                            when (mode) {
                                1 -> IosIconButton(onClick = { resetAll() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = cs.onBackground)
                                }
                                2 -> IosIconButton(onClick = { resetAll() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
                                }
                                else -> Spacer(modifier = Modifier.size(44.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IosIconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = cs.onBackground)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ---- พื้นที่หลัก: เปลี่ยนสถานะด้วย fade + scale นุ่มๆ ----
                    AnimatedContent(
                        targetState = phase,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(350, easing = IosEasing)) +
                                scaleIn(initialScale = 0.94f, animationSpec = tween(350, easing = IosEasing))) togetherWith
                                fadeOut(animationSpec = tween(180))
                        },
                        label = "phase"
                    ) { p ->
                        when (p) {
                            Phase.Idle -> CardBox {
                                Text(
                                    text = tr("ยังไม่ได้เลือกรูปภาพ", "No image selected"),
                                    color = cs.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }

                            Phase.Processing -> CardBox {
                                ProcessingView(
                                    progress = progress,
                                    etaSeconds = etaSeconds,
                                    statusText = statusText,
                                    thai = lang == "th"
                                )
                            }

                            Phase.Error -> CardBox {
                                Text(
                                    text = errorText ?: "",
                                    color = cs.error,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(24.dp)
                                )
                            }

                            Phase.Result -> {
                                val data = shown
                                if (data != null) {
                                    ResultView(
                                        data = data,
                                        position = { sliderPosition },
                                        onDrag = {
                                            userTouched = true
                                            sliderPosition = it
                                        },
                                        onTap = { target ->
                                            userTouched = true
                                            scope.launch {
                                                animate(
                                                    initialValue = sliderPosition,
                                                    targetValue = target,
                                                    animationSpec = spring(dampingRatio = 1f, stiffness = 400f)
                                                ) { v, _ -> sliderPosition = v }
                                            }
                                        },
                                        beforeLabel = tr("ก่อน", "Before"),
                                        afterLabel = tr("หลัง", "After"),
                                        timeText = tr(
                                            "เสร็จใน ${String.format(Locale.US, "%.1f", data.seconds)} วินาที",
                                            "Done in ${String.format(Locale.US, "%.1f", data.seconds)} s"
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ---- ปุ่มด้านล่าง ----
                    val buttonMode = when (phase) {
                        Phase.Result -> 2
                        Phase.Processing -> 0
                        else -> 1
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = buttonMode,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(300)) +
                                    slideInVertically(animationSpec = iosSpring(400f)) { it / 2 }) togetherWith
                                    fadeOut(animationSpec = tween(150))
                            },
                            label = "buttons"
                        ) { mode ->
                            when (mode) {
                                2 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IosButton(
                                        text = tr("บันทึกภาพ", "Save Image"),
                                        onClick = { requestSave() },
                                        enabled = !isSaving,
                                        modifier = Modifier.width(150.dp)
                                    )
                                    IosButton(
                                        text = tr("เลือกรูปอื่น", "Another image"),
                                        onClick = { picker.launch("image/*") },
                                        filled = false,
                                        modifier = Modifier.width(150.dp)
                                    )
                                }

                                1 -> IosButton(
                                    text = tr("เลือกรูปภาพ", "Select Image"),
                                    onClick = { picker.launch("image/*") },
                                    modifier = Modifier.width(200.dp)
                                )

                                else -> Spacer(modifier = Modifier.height(50.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "© 2026 icecat",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun CardBox(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
        content = content
    )
}

private fun formatEta(seconds: Int, thai: Boolean): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (thai) {
        if (m > 0) "$m นาที $s วินาที" else "$seconds วินาที"
    } else {
        if (m > 0) "$m min $s s" else "$seconds s"
    }
}

/** หน้าระหว่างประมวลผล: เปอร์เซ็นต์ + แถบความคืบหน้า + เวลาที่เหลือ */
@Composable
private fun ProcessingView(
    progress: Int,
    etaSeconds: Int?,
    statusText: String,
    thai: Boolean
) {
    val cs = MaterialTheme.colorScheme
    val shownPercent by animateIntAsState(
        targetValue = progress,
        animationSpec = tween(450, easing = IosEasing),
        label = "percent"
    )
    val fraction by animateFloatAsState(
        targetValue = (progress / 100f).coerceIn(0f, 1f),
        animationSpec = tween(450, easing = IosEasing),
        label = "fraction"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (thai) "กำลังประมวลผล" else "Processing",
            fontSize = 14.sp,
            color = cs.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$shownPercent%",
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            color = cs.onSurface
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(cs.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(cs.onSurface)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(text = statusText, fontSize = 13.sp, color = cs.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (etaSeconds == null) {
                if (thai) "กำลังคำนวณเวลา..." else "Estimating time..."
            } else {
                if (thai) "เหลืออีกประมาณ ${formatEta(etaSeconds, true)}"
                else "About ${formatEta(etaSeconds, false)} left"
            },
            fontSize = 13.sp,
            color = cs.onSurfaceVariant
        )
    }
}

/** หน้าผลลัพธ์: กรอบพอดีกับสัดส่วนภาพ ไม่มีแถบดำ */
@Composable
private fun ResultView(
    data: ResultData,
    position: () -> Float,
    onDrag: (Float) -> Unit,
    onTap: (Float) -> Unit,
    beforeLabel: String,
    afterLabel: String,
    timeText: String
) {
    val cs = MaterialTheme.colorScheme
    val beforeImg = remember(data.before) { data.before.asImageBitmap() }
    val afterImg = remember(data.previewAfter) { data.previewAfter.asImageBitmap() }
    val ratio = data.before.width.toFloat() / data.before.height.toFloat()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            CompareView(
                before = beforeImg,
                after = afterImg,
                position = position,
                onDrag = onDrag,
                onTap = onTap,
                beforeLabel = beforeLabel,
                afterLabel = afterLabel,
                modifier = Modifier
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(16.dp))
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = timeText, fontSize = 12.sp, color = cs.onSurfaceVariant)
    }
}

/** เปรียบเทียบก่อน/หลัง: ลากหรือแตะเพื่อเลื่อนเส้นแบ่ง (ซ้าย = ก่อน, ขวา = หลัง) */
@Composable
private fun CompareView(
    before: ImageBitmap,
    after: ImageBitmap,
    position: () -> Float,
    onDrag: (Float) -> Unit,
    onTap: (Float) -> Unit,
    beforeLabel: String,
    afterLabel: String,
    modifier: Modifier = Modifier
) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnTap by rememberUpdatedState(onTap)
    var dragging by remember { mutableStateOf(false) }
    val handleScale by animateFloatAsState(
        targetValue = if (dragging) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "handleScale"
    )

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    change.consume()
                    latestOnDrag((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    latestOnTap((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        Image(
            bitmap = after,
            contentDescription = afterLabel,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Image(
            bitmap = before,
            contentDescription = beforeLabel,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * position()) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = size.width * position()
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3f
            )
            drawCircle(
                color = Color(0x33000000),
                radius = 17.dp.toPx() * handleScale,
                center = Offset(x, size.height / 2f)
            )
            drawCircle(
                color = Color.White,
                radius = 14.dp.toPx() * handleScale,
                center = Offset(x, size.height / 2f)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(beforeLabel, color = Color.White, fontSize = 11.sp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(afterLabel, color = Color.White, fontSize = 11.sp)
        }
    }
}
