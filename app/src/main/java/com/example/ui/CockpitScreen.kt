package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BotLiveTelemetry
import com.example.model.BotRuntimeStatus
import com.example.model.BotSettings
import com.example.service.BotCommand
import com.example.ui.theme.AmberGold
import com.example.ui.theme.EmeraldSafe
import com.example.ui.theme.EmeraldSurface
import com.example.ui.theme.SkyPredict
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import kotlin.math.roundToInt

@Composable
fun CockpitScreen(
    telemetry: BotLiveTelemetry,
    settings: BotSettings,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLaunchFloatingBot: () -> Unit,
    onSendCommand: (BotCommand) -> Unit,
    onUpdateSettings: ((BotSettings) -> BotSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Hero Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(
                1.dp,
                when (telemetry.status) {
                    BotRuntimeStatus.RUNNING -> EmeraldSafe
                    BotRuntimeStatus.NEEDS_INTERVENTION -> MaterialTheme.colorScheme.error
                    else -> SlateBorder
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart Auto-Solver Bot",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = telemetry.lastActionLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(status = telemetry.status)
                }

                // Live Telemetry Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricChip(
                        label = "Moves",
                        value = "${telemetry.movesExecuted}",
                        modifier = Modifier.weight(1f),
                    )
                    MetricChip(
                        label = "Triples",
                        value = "${telemetry.triplesCleared}",
                        modifier = Modifier.weight(1f),
                    )
                    MetricChip(
                        label = "Tray",
                        value = "${telemetry.lastAnalysis?.trayOccupiedCount ?: 0}/7",
                        modifier = Modifier.weight(1f),
                    )
                    MetricChip(
                        label = "Scan",
                        value = "${telemetry.lastAnalysis?.analysisTimeMs ?: 0}ms",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Primary Launch / Floating Board Controls
        if (!telemetry.isProjectionActive) {
            Button(
                onClick = onLaunchFloatingBot,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberGold,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("launch_floating_bot_button"),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Launch Floating Bot Board",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onSendCommand(BotCommand.TOGGLE_AUTO_PLAY) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (telemetry.status == BotRuntimeStatus.RUNNING) AmberGold else EmeraldSafe,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("toggle_autoplay_button"),
                ) {
                    Icon(
                        imageVector = if (telemetry.status == BotRuntimeStatus.RUNNING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (telemetry.status == BotRuntimeStatus.RUNNING) "Pause" else "Start Auto-Play",
                    )
                }

                OutlinedButton(
                    onClick = { onSendCommand(BotCommand.STOP_SERVICE) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("stop_bot_button"),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Stop",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Live Lookahead Plan Queue (shows upcoming moves decided in advance)
        val livePlan = telemetry.lastAnalysis?.plan.orEmpty()
        if (livePlan.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Route, contentDescription = null, tint = EmeraldSafe)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Planned Lookahead Queue (${livePlan.size} steps)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    livePlan.take(5).forEach { move ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, SlateBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "#${move.stepNumber}  ${move.reason}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                Text(
                                    text = if (move.clearsMatch3) "CLEAR -> ${move.predictedTrayAfter}/7" else "Tray ${move.predictedTrayAfter}/7",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (move.clearsMatch3) EmeraldSafe else AmberGold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Required System Permissions Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Device Setup (3 Permissions)",
                    style = MaterialTheme.typography.titleMedium,
                )

                PermissionRow(
                    icon = Icons.Rounded.TouchApp,
                    title = "1. Auto-Tap Accessibility Service",
                    subtitle = "Dispatches taps onto unlocked tiles",
                    granted = telemetry.isAccessibilityConnected,
                    buttonLabel = if (telemetry.isAccessibilityConnected) "Active" else "Enable",
                    testTag = "enable_accessibility_button",
                    onClick = onOpenAccessibilitySettings,
                )

                PermissionRow(
                    icon = Icons.Rounded.Layers,
                    title = "2. Floating Control Overlay",
                    subtitle = "Shows Play/Pause pill & live move hints",
                    granted = telemetry.isOverlayGranted,
                    buttonLabel = if (telemetry.isOverlayGranted) "Granted" else "Grant",
                    testTag = "enable_overlay_button",
                    onClick = onOpenOverlaySettings,
                )

                PermissionRow(
                    icon = Icons.Rounded.ScreenshotMonitor,
                    title = "3. Live Entire-Screen Capture",
                    subtitle = "Reads stacked layers & 7-slot dock directly",
                    granted = telemetry.isProjectionActive,
                    buttonLabel = if (telemetry.isProjectionActive) "Live" else "Start",
                    testTag = "start_capture_row_button",
                    onClick = onLaunchFloatingBot,
                )
            }
        }

        // Lookahead Intelligence Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = SkyPredict)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lookahead Solver Tuning",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Lookahead Depth",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${settings.lookaheadDepth} moves",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberGold,
                        )
                    }
                    Slider(
                        value = settings.lookaheadDepth.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(lookaheadDepth = v.roundToInt()) } },
                        valueRange = 6f..18f,
                        steps = 11,
                        colors = SliderDefaults.colors(thumbColor = AmberGold, activeTrackColor = AmberGold),
                        modifier = Modifier.testTag("lookahead_depth_slider"),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Search Intelligence Mode",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            160 to "Fast (160)",
                            280 to "Smart (280)",
                            420 to "Deep (420)",
                        ).forEach { (width, label) ->
                            FilterChip(
                                selected = settings.beamWidth == width,
                                onClick = { onUpdateSettings { it.copy(beamWidth = width) } },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Tap Interval",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${settings.tapDelayMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = SkyPredict,
                        )
                    }
                    Slider(
                        value = settings.tapDelayMs.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(tapDelayMs = v.roundToInt().toLong()) } },
                        valueRange = 110f..400f,
                        colors = SliderDefaults.colors(thumbColor = SkyPredict, activeTrackColor = SkyPredict),
                        modifier = Modifier.testTag("tap_delay_slider"),
                    )
                }

                ToggleRow(
                    title = "Show Visual Move Overlay",
                    subtitle = "Draws unlocked/locked boxes & #1..#5 path",
                    checked = settings.showMoveOverlay,
                    testTag = "toggle_move_overlay_switch",
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(showMoveOverlay = checked) } },
                )

                ToggleRow(
                    title = "Auto-Use Rescue Boosters",
                    subtitle = "Uses Return-3 / Shuffle if 7-slot dock deadlocks",
                    checked = settings.autoUseBoosters,
                    testTag = "toggle_auto_booster_switch",
                    onCheckedChange = { checked -> onUpdateSettings { it.copy(autoUseBoosters = checked) } },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun StatusPill(status: BotRuntimeStatus) {
    val (bg, fg, label) = when (status) {
        BotRuntimeStatus.RUNNING -> Triple(EmeraldSurface, EmeraldSafe, "ACTIVE")
        BotRuntimeStatus.READY -> Triple(MaterialTheme.colorScheme.secondaryContainer, SkyPredict, "READY")
        BotRuntimeStatus.PAUSED -> Triple(MaterialTheme.colorScheme.primaryContainer, AmberGold, "PAUSED")
        BotRuntimeStatus.NEEDS_INTERVENTION -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "ALERT")
        BotRuntimeStatus.IDLE -> Triple(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant, "IDLE")
    }
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value, style = MaterialTheme.typography.labelLarge, color = AmberGold)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) EmeraldSafe else AmberGold,
            modifier = Modifier.size(22.dp),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag),
        ) {
            Text(text = buttonLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}
