package com.shayor.compass.sensor

import android.hardware.SensorManager
import com.shayor.compass.location.GpsAltitude
import kotlin.math.abs
import kotlin.math.roundToInt

/** Altitude for display. [approximate] marks a reading that may be off by tens of meters. */
data class AltitudeReading(val meters: Int, val approximate: Boolean)

/**
 * Picks the best available altitude source and keeps the displayed value calm.
 *
 * Barometric altitude is smooth and reacts to a single flight of stairs, but the standard-atmosphere formula
 * drifts with the weather (±10 hPa ≈ ±80 m). So whenever GPS delivers a decent sea-level altitude, the
 * barometric reading is gradually pulled onto it: barometer smoothness with GPS's absolute reference.
 * Without a barometer, the GPS altitude is shown on its own.
 *
 * Not thread-safe; call from the UI's read-out loop.
 */
class AltitudeEstimator {
    private var offset = 0f
    private var calibrated = false
    private var lastFix: GpsAltitude? = null
    private var shown: AltitudeReading? = null

    /** Returns the altitude to display, or null when no usable source exists yet. */
    fun update(pressureHpa: Float, gps: GpsAltitude?): AltitudeReading? {
        val raw: Float
        val approximate: Boolean
        if (!pressureHpa.isNaN()) {
            val baro = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa)
            if (gps != null && gps !== lastFix) {
                lastFix = gps
                calibrate(gps, baro)
            }
            raw = baro + offset
            approximate = !calibrated
        } else if (gps != null && (gps.accuracy ?: 0f) <= MAX_DISPLAY_ACCURACY) {
            raw = gps.meters
            approximate = !gps.aboveSeaLevel || (gps.accuracy ?: Float.MAX_VALUE) > PRECISE_ACCURACY
        } else {
            shown = null
            return null
        }

        val prev = shown
        // Hysteresis: hold the shown meter until the reading has clearly moved, so the last digit never flickers.
        val meters = if (prev != null && abs(raw - prev.meters) < 0.8f) prev.meters else raw.roundToInt()
        return AltitudeReading(meters, approximate).also { shown = it }
    }

    private fun calibrate(gps: GpsAltitude, baro: Float) {
        // An ellipsoid height would bake a regional error of up to ±100 m into the barometer; better to stay
        // uncalibrated (and marked approximate) than to be confidently wrong.
        val accuracy = gps.accuracy ?: return
        if (!gps.aboveSeaLevel || accuracy > MAX_CALIBRATION_ACCURACY) return
        val sample = gps.meters - baro
        // GPS altitude is noisy, so average many fixes. The first one is taken outright to converge quickly.
        offset = if (calibrated) offset + 0.1f * (sample - offset) else sample
        calibrated = true
    }

    private companion object {
        const val MAX_CALIBRATION_ACCURACY = 20f
        const val MAX_DISPLAY_ACCURACY = 50f
        const val PRECISE_ACCURACY = 10f
    }
}
