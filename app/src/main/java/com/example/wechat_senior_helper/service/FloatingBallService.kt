package com.example.wechat_senior_helper.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.wechat_senior_helper.MainActivity
import com.example.wechat_senior_helper.R
import com.example.wechat_senior_helper.transaction.Transaction
import com.example.wechat_senior_helper.transaction.TransactionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating Ball Service
 * Provides a persistent overlay control for WeChat automation
 * Runs as foreground service to avoid being killed by system
 * 
 * @author moxuan
 */
class FloatingBallService : Service() {

    private companion object {
        private const val TAG = "FloatingBallService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_ball_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val WECHAT_LAUNCHER_ACTIVITY = "com.tencent.mm.ui.LauncherUI"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Transaction state
    private var currentTransaction: Transaction? = null
    private var isExecuting = false

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "========================================")
        Log.e(TAG, "悬浮球服务创建")
        Log.e(TAG, "========================================")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "悬浮球服务启动命令: ${intent?.action}")
        
        when (intent?.action) {
            "ACTION_OPEN_WECHAT" -> openWeChat()
            "ACTION_EXECUTE_TRANSACTION" -> executeTransaction()
            "ACTION_STOP_SERVICE" -> stopSelf()
        }
        
        return START_STICKY
    }

    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "微信老年助手悬浮球",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供微信自动化控制的悬浮球服务"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Build foreground service notification
     */
    private fun buildNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("微信老年助手")
            .setContentText("悬浮球服务运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Create floating ball overlay
     */
    private fun createFloatingBall() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Inflate floating ball layout
        floatingView = LayoutInflater.from(this).inflate(
            R.layout.floating_ball_layout, null
        )

        // Setup layout parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 100
            y = 100
        }

        // Add view to window
        try {
            windowManager?.addView(floatingView, params)
            setupFloatingBallViews()
            Log.e(TAG, "✅ 悬浮球已显示")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 悬浮球显示失败: ${e.message}", e)
        }
    }

    /**
     * Setup floating ball button click listeners
     */
    private fun setupFloatingBallViews() {
        val btnOpenWeChat = floatingView?.findViewById<Button>(R.id.btn_open_wechat)
        val btnExecuteTx = floatingView?.findViewById<Button>(R.id.btn_execute_tx)
        val btnStop = floatingView?.findViewById<Button>(R.id.btn_stop)
        val tvStatus = floatingView?.findViewById<TextView>(R.id.tv_status)

        // Open WeChat button
        btnOpenWeChat?.setOnClickListener {
            Log.e(TAG, "🚀 点击打开微信按钮")
            openWeChat()
            updateStatus("正在打开微信...")
        }

        // Execute transaction button
        btnExecuteTx?.setOnClickListener {
            if (!isExecuting) {
                Log.e(TAG, "🚀 点击执行事务按钮")
                executeTransaction()
            } else {
                Log.w(TAG, "⚠️ 事务正在执行中，请稍候")
                updateStatus("事务执行中...")
            }
        }

        // Stop service button
        btnStop?.setOnClickListener {
            Log.e(TAG, "🛑 点击停止服务按钮")
            stopSelf()
        }

        // Initial status
        updateStatus("就绪")
    }

    /**
     * Open WeChat application
     */
    private fun openWeChat() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.e(TAG, "✅ 已启动微信")
                updateStatus("微信已启动")
            } else {
                Log.e(TAG, "❌ 未找到微信应用")
                updateStatus("未安装微信")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动微信失败: ${e.message}", e)
            updateStatus("启动微信失败")
        }
    }

    /**
     * Execute back-once transaction
     */
    private fun executeTransaction() {
        if (isExecuting) {
            Log.w(TAG, "事务已在执行中")
            return
        }

        isExecuting = true
        updateStatus("执行事务中...")

        serviceScope.launch {
            try {
                Log.e(TAG, "========================================")
                Log.e(TAG, "🚀 开始执行最小事务原型")
                Log.e(TAG, "========================================")

                currentTransaction = TransactionScheduler.executeBackOnceTransaction()
                
                val tx = currentTransaction
                if (tx != null) {
                    val statusDesc = TransactionScheduler.getStatusDescription(tx)
                    updateStatus(statusDesc)
                    
                    Log.e(TAG, "========================================")
                    Log.e(TAG, "事务完成: ${tx.status.name}")
                    Log.e(TAG, "耗时: ${tx.duration()}ms")
                    Log.e(TAG, "原因: ${tx.reason}")
                    Log.e(TAG, "========================================")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 事务执行异常: ${e.message}", e)
                updateStatus("事务异常: ${e.message}")
            } finally {
                isExecuting = false
            }
        }
    }

    /**
     * Update status text on floating ball
     */
    private fun updateStatus(status: String) {
        floatingView?.findViewById<TextView>(R.id.tv_status)?.text = status
        Log.d(TAG, "状态更新: $status")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "悬浮球服务销毁")
        
        // Remove floating view
        try {
            if (floatingView != null && windowManager != null) {
                windowManager?.removeView(floatingView)
                Log.e(TAG, "悬浮球已移除")
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除悬浮球失败: ${e.message}", e)
        }
        
        // Cancel coroutine scope
        serviceScope.cancel()
        
        floatingView = null
        windowManager = null
    }
}
