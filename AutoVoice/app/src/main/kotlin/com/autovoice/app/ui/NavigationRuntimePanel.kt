package com.autovoice.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autovoice.app.navigation.NavigationRuntime
import com.autovoice.app.navigation.NavigationRuntimeUi
import com.autovoice.voicebusiness.navigation.MapAlongCategory
import com.autovoice.voicebusiness.navigation.MapViewAction
import com.autovoice.voicebusiness.navigation.RoutePreference

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NavigationRuntimePanel(state: NavigationRuntimeUi, runtime: NavigationRuntime) {
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snapshot = state.snapshot
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.sdkConfigured && !state.embeddedEnabled) {
            OutlinedButton(onClick = { showPrivacy = true }) { Text("启用高德内嵌导航") }
        } else if (!state.sdkConfigured) {
            Text("导航由高德地图执行", style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val plan = snapshot.plan
        if (plan != null) {
            Text("到${plan.request.trip.destination.name}的路线", style = MaterialTheme.typography.titleMedium)
            Text("起点为当前定位 · ${plan.request.preference.label} · 高德预计")
            if (plan.routes == null) Text("正在查询路线…")
            else {
                if (!plan.selectable) {
                    Text(if (state.embeddedEnabled) "预览方案，开始前需要确认路线。" else "预览方案。高德地图会在出发时重新规划。")
                    if (state.embeddedEnabled) OutlinedButton(onClick = { runtime.prepareStart(plan.request.id) }) {
                        Text("按此目的地开始")
                    }
                }
                plan.routes?.forEachIndexed { index, route ->
                    val duration = route.seconds?.let(NavigationRuntime::duration) ?: "时间未知"
                    val distance = route.meters?.let(NavigationRuntime::distance) ?: "距离未知"
                    val toll = route.tollYuan?.let { "收费约${it.toBigDecimal().stripTrailingZeros().toPlainString()}元" } ?: "收费未知"
                    Text("路线 ${index + 1} · $duration · $distance · $toll")
                    if (plan.selectable) {
                        OutlinedButton(
                            onClick = { runtime.start(plan.request.id, route.id) },
                            enabled = snapshot.starting == null,
                        ) { Text("走路线 ${index + 1}") }
                    }
                }
                Text("按偏好重新规划", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutePreference.entries.forEach { preference ->
                        if (preference != plan.request.preference) TextButton(
                            onClick = { runtime.changePreference(plan.request.id, preference) },
                            enabled = snapshot.starting == null,
                        ) { Text(preference.label) }
                    }
                }
            }
            TextButton(onClick = { runtime.dismissPlan(plan.request.id) }, enabled = snapshot.starting == null) {
                Text("关闭路线列表")
            }
            if (snapshot.starting != null && state.message?.contains("状态未知") == true) {
                TextButton(onClick = runtime::leaveTracking) { Text("退出本地跟踪") }
            }
            HorizontalDivider()
        }
        val active = snapshot.active
        if (active != null) {
            Text("前往${active.trip.destination.name}", style = MaterialTheme.typography.titleMedium)
            NavigationProgress(state)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runtime.setMapVisible(true) }) { Text("查看地图") }
                TextButton(onClick = { confirmStop = true }, enabled = snapshot.stopping == null) { Text("结束导航") }
                if (snapshot.stopping != null) TextButton(onClick = runtime::leaveTracking) { Text("退出本地跟踪") }
            }
            if (snapshot.stopping == null) {
                Text("沿途搜索", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapAlongCategory.entries.forEach { category ->
                        TextButton(onClick = { runtime.search(category) }) { Text(category.label) }
                    }
                }
            }
            snapshot.search?.let { search ->
                search.results?.forEach { stop ->
                    val extra = stop.extraMeters?.let { "预计绕行${NavigationRuntime.distance(it)}" } ?: "绕行距离未知"
                    Text("${stop.target.name} · $extra")
                }
                TextButton(onClick = { runtime.cancelSearch() }) { Text("放弃沿途搜索") }
            }
        } else if (snapshot.lastTrip != null && plan == null) {
            TextButton(onClick = { runtime.recover() }) { Text("按原目的地重新规划") }
        }
    }
    if (showPrivacy) AlertDialog(
        onDismissRequest = { showPrivacy = false },
        title = { Text("高德内嵌导航") },
        text = {
            Column {
                Text("开启后，AutoVoice 将使用高德地图、定位、搜索和导航 SDK。高德将处理定位、行程和设备信息，用于规划路线、沿途搜索及实时导航。")
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))) }
                }) { Text("查看高德隐私政策") }
            }
        },
        confirmButton = {
            Button(onClick = { showPrivacy = false; runtime.enableEmbedded() }) { Text("同意并启用") }
        },
        dismissButton = { TextButton(onClick = { showPrivacy = false }) { Text("继续使用高德地图") } },
    )
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("结束导航？") },
        text = { Text("AutoVoice 将向高德发送结束请求。") },
        confirmButton = { Button(onClick = { confirmStop = false; runtime.requestStop() }) { Text("结束导航") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续导航") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmbeddedNavigationMap(state: NavigationRuntimeUi, runtime: NavigationRuntime) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val current = remember(runtime, context) {
        runtime.createMapView(context)
    }
    BackHandler { runtime.setMapVisible(false) }
    DisposableEffect(lifecycle, current) {
        val observer = LifecycleEventObserver { _, event ->
            if (current != null) when (event) {
                Lifecycle.Event.ON_RESUME -> runtime.resumeMapView(current)
                Lifecycle.Event.ON_PAUSE -> runtime.pauseMapView(current)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (current != null) runtime.releaseMapView(current)
        }
    }
    var gain by remember { mutableStateOf(100f) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { runtime.setMapVisible(false) }) { Text("返回语音首页") }
        state.snapshot.active?.trip?.destination?.let { Text(it.name, style = MaterialTheme.typography.titleLarge) }
        NavigationProgress(state)
        state.message?.let { Text(it) }
        if (current != null) {
            AndroidView(factory = { current }, modifier = Modifier.fillMaxWidth().height(320.dp))
        } else Text("地图暂时无法显示，请返回后重试")
        if (state.snapshot.stopping == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                mapOf(
                    "放大" to MapViewAction.ZOOM_IN, "缩小" to MapViewAction.ZOOM_OUT,
                    "全程" to MapViewAction.OVERVIEW, "路况" to MapViewAction.TRAFFIC, "卫星" to MapViewAction.SATELLITE,
                ).forEach { (label, action) ->
                    TextButton(onClick = { runtime.report(runtime.mapAction(action)) }) { Text(label) }
                }
                TextButton(onClick = { runtime.report(runtime.mute(true)) }) { Text("静音导航") }
                TextButton(onClick = { runtime.report(runtime.mute(false)) }) { Text("恢复播报") }
            }
            Text("导航播报音量")
            Slider(
                value = gain, valueRange = 0f..100f,
                onValueChange = { gain = it },
                onValueChangeFinished = { runtime.report(runtime.volume(gain.toInt())) },
            )
        }
    }
}

@Composable
private fun NavigationProgress(state: NavigationRuntimeUi) {
    val progress = state.freshProgress
    val distance = progress?.meters?.let(NavigationRuntime::distance)
    val duration = progress?.seconds?.let(NavigationRuntime::duration)
    Text(listOfNotNull(distance?.let { "剩余$it" }, duration?.let { "还要$it" })
        .joinToString(" · ").ifBlank { "剩余时间和里程暂时未知" })
}
