package com.rozin.compass.ui

import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rozin.compass.ui.theme.LocalCompassColors

// ---- Buttons -------------------------------------------------------------------------------------------------

/** Round hairline button with a press-scale spring and a custom-drawn glyph. */
@Composable
fun GlyphButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    glyph: DrawScope.() -> Unit,
) {
    val c = LocalCompassColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "press",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(c.surface.copy(alpha = 0.65f))
            .border(1.dp, c.hairline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier
                .size(18.dp)
                .drawBehind(glyph)
        )
    }
}

// ---- Glyphs --------------------------------------------------------------------------------------------------

/** Two slider tracks; knobs swap sides as [t] goes 0 → 1. */
fun DrawScope.tuneGlyph(color: Color, background: Color, t: Float) {
    val w = size.width
    val h = size.height
    val stroke = 1.5.dp.toPx()
    val knob = w * 0.15f
    val y1 = h * 0.3f
    val y2 = h * 0.7f
    val x1 = w * (0.68f - 0.36f * t)
    val x2 = w * (0.32f + 0.36f * t)
    drawLine(color, Offset(w * 0.04f, y1), Offset(w * 0.96f, y1), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.04f, y2), Offset(w * 0.96f, y2), stroke, StrokeCap.Round)
    drawCircle(background, knob + stroke, Offset(x1, y1))
    drawCircle(background, knob + stroke, Offset(x2, y2))
    drawCircle(color, knob, Offset(x1, y1), style = Stroke(stroke))
    drawCircle(color, knob, Offset(x2, y2), style = Stroke(stroke))
}

fun DrawScope.backGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = 1.6.dp.toPx()
    drawLine(color, Offset(w * 0.62f, h * 0.18f), Offset(w * 0.3f, h * 0.5f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.62f, h * 0.82f), stroke, StrokeCap.Round)
}

fun DrawScope.chevronGlyph(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = 1.5.dp.toPx()
    drawLine(color, Offset(w * 0.38f, h * 0.2f), Offset(w * 0.68f, h * 0.5f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.68f, h * 0.5f), Offset(w * 0.38f, h * 0.8f), stroke, StrokeCap.Round)
}

// ---- Accuracy ------------------------------------------------------------------------------------------------

fun accuracyLabel(accuracy: Int) = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High accuracy"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium accuracy"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low accuracy"
    else -> "Calibrate"
}

@Composable
fun accuracyColor(accuracy: Int): State<Color> {
    val c = LocalCompassColors.current
    return animateColorAsState(
        when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> c.good
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> c.warn
            else -> c.bad
        },
        tween(400),
        label = "accuracyColor",
    )
}

/** Status dot; radiates a soft ring while calibration is needed. */
@Composable
fun AccuracyDot(accuracy: Int, modifier: Modifier = Modifier) {
    val color by accuracyColor(accuracy)
    val needsCalibration = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    val ripple = if (needsCalibration) {
        rememberInfiniteTransition(label = "ripple").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "rippleT",
        )
    } else {
        null
    }
    Canvas(modifier.size(14.dp)) {
        val core = 3.dp.toPx()
        ripple?.let {
            val p = it.value
            drawCircle(color.copy(alpha = 0.45f * (1f - p)), core + (size.minDimension / 2f - core) * p)
        }
        drawCircle(color.copy(alpha = 0.22f), core * 1.9f)
        drawCircle(color, core)
    }
}

@Composable
fun AccuracyPill(accuracy: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalCompassColors.current
    val pill = RoundedCornerShape(50)
    Row(
        modifier
            .height(32.dp)
            .clip(pill)
            .background(c.surface.copy(alpha = 0.65f))
            .border(1.dp, c.hairline, pill)
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccuracyDot(accuracy)
        Spacer(Modifier.width(6.dp))
        Text(
            accuracyLabel(accuracy),
            color = c.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---- Switch --------------------------------------------------------------------------------------------------

/** Compact iOS-style switch. Purely visual – the containing row handles the toggle. */
@Composable
fun MiniSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalCompassColors.current
    val t by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "switch",
    )
    Canvas(modifier.size(width = 40.dp, height = 24.dp)) {
        val h = size.height
        val w = size.width
        drawRoundRect(lerp(c.track, c.accent, t), cornerRadius = CornerRadius(h / 2f))
        val r = h / 2f - 2.5.dp.toPx()
        val x = h / 2f + (w - h) * t
        drawCircle(Color.Black.copy(alpha = 0.12f), r, Offset(x, h / 2f + 0.8.dp.toPx()))
        drawCircle(Color.White, r, Offset(x, h / 2f))
    }
}
