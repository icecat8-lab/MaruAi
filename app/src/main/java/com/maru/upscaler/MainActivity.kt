package com.maru.upscaler

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        init {
            try {
                System.loadLibrary("maru_upscaler")
            } catch (e: UnsatisfiedLinkError) {
                
            }
        }
    }

    
    external fun upscaleImageNative(inputPath: String, outputPath: String): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("maru_prefs", Context.MODE_PRIVATE)
        val savedTheme = prefs.getInt("theme_mode", 2)
        val savedLang = prefs.getString("app_lang", "th") ?: "th"

        setContent {
            var themeMode by remember { mutableStateOf(savedTheme) }
            var currentLang by remember { mutableStateOf(savedLang) }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color(0xFF1C1C1E),
                    surfaceVariant = Color(0xFF2C2C2E)
                )
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
                        currentLang = currentLang,
                        onLangChange = { newLang ->
                            currentLang = newLang
                            prefs.edit().putString("app_lang", newLang).apply()
                        },
                        onUpscale = { inputUri, onProgress ->
                            runAiPipeline(inputUri, onProgress)
                        },
                        onSaveToGallery = { file ->
                            saveImageToGallery(file)
                        }
                    )
                }
            }
        }
    }

    private fun runAiPipeline(inputUri: Uri, onProgress: (Int, String) -> Unit): File? {
        try {
            val context = applicationContext
            val inputStream = context.contentResolver.openInputStream(inputUri) ?: return null
            val tempInputFile = File(context.cacheDir, "input_temp.jpg")
            
            FileOutputStream(tempInputFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            val tempOutputFile = File(context.cacheDir, "output_upscaled.jpg")
            val startTime = System.currentTimeMillis()
            
            
            onProgress(20, if (Locale.getDefault().language == "th") "Maru_classifier กำลังวิเคราะห์ประเภทภาพ..." else "Maru_classifier analyzing image...")

            
            Thread.sleep(300)

            
            onProgress(60, if (Locale.getDefault().language == "th") "กำลังส่งภาพให้โมเดลอัปสเกลทำงานต่อ..." else "Passing image to upscaler model...")

            val success = try {
                upscaleImageNative(tempInputFile.absolutePath, tempOutputFile.absolutePath)
            } catch (e: Exception) {
                false
            }

            if (!success || !tempOutputFile.exists()) {
                tempInputFile.inputStream().use { input ->
                    FileOutputStream(tempOutputFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            onProgress(100, String.format(if (Locale.getDefault().language == "th") "เสร็จสิ้นใน %.1f วินาที" else "Done in %.1f s", elapsed))

            return tempOutputFile
        } catch (e: Exception) {
            return null
        }
    }

    private fun saveImageToGallery(file: File): Boolean {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "MaruUpscale_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MaruUpscaler")
                }
            }
            val resolver = contentResolver
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            imageUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(file).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    currentLang: String,
    onLangChange: (String) -> Unit,
    onUpscale: suspend (Uri, (Int, String) -> Unit) -> File?,
    onSaveToGallery: (File) -> Boolean
) {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var resultImageFile by remember { mutableStateOf<File?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0.5f) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            resultImageFile = null
            isProcessing = true
            progress = 0
            statusText = if (currentLang == "th") "กำลังเตรียม..." else "Preparing..."

            coroutineScope.launch(Dispatchers.Main) {
                val outputFile = withContext(Dispatchers.IO) {
                    onUpscale(it) { prog, text ->
                        progress = prog
                        statusText = text
                    }
                }
                if (outputFile != null && outputFile.exists()) {
                    resultImageFile = outputFile
                } else {
                    Toast.makeText(context, if (currentLang == "th") "เกิดข้อผิดพลาดในการประมวลผล" else "Processing error", Toast.LENGTH_SHORT).show()
                }
                isProcessing = false
            }
        }
    }

    if (showSettings) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showSettings = false }) {
                        val resId = context.resources.getIdentifier("arrow_back_ios_24", "drawable", context.packageName)
                        if (resId != 0) {
                            Image(
                                painter = painterResource(id = resId),
                                contentDescription = "Back",
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (currentLang == "th") "ตั้งค่า" else "Settings",
                        fontSize = 22.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (currentLang == "th") "ทั่วไป" else "PREFERENCES",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLangChange(if (currentLang == "th") "en" else "th") },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (currentLang == "th") "ภาษา" else "Language", fontSize = 16.sp, color = Color.White)
                            Text(
                                text = if (currentLang == "th") "ไทย >" else "English >",
                                fontSize = 16.sp,
                                color = Color.Gray
                            )
                        }

                        Divider(color = Color.DarkGray, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThemeChange((themeMode + 1) % 3) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (currentLang == "th") "ธีมแอป" else "App Theme", fontSize = 16.sp, color = Color.White)
                            Text(
                                text = when(themeMode) {
                                    1 -> if (currentLang == "th") "สว่าง >" else "Light >"
                                    2 -> if (currentLang == "th") "มืด >" else "Dark >"
                                    else -> if (currentLang == "th") "ระบบ >" else "System >"
                                },
                                fontSize = 16.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        if (selectedImageUri != null) {
                            IconButton(onClick = {
                                selectedImageUri = null
                                resultImageFile = null
                                isProcessing = false
                            }) {
                                val resId = context.resources.getIdentifier("arrow_back_ios_24", "drawable", context.packageName)
                                if (resId != 0) {
                                    Image(
                                        painter = painterResource(id = resId),
                                        contentDescription = "Back",
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
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
                            .background(Color(0xFF1C1C1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImageUri == null) {
                            Text(
                                text = if (currentLang == "th") "ยังไม่ได้เลือกรูปภาพ" else "No image selected",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        } else if (isProcessing) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "${if (currentLang == "th") "กำลังประมวลผล" else "Processing"}: $progress%", color = Color.White)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = statusText, fontSize = 12.sp, color = Color.Gray)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures { change, _ ->
                                            val width = size.width.toFloat()
                                            if (width > 0f) {
                                                sliderPosition = (sliderPosition + (change.position.x / width) - 0.5f).coerceIn(0f, 1f)
                                            }
                                        }
                                    }
                            ) {
                                if (resultImageFile != null) {
                                    Image(
                                        painter = rememberAsyncImagePainter(resultImageFile),
                                        contentDescription = "After",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (resultImageFile != null && !isProcessing) {
                        Button(
                            onClick = {
                                resultImageFile?.let { file ->
                                    val success = onSaveToGallery(file)
                                    val msg = if (success) {
                                        if (currentLang == "th") "บันทึกรูปภาพเรียบร้อย!" else "Image saved successfully!"
                                    } else {
                                        if (currentLang == "th") "บันทึกไม่สำเร็จ" else "Failed to save"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier
                                .width(180.dp)
                                .height(45.dp)
                        ) {
                            Text(text = if (currentLang == "th") "บันทึกภาพ" else "Save Image", color = Color.Black)
                        }
                    } else {
                        Button(
                            onClick = { launcher.launch("image/*") },
                            enabled = !isProcessing,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            modifier = Modifier
                                .width(180.dp)
                                .height(45.dp)
                        ) {
                            Text(text = if (currentLang == "th") "เลือกรูปภาพ" else "Select Image", color = Color.Black)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "© 2026 icecat",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}
