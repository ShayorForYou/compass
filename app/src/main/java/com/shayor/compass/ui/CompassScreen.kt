package com.shayor.compass.ui

import android.hardware.GeomagneticField
import android.view.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shayor.compass.data.AppSettings
import com.shayor.compass.location.LocationTracker
import com.shayor.compass.sensor.AltitudeEstimator
import com.shayor.compass.sensor.AltitudeReading
import com.shayor.compass.sensor.CompassEngine
import com.shayor.compass.ui.theme.LocalCompassColors
import com.shayor.compass.ui.theme.TabularStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CompassScreen(
    engine: CompassEngine,
    tracker: LocationTracker,
    settings: AppSettings,
    onRequestLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val c = LocalCompassColors.current
    val live = remember { LiveReadings() }

    // Keep the sensor remapping in sync with the display rotation (re-runs on configuration change).
    val view = LocalView.current
    LocalConfiguration.current
    SideEffect { engine.displayRotation = view.display?.rotation ?: Surface.ROTATION_0 }

    val location = tracker.location
    val declination = remember(location) {
        location?.let {
            GeomagneticField(
                it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis(),
            ).declination
        }
    }
    val useTrueNorth = settings.trueNorth && declination != null
    SensorAnimationLoop(engine, live, if (useTrueNorth) declination else 0f, settings.haptics)

    // Low-rate read-outs (4 Hz) – no need to touch these every frame.
    var strength by remember { mutableIntStateOf(0) }
    var accuracy by remember { mutableIntStateOf(engine.accuracy) }
    var pressure by remember { mutableIntStateOf(0) }
    var altitude by remember { mutableStateOf<AltitudeReading?>(null) }
    val altitudeEstimator = remember { AltitudeEstimator() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(engine, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Read-outs hold their shown value until the reading clearly moves, so noise never flickers a digit
                // (and never costs a redraw on a resting phone).
                val field = engine.fieldStrength
                if (abs(field - strength) > 0.8f) strength = field.roundToInt()
                accuracy = engine.accuracy
                val hpa = engine.pressureHpa
                if (!hpa.isNaN() && (pressure == 0 || abs(hpa - pressure) > 0.8f)) pressure = hpa.roundToInt()
                altitude = altitudeEstimator.update(hpa, tracker.altitude)
                delay(250)
            }
        }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    var showLevel by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            TopBar(
                accuracy = accuracy,
                onAccuracyClick = { showCalibration = true },
                settingsOpen = showSettings,
                onSettingsClick = { showSettings = true },
            )
            Spacer(Modifier.height(20.dp))
            HeadingBlock(
                live = live,
                strength = strength,
                trueNorth = useTrueNorth,
                // Without a barometer, altitude can still come from GPS, so the slot exists once location is allowed.
                showAltitude = engine.hasBarometer || tracker.permissionGranted,
                altitude = altitude,
                pressure = if (engine.hasBarometer && pressure > 0) pressure else null,
                hasBarometer = engine.hasBarometer,
            )

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (engine.hasCompass) {
                    CompassDial(live, Modifier.size(min(maxWidth * 0.86f, maxHeight * 0.96f)))
                } else {
                    Text(
                        "No magnetic sensor on this device",
                        color = c.textMuted,
                        fontSize = 13.sp,
                    )
                }
            }

            BottomRow(
                tracker = tracker,
                live = live,
                levelAvailable = engine.hasLevel,
                onLevelClick = { showLevel = true },
            )
            LocationFooter(tracker, onRequestLocation, onOpenLocationSettings)
        }

        LevelOverlay(visible = showLevel, live = live, onClose = { showLevel = false })

        if (showSettings) {
            SettingsSheet(
                settings = settings,
                declination = declination,
                locationGranted = tracker.permissionGranted,
                onRequestLocation = onRequestLocation,
                onCalibrate = {
                    showSettings = false
                    showCalibration = true
                },
                onDismiss = { showSettings = false },
            )
        }
        if (showCalibration) {
            CalibrationDialog(accuracy, settings.haptics) { showCalibration = false }
        }
    }
}

