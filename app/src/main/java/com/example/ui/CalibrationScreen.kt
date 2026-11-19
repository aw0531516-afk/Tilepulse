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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.RoiCalibration
import com.example.ui.theme.AmberGold
import com.example.ui.theme.SkyPredict
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import kotlin.math.roundToInt

@Composable
fun CalibrationScreen(
    calibration: RoiCalibration,
    onUpdateCalibration: (RoiCalibration) -> Unit,
    onResetDefaults: () -> Unit,
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
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Screen Region Calibration",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(
                        onClick = onResetDefaults,
                        modifier = Modifier.testTag("reset_calibration_button"),
                    ) {
                        Text("Reset")
                    }
                }
                Text(
                    text = "Pre-calibrated to your 3-tile puzzle layout. Adjust sliders if your phone's aspect ratio shifts the board or 7-slot dock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RoiSliderRow(
                    label = "Board Top Y",
                    value = calibration.boardTop,
                    range = 0.10f..0.35f,
                    onValueChange = { onUpdateCalibration(calibration.copy(boardTop = it)) },
                )
                RoiSliderRow(
                    label = "Board Bottom Y",
                    value = calibration.boardBottom,
                    range = 0.50f..0.80f,
                    onValueChange = { onUpdateCalibration(calibration.copy(boardBottom = it)) },
                )
                RoiSliderRow(
                    label = "Tray Dock Top Y",
                    value = calibration.trayTop,
                    range = 0.70f..0.85f,
                    onValueChange = { onUpdateCalibration(calibration.copy(trayTop = it)) },
                )
                RoiSliderRow(
                    label = "Tray Dock Bottom Y",
                    value = calibration.trayBottom,
                    range = 0.78f..0.92f,
                    onValueChange = { onUpdateCalibration(calibration.copy(trayBottom = it)) },
                )
                RoiSliderRow(
                    label = "Tile Width Ratio",
                    value = calibration.tileWidthRatio,
                    range = 0.08f..0.16f,
                    accentColor = SkyPredict,
                    onValueChange = { onUpdateCalibration(calibration.copy(tileWidthRatio = it)) },
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "How Auto-Play Works",
                    style = MaterialTheme.typography.titleMedium,
                )
                listOf(
                    "1. Enable Accessibility Service & Floating Overlay in the Cockpit tab.",
                    "2. Tap 'Launch Floating Bot Board' to open Entire-Screen capture.",
                    "3. Switch to your installed 3-tile puzzle game.",
                    "4. Tap PLAY on the floating HUD — the solver maps locked/unlocked DAG layers, predicts hidden matches up to 18 steps ahead, and manages the 7-slot dock automatically.",
                ).forEach { step ->
                    Text(text = step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun RoiSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accentColor: Color = AmberGold,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${(value * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor),
        )
    }
}
