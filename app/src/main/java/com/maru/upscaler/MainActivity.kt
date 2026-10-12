package com.maru.upscaler

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var timeLeft by remember { mutableStateOf("") }
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
                delay(30)
                progress = i
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val estimatedTotal = elapsed / (i / 100.0)
                val remaining = kotlin.math.max(0.0, estimatedTotal - elapsed)
                timeLeft = String.format("Remaining: %.1f s", remaining)
            }
            isProcessing = false
            isCompleted = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Maru Upscaler", fontSize = 18.sp)
                        Text(text = "© 2026 icecat", fontSize = 10.sp, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        selectedImageUri = null
                        isCompleted = false
                        isProcessing = false
                    }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
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
                            text = "No image selected",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    } else if (isProcessing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "Processing: $progress%")
                            Text(text = timeLeft, fontSize = 12.sp, color = Color.Gray)
                        }
                    } else {
                        Image(
                            painter = rememberAsyncImagePainter(selectedImageUri),
                            contentDescription = "Upscaled Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { launcher.launch("image/*") },
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .width(180.dp)
                        .height(45.dp)
                ) {
                    Text(text = "Select Image")
                }
            }
        }
    }
}
