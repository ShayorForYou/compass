package com.shayor.compass.ui

import android.hardware.SensorManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shayor.compass.ui.theme.LocalCompassColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Calibration guide. Android calibrates the magnetometer itself as the phone moves; this dialog shows how,
 * watches the reported accuracy, and finishes on its own once accuracy improves to HIGH while it is open.
 */
@Composable
fun CalibrationDialog(state: AccuracyState, hapticsEnabled: Boolean, onDismiss: () -> Unit) {
    val accuracy = state.level
    val c = LocalCompassColors.current
    val shape = RoundedCornerShape(24.dp)
    val view = LocalView.current
    val dismiss by rememberUpdatedState(onDismiss)

    // Only auto-finish on an improvement seen while open – if it was already HIGH, let the user close it.
    var sawImprovableState by remember { mutableStateOf(accuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }
    LaunchedEffect(accuracy) {
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH) sawImprovableState = true
    }
    val calibrated = sawImprovableState && accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    LaunchedEffect(calibrated) {
        if (calibrated) {
            if (hapticsEnabled) {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY
                )
            }
            delay(1400)
            dismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.hairline, shape)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Calibrate compass", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            AnimatedContent(
                targetState = calibrated,
                transitionSpec = {
                    (fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 0.92f)) togetherWith fadeOut(tween(150))
                },
                contentAlignment = Alignment.Center,
                label = "calibrationState",
            ) { done ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (done) {
                        SuccessCheck(
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                        Text(
                            "Compass calibrated",
                            color = c.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        FigureEight(
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                        Text(
                            "Move your phone in a figure-8 a few times, tilting as you go. " +
                                "Keep clear of magnets, metal and electronics.",
                            color = c.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccuracyDot(accuracy)
                Spacer(Modifier.width(6.dp))
                Text(accuracyLabel(state), color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                headingErrorLabel(state.headingErrorDeg)?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            fieldComparisonLabel(state)?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.expectedUt.isNaN()) "Field $it" else "Field $it expected",
                    color = c.textMuted,
                    fontSize = 12.sp,
                )
            }
            if (state.interference) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "The magnetic field here doesn't match Earth's. Move away from metal, magnets and " +
                        "electronics (or remove a magnetic case), then calibrate again.",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Done",
                color = c.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.accent.copy(alpha = 0.1f))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** Green ring that springs in, then a check mark that draws itself. */
@Composable
private fun SuccessCheck(modifier: Modifier) {
    val c = LocalCompassColors.current
    val ring = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) { ring.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
    LaunchedEffect(Unit) {
        delay(140)
        tick.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    Spacer(
        modifier.drawWithCache {
            val r = min(size.width, size.height) * 0.3f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 3.dp.toPx()
            val a = Offset(cx - r * 0.42f, cy + r * 0.02f)
            val b = Offset(cx - r * 0.1f, cy + r * 0.34f)
            val d = Offset(cx + r * 0.46f, cy - r * 0.3f)
            val l1 = (b - a).getDistance()
            val l2 = (d - b).getDistance()
            onDrawBehind {
                val s = ring.value
                drawCircle(c.good.copy(alpha = 0.14f), r * 1.4f * s)
                drawCircle(c.good, r * s)
                val p = tick.value * (l1 + l2)
                if (p > 0f) {
                    val end1 = if (p < l1) a + (b - a) * (p / l1) else b
                    drawLine(Color.White, a, end1, stroke, StrokeCap.Round)
                    if (p > l1) drawLine(Color.White, b, b + (d - b) * ((p - l1) / l2), stroke, StrokeCap.Round)
                }
            }
        }
    )
}

/** A phone gliding along a lemniscate, leaving a fading trail. */
@Composable
private fun FigureEight(modifier: Modifier) {
    val c = LocalCompassColors.current
    val progress = rememberInfiniteTransition(label = "figure8").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "t",
    )
    Spacer(
        modifier.drawWithCache {
            val a = min(size.width * 0.4f, size.height * 1.3f)
            val cx = size.width / 2f
            val cy = size.height / 2f
            fun point(theta: Float): Offset {
                val s = sin(theta)
                val d = 1f + s * s
                return Offset(cx + a * cos(theta) / d, cy + a * s * cos(theta) / d)
            }
            val path = Path().apply {
                val steps = 120
                for (i in 0..steps) {
                    val p = point(i / steps.toFloat() * 2f * PI.toFloat())
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            val track = Stroke(
                width = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            )
            val phone = Size(16.dp.toPx(), 28.dp.toPx())
            val corner = CornerRadius(4.dp.toPx())
            val inset = 2.dp.toPx()
            val trailR = 3.dp.toPx()

            onDrawBehind {
                drawPath(path, c.track, style = track)
                val theta = progress.value * 2f * PI.toFloat()
                for (k in 1..6) {
                    val p = point(theta - k * 0.12f)
                    drawCircle(c.accent.copy(alpha = 0.3f * (1f - k / 7f)), trailR * (1f - k / 9f), p)
                }
                val p = point(theta)
                val ahead = point(theta + 0.01f)
                val angle = Math.toDegrees(atan2(ahead.y - p.y, ahead.x - p.x).toDouble()).toFloat()
                rotate(angle * 0.35f, p) {
                    drawRoundRect(c.accent, Offset(p.x - phone.width / 2f, p.y - phone.height / 2f), phone, corner)
                    drawRoundRect(
                        Color.White.copy(alpha = 0.9f),
                        Offset(p.x - phone.width / 2f + inset, p.y - phone.height / 2f + inset * 2f),
                        Size(phone.width - inset * 2f, phone.height - inset * 4f),
                        CornerRadius(corner.x / 2f),
                    )
                }
            }
        }
    )
}
