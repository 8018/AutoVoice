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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autovoice.app.ui.VoiceScreen

/**
 * 启动 Activity（Compose Material3）：RECORD_AUDIO 运行时权限在录音开始前申请——
 * 未授权时只显示提示，录音不开始（recorder 缺权限会静默创建失败）。
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
                    viewModel.startRecording()
                } else {
                    viewModel.onPermissionDenied()
                }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    VoiceScreen(
                        state = state,
                        onStartRecording = {
                            // 权限未授予 → 弹系统请求；已授予 → 直接开始
                            if (hasRecordAudioPermission()) {
                                viewModel.clearPermissionHint()
                                viewModel.startRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
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
