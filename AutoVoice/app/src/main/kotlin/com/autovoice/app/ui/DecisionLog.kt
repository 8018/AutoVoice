package com.autovoice.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autovoice.voicecore.DecisionEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 决策日志列表（LazyColumn）：仲裁器/路由/原因/时间，最新条目置顶。
 * Task 19 种子为空（显示空态），条目由 Task 20 仲裁器 sink 追加。
 */
@Composable
fun DecisionLog(entries: List<DecisionEntry>, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无决策记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 倒序展示：最新决策在顶部
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(entries.asReversed()) { index, entry ->
                    DecisionRow(entry)
                    if (index < entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionRow(entry: DecisionEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "${formatTime(entry.timestampMs)}  ·  仲裁 ${entry.arbiter}  →  ${entry.route}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun formatTime(timestampMs: Long): String =
    Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_FORMAT)
