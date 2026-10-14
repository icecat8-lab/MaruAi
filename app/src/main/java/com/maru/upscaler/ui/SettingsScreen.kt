package com.maru.upscaler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    var langMenuOpen by remember { mutableStateOf(false) }
    var themeMenuOpen by remember { mutableStateOf(false) }

    val themeLabel = when (themeMode) {
        1 -> tr("สว่าง", "Light")
        2 -> tr("มืด", "Dark")
        else -> tr("ตามระบบ", "System")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IosIconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tr("ตั้งค่า", "Settings"),
                    fontSize = 22.sp,
                    color = cs.onBackground
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = tr("ทั่วไป", "PREFERENCES"),
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
            )

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingRow(
                        title = tr("ภาษา", "Language"),
                        value = if (lang == "th") "ไทย" else "English",
                        expanded = langMenuOpen,
                        onExpandedChange = { langMenuOpen = it },
                        options = listOf("th" to "ไทย", "en" to "English"),
                        selectedKey = lang,
                        onSelect = { onLangChange(it) }
                    )

                    Divider(
                        color = cs.surfaceVariant,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 16.dp)
                    )

                    SettingRow(
                        title = tr("ธีมแอป", "App Theme"),
                        value = themeLabel,
                        expanded = themeMenuOpen,
                        onExpandedChange = { themeMenuOpen = it },
                        options = listOf(
                            "0" to tr("ตามระบบ", "System"),
                            "1" to tr("สว่าง", "Light"),
                            "2" to tr("มืด", "Dark")
                        ),
                        selectedKey = themeMode.toString(),
                        onSelect = { onThemeChange(it.toInt()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iosClickable(pressedScale = 1f) { onExpandedChange(true) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, color = cs.onSurface)

        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, fontSize = 16.sp, color = cs.onSurfaceVariant)
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                options.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 16.sp) },
                        trailingIcon = {
                            if (key == selectedKey) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            onSelect(key)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}
