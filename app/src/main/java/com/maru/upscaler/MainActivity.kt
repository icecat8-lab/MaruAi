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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.maru.upscaler.ui.MaruSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val label: String,
    val seconds: Double
)

sealed class PipelineResult {
    class Success(val data: ResultData) : PipelineResult()
    class Error(val message: String) : PipelineResult()
    object Cancelled : PipelineResult()
}

private val MaruDark = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFF8E8E93),
    error = Color(0xFFFF6B6B)
)

private val MaruLight = lightColorScheme(
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5E5EA),
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
    external fun upscaleBitmapNative(input: Bitmap, output: Bitmap, cb: MaruProgress?): Int
    external fun cancelNative()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

            MaterialTheme(colorScheme = if (darkTheme) MaruDark else MaruLight) {
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

        onProgress(2, tr("กำลังโหลดโมเดล...", "Loading models..."))
        val flags = initEngineNative(assets)
        if ((flags and 2) == 0) {
            return@withContext PipelineResult.Error(
                tr(
                    "โหลดโมเดลอัปสเกลไม่สำเร็จ — ตรวจว่าไฟล์โมเดลใน assets เป็นฟอร์แมต NCNN",
                    "Failed to load the upscale model — make sure the model files in assets are NCNN format"
                )
            )
        }

        onProgress(5, tr("กำลังอ่านภาพ...", "Reading image..."))
        val input = decodeInput(uri)
            ?: return@withContext PipelineResult.Error(tr("อ่านภาพไม่สำเร็จ", "Cannot read the image"))

        val prob = classifyBitmapNative(input)
        val label = when {
            prob < 0f -> tr("ไม่ทราบประเภทภาพ", "Unknown image type")
            prob >= 0.5f -> tr("ตรวจพบ: อนิเมะ", "Detected: anime")
            else -> tr(
                "ตรวจพบ: ภาพถ่าย (ใช้โมเดลอนิเมะ ผลอาจไม่เหมาะ)",
                "Detected: photo (using the anime model, results may vary)"
            )
        }
        onProgress(10, label)

        val out = try {
            Bitmap.createBitmap(input.width * 4, input.height * 4, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return@withContext PipelineResult.Error(tr("หน่วยความจำไม่พอ", "Out of memory"))
        }

        val upscalingText = tr("กำลังอัปสเกล...", "Upscaling...")
        val code = upscaleBitmapNative(input, out, MaruProgress { p ->
            onProgress(10 + p * 85 / 100, upscalingText)
        })
        when (code) {
            0 -> Unit
            3 -> return@withContext PipelineResult.Cancelled
            1 -> return@withContext PipelineResult.Error(tr("โมเดลยังไม่พร้อม", "Model is not ready"))
            2 -> return@withContext PipelineResult.Error(tr("ภาพไม่ถูกต้อง", "Invalid bitmap"))
            else -> return@withContext PipelineResult.Error(tr("การอัปสเกลล้มเหลว", "Upscaling failed"))
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
        onProgress(100, String.format(Locale.US, tr("เสร็จสิ้นใน %.1f วินาที", "Done in %.1f s"), seconds))
        PipelineResult.Success(ResultData(out, input, previewAfter, label, seconds))
    }

    
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var showSettings by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0.5f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val runCounter = remember { intArrayOf(0) }

    val hasContent = isProcessing || result != null || errorText != null

    fun tr(th: String, en: String) = if (lang == "th") th else en

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
        sliderPosition = 0.5f
        statusText = tr("กำลังเตรียม...", "Preparing...")
        isProcessing = true
        job = scope.launch {
            val r = onUpscale(uri, lang) { p, s ->
                if (runId == runCounter[0]) {
                    progress = p
                    statusText = s
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

    if (showSettings) {
        MaruSettingsScreen(
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            lang = lang,
            onLangChange = onLangChange,
            onBack = { showSettings = false }
        )
    } else {
        Scaffold(
            containerColor = cs.background,
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        if (hasContent) {
                            IconButton(onClick = { resetAll() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = cs.onBackground)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(cs.background)
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cs.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        val data = result
                        val err = errorText
                        when {
                            isProcessing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = cs.onSurface)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "${tr("กำลังประมวลผล", "Processing")}: $progress%",
                                    color = cs.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = statusText, fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }

                            data != null -> {
                                val beforeImg = remember(data.before) { data.before.asImageBitmap() }
                                val afterImg = remember(data.previewAfter) { data.previewAfter.asImageBitmap() }
                                CompareView(
                                    before = beforeImg,
                                    after = afterImg,
                                    position = sliderPosition,
                                    onPosition = { sliderPosition = it },
                                    beforeLabel = tr("ก่อน", "Before"),
                                    afterLabel = tr("หลัง", "After")
                                )
                            }

                            err != null -> Text(
                                text = err,
                                color = cs.error,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(24.dp)
                            )

                            else -> Text(
                                text = tr("ยังไม่ได้เลือกรูปภาพ", "No image selected"),
                                color = cs.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    result?.let {
                        if (!isProcessing) {
                            Text(
                                text = "${it.label} • ${String.format(Locale.US, "%.1f", it.seconds)} s",
                                fontSize = 12.sp,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val buttonColors = ButtonDefaults.buttonColors(
                        containerColor = cs.onBackground,
                        contentColor = cs.background
                    )
                    val buttonShape = RoundedCornerShape(8.dp)

                    if (result != null && !isProcessing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { requestSave() },
                                enabled = !isSaving,
                                shape = buttonShape,
                                colors = buttonColors,
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(45.dp)
                            ) {
                                Text(tr("บันทึกภาพ", "Save Image"))
                            }
                            OutlinedButton(
                                onClick = { picker.launch("image/*") },
                                shape = buttonShape,
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(45.dp)
                            ) {
                                Text(tr("เลือกรูปอื่น", "Another image"), color = cs.onBackground)
                            }
                        }
                    } else {
                        Button(
                            onClick = { picker.launch("image/*") },
                            enabled = !isProcessing,
                            shape = buttonShape,
                            colors = buttonColors,
                            modifier = Modifier
                                .width(180.dp)
                                .height(45.dp)
                        ) {
                            Text(tr("เลือกรูปภาพ", "Select Image"))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "© 2026 icecat",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}


@Composable
private fun CompareView(
    before: ImageBitmap,
    after: ImageBitmap,
    position: Float,
    onPosition: (Float) -> Unit,
    beforeLabel: String,
    afterLabel: String
) {
    val latestOnPosition by rememberUpdatedState(onPosition)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    latestOnPosition((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    latestOnPosition((offset.x / size.width).coerceIn(0f, 1f))
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
                    clipRect(right = size.width * position) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = size.width * position
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3f
            )
            drawCircle(color = Color.White, radius = 14.dp.toPx(), center = Offset(x, size.height / 2f))
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
  เปรียบเทียบก่อน
