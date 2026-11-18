package com.shayor.compass.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Lightweight location source built on the platform LocationManager (no Play Services dependency).
 * Uses the fused provider on Android 12+, GPS + network otherwise, and reverse-geocodes the position
 * only when the user has moved a meaningful distance.
 */
@Stable
class LocationTracker(private val context: Context, private val scope: CoroutineScope) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val geocoder by lazy { Geocoder(context, Locale.getDefault()) }

    var location by mutableStateOf<Location?>(null)
        private set
    var address by mutableStateOf<String?>(null)
        private set
    var permissionGranted by mutableStateOf(hasPermission())
        private set
    var locationEnabled by mutableStateOf(LocationManagerCompat.isLocationEnabled(locationManager))
        private set

    private var running = false
    private var lastGeocoded: Location? = null
    private var lastGeocodeAttempt = 0L
    private var geocodeJob: Job? = null

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        override fun onProviderEnabled(provider: String) = refreshEnabled()
        override fun onProviderDisabled(provider: String) = refreshEnabled()
    }

    fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshEnabled() {
        locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        permissionGranted = hasPermission()
        refreshEnabled()
        if (!permissionGranted || running) return

        val providers = activeProviders()
        // Seed with the freshest cached fix so coordinates appear instantly.
        for (provider in providers + LocationManager.PASSIVE_PROVIDER) {
            val last = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
            if (last != null) onNewLocation(last)
        }

        val request = LocationRequestCompat.Builder(5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(3f)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        val executor = ContextCompat.getMainExecutor(context)
        for (provider in providers) {
            try {
                LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, executor, listener)
                running = true
            } catch (_: SecurityException) {
                // e.g. GPS with coarse-only permission; the other provider still works.
            } catch (_: IllegalArgumentException) {
                // Provider vanished.
            }
        }
    }

    // Updates are only ever registered after permission was granted; the catch covers a mid-session revoke.
    @SuppressLint("MissingPermission")
    fun stop() {
        if (running) {
            try {
                LocationManagerCompat.removeUpdates(locationManager, listener)
            } catch (_: SecurityException) {
                // Permission was revoked; the system has already dropped our listener.
            }
        }
        running = false
    }

    private fun activeProviders(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            locationManager.hasProvider(LocationManager.FUSED_PROVIDER)
        ) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        val all = locationManager.allProviders
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
    }

    private fun onNewLocation(new: Location) {
        if (!isBetter(new, location)) return
        location = new
        locationEnabled = true
        val previous = lastGeocoded
        // A big jump makes the old address wrong; show coordinates until the new one resolves.
        if (previous != null && previous.distanceTo(new) > 1_000f) address = null
        if (previous == null || previous.distanceTo(new) > 75f) reverseGeocode()
    }

    /** Geocodes the latest fix, rate-limited to one lookup per 10 s, and catches up if the user kept moving. */
    private fun reverseGeocode() {
        if (!Geocoder.isPresent() || geocodeJob?.isActive == true) return
        geocodeJob = scope.launch {
            while (true) {
                val wait = GEOCODE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastGeocodeAttempt)
                if (wait > 0) delay(wait)
                val target = location ?: return@launch
                val previous = lastGeocoded
                if (previous != null && previous.distanceTo(target) <= 75f) return@launch
                lastGeocodeAttempt = SystemClock.elapsedRealtime()
                val result = lookup(target) ?: return@launch // Failure: the next fix will retry.
                address = result
                lastGeocoded = target
            }
        }
    }

    private suspend fun lookup(target: Location): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(target.latitude, target.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        cont.resume(addresses.firstOrNull()?.let(::format))
                    }

                    override fun onError(errorMessage: String?) {
                        cont.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(target.latitude, target.longitude, 1)?.firstOrNull()?.let(::format)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun format(address: Address): String? {
        address.getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { return dedupe(it) }
        return listOfNotNull(
            address.thoroughfare,
            address.locality,
            address.adminArea,
            address.countryName,
        ).distinct().joinToString(", ").ifBlank { null }
    }

    /**
     * Geocoders often repeat parts ("MG Road, MG Road, Shivajinagar, Shivaji Nagar, …"). Drop any part that
     * matches an earlier one ignoring case and spacing.
     */
    private fun dedupe(line: String): String {
        val seen = HashSet<String>()
        return line.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase().replace(" ", "")) }
            .joinToString(", ")
    }

    private fun isBetter(new: Location, current: Location?): Boolean {
        if (current == null) return true
        val timeDelta = new.time - current.time
        if (timeDelta > 60_000L) return true
        if (timeDelta < -60_000L) return false
        val accuracyDelta = new.accuracy - current.accuracy
        if (accuracyDelta < 0f) return true
        return timeDelta > 0 && accuracyDelta <= 100f
    }

    private companion object {
        const val GEOCODE_INTERVAL_MS = 10_000L
    }
}
