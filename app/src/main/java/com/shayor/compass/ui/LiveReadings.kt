package com.shayor.compass.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shayor.compass.sensor.CompassEngine
import com.shayor.compass.sensor.HEADING_WAKE_DEG
import com.shayor.compass.sensor.TILT_WAKE
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Display-ready values, smoothed once per frame. Read them only in draw / layer lambdas (or through
 * derivedStateOf) so per-frame changes invalidate drawing, never composition.
 */
@Stable
class LiveReadings {
    /** Displayed heading in degrees [0, 360). */
    var heading by mutableFloatStateOf(0f)
    /** Index into [DIRECTIONS], with hysteresis so the label never flickers at a sector boundary. -1 = unknown. */
    var directionIndex by mutableIntStateOf(-1)
    /** Smoothed screen-space tilt (unit gravity components). */
    var tiltX by mutableFloatStateOf(0f)
    var tiltY by mutableFloatStateOf(0f)
}

private const val HEADING_RESPONSE = 9f   // 1/s – higher = snappier needle
private const val BUBBLE_RESPONSE = 7f

/**
 * Pulls the latest sensor values on every vsync and eases the displayed values toward them with a
 * frame-rate independent exponential filter. A value starts moving only once the sensor is past its wake
 * threshold, then eases all the way to its target. When everything has settled the loop parks in the engine,
 * which wakes it only for a real change, so a still phone costs no frames and almost no CPU.
 */
@Composable
fun SensorAnimationLoop(
    engine: CompassEngine,
    live: LiveReadings,
    headingOffset: Float,
    hapticsEnabled: Boolean,
) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offset by rememberUpdatedState(headingOffset)
    val haptics by rememberUpdatedState(hapticsEnabled)

    LaunchedEffect(engine, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastNanos = 0L
            var firedLabel = -1
            var hapticsArmed = false
            var headingMoving = true
            var tiltMoving = true
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
                lastNanos = now
                var moving = false

                if (engine.hasHeading) {
                    val target = normalizeDegrees(engine.headingDegrees + offset)
                    val current = live.heading
                    val delta = shortestDelta(current, target)
                    if (abs(delta) >= HEADING_WAKE_DEG) headingMoving = true
                    val next = if (!headingMoving) {
                        current
                    } else if (abs(delta) < 0.01f) {
                        headingMoving = false
                        target
                    } else {
                        moving = true
                        normalizeDegrees(current + delta * (1f - exp(-dt * HEADING_RESPONSE)))
                    }
                    live.heading = next

                    // Direction label with ±1.5° hysteresis.
                    val sector = ((next + 22.5f) / 45f).toInt() % 8
                    if (sector != live.directionIndex &&
                        (live.directionIndex < 0 || abs(shortestDelta(sector * 45f, next)) < 21f)
                    ) {
                        live.directionIndex = sector
                    }

                    // Haptic tick on every 30° mark (stronger on cardinals). Armed once the intro spin settles.
                    if (!hapticsArmed && abs(delta) < 2f) hapticsArmed = true
                    val nearest = (next / 30f).roundToInt() % 12
                    if (firedLabel >= 0 && abs(shortestDelta(firedLabel * 30f, next)) > 4f) firedLabel = -1
                    if (abs(shortestDelta(nearest * 30f, next)) < 1f && nearest != firedLabel) {
                        if (hapticsArmed && haptics) {
                            view.performHapticFeedback(
                                if (nearest % 3 == 0) HapticFeedbackConstants.VIRTUAL_KEY
                                else HapticFeedbackConstants.CLOCK_TICK
                            )
                        }
                        firedLabel = nearest
                    }
                }

                if (engine.hasTilt) {
                    val k = 1f - exp(-dt * BUBBLE_RESPONSE)
                    val dx = engine.tiltX - live.tiltX
                    val dy = engine.tiltY - live.tiltY
                    if (abs(dx) >= TILT_WAKE || abs(dy) >= TILT_WAKE) tiltMoving = true
                    if (tiltMoving && (abs(dx) > 1e-4f || abs(dy) > 1e-4f)) {
                        live.tiltX += dx * k
                        live.tiltY += dy * k
                        moving = true
                    } else {
                        tiltMoving = false
                    }
                }

                if (!moving) {
                    do {
                        val shown = if (live.directionIndex < 0) Float.NaN else normalizeDegrees(live.heading - offset)
                        engine.awaitChange(shown, live.tiltX, live.tiltY)
                    } while (!engine.needsFrame(live, offset))
                    lastNanos = 0L
                }
            }
        }
    }
}

private fun CompassEngine.needsFrame(live: LiveReadings, offset: Float): Boolean {
    if (hasHeading) {
        if (live.directionIndex < 0) return true
        val target = normalizeDegrees(headingDegrees + offset)
        if (abs(shortestDelta(live.heading, target)) >= HEADING_WAKE_DEG) return true
    }
    return hasTilt && (abs(tiltX - live.tiltX) >= TILT_WAKE || abs(tiltY - live.tiltY) >= TILT_WAKE)
}
