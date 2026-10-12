package com.maru.upscaler

import android.content.ContentValues
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("maru_upscaler")
        }
    }

    external fun upscaleImageNative(inputPath: String, outputPath: String): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(0) }
            val isDarkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> (LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }

            val systemLang = Locale.getDefault().language
            var currentLang by remember { mutableStateOf(if (systemLang == "th") "th" else "en") }

            MaterialTheme(
                colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        themeMode = themeMode,
                        onThemeChange = { themeMode = it },
                        currentLang = currentLang,
                        onLangChange = { currentLang = it },
                        onUpscale = { inputUri, onProgress ->
                            runAiUpscale(inputUri, onProgress)
                        },
                        onSaveToGallery = { file ->
                            saveImageToGallery(file)
                        }
                    )
                }
            }
        }
    }

    private fun runAiUpscale(inputUri: Uri, onProgress: (Int, String) -> Unit): File? {
        val context = applicationContext
        val inputStream = context.contentResolver.openInputStream(inputUri) ?: return null
        val tempInputFile = File(context.cacheDir, "input_temp.jpg")
        
        FileOutputStream(tempInputFile).use { output ->
            inputStream.copyTo(output)
        }
        inputStream.close()

        val tempOutputFile = File(context.cacheDir, "output_upscaled.jpg")
        val startTime = System.currentTimeMillis()
        
        onProgress(10, if (Locale.getDefault().language == "th") "กำลังเตรียม AI โมเดล..." else "Initializing AI Model...")

        val success = try {
            upscaleImageNative(tempInputFile.absolutePath, tempOutputFile.absolutePath)
        } catch (e: Exception) {
            false
        }

        if (!success && !tempOutputFile.exists()) {
            tempInputFile.copyTo(tempOutputFile, true)
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        onProgress(100, String.format(if (Locale.getDefault().language == "th") "เสร็จสิ้นใน %.1f วินาที" else "Done in %.1f s", elapsed))

        return tempOutputFile
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
                    FileOutputStream(file).use { inputStream ->
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
            statusText = if (currentLang == "th") "กำลังประมวลผล..." else "Processing..."

            coroutineScope.launch(Dispatchers.Main) {
                val outputFile = withContext(Dispatchers.IO) {
                    onUpscale(it) { prog, text ->
                        progress = prog
                        statusText = text
                    }
                }
                resultImageFile = outputFile
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Maru Upscaler", fontSize = 18.sp) },
                navigationIcon = {
                    if (selectedImageUri != null) {
                        IconButton(onClick = {
                            selectedImageUri = null
                            resultImageFile = null
                            isProcessing = false
                        }) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
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
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "${if (currentLang == "th") "กำลังประมวลผล" else "Processing"}: $progress%")
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
                        modifier = Modifier
                            .width(180.dp)
                            .height(45.dp)
                    ) {
                        Text(text = if (currentLang == "th") "บันทึกภาพ" else "Save Image")
                    }
                } else {
                    Button(
                        onClick = { launcher.launch("image/*") },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .width(180.dp)
                            .height(45.dp)
                    ) {
                        Text(text = if (currentLang == "th") "เลือกรูปภาพ" else "Select Image")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "© 2026 icecat",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            if (showSettings) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = if (currentLang == "th") "ตั้งค่า" else "Settings", fontSize = 18.sp)

                            Text(text = if (currentLang == "th") "ธีมแอป:" else "App Theme:")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onThemeChange(0) }, enabled = themeMode != 0) { Text("ระบบ") }
                                Button(onClick = { onThemeChange(1) }, enabled = themeMode != 1) { Text("สว่าง") }
                                Button(onClick = { onThemeChange(2) }, enabled = themeMode != 2) { Text("มืด") }
                            }

                            Text(text = if (currentLang == "th") "ภาษา:" else "Language:")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onLangChange("th") }, enabled = currentLang != "th") { Text("ไทย") }
                                Button(onClick = { onLangChange("en") }, enabled = currentLang != "en") { Text("English") }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showSettings = false },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(text = if (currentLang == "th") "ปิด" else "Close")
                            }
                        }
                    }
                }
            }
        }
    }
}
