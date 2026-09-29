package com.autovoice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autovoice.app.VehicleUiState
import com.autovoice.app.toDigitsString

/** 车辆状态只读展示；不提供看似可点击、实际无效的车控按钮。 */
@Composable
fun VehiclePanel(vehicle: VehicleUiState, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VehicleStatCard(
            label = "空调",
            value = if (vehicle.acOn) "${vehicle.acTemperature.toDigitsString()}°C" else "已关闭",
            detail = if (vehicle.acOn) "运行中" else "待机",
            modifier = Modifier.weight(1f),
        )
        VehicleStatCard(
            label = "车窗",
            value = if (vehicle.windowsOpen) "已打开" else "已关闭",
            detail = "车辆状态",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VehicleStatCard(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
