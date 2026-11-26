package com.shayor.compass.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// Wake thresholds for the UI. A resting phone's sensor noise stays below these, so it never costs a frame.
// 0.15° is ~1 px at the dial's edge; 0.0015 of tilt is ~0.09°, under the level's 0.1° read-out.
const val HEADING_WAKE_DEG = 0.15f
const val TILT_WAKE = 0.0015f

/**
 * Reads the motion sensors on a dedicated background thread and publishes the latest heading, tilt and
 * magnetic field strength through volatile fields. The UI pulls these values once per frame (see
 * `SensorAnimationLoop`), so sensor callbacks never touch the snapshot system or the main thread.
 *
 * Heading comes from the fused ROTATION_VECTOR sensor when available (smooth, gyro-assisted), otherwise from
 * accelerometer + magnetometer. The heading is derived from the horizontal projection of the device's "forward"
 * direction, blending the top edge (phone flat) with the camera axis (phone upright), so it stays stable at any
 * tilt instead of spinning wildly near vertical like a plain azimuth would.
 *
 * The barometer, when present, is sampled slowly and only polled by the UI's low-rate read-out loop.
 */
class CompassEngine(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravityIsRaw = gravitySensor?.type == Sensor.TYPE_ACCELEROMETER
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val hasCompass: Boolean = magneticSensor != null && (rotationSensor != null || gravitySensor != null)
    val hasLevel: Boolean = gravitySensor != null
    val hasBarometer: Boolean = pressureSensor != null

    // ---- Published state (written on the sensor thread, read on the main thread) -----------------------------
    /** Magnetic heading in degrees, [0, 360). */
    @Volatile var headingDegrees = 0f
        private set
    @Volatile var hasHeading = false
        private set
    /** Unit gravity vector in screen coordinates: +x = right edge raised, +y = top edge raised. */
    @Volatile var tiltX = 0f
        private set
    @Volatile var tiltY = 0f
        private set
    @Volatile var hasTilt = false
        private set
    /** Magnetic field magnitude in µT, smoothed (raw samples swing by several µT on a resting phone). */
    @Volatile var fieldStrength = 0f
        private set
    /** Smoothed atmospheric pressure in hPa; NaN until the first sample (or without a barometer). */
    @Volatile var pressureHpa = Float.NaN
        private set
    /** One of SensorManager.SENSOR_STATUS_*. */
    @Volatile var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set
    /**
     * The fused sensor's own estimate of heading error, in degrees (ROTATION_VECTOR values[4]). NaN when the device
     * doesn't report one. Unlike [accuracy], which has only four coarse levels and often reads HIGH regardless,
     * this is the actual uncertainty of the shown heading.
     */
    @Volatile var headingErrorDeg = Float.NaN
        private set
    /** Current display rotation (Surface.ROTATION_*), updated by the UI. */
    @Volatile var displayRotation = Surface.ROTATION_0

    private val updates = Channel<Unit>(Channel.CONFLATED)

    // While the UI is at rest it parks the values it shows here. Samples within the wake thresholds of them are
    // not forwarded, so a resting phone doesn't wake the main thread at the sensor rate.
    @Volatile private var parked = false
    @Volatile private var parkedHeading = 0f
    @Volatile private var parkedTiltX = 0f
    @Volatile private var parkedTiltY = 0f

    /**
     * Suspends until a sample moves past the wake thresholds of the shown values (or may have: a stale wake-up is
     * possible, so callers re-check). [heading] is magnetic; NaN wakes on the first heading.
     */
    suspend fun awaitChange(heading: Float, tiltX: Float, tiltY: Float) {
        parkedHeading = heading
        parkedTiltX = tiltX
        parkedTiltY = tiltY
        parked = true
        try {
            updates.receive()
        } finally {
            parked = false
        }
    }

    private fun notifyUi() {
        if (parked && !movedFromParked()) return
        updates.trySend(Unit)
    }

    private fun movedFromParked(): Boolean {
        if (hasHeading) {
            val h = parkedHeading
            if (h.isNaN()) return true
            var d = abs(headingDegrees - h) % 360f
            if (d > 180f) d = 360f - d
            if (d >= HEADING_WAKE_DEG) return true
        }
        return hasTilt && (abs(tiltX - parkedTiltX) >= TILT_WAKE || abs(tiltY - parkedTiltY) >= TILT_WAKE)
    }

    // ---- Sensor-thread-only scratch state (no allocations per event) -----------------------------------------
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var filtEast = 0f
    private var filtNorth = 0f
    private var filterPrimed = false

    private var thread: HandlerThread? = null

    fun start() {
        if (thread != null) return
        filterPrimed = false
        hasGravity = false
        headingErrorDeg = Float.NaN
        val t = HandlerThread("compass-sensors", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        val fast = SensorManager.SENSOR_DELAY_GAME
        rotationSensor?.let { sensorManager.registerListener(this, it, fast, handler) }
        // With a fused rotation sensor the magnetometer is only needed for the strength read-out.
        magneticSensor?.let {
            sensorManager.registerListener(this, it, if (rotationSensor != null) SensorManager.SENSOR_DELAY_UI else fast, handler)
        }
        gravitySensor?.let { sensorManager.registerListener(this, it, fast, handler) }
        // Air pressure changes slowly; the normal (~5 Hz) rate is plenty and keeps the sensor near idle.
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, v)
                // values[4] is the estimated heading accuracy in radians; -1 (or 0 on some devices) = not reported.
                if (v.size > 4 && v[4] > 0f) {
                    val err = Math.toDegrees(v[4].toDouble()).toFloat()
                    val e = headingErrorDeg
                    headingErrorDeg = if (e.isNaN()) err else e + 0.1f * (err - e)
                }
                publishHeading(alpha = 0.45f)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = v[0]; geomagnetic[1] = v[1]; geomagnetic[2] = v[2]
                val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                fieldStrength = if (fieldStrength == 0f) magnitude else fieldStrength + 0.05f * (magnitude - fieldStrength)
                accuracy = event.accuracy
                if (rotationSensor == null && hasGravity &&
                    SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)
                ) {
                    publishHeading(alpha = 0.15f)
                }
            }

            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                if (gravityIsRaw && hasGravity) {
                    // Low-pass the raw accelerometer to strip hand shake / linear acceleration.
                    gravity[0] += 0.2f * (v[0] - gravity[0])
                    gravity[1] += 0.2f * (v[1] - gravity[1])
                    gravity[2] += 0.2f * (v[2] - gravity[2])
                } else {
                    gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2]
                }
                hasGravity = true
                publishTilt()
            }

            Sensor.TYPE_PRESSURE -> {
                val p = pressureHpa
                // Light low-pass: raw samples jitter by a few hundredths of a hPa (~0.3 m of altitude).
                pressureHpa = if (p.isNaN()) v[0] else p + 0.1f * (v[0] - p)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) this.accuracy = accuracy
    }

    private fun publishHeading(alpha: Float) {
        val m = when (displayRotation) {
            Surface.ROTATION_90 -> remap(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> remap(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> remap(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> rotation
        }
        // Columns of m are the screen axes in world (east, north, up) coordinates.
        // Forward = horizontal part of the top edge (Y) plus the camera axis (-Z) weighted by how far the top edge
        // is raised (m[7], signed). Their horizontal parts always point the same way, so the sum never collapses,
        // and the weight fades to zero as the phone lies flat. (A hard sign switch here made a flat, slightly
        // rolled phone jump back and forth by up to the roll angle whenever m[7] hovered around zero.)
        val up = m[7]
        val east = m[1] - up * m[2]
        val north = m[4] - up * m[5]
        if (!filterPrimed) {
            filtEast = east; filtNorth = north; filterPrimed = true
        } else {
            // Adaptive smoothing: sub-degree changes are sensor noise and get filtered hard so a resting phone
            // reads steady, while real turns (several degrees per sample) pass through at the full rate.
            val diff = abs(atan2(filtEast * north - filtNorth * east, filtEast * east + filtNorth * north))
            val k = alpha * (diff / NOISE_BAND_RAD).coerceIn(0.15f, 1f)
            // Filtering the vector (not the angle) avoids any 359° -> 0° wrap-around artefacts.
            filtEast += k * (east - filtEast)
            filtNorth += k * (north - filtNorth)
        }
        if (filtEast * filtEast + filtNorth * filtNorth < 1e-6f) return
        var deg = Math.toDegrees(atan2(filtEast, filtNorth).toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        headingDegrees = deg
        hasHeading = true
        notifyUi()
    }

    private fun remap(x: Int, y: Int): FloatArray {
        SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
        return remapped
    }

    private fun publishTilt() {
        val gx = gravity[0]; val gy = gravity[1]; val gz = gravity[2]
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 1e-3f) return
        val x = gx / g
        val y = gy / g
        when (displayRotation) {
            Surface.ROTATION_90 -> { tiltX = y; tiltY = -x }
            Surface.ROTATION_180 -> { tiltX = -x; tiltY = -y }
            Surface.ROTATION_270 -> { tiltX = -y; tiltY = x }
            else -> { tiltX = x; tiltY = y }
        }
        hasTilt = true
        notifyUi()
    }

    private companion object {
        /** Heading changes below ~3° per sample are treated (progressively) as noise. */
        const val NOISE_BAND_RAD = 0.05f
    }
}
