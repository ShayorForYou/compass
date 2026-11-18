package com.shayor.compass.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shayor.compass.data.AppSettings
import com.shayor.compass.data.ThemeMode
import com.shayor.compass.ui.theme.LocalCompassColors
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    declination: Float?,
    onCalibrate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalCompassColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.background,
        contentColor = c.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(c.track, RoundedCornerShape(50))
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                "Settings",
                color = c.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp),
            )

            SectionLabel("COMPASS")
            Group {
                ToggleRow(
                    title = "True north",
                    subtitle = declination?.let {
                        "Declination %.1f° %s".format(abs(it), if (it >= 0f) "E" else "W")
                    } ?: "Requires location",
                    checked = settings.trueNorth,
                    onCheckedChange = { settings.trueNorth = it },
                )
                Divider()
                ToggleRow(
                    title = "Haptic ticks",
                    subtitle = "Every 30°",
                    checked = settings.haptics,
                    onCheckedChange = { settings.haptics = it },
                )
                Divider()
                ToggleRow(
                    title = "Keep screen on",
                    subtitle = null,
                    checked = settings.keepScreenOn,
                    onCheckedChange = { settings.keepScreenOn = it },
                )
                Divider()
                NavRow(title = "Calibrate", onClick = onCalibrate)
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("APPEARANCE")
            ThemeSelector(settings.theme) { settings.theme = it }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val c = LocalCompassColors.current
    Text(
        text,
        color = c.textMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val c = LocalCompassColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
    ) { content() }
}

@Composable
private fun Divider() {
    val c = LocalCompassColors.current
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(c.hairline)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = LocalCompassColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = c.textMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        MiniSwitch(checked)
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    val c = LocalCompassColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Spacer(
            Modifier
                .size(14.dp)
                .drawBehind { chevronGlyph(c.textMuted) }
        )
    }
}

/** Segmented control with a sliding, springy selection pill. */
@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val c = LocalCompassColors.current
    val options = ThemeMode.entries
    val labels = listOf("Auto", "Light", "Dark")
    val pill = RoundedCornerShape(50)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(pill)
            .background(c.surfaceVariant)
            .padding(3.dp)
    ) {
        val segment = maxWidth / options.size
        val indicatorX by animateDpAsState(
            segment * selected.ordinal,
            spring(dampingRatio = 0.75f, stiffness = 450f),
            label = "themeIndicator",
        )
        Box(
            Modifier
                .offset { IntOffset(indicatorX.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .shadow(1.5.dp, pill)
                .background(c.surface, pill)
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            options.forEachIndexed { i, mode ->
                val tint by animateColorAsState(
                    if (mode == selected) c.textPrimary else c.textMuted,
                    label = "themeTint",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .clickable { onSelect(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(labels[i], color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
