package com.maru.upscaler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MaruSettingsScreen() {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)) // True Black ดำสนิทแบบในรูป
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("AI UPSCALER", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        
        // กล่องตั้งค่าสไตล์ iOS/Pro Camera
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)) // สีเทาเข้ม
        ) {
            Column {
                // จำลองบรรทัด Toggle Switch
                Text("Auto-Routing (Anime/Photo)", color = Color.White, modifier = Modifier.padding(16.dp))
                
                Divider(color = Color.DarkGray, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                
                // จำลองบรรทัดเลือกค่า
                Text("Processing Resolution   2K >", color = Color.Gray, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
