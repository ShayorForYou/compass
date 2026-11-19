package com.shayor.compass.ui

import android.graphics.BlurMaskFilter
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.shayor.compass.ui.theme.AppFont
import com.shayor.compass.ui.theme.LocalCompassColors
import kotlin.math.min

private val LABELS = arrayOf("N", "30", "60", "E", "120", "150", "S", "210", "240", "W", "300", "330")

/**
 * The rotating dial. Ticks, labels and dots are recorded once into a cached draw and rasterised once into an
 * offscreen layer; the ring is then rotated as that texture, so a heading change costs a single layer
 * transform – no recomposition, no re-measurement, no re-draw of its ~200 primitives on the GPU.
 */
@Composable
fun CompassDial(live: LiveReadings, modifier: Modifier = Modifier) {
    val c = LocalCompassColors.current
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 120f))
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Soft accent halo.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val brush = Brush.radialGradient(
                        0f to c.glow,
                        0.6f to c.glow.copy(alpha = c.glow.alpha * 0.4f),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = r * 0.9f,
                    )
                    onDrawBehind { drawCircle(brush, radius = r * 0.9f) }
                }
        )

        // Rotating ring.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val a = appear.value
                    rotationZ = -live.heading
                    val s = 0.88f + 0.12f * a
                    scaleX = s
                    scaleY = s
                    alpha = a.coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val ringR = r * 0.94f
                    val labelR = r * 0.74f
                    val dotR = r * 0.585f

                    val numberStyle = TextStyle(
                        color = c.textSecondary,
                        fontFamily = AppFont,
                        fontSize = (r * 0.064f).toSp(),
                        fontWeight = FontWeight.Normal,
                        letterSpacing = (r * 0.002f).toSp(),
                    )
                    val cardinalStyle = numberStyle.copy(
                        color = c.textPrimary,
                        fontSize = (r * 0.08f).toSp(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    val layouts = Array(LABELS.size) { i ->
                        val style = when {
                            i == 0 -> cardinalStyle.copy(color = c.north)
                            i % 3 == 0 -> cardinalStyle
                            else -> numberStyle
                        }
                        measurer.measure(LABELS[i], style)
                    }
                    val dotColors = Array(LABELS.size) { i ->
                        when (i) {
                            0 -> c.north
                            3 -> c.east
                            6 -> c.south
                            9 -> c.west
                            else -> c.dot
                        }
                    }
                    val hair = 1.dp.toPx()
                    val minorLen = r * 0.03f
                    val majorLen = r * 0.055f
                    val minorColor = c.dot
                    val majorColor = c.textMuted
                    val smallDot = r * 0.011f
                    val bigDot = r * 0.019f

                    onDrawBehind {
                        val cx = center.x
                        val cy = center.y
                        drawCircle(c.hairline, ringR, style = Stroke(hair))
                        // Precision tick ring: 2° minor, 10° major, north highlighted.
                        for (deg in 0 until 360 step 2) {
                            val major = deg % 10 == 0
                            val isNorth = deg == 0
                            val len = if (isNorth) majorLen * 1.25f else if (major) majorLen else minorLen
                            val color = if (isNorth) c.north else if (major) majorColor else minorColor
                            val width = if (isNorth) hair * 2.2f else if (major) hair * 1.3f else hair
                            rotate(deg.toFloat(), center) {
                                drawLine(
                                    color,
                                    Offset(cx, cy - ringR),
                                    Offset(cx, cy - ringR + len),
                                    width,
                                    StrokeCap.Round,
                                )
                            }
                        }
                        for (i in LABELS.indices) {
                            rotate(i * 30f, center) {
                                val l = layouts[i]
                                drawText(l, topLeft = Offset(cx - l.size.width / 2f, cy - labelR - l.size.height / 2f))
                                drawCircle(
                                    dotColors[i],
                                    radius = if (i % 3 == 0) bigDot else smallDot,
                                    center = Offset(cx, cy - dotR),
                                )
                            }
                        }
                    }
                }
        )

        // Fixed lubber line marking the heading.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val w = 2.5.dp.toPx()
                    val top = size.height / 2f - r * 0.995f
                    val bottom = size.height / 2f - r * 0.86f
                    onDrawBehind {
                        drawLine(c.accent, Offset(center.x, top), Offset(center.x, bottom), w, StrokeCap.Round)
                    }
                }
        )

        NeedleArrow(
            Modifier
                .fillMaxSize(0.3f)
                .graphicsLayer {
                    val a = appear.value
                    val s = 0.7f + 0.3f * a
                    scaleX = s
                    scaleY = s
                    alpha = a.coerceIn(0f, 1f)
                    // Rasterise the blurred glow once instead of on every frame the dial turns.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
        )
    }
}

/** The fixed arrow: slim rounded arrowhead with a vertical gradient, a facet and a soft glow. */
@Composable
fun NeedleArrow(modifier: Modifier = Modifier) {
    val c = LocalCompassColors.current
    Spacer(
        modifier.drawWithCache {
            val s = min(size.width, size.height)
            val ox = (size.width - s) / 2f
            val oy = (size.height - s) / 2f
            val half = s * 0.3f
            val cx = ox + s * 0.5f
            val path = Path().apply {
                moveTo(cx, oy + s * 0.04f)
                lineTo(cx + half, oy + s * 0.94f)
                lineTo(cx, oy + s * 0.78f)
                lineTo(cx - half, oy + s * 0.94f)
                close()
            }
            val facetPath = Path().apply {
                moveTo(cx, oy + s * 0.04f)
                lineTo(cx + half, oy + s * 0.94f)
                lineTo(cx, oy + s * 0.78f)
                close()
            }
            val corners = CornerPathEffect(s * 0.05f)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                pathEffect = corners
                shader = LinearGradient(
                    0f, oy, 0f, oy + s,
                    c.accentLight.toArgb(), c.accentDeep.toArgb(), Shader.TileMode.CLAMP,
                )
            }
            val facet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                pathEffect = corners
                color = Color.Black.copy(alpha = 0.09f).toArgb()
            }
            // Blur mask filters are hardware-accelerated from API 28; older devices just skip the glow.
            val glow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    pathEffect = corners
                    color = c.accent.copy(alpha = 0.32f).toArgb()
                    maskFilter = BlurMaskFilter(s * 0.06f, BlurMaskFilter.Blur.NORMAL)
                }
            } else {
                null
            }
            val glowShift = s * 0.035f
            onDrawBehind {
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    if (glow != null) {
                        nc.withTranslation(0f, glowShift) { drawPath(path, glow) }
                    }
                    nc.drawPath(path, fill)
                    nc.withClip(path) { drawPath(facetPath, facet) }
                }
            }
        }
    )
}
