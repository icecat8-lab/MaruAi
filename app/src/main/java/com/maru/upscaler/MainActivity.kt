package com.maru.upscaler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.maru.upscaler.ui.MaruSettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // สั่งให้แอปวาดหน้าจอ UI โทนดาร์กเท่ๆ ที่เราเขียนไว้
        setContent {
            MaruSettingsScreen()
        }
    }
}
