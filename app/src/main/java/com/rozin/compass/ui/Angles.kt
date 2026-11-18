package com.rozin.compass.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

val DIRECTION_NAMES = arrayOf(
    "NORTH", "NORTHEAST", "EAST", "SOUTHEAST", "SOUTH", "SOUTHWEST", "WEST", "NORTHWEST",
)

fun normalizeDegrees(degrees: Float): Float {
    val d = degrees % 360f
    return if (d < 0f) d + 360f else d
}

/** Signed smallest rotation (-180..180] that takes [from] to [to]. */
fun shortestDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** 34.0308 -> 34°01′51″ */
fun toDms(value: Double): String {
    val a = abs(value)
    var d = floor(a).toInt()
    val minutesFull = (a - d) * 60.0
    var m = floor(minutesFull).toInt()
    var s = ((minutesFull - m) * 60.0).roundToInt()
    if (s == 60) { s = 0; m++ }
    if (m == 60) { m = 0; d++ }
    return "$d°${m.toString().padStart(2, '0')}′${s.toString().padStart(2, '0')}″"
}
