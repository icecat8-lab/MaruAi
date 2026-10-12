package com.maru.upscaler

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF121212) // ธีมมืดแบบโล่งๆ สบายตา
            ) {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var timeLeft by remember { mutableStateOf("คำนวณเวลา...") }
    var isCompleted by remember { mutableStateOf(false) }

    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            isCompleted = false
            progress = 0
            
            isProcessing = true
        }
    }

    
    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            val startTime = System.currentTimeMillis()
            for (i in 1..100) {
                delay(40)
                progress = i
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val estimatedTotal = elapsed / (i / 100.0)
                val remaining = kotlin.math.max(0.0, estimatedTotal - elapsed)
                timeLeft = String.format("เหลือเวลาอีกประมาณ %.1f วินาที", remaining)
            }
            isProcessing = false
            isCompleted = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Maru Upscaler", color = Color.White, fontSize = 22.sp)
            Text(text = "© 2026 icecat", color = Color.Gray, fontSize = 12.sp)
        }

        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center
        ) {
            if (selectedImageUri == null) {
                Text(text = "ยังไม่ได้เลือกรูปภาพ", color = Color.Gray, fontSize = 14.sp)
            } else if (isProcessing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "กำลังประมวลผล: $progress%", color = Color.White, fontSize = 14.sp)
                    Text(text = timeLeft, color = Color.Gray, fontSize = 12.sp)
                }
            } else if (isCompleted) {
                
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = rememberAsyncImagePainter(selectedImageUri),
                        contentDescription = "Upscaled Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "เสร็จสิ้น", color = Color.Cyan, fontSize = 12.sp)
                    }
                }
            } else {
                Image(
                    painter = rememberAsyncImagePainter(selectedImageUri),
                    contentDescription = "Selected Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        
        Button(
            onClick = { launcher.launch("image/*") },
            enabled = !isProcessing,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .width(180.dp)
                .height(45.dp)
        ) {
            Text(text = "เลือกรูปภาพ", color = Color.White, fontSize = 14.sp)ปุ่มเพิ่มรูปภาพขนาดพอดี
