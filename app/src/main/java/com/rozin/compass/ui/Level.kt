package com.rozin.compass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rozin.compass.ui.theme.LocalCompassColors
import com.rozin.compass.ui.theme.TabularStyle
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private fun asinDeg(v: Float) = Math.toDegrees(asin(v.coerceIn(-1f, 1f).toDouble())).toFloat()

/**
 * Outlined bubble level. The bubble floats to the highest point and everything shifts from accent to green
 * as the surface approaches level. Tilt is read only in the draw lambda, so motion just redraws this disc.
 */
@Composable
fun LevelDisc(live: LiveReadings, modifier: Modifier = Modifier, detailed: Boolean = false) {
    val c = LocalCompassColors.current
    val maxTiltDeg = if (detailed) 20f else 25f
    Spacer(
        modifier.drawWithCache {
            val stroke = (if (detailed) 1.5.dp else 1.2.dp).toPx()
            val r = size.minDimension / 2f - stroke
            val bubbleR = r * if (detailed) 0.1f else 0.17f
            val maxSin = sin(Math.toRadians(maxTiltDeg.toDouble())).toFloat()
            val travel = r - bubbleR - stroke * 2f
            val hair = Stroke(1.dp.toPx())
            val outline = Stroke(stroke)
            val ringRadii = if (detailed) {
                floatArrayOf(5f, 10f, 15f).map { sin(Math.toRadians(it.toDouble())).toFloat() / maxSin * travel }
            } else {
                emptyList()
            }
            val zoneR = bubbleR * 1.45f
            val crossLen = if (detailed) r else r * 0.28f

            onDrawBehind {
                val x = live.tiltX
                val y = live.tiltY
                // 1 within ~0.5°, 0 beyond ~2.5°.
                val levelness = 1f - ((hypot(x, y) - 0.009f) / 0.035f).coerceIn(0f, 1f)
                val tint = lerp(c.accent, c.good, levelness)

                drawCircle(tint.copy(alpha = 0.07f + 0.05f * levelness), r)
                drawCircle(tint.copy(alpha = 0.5f), r, style = outline)
                for (rr in ringRadii) drawCircle(tint.copy(alpha = 0.18f), rr, style = hair)
                val cross = tint.copy(alpha = 0.28f)
                drawLine(cross, Offset(center.x - crossLen, center.y), Offset(center.x + crossLen, center.y), hair.width)
                drawLine(cross, Offset(center.x, center.y - crossLen), Offset(center.x, center.y + crossLen), hair.width)
                drawCircle(tint.copy(alpha = 0.55f), zoneR, style = hair)

                var vx = x / maxSin
                var vy = -y / maxSin
                val len = hypot(vx, vy)
                if (len > 1f) { vx /= len; vy /= len }
                val bc = Offset(center.x + vx * travel, center.y + vy * travel)
                drawCircle(tint.copy(alpha = 0.22f), bubbleR * 1.7f, bc)
                drawCircle(tint, bubbleR, bc)
                drawCircle(Color.White.copy(alpha = 0.55f), bubbleR * 0.32f, Offset(bc.x - bubbleR * 0.3f, bc.y - bubbleR * 0.3f))
            }
        }
    )
}

/** Full-screen surface level with angle read-outs. */
@Composable
fun LevelOverlay(visible: Boolean, live: LiveReadings, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + scaleIn(tween(320), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0.85f)),
        exit = fadeOut(tween(200)) + scaleOut(tween(220), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0.85f)),
    ) {
        BackHandler(onBack = onClose)
        val c = LocalCompassColors.current
        val angleX by remember { derivedStateOf { (asinDeg(live.tiltX) * 10f).roundToInt() / 10f } }
        val angleY by remember { derivedStateOf { (asinDeg(live.tiltY) * 10f).roundToInt() / 10f } }
        val total by remember {
            derivedStateOf { (asinDeg(hypot(live.tiltX, live.tiltY)) * 10f).roundToInt() / 10f }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(c.background)
                // Swallow touches so they don't reach the compass underneath.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .systemBarsPadding()
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton("Back", onClose) { backGlyph(c.icon) }
                Spacer(Modifier.width(14.dp))
                Text("Surface level", color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "%.1f°".format(total),
                    color = c.textPrimary,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = (-1.5).sp,
                    style = TabularStyle,
                )
                val isLevel = total < 0.5f
                AnimatedContent(
                    targetState = isLevel,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "levelState",
                ) { level ->
                    Text(
                        if (level) "LEVEL" else "TILTED",
                        color = if (level) c.good else c.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 3.sp,
                    )
                }
                Spacer(Modifier.height(36.dp))
                LevelDisc(
                    live,
                    Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(1f),
                    detailed = true,
                )
                Spacer(Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    AxisReadout("PITCH", angleY)
                    AxisReadout("ROLL", angleX)
                }
            }
        }
    }
}

@Composable
private fun AxisReadout(label: String, value: Float) {
    val c = LocalCompassColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "%.1f°".format(if (abs(value) < 0.05f) 0f else value),
            color = c.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            style = TabularStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(80.dp),
        )
    }
}
