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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.wechat_senior_helper.MainActivity
import com.example.wechat_senior_helper.R
import com.example.wechat_senior_helper.flow.CallMode
import com.example.wechat_senior_helper.net.TencentSpeechHelper
import com.example.wechat_senior_helper.flow.IntentHandler
import com.example.wechat_senior_helper.transaction.Transaction
import com.example.wechat_senior_helper.transaction.TransactionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingBallService : Service() {

    private companion object {
        private const val TAG = "FloatingBallService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_ball_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val WECHAT_LAUNCHER_ACTIVITY = "com.tencent.mm.ui.LauncherUI"
    }

    private var windowManager: WindowManager? = null
    private var statusView: View? = null
    private var inputView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentTransaction: Transaction? = null
    private var isExecuting = false
    private var callInProgress = false
    private var pendingAction: IntentHandler.PendingAction? = null
    private lateinit var tvMessage: TextView
    private lateinit var llConfirmBar: LinearLayout
    private lateinit var llCallChooser: LinearLayout
    private lateinit var btnConfirm: Button
    private lateinit var btnReject: Button
    private lateinit var btnChooseAudio: Button
    private lateinit var btnChooseVideo: Button

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "========================================")
        Log.e(TAG, "悬浮球服务创建")
        Log.e(TAG, "========================================")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlays()
        // monitorCallState()  // TODO: 与搜索流程的 takeScreenshot 冲突，待解决后启用
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

    // ============================================================
    // 双窗口：顶部状态窗 + 底部输入窗
    // ============================================================

    private fun showOverlays() {
        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        // --- 顶部状态窗：首次创建 ---
        if (statusView == null) {
            statusView = LayoutInflater.from(this).inflate(R.layout.overlay_status, null)
        }

        // --- 底部输入窗：首次创建 + 绑定事件 ---
        val isFirstInputSetup = inputView == null
        if (inputView == null) {
            inputView = LayoutInflater.from(this).inflate(R.layout.overlay_input, null)
        }
        if (isFirstInputSetup) {
            setupInputViews()
        }

        // --- 添加到窗口 ---
        if (statusView!!.parent == null) {
            try {
                windowManager?.addView(statusView, createStatusParams())
                Log.e(TAG, "✅ 顶部状态窗已显示")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 顶部状态窗显示失败: ${e.message}", e)
            }
        }
        if (inputView!!.parent == null) {
            try {
                windowManager?.addView(inputView, createInputParams())
                Log.e(TAG, "✅ 底部输入窗已显示")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 底部输入窗显示失败: ${e.message}", e)
            }
        }

        overlaysVisible = true
    }

    private fun createStatusParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = 0
        }
    }

    private fun createInputParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // ============================================================
    // 底部输入窗视图绑定 & 事件
    // ============================================================

    private fun setupInputViews() {
        val etIntent = inputView?.findViewById<EditText>(R.id.et_intent_text)
        val btnSendIntent = inputView?.findViewById<Button>(R.id.btn_send_intent)

        // 消息 / 确认栏 / 电话类型选择
        tvMessage = statusView?.findViewById(R.id.tv_message)!!
        llConfirmBar = inputView?.findViewById(R.id.ll_confirm_bar)!!
        llCallChooser = inputView?.findViewById(R.id.ll_call_chooser)!!
        btnConfirm = inputView?.findViewById(R.id.btn_confirm)!!
        btnReject = inputView?.findViewById(R.id.btn_reject)!!
        btnChooseAudio = inputView?.findViewById(R.id.btn_choose_audio)!!
        btnChooseVideo = inputView?.findViewById(R.id.btn_choose_video)!!

        // --- 发送意图 ---
        btnSendIntent?.setOnClickListener {
            val text = etIntent?.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isBlank()) return@setOnClickListener
            etIntent?.text?.clear()

            // 退出指令拦截，不走 LLM
            if (isExitCommand(text)) {
                closeAllOverlays()
                stopSelf()
                return@setOnClickListener
            }

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
                    hideOverlays()
                    serviceScope.launch {
                        val ok = service.confirmAction(
                            IntentHandler.PendingAction.Call(pending.target, chosenMode)
                        )
                        showMessage(if (ok) "执行完成" else "执行失败，请重试")
                        showOverlays()
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
                val result = service.handleIntent(text)
                Log.e(TAG, ">>> LLM 返回: ${result::class.simpleName}, message='${when (result) { is IntentHandler.RouteResult.Reply -> result.message; is IntentHandler.RouteResult.NeedConfirm -> result.message; is IntentHandler.RouteResult.DirectExecute -> result.message; else -> "" }}'")
                when (result) {
                    is IntentHandler.RouteResult.DirectExecute -> {
                        updateStatus("执行中...")
                        hideOverlays()
                        val ok = result.action.invoke()
                        showMessage(if (ok) result.message else "执行失败，请重试")
                        showOverlays()
                        updateStatus("就绪")
                    }
                    is IntentHandler.RouteResult.NeedConfirm -> {
                        hideKeyboardAndClearFocus()
                        showMessage(result.message)
                        pendingAction = result.pending
                        btnConfirm.text = "确认"
                        llConfirmBar.visibility = View.VISIBLE
                        llCallChooser.visibility = View.GONE
                        updateStatus("等待确认")
                    }
                    is IntentHandler.RouteResult.Reply -> {
                        showMessage(result.message)
                        updateStatus("就绪")
                    }
                }
            }
        }

        // --- 确认按钮 ---
        btnConfirm.setOnClickListener {
            val action = pendingAction ?: return@setOnClickListener
            val service = AccessibilityServiceStateManager.instance ?: return@setOnClickListener

            // 电话类型不明确 → 弹出选择
            if (action is IntentHandler.PendingAction.Call && action.mode == CallMode.UNKNOWN) {
                showCallModeChooser(action.target)
                return@setOnClickListener
            }

            llConfirmBar.visibility = View.GONE
            pendingAction = null
            updateStatus("执行中...")
            hideOverlays()
            serviceScope.launch {
                val ok = service.confirmAction(action)
                showMessage(if (ok) "执行完成" else "执行失败，请重试")
                showOverlays()
                updateStatus("就绪")
            }
        }

        // --- 否决按钮 ---
        btnReject.setOnClickListener {
            pendingAction = null
            llConfirmBar.visibility = View.GONE
            hideCallModeChooser()
            hideKeyboardAndClearFocus()
            showMessage("已取消")
            updateStatus("就绪")
        }

        // --- 电话类型选择：语音电话 ---
        btnChooseAudio.setOnClickListener {
            val action = pendingAction as? IntentHandler.PendingAction.Call ?: return@setOnClickListener
            val service = AccessibilityServiceStateManager.instance ?: return@setOnClickListener
            hideCallModeChooser()
            pendingAction = null
            updateStatus("执行中...")
            hideOverlays()
            serviceScope.launch {
                val ok = service.confirmAction(IntentHandler.PendingAction.Call(action.target, CallMode.AUDIO))
                showMessage(if (ok) "执行完成" else "执行失败，请重试")
                showOverlays()
                updateStatus("就绪")
            }
        }

        // --- 电话类型选择：视频电话 ---
        btnChooseVideo.setOnClickListener {
            val action = pendingAction as? IntentHandler.PendingAction.Call ?: return@setOnClickListener
            val service = AccessibilityServiceStateManager.instance ?: return@setOnClickListener
            hideCallModeChooser()
            pendingAction = null
            updateStatus("执行中...")
            hideOverlays()
            serviceScope.launch {
                val ok = service.confirmAction(IntentHandler.PendingAction.Call(action.target, CallMode.VIDEO))
                showMessage(if (ok) "执行完成" else "执行失败，请重试")
                showOverlays()
                updateStatus("就绪")
            }
        }

        updateStatus("就绪")
    }

    // ============================================================
    // 微信操作（保留，供 service intent 调用）
    // ============================================================

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

    // ============================================================
    // 悬浮窗显示/隐藏
    // ============================================================

    private var overlaysVisible = true

    private fun hideOverlays() {
        if (!overlaysVisible || windowManager == null) return
        try {
            statusView?.let { windowManager?.removeView(it) }
            inputView?.let { windowManager?.removeView(it) }
            overlaysVisible = false
            Log.e(TAG, "⏳ 悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 隐藏悬浮窗失败: ${e.message}", e)
        }
    }


    // ============================================================
    // UI 状态更新
    // ============================================================

    private fun updateStatus(status: String) {
        statusView?.findViewById<TextView>(R.id.tv_status)?.text = status
        Log.d(TAG, "状态更新: $status")
    }

    private fun showMessage(msg: String) {
        Log.e(TAG, ">>> showMessage 被调用, text='${msg.take(60)}'")
        tvMessage.text = msg
        tvMessage.visibility = View.VISIBLE
        try {
            TencentSpeechHelper.stop()   // 打断上一轮未播完的队列
            TencentSpeechHelper.speak(msg, this)
        } catch (e: Exception) {
            Log.e(TAG, "TTS 调用异常: ${e.message}", e)
        }
    }

    private fun showCallModeChooser(target: String) {
        hideKeyboardAndClearFocus()
        showMessage("请确认给${target}打：")
        llConfirmBar.visibility = View.GONE
        llCallChooser.visibility = View.VISIBLE
        Log.d(TAG, "显示电话类型选择: 语音/视频")
    }

    private fun hideCallModeChooser() {
        llCallChooser.visibility = View.GONE
        Log.d(TAG, "隐藏电话类型选择")
    }

    private fun isExitCommand(text: String): Boolean {
        return text.contains("退出") ||
               text.contains("关闭") ||
               text.contains("结束") ||
               text.contains("退出悬浮窗")
    }

    private fun closeAllOverlays() {
        try {
            statusView?.let { if (it.parent != null) windowManager?.removeView(it) }
        } catch (_: Exception) {}
        try {
            inputView?.let { if (it.parent != null) windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlaysVisible = false
    }

    private suspend fun isWeChatInCall(): Boolean {
        val service = AccessibilityServiceStateManager.instance ?: return false
        return service.isWeChatInCall()
    }

    private fun onCallStart() {
        callInProgress = true
        closeAllOverlays()
    }

    private fun monitorCallState() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val inCall = isWeChatInCall()
                    if (inCall && !callInProgress) {
                        callInProgress = true
                        closeAllOverlays()
                        Log.d(TAG, "检测到通话中，隐藏悬浮窗")
                    } else if (!inCall && callInProgress) {
                        callInProgress = false
                        showOverlays()
                        Log.d(TAG, "通话已结束，恢复悬浮窗")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "通话状态监测异常: ${e.message}")
                }
                delay(800)
            }
        }
    }

    private fun hideKeyboardAndClearFocus() {
        val view = inputView ?: return
        val etIntent = view.findViewById<EditText>(R.id.et_intent_text)
        etIntent?.clearFocus()

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "悬浮球服务销毁")
        try {
            statusView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        try {
            inputView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        serviceScope.cancel()
        statusView = null
        inputView = null
        windowManager = null
    }
}
