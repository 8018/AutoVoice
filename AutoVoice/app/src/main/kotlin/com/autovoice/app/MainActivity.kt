package com.autovoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.autovoice.app.ui.VoiceScreen

/**
 * 启动 Activity（Compose Material3）：
 * - Task 50 按钮录音——启动即申请 RECORD_AUDIO（录音由底部按钮按住驱动，不自动开始）。
 * - Task 55 对齐讯飞 demo 权限：Android 13+ 追加 READ_MEDIA_IMAGES/VIDEO（照片和视频），
 *   并检查「所有文件访问」（MANAGE_EXTERNAL_STORAGE，非 runtime 权限，未授予时跳系统设置页）。
 *   未授权只显示提示，不阻塞 UI。
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        mainViewModel.onForeground()
    }

    override fun onStop() {
        mainViewModel.onBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TTS 使用导航语音属性并映射到媒体流；显式指定后，物理音量键始终调整
        // 当前播报音量。AEC 由录音侧 VOICE_COMMUNICATION + AcousticEchoCanceler 提供。
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            val viewModel = mainViewModel
            val state by viewModel.uiState.collectAsState()

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                if (result[Manifest.permission.RECORD_AUDIO] == true) {
                    viewModel.clearPermissionHint()
                } else {
                    viewModel.onPermissionDenied()
                }
            }

            // 启动即申请权限：录音 + Android 13+ 媒体（照片/视频，等价 demo 的 XXPermissions 行为）
            // + 定位（高德导航 §4.2：导航启动参数/附近搜索上下文）
            LaunchedEffect(Unit) {
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                        add(Manifest.permission.READ_MEDIA_VIDEO)
                    }
                }
                if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    permissionLauncher.launch(permissions.toTypedArray())
                } else {
                    viewModel.onAudioPermissionGranted()
                }
                // 所有文件访问（讯飞 AIKit 读写 workDir=/sdcard/iflytek/ 必需，非 runtime 权限只能跳系统设置）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    requestAllFilesAccess()
                }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    VoiceScreen(
                        state = state,
                        onModeChange = viewModel::setMode,
                        onWeakNetworkChange = viewModel::setWeakNetwork,
                    )
                }
            }
        }
    }

    /** 跳「所有文件访问」设置页；部分 ROM（荣耀 MagicOS）解析不了专用 action，兜底到应用详情页。 */
    private fun requestAllFilesAccess() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(
            if (settingsIntent.resolveActivity(packageManager) != null) {
                settingsIntent
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            },
        )
    }
}
