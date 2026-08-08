package com.autovoice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autovoice.app.VehicleUiState

/**
 * 车辆状态面板：空调（开关 + 温度）与车窗（开/关）以 AssistChip 呈现。
 * 状态只读展示——变更由车控意图执行（Task 20）驱动。
 */
@Composable
fun VehiclePanel(vehicle: VehicleUiState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "车辆状态",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (vehicle.acOn) "空调：开 · ${formatDigits(vehicle.acTemperature)}°C"
                            else "空调：关",
                        )
                    },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (vehicle.windowsOpen) "车窗：已开" else "车窗：已关") },
                )
            }
        }
    }
}

/** 温度数字直书：24.0 → "24"、25.5 → "25.5"（与播报文案一致）。 */
private fun formatDigits(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
