package com.example.wechat_senior_helper.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.wechat_senior_helper.MainActivity
import com.example.wechat_senior_helper.R
import com.example.wechat_senior_helper.flow.CallMode
import com.example.wechat_senior_helper.flow.IntentHandler
import com.example.wechat_senior_helper.transaction.Transaction
import com.example.wechat_senior_helper.transaction.TransactionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

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

    private var currentTransaction: Transaction? = null
    private var isExecuting = false
    private var pendingAction: IntentHandler.PendingAction? = null
    private lateinit var tvMessage: android.widget.TextView
    private lateinit var btnConfirm: android.widget.Button
    private lateinit var btnChooseAudio: android.widget.Button
    private lateinit var btnChooseVideo: android.widget.Button

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

    private fun createFloatingBall() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_ball_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 100
            y = 100
        }

        try {
            windowManager?.addView(floatingView, params)
            setupFloatingBallViews()
            Log.e(TAG, "✅ 悬浮球已显示")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 悬浮球显示失败: ${e.message}", e)
        }
    }

    private fun setupFloatingBallViews() {
        val etSearchContact = floatingView?.findViewById<EditText>(R.id.et_search_contact)
        val btnSearchContact = floatingView?.findViewById<Button>(R.id.btn_search_contact)
        val btnOpenWeChat = floatingView?.findViewById<Button>(R.id.btn_open_wechat)
        val btnExecuteTx = floatingView?.findViewById<Button>(R.id.btn_execute_tx)
        val btnStop = floatingView?.findViewById<Button>(R.id.btn_stop)
        val tvStatus = floatingView?.findViewById<TextView>(R.id.tv_status)

        // Search contact button
        btnSearchContact?.setOnClickListener {
            val contactName = etSearchContact?.text?.toString()?.trim()
            if (contactName.isNullOrEmpty()) {
                updateStatus("请输入联系人名")
                return@setOnClickListener
            }
            Log.e(TAG, "🔍 开始搜索联系人: $contactName")
            searchAndOpenContact(contactName)
        }

        btnOpenWeChat?.setOnClickListener {
            Log.e(TAG, "🚀 点击打开微信按钮")
            openWeChat()
            updateStatus("正在打开微信...")
        }

        btnExecuteTx?.setOnClickListener {
            if (!isExecuting) {
                Log.e(TAG, "🚀 点击执行事务按钮")
                executeTransaction()
            } else {
                Log.w(TAG, "⚠️ 事务正在执行中，请稍候")
                updateStatus("事务执行中...")
            }
        }

        // Send voice button
        val btnSendVoice = floatingView?.findViewById<Button>(R.id.btn_send_voice)
        btnSendVoice?.setOnClickListener {
            if (isExecuting) {
                updateStatus("操作进行中...")
                return@setOnClickListener
            }
            val service = AccessibilityServiceStateManager.instance
            if (service == null) {
                updateStatus("无障碍服务未连接")
                return@setOnClickListener
            }
            Log.e(TAG, "🎤 发送语音")
            isExecuting = true
            updateStatus("录音中...")
            hideFloatingBall()
            service.requestSendVoice(2000L)
            serviceScope.launch {
                delay(5000)
                isExecuting = false
                showFloatingBall()
                updateStatus("就绪")
            }
        }

        // Message display + confirm + call mode chooser
        tvMessage = floatingView?.findViewById(R.id.tv_message)!!
        btnConfirm = floatingView?.findViewById(R.id.btn_confirm)!!
        btnChooseAudio = floatingView?.findViewById(R.id.btn_choose_audio)!!
        btnChooseVideo = floatingView?.findViewById(R.id.btn_choose_video)!!

        // Intent input
        val etIntent = floatingView?.findViewById<EditText>(R.id.et_intent_text)
        val btnSendIntent = floatingView?.findViewById<Button>(R.id.btn_send_intent)
        btnSendIntent?.setOnClickListener {
            val text = etIntent?.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isBlank()) return@setOnClickListener
            etIntent?.text?.clear()

            // 如果当前正在等待电话类型选择（CallMode.UNKNOWN），
            // 用户用文本回复"视频电话/语音电话"时，直接复用 pendingAction.target 执行
            val pending = pendingAction
            if (pending is IntentHandler.PendingAction.Call && pending.mode == CallMode.UNKNOWN) {
                val chosenMode: CallMode? = when {
                    text.contains("视频") -> CallMode.VIDEO
                    text.contains("语音") -> CallMode.AUDIO
                    else -> null
                }
                if (chosenMode != null) {
                    val service = AccessibilityServiceStateManager.instance
                    if (service == null) {
                        showMessage("无障碍服务未连接")
                        return@setOnClickListener
                    }
                    hideCallModeChooser()
                    pendingAction = null
                    Log.e(TAG, "💬 电话类型选择: 目标=${pending.target} 模式=$chosenMode")
                    updateStatus("执行中...")
                    hideFloatingBall()
                    serviceScope.launch {
                        val ok = service.confirmAction(
                            IntentHandler.PendingAction.Call(pending.target, chosenMode)
                        )
                        showMessage(if (ok) "执行完成" else "执行失败，请重试")
                        showFloatingBall()
                        updateStatus("就绪")
                    }
                    return@setOnClickListener
                }
            }

            val service = AccessibilityServiceStateManager.instance
            if (service == null) {
                showMessage("无障碍服务未连接")
                return@setOnClickListener
            }
            Log.e(TAG, "💬 意图输入: $text")
            updateStatus("思考中...")
            serviceScope.launch {
                when (val result = service.handleIntent(text)) {
                    is IntentHandler.RouteResult.DirectExecute -> {
                        updateStatus("执行中...")
                        hideFloatingBall()
                        val ok = result.action.invoke()
                        showMessage(if (ok) result.message else "执行失败，请重试")
                        showFloatingBall()
                        updateStatus("就绪")
                    }
                    is IntentHandler.RouteResult.NeedConfirm -> {
                        showMessage(result.message)
                        pendingAction = result.pending
                        btnConfirm.text = "确认"
                        btnConfirm.visibility = View.VISIBLE
                        updateStatus("等待确认")
                    }
                    is IntentHandler.RouteResult.Reply -> {
                        showMessage(result.message)
                        updateStatus("就绪")
                    }
                }
            }
        }

        // Confirm button
        btnConfirm.setOnClickListener {
            val action = pendingAction ?: return@setOnClickListener
            val service = AccessibilityServiceStateManager.instance ?: return@setOnClickListener

            // 电话类型不明确 → 弹出选择
            if (action is IntentHandler.PendingAction.Call && action.mode == CallMode.UNKNOWN) {
                showCallModeChooser(action.target)
                return@setOnClickListener
            }

            btnConfirm.visibility = View.GONE
            pendingAction = null
            updateStatus("执行中...")
            hideFloatingBall()
            serviceScope.launch {
                val ok = service.confirmAction(action)
                showMessage(if (ok) "执行完成" else "执行失败，请重试")
                showFloatingBall()
                updateStatus("就绪")
            }
        }

        // 电话类型选择：语音电话
        btnChooseAudio.setOnClickListener {
            val action = pendingAction as? IntentHandler.PendingAction.Call ?: return@setOnClickListener
            val service = AccessibilityServiceStateManager.instance ?: return@setOnClickListener
            hideCallModeChooser()
            pendingAction = null
            updateStatus("执行中...")
            hideFloatingBall()
            serviceScope.launch {
                val ok = service.confirmAction(IntentHandler.PendingAction.Call(action.target, CallMode.AUDIO))
                showMessage(if (ok) "执行完成" else "执行失败，请重试")
                showFloatingBall()
                updateStatus("就绪")
            }
        }

        // 电话类型选择：视频电话
        btnChooseVideo.setOnClickListener {
            val action = pendingAction as? IntentHandler.PendingAction.Call ?: return@setOnClickListener
            val service = AccessibilityServiceStateManager.instance ?: return@setOnClickListener
            hideCallModeChooser()
            pendingAction = null
            updateStatus("执行中...")
            hideFloatingBall()
            serviceScope.launch {
                val ok = service.confirmAction(IntentHandler.PendingAction.Call(action.target, CallMode.VIDEO))
                showMessage(if (ok) "执行完成" else "执行失败，请重试")
                showFloatingBall()
                updateStatus("就绪")
            }
        }

        // Video call button
        val btnVideoCall = floatingView?.findViewById<Button>(R.id.btn_video_call)
        btnVideoCall?.setOnClickListener {
            if (isExecuting) {
                updateStatus("操作进行中...")
                return@setOnClickListener
            }
            val service = AccessibilityServiceStateManager.instance
            if (service == null) {
                updateStatus("无障碍服务未连接")
                return@setOnClickListener
            }
            Log.e(TAG, "📞 发起语音通话")
            isExecuting = true
            updateStatus("发起语音通话...")
            hideFloatingBall()
            serviceScope.launch {
                val ok = service.requestVideoCall(CallMode.AUDIO)
                isExecuting = false
                showFloatingBall()
                updateStatus(if (ok) "就绪" else "执行失败")
            }
        }

        btnStop?.setOnClickListener {
            Log.e(TAG, "🛑 点击停止服务按钮")
            stopSelf()
        }

        updateStatus("就绪")
    }

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

    private fun searchAndOpenContact(contactName: String) {
        Log.e("OverlayUI", "[CLICK] search contact pressed")
        val service = AccessibilityServiceStateManager.instance
        Log.e("OverlayUI", "[CLICK] service=${service != null}")
        if (service == null) {
            Log.e("OverlayUI", "[CLICK_FAIL] accessibility service is null")
            updateStatus("无障碍服务未连接")
            return
        }
        if (isExecuting) {
            updateStatus("操作进行中...")
            return
        }
        isExecuting = true
        updateStatus("搜索中...")
        hideFloatingBall()

        service.requestWechatSearch(contactName)
        Log.e(TAG, "========== 已提交搜索请求: $contactName ==========")

        // 延迟恢复悬浮窗
        serviceScope.launch {
            delay(5000)
            isExecuting = false
            showFloatingBall()
            updateStatus("就绪")
        }
    }

    private var floatingBallIsVisible = true

    private fun hideFloatingBall() {
        if (!floatingBallIsVisible || floatingView == null || windowManager == null) return
        try {
            windowManager?.removeView(floatingView)
            floatingBallIsVisible = false
            Log.e(TAG, "⏳ 悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 隐藏悬浮窗失败: ${e.message}", e)
        }
    }

    private fun showFloatingBall() {
        if (floatingBallIsVisible || floatingView == null || windowManager == null) return
        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 100
                y = 100
            }
            windowManager?.addView(floatingView, params)
            floatingBallIsVisible = true
            Log.e(TAG, "✅ 悬浮窗已恢复")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 恢复悬浮窗失败: ${e.message}", e)
        }
    }

    private fun updateStatus(status: String) {
        floatingView?.findViewById<TextView>(R.id.tv_status)?.text = status
        Log.d(TAG, "状态更新: $status")
    }

    private fun showMessage(msg: String) {
        tvMessage.text = msg
        tvMessage.visibility = View.VISIBLE
        Log.d(TAG, "消息: $msg")
    }

    private fun showCallModeChooser(target: String) {
        showMessage("请确认给${target}打：")
        btnConfirm.visibility = View.GONE
        btnChooseAudio.visibility = View.VISIBLE
        btnChooseVideo.visibility = View.VISIBLE
        Log.d(TAG, "显示电话类型选择: 语音/视频")
    }

    private fun hideCallModeChooser() {
        btnChooseAudio.visibility = View.GONE
        btnChooseVideo.visibility = View.GONE
        Log.d(TAG, "隐藏电话类型选择")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "悬浮球服务销毁")
        try {
            if (floatingView != null && windowManager != null) {
                windowManager?.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除悬浮球失败: ${e.message}", e)
        }
        serviceScope.cancel()
        floatingView = null
        windowManager = null
    }
}
