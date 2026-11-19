package com.shayor.compass

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.shayor.compass.data.AppSettings
import com.shayor.compass.data.ThemeMode
import com.shayor.compass.location.LocationTracker
import com.shayor.compass.sensor.CompassEngine
import com.shayor.compass.ui.CompassScreen
import com.shayor.compass.ui.theme.CompassTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private lateinit var engine: CompassEngine
    private lateinit var tracker: LocationTracker

    // Asked once on first launch, and again whenever the user turns on something that needs it. True north can't
    // work without location, so a refusal switches it back off.
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            tracker.start()
            if (!tracker.hasPermission()) settings.trueNorth = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        engine = CompassEngine(this)
        tracker = LocationTracker(applicationContext, lifecycleScope)

        // Sensors and GPS run only while the app is visible.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                engine.start()
                tracker.start()
                // Covers a refusal in system settings and a permission revoked while the app was away.
                if (!tracker.hasPermission()) settings.trueNorth = false
            }

            override fun onStop(owner: LifecycleOwner) {
                engine.stop()
                tracker.stop()
            }
        })

        if (savedInstanceState == null && !settings.askedLocation && !tracker.hasPermission()) {
            settings.askedLocation = true
            locationPermission.launch(LOCATION_PERMISSIONS)
        }

        setContent {
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val keepScreenOn = settings.keepScreenOn
            LaunchedEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            val smoothMotion = settings.smoothMotion
            LaunchedEffect(smoothMotion) {
                // A frame-rate vote, not a mode switch: the system renders this window at 60 Hz (0 = no preference).
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = if (smoothMotion) 0f else BATTERY_REFRESH_RATE
                }
            }
            CompassTheme(darkTheme = dark) {
                CompassScreen(
                    engine = engine,
                    tracker = tracker,
                    settings = settings,
                    onRequestLocation = ::requestLocation,
                    onOpenLocationSettings = ::openLocationSettings,
                )
            }
        }
    }

    private fun requestLocation() {
        val permanentlyDenied = settings.askedLocation &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.askedLocation = true
        if (permanentlyDenied) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            locationPermission.launch(LOCATION_PERMISSIONS)
        }
    }

    private fun openLocationSettings() {
        safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No settings screen available on this device; nothing else to do.
        }
    }

    private companion object {
        const val BATTERY_REFRESH_RATE = 60f
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