// ---- Top bar -------------------------------------------------------------------------------------------------

@Composable
private fun TopBar(
    accuracy: Int,
    onAccuracyClick: () -> Unit,
    settingsOpen: Boolean,
    onSettingsClick: () -> Unit,
) {
    val c = LocalCompassColors.current
    val tuneT by animateFloatAsState(
        if (settingsOpen) 1f else 0f,
        spring(dampingRatio = 0.65f, stiffness = 300f),
        label = "tune",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccuracyPill(accuracy, onAccuracyClick)
        Spacer(Modifier.weight(1f))
        GlyphButton(contentDescription = "Settings", onClick = onSettingsClick) {
            tuneGlyph(c.icon, c.surface, tuneT)
        }
    }
}

// ---- Heading -------------------------------------------------------------------------------------------------

/**
 * Heading, direction and a quiet line of field strength / north mode. A second line with altitude and air pressure
 * appears only when the device can produce them, so phones without a barometer or location simply show less.
 */
@Composable
private fun HeadingBlock(
    live: LiveReadings,
    strength: Int,
    trueNorth: Boolean,
    showAltitude: Boolean,
    altitude: AltitudeReading?,
    pressure: Int?,
    hasBarometer: Boolean,
) {
    val c = LocalCompassColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val degrees by remember { derivedStateOf { live.heading.roundToInt() % 360 } }
        Text(
            buildAnnotatedString {
                append(degrees.toString())
                withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Light)) { append("°") }
            },
            color = c.textPrimary,
            fontSize = 54.sp,
            lineHeight = 58.sp,
            fontWeight = FontWeight.ExtraLight,
            letterSpacing = (-1.5).sp,
            style = TabularStyle,
        )
        DirectionLabel(live.directionIndex)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaText("$strength µT")
            MetaDot()
            Crossfade(trueNorth, label = "northMode") { tn -> MetaText(if (tn) "TRUE NORTH" else "MAGNETIC") }
        }
        if (showAltitude || hasBarometer) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showAltitude) {
                    // "≈" flags an estimate: barometer not yet anchored by GPS, or a GPS-only height.
                    MetaText("ALT ${altitude?.let(::formatAltitude) ?: "—"}")
                }
                if (showAltitude && hasBarometer) MetaDot()
                if (hasBarometer) MetaText("PRESSURE ${pressure ?: "—"} hPa")
            }
        }
    }
}

/** "27 m", "≈ 27 m", "≈ −7 m": a spaced "≈" and a true minus sign so an estimate never reads like a typo. */
private fun formatAltitude(reading: AltitudeReading): String {
    val value = if (reading.meters < 0) "−${-reading.meters}" else reading.meters.toString()
    return if (reading.approximate) "≈ $value m" else "$value m"
}

@Composable
private fun MetaDot() {
    val c = LocalCompassColors.current
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .size(3.dp)
            .background(c.textMuted.copy(alpha = 0.6f), RoundedCornerShape(50))
    )
}

@Composable
private fun MetaText(text: String) {
    val c = LocalCompassColors.current
    Text(
        text,
        color = c.textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        style = TabularStyle,
    )
}

@Composable
private fun DirectionLabel(index: Int) {
    val c = LocalCompassColors.current
    AnimatedContent(
        targetState = index,
        transitionSpec = {
            // Slide in the direction of rotation.
            val clockwise = ((targetState - initialState + 8) % 8) in 1..4
            val dir = if (clockwise) 1 else -1
            (slideInVertically(tween(280)) { h -> h * dir } + fadeIn(tween(240))) togetherWith
                (slideOutVertically(tween(280)) { h -> -h * dir } + fadeOut(tween(160))) using
                SizeTransform(clip = false)
        },
        contentAlignment = Alignment.Center,
        label = "direction",
    ) { i ->
        Text(
            if (i < 0) " " else DIRECTION_NAMES[i],
            color = c.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.sp,
        )
    }
}

