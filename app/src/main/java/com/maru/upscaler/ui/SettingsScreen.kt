package com.maru.upscaler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MaruSettingsScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    lang: String,
    onLangChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    fun tr(th: String, en: String) = if (lang == "th") th else en

    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tr("ตั้งค่า", "Settings"),
                    fontSize = 22.sp,
                    color = cs.onBackground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = tr("ทั่วไป", "PREFERENCES"),
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLangChange(if (lang == "th") "en" else "th") },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("ภาษา", "Language"), fontSize = 16.sp, color = cs.onSurface)
                        Text(
                            text = if (lang == "th") "ไทย >" else "English >",
                            fontSize = 16.sp,
                            color = cs.onSurfaceVariant
                        )
                    }

                    Divider(color = cs.surfaceVariant, thickness = 0.5.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeChange((themeMode + 1) % 3) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tr("ธีมแอป", "App Theme"), fontSize = 16.sp, color = cs.onSurface)
                        Text(
                            text = when (themeMode) {
                                1 -> tr("สว่าง >", "Light >")
                                2 -> tr("มืด >", "Dark >")
                                else -> tr("ระบบ >", "System >")
                            },
                            fontSize = 16.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = tr("เกี่ยวกับ", "ABOUT"),
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(tr("โมเดล", "Model"), fontSize = 16.sp, color = cs.onSurface)
                        Text(
                            text = tr(
                                "Maru — โมเดลอัปสเกล RRDB x4 ที่เทรนเองตั้งแต่ศูนย์",
                                "Maru — an RRDB x4 upscaler trained from scratch"
                            ),
                            fontSize = 13.sp,
                            color = cs.onSurfaceVariant
                        )
                    }

                    Divider(color = cs.surfaceVariant, thickness = 0.5.dp)

                    Column {
                        Text(tr("ไลบรารีโอเพนซอร์ส", "Open-source libraries"), fontSize = 16.sp, color = cs.onSurface)
                        Text(
                            text = "ncnn — © Tencent, BSD 3-Clause License\nhttps://github.com/Tencent/ncnn",
                            fontSize = 13.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
