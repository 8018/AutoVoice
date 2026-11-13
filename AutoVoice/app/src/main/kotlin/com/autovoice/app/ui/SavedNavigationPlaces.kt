package com.autovoice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autovoice.app.SavedPlacesUiState
import com.autovoice.voicebusiness.navigation.SavedPlaceEdit
import com.autovoice.voicebusiness.navigation.SavedPlaceKind

@Composable
internal fun SavedNavigationPlacesSection(
    state: SavedPlacesUiState,
    onSaveCurrent: (SavedPlaceKind) -> Unit,
    onDelete: (SavedPlaceKind) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("常用地址", style = MaterialTheme.typography.titleMedium)
            Text("保存在本机。也可以从导航搜索结果中选取地址。", style = MaterialTheme.typography.bodySmall)
            SavedPlaceKind.entries.forEach { kind ->
                val address = state.addresses[kind]?.target?.name
                Column {
                    Text(kind.label, style = MaterialTheme.typography.titleSmall)
                    Text(address ?: if (state.readFailed) "暂时无法读取" else "未设置")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !state.resolving,
                            onClick = { onSaveCurrent(kind) },
                        ) { Text("使用当前位置") }
                        if (address != null) {
                            TextButton(onClick = { onDelete(kind) }) { Text("删除") }
                        }
                    }
                }
            }
            if (state.resolving) Text("正在解析当前位置…")
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun SavedNavigationPlaceConfirmation(
    edit: SavedPlaceEdit,
    onConfirm: (SavedPlaceEdit) -> Unit,
    onCancel: (SavedPlaceEdit) -> Unit,
) {
    val action = when (edit.action) {
        SavedPlaceEdit.Action.SAVE -> "保存"
        SavedPlaceEdit.Action.REPLACE -> "替换"
        SavedPlaceEdit.Action.DELETE -> "删除"
    }
    AlertDialog(
        onDismissRequest = { onCancel(edit) },
        title = { Text("$action${edit.kind.label}？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                edit.previousAddress?.let {
                    Text("当前地址", style = MaterialTheme.typography.labelMedium)
                    Text(it)
                }
                edit.newAddress?.let {
                    Text("新地址", style = MaterialTheme.typography.labelMedium)
                    Text(it)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(edit) }) { Text("确认$action") } },
        dismissButton = { TextButton(onClick = { onCancel(edit) }) { Text("取消") } },
    )
}
