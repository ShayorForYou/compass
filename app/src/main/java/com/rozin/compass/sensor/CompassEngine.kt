package com.rozin.compass.sensor

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
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Reads the motion sensors on a dedicated background thread and publishes the latest heading, tilt and
 * magnetic field strength through volatile fields. The UI pulls these values once per frame (see
 * `SensorAnimationLoop`), so sensor callbacks never touch the snapshot system or the main thread.
 *
 * Heading comes from the fused ROTATION_VECTOR sensor when available (smooth, gyro-assisted), otherwise from
 * accelerometer + magnetometer. The heading is derived from the horizontal projection of the device's "forward"
 * direction, blending the top edge (phone flat) with the camera axis (phone upright), so it stays stable at any
 * tilt instead of spinning wildly near vertical like a plain azimuth would.
 */
class CompassEngine(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravityIsRaw = gravitySensor?.type == Sensor.TYPE_ACCELEROMETER

    val hasCompass: Boolean = magneticSensor != null && (rotationSensor != null || gravitySensor != null)
    val hasLevel: Boolean = gravitySensor != null

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
    /** Magnetic field magnitude in µT. */
    @Volatile var fieldStrength = 0f
        private set
    /** One of SensorManager.SENSOR_STATUS_*. */
    @Volatile var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set
    /** Current display rotation (Surface.ROTATION_*), updated by the UI. */
    @Volatile var displayRotation = Surface.ROTATION_0

    private val updates = Channel<Unit>(Channel.CONFLATED)

    /** Suspends until a new sensor sample has been published. */
    suspend fun awaitUpdate() {
        updates.receive()
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
                publishHeading(alpha = 0.45f)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = v[0]; geomagnetic[1] = v[1]; geomagnetic[2] = v[2]
                fieldStrength = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
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
        // Forward = horizontal part of the top edge (Y) plus the camera axis (-Z) signed by which way the top tilts;
        // their horizontal components always point the same way, so the sum never collapses.
        val sign = if (m[7] >= 0f) 1f else -1f
        val east = m[1] - sign * m[2]
        val north = m[4] - sign * m[5]
        if (!filterPrimed) {
            filtEast = east; filtNorth = north; filterPrimed = true
        } else {
            // Filtering the vector (not the angle) avoids any 359° -> 0° wrap-around artefacts.
            filtEast += alpha * (east - filtEast)
            filtNorth += alpha * (north - filtNorth)
        }
        if (filtEast * filtEast + filtNorth * filtNorth < 1e-6f) return
        var deg = Math.toDegrees(atan2(filtEast, filtNorth).toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        headingDegrees = deg
        hasHeading = true
        updates.trySend(Unit)
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
        updates.trySend(Unit)
    }
}