// ---- Bottom: coordinates + level -----------------------------------------------------------------------------

@Composable
private fun BottomRow(
    tracker: LocationTracker,
    live: LiveReadings,
    levelAvailable: Boolean,
    onLevelClick: () -> Unit,
) {
    val loc = tracker.location
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Coordinate(
            label = "LATITUDE",
            value = loc?.let { "${toDms(it.latitude)} ${if (it.latitude >= 0) "N" else "S"}" },
            alignEnd = false,
            modifier = Modifier.weight(1f),
        )
        if (levelAvailable) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                if (pressed) 0.88f else 1f,
                spring(dampingRatio = 0.5f, stiffness = 500f),
                label = "levelPress",
            )
            LevelDisc(
                live,
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(68.dp)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = "Open surface level",
                        onClick = onLevelClick,
                    ),
            )
        }
        Coordinate(
            label = "LONGITUDE",
            value = loc?.let { "${toDms(it.longitude)} ${if (it.longitude >= 0) "E" else "W"}" },
            alignEnd = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Coordinate(label: String, value: String?, alignEnd: Boolean, modifier: Modifier) {
    val c = LocalCompassColors.current
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(3.dp))
        Crossfade(value, label = "coordinate") { v ->
            Text(
                v ?: "—",
                color = c.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                style = TabularStyle,
                maxLines = 1,
            )
        }
    }
}

// ---- Footer: address / location state ------------------------------------------------------------------------

private enum class FooterState { NoPermission, Disabled, Searching, Ready }

@Composable
private fun LocationFooter(
    tracker: LocationTracker,
    onRequestLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val c = LocalCompassColors.current
    val state = when {
        !tracker.permissionGranted -> FooterState.NoPermission
        tracker.location == null && !tracker.locationEnabled -> FooterState.Disabled
        tracker.location == null -> FooterState.Searching
        else -> FooterState.Ready
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 3 }) togetherWith fadeOut(tween(150))
        },
        label = "footer",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { s ->
        when (s) {
            FooterState.NoPermission -> FooterLink("Enable location for coordinates", onRequestLocation)
            FooterState.Disabled -> FooterLink("Turn on location services", onOpenLocationSettings)
            FooterState.Searching -> FooterLine("Locating…", pulsing = true)
            FooterState.Ready -> {
                val loc = tracker.location
                val text = tracker.address
                    ?: loc?.let { "%.5f, %.5f".format(it.latitude, it.longitude) }
                    ?: ""
                Crossfade(text, label = "address") { t -> FooterLine(t, pulsing = false) }
            }
        }
    }
}

@Composable
private fun FooterLine(text: String, pulsing: Boolean) {
    val c = LocalCompassColors.current
    // The dot is inline content, so it sits on the first line and wraps with the text
    // instead of floating between lines when a long address breaks.
    val content = remember(text) {
        buildAnnotatedString {
            appendInlineContent(DOT_ID, "•")
            append("  ")
            append(text)
        }
    }
    val inline = remember(pulsing) {
        mapOf(
            DOT_ID to InlineTextContent(
                Placeholder(10.sp, 10.sp, PlaceholderVerticalAlign.TextCenter),
            ) { LocationDot(pulsing) },
        )
    }
    Text(
        content,
        inlineContent = inline,
        color = c.textSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DOT_ID = "dot"

@Composable
private fun LocationDot(pulsing: Boolean) {
    val c = LocalCompassColors.current
    val pulse = if (pulsing) {
        rememberInfiniteTransition(label = "locating").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "locatingAlpha",
        )
    } else {
        null
    }
    Canvas(Modifier.fillMaxSize()) {
        val a = pulse?.value ?: 1f
        drawCircle(c.accent.copy(alpha = 0.25f * a))
        drawCircle(c.accent.copy(alpha = a), size.minDimension * 0.22f)
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    val c = LocalCompassColors.current
    Text(
        "$text  →",
        color = c.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
