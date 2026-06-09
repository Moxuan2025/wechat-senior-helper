package com.example.wechat_senior_helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wechat_senior_helper.service.AccessibilityServiceStateManager
import com.example.wechat_senior_helper.service.FloatingBallService
import com.example.wechat_senior_helper.ui.theme.WechatseniorhelperTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestRecordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "麦克风权限已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 来自悬浮窗的权限申请跳转
        if (intent?.getStringExtra("request_permission") == "record_audio") {
            ensureRecordAudioPermission()
        }

        enableEdgeToEdge()
        setContent {
            WechatseniorhelperTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onOpenAccessibilitySettings = {
                            openAccessibilitySettings()
                        },
                        onRequestOverlayPermission = {
                            requestOverlayPermission()
                        },
                        onStartFloatingBall = {
                            startFloatingBallService()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh service status from system settings when returning to app
        AccessibilityServiceStateManager.refreshStatus(this)
    }

    /**
     * Open system accessibility settings page
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun ensureRecordAudioPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Request overlay permission for floating ball
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    /**
     * Start floating ball foreground service
     */
    private fun startFloatingBallService() {
        // Check overlay permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.e(TAG, "❌ 未授予悬浮窗权限")
                requestOverlayPermission()
                return
            }
        }

        // Check accessibility service
        val isAccessibilityEnabled = AccessibilityServiceStateManager.isAccessibilityEnabled(this)
        if (!isAccessibilityEnabled) {
            Log.e(TAG, "❌ 无障碍服务未启用")
            openAccessibilitySettings()
            return
        }

        // Start floating ball service
        val intent = Intent(this, FloatingBallService::class.java)
        startForegroundService(intent)
        Log.e(TAG, "✅ 悬浮球服务已启动")
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onOpenAccessibilitySettings: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
    onStartFloatingBall: () -> Unit = {}
) {
    // Subscribe to service status StateFlow - this is the single source of truth
    val serviceStatus by AccessibilityServiceStateManager.serviceStatus.collectAsState(
        AccessibilityServiceStateManager.ServiceStatus.DISABLED
    )
    
    // Derive UI-friendly state from ServiceStatus
    val isServiceRunning = serviceStatus == AccessibilityServiceStateManager.ServiceStatus.CONNECTED
    val statusText = when (serviceStatus) {
        AccessibilityServiceStateManager.ServiceStatus.CONNECTED -> "✅ 无障碍服务已连接"
        AccessibilityServiceStateManager.ServiceStatus.ENABLED -> "⚠️ 服务已启用但未连接"
        AccessibilityServiceStateManager.ServiceStatus.DISCONNECTED -> "❌ 服务已断开"
        AccessibilityServiceStateManager.ServiceStatus.DISABLED -> "❌ 无障碍服务未启用"
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "微信老年助手",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "悬浮球控制模式：无需切回应用即可操作微信",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("开启无障碍服务")
        }

        Button(
            onClick = onRequestOverlayPermission,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("授予悬浮窗权限")
        }

        Button(
            onClick = onStartFloatingBall,
            enabled = isServiceRunning,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("启动悬浮球")
        }

        Text(
            text = "使用说明：",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 24.dp)
        )
        
        Text(
            text = "1. 开启无障碍服务\n2. 授予悬浮窗权限\n3. 启动悬浮球\n4. 点击悬浮球打开微信\n5. 在微信中点击悬浮球执行事务",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    WechatseniorhelperTheme {
        MainScreen()
    }
}