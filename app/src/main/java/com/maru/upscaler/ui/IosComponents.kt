package com.maru.upscaler.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** easing แบบเดียวกับแอนิเมชันของ iOS */
val IosEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** spring แบบไม่เด้ง (critically damped) ใช้กับการเลื่อนหน้าจอ */
fun <T> iosSpring(stiffness: Float = 380f): FiniteAnimationSpec<T> =
    spring(dampingRatio = 1f, stiffness = stiffness)

/** กดแล้วย่อ/จางลงเล็กน้อยด้วย spring (ไม่มี ripple) */
fun Modifier.iosClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "iosScale"
    )
    val fade by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.7f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "iosFade"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = fade
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

@Composable
fun IosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .iosClickable(enabled = enabled, onClick = onClick)
            .clip(shape)
            .then(
                if (filled) Modifier.background(cs.onBackground)
                else Modifier.border(1.dp, cs.onSurfaceVariant.copy(alpha = 0.5f), shape)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (filled) cs.background else cs.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun IosIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .iosClickable(pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
