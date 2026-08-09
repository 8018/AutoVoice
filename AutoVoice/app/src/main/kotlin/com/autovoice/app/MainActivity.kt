package com.autovoice.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autovoice.app.ui.VoiceScreen

/**
 * 启动 Activity（Compose Material3）：Task 50 按钮录音——启动即申请 RECORD_AUDIO
 * 权限（录音由底部按钮按住驱动，不自动开始）。未授权时只显示提示，
 * 按下按钮时 recorder 缺权限会静默创建失败 → ViewModel 提示授权。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    viewModel.clearPermissionHint()
                } else {
                    viewModel.onPermissionDenied()
                }
            }

            // 启动即申请权限（Task 50：授予后只清提示；录音由按钮驱动，不自动开始）
            LaunchedEffect(Unit) {
                if (!hasRecordAudioPermission()) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    VoiceScreen(
                        state = state,
                        onStartRecording = viewModel::startRecording,
                        onStopRecording = viewModel::stopRecording,
                        onModeChange = viewModel::setMode,
                        onWeakNetworkChange = viewModel::setWeakNetwork,
                    )
                }
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
