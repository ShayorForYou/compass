package com.shayor.compass.ui

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
import com.shayor.compass.ui.theme.LocalCompassColors
import kotlin.math.roundToInt
import androidx.compose.runtime.Immutable
import kotlin.math.abs

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

/**
 * Everything the accuracy read-outs show. [level] is one of SensorManager.SENSOR_STATUS_*, already combining the
 * OS's coarse status with the checks below, so it can't claim HIGH while the heading is clearly off.
 */
@Immutable
data class AccuracyState(
    val level: Int,
    /** Fused sensor's heading-error estimate in degrees; NaN when the device doesn't report one. */
    val headingErrorDeg: Float = Float.NaN,
    /** Measured field magnitude, µT (0 = no reading yet). */
    val fieldUt: Float = 0f,
    /** Earth's field expected at the current location, µT; NaN without a location fix. */
    val expectedUt: Float = Float.NaN,
    /** The measured field doesn't look like Earth's: nearby magnets/metal, or a badly calibrated magnetometer. */
    val interference: Boolean = false,
)

// Earth's field is ~22–67 µT everywhere; outside that (with a margin) it can't be the bare geomagnetic field.
private const val EARTH_MIN_UT = 20f
private const val EARTH_MAX_UT = 70f
// Relative gap from the expected field at which the heading becomes suspect / unusable. The model (WMM) itself is
// good to ~1%, so anything past these is local distortion or miscalibration.
private const val FIELD_GAP_MEDIUM = 0.10f
private const val FIELD_GAP_BAD = 0.20f
private const val FIELD_GAP_CLEAR = 0.17f // hysteresis: interference clears only below this

fun assessAccuracy(
    status: Int,
    headingErrorDeg: Float,
    fieldUt: Float,
    expectedUt: Float,
    wasInterference: Boolean,
): AccuracyState {
    var level = status
    if (!headingErrorDeg.isNaN()) {
        level = minOf(
            level,
            when {
                headingErrorDeg <= 5f -> SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                headingErrorDeg <= 15f -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                else -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
            },
        )
    }
    var interference = false
    if (fieldUt > 0f) {
        if (!expectedUt.isNaN() && expectedUt > 0f) {
            val gap = abs(fieldUt - expectedUt) / expectedUt
            interference = gap > (if (wasInterference) FIELD_GAP_CLEAR else FIELD_GAP_BAD)
            if (gap > FIELD_GAP_MEDIUM) level = minOf(level, SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        } else {
            interference = fieldUt < EARTH_MIN_UT || fieldUt > EARTH_MAX_UT
        }
        if (interference) level = minOf(level, SensorManager.SENSOR_STATUS_ACCURACY_LOW)
    }
    return AccuracyState(level, headingErrorDeg, fieldUt, expectedUt, interference)
}

/** "±4°", or null when the device reports no heading-error estimate. */
fun headingErrorLabel(headingErrorDeg: Float): String? =
    if (headingErrorDeg.isNaN()) null else "±${headingErrorDeg.roundToInt().coerceAtLeast(1)}°"

fun accuracyLabel(state: AccuracyState) = if (state.interference) "Interference" else accuracyLabel(state.level)

/** "31 / 44 µT" (measured / expected), "31 µT" without a location, or null before the first reading. */
fun fieldComparisonLabel(state: AccuracyState): String? = when {
    state.fieldUt <= 0f -> null
    state.expectedUt.isNaN() -> "${state.fieldUt.roundToInt()} µT"
    else -> "${state.fieldUt.roundToInt()} / ${state.expectedUt.roundToInt()} µT"
}

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
fun AccuracyPill(state: AccuracyState, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        AccuracyDot(state.level)
        Spacer(Modifier.width(6.dp))
        Text(
            accuracyLabel(state),
            color = c.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        // Interference trumps the ± estimate: the fused sensor doesn't know the field it trusts is distorted.
        (if (state.interference) fieldComparisonLabel(state) else headingErrorLabel(state.headingErrorDeg))?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
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
