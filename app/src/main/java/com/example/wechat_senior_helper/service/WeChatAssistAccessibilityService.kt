package com.example.wechat_senior_helper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.wechat_senior_helper.flow.CallMode
import com.example.wechat_senior_helper.flow.WeChatChatReadFlow
import com.example.wechat_senior_helper.flow.WeChatSearchContactFlow
import com.example.wechat_senior_helper.flow.ChatNavigationGuard
import com.example.wechat_senior_helper.flow.IntentHandler
import com.example.wechat_senior_helper.flow.WeChatVideoCallFlow
import com.example.wechat_senior_helper.flow.WeChatVoiceFlow
import com.example.wechat_senior_helper.input.CoordinateInputHelper
import com.example.wechat_senior_helper.ocr.AccessibilityScreenshotProvider
import com.example.wechat_senior_helper.ocr.MlKitOcrEngine
import com.example.wechat_senior_helper.ocr.ScreenCropper
import com.example.wechat_senior_helper.ocr.WechatScreenAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WeChatAssistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WeChatAssistService"

        @Deprecated("Use AccessibilityServiceStateManager.serviceStatus instead")
        val instance: WeChatAssistAccessibilityService?
            get() = AccessibilityServiceStateManager.instance
    }

    // ===================== 坐标参数 =====================
    private val SEARCH_ICON_X_RATIO = 0.86f
    private val SEARCH_ICON_Y_RATIO = 0.055f
    private val PASTE_MENU_X_RATIO = 0.12f
    private val PASTE_MENU_Y_RATIO = 0.14f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val busy = AtomicBoolean(false)
    private val flowLock = Mutex()

    private lateinit var screenshotProvider: AccessibilityScreenshotProvider
    private lateinit var ocrEngine: MlKitOcrEngine
    private lateinit var analyzer: WechatScreenAnalyzer
    private lateinit var inputHelper: CoordinateInputHelper
    private lateinit var searchFlow: WeChatSearchContactFlow
    private lateinit var chatReadFlow: WeChatChatReadFlow
    private lateinit var voiceFlow: WeChatVoiceFlow
    private lateinit var videoCallFlow: WeChatVideoCallFlow
    private lateinit var chatNavGuard: ChatNavigationGuard
    private lateinit var intentHandler: IntentHandler

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        AccessibilityServiceStateManager.setInstance(this)
        AccessibilityServiceStateManager.updateStatus(AccessibilityServiceStateManager.ServiceStatus.CONNECTED)

        val dm = resources.displayMetrics
        screenshotProvider = AccessibilityScreenshotProvider(this)
        ocrEngine = MlKitOcrEngine()
        analyzer = WechatScreenAnalyzer(ocrEngine)
        inputHelper = CoordinateInputHelper(this, dm.widthPixels, dm.heightPixels)

        searchFlow = WeChatSearchContactFlow(
            screenshotProvider = screenshotProvider,
            analyzer = analyzer,
            input = inputHelper,
            pasteMenuXRatio = PASTE_MENU_X_RATIO,
            pasteMenuYRatio = PASTE_MENU_Y_RATIO,
            setClipboardText = { text -> setClipboardText(text) },
            tap = { x, y -> dispatchTap(x, y) },
            clickSearchIconByPosition = { clickSearchIconByPosition() }
        )

        chatReadFlow = WeChatChatReadFlow(
            screenshotProvider = screenshotProvider,
            analyzer = analyzer
        )

        voiceFlow = WeChatVoiceFlow(
            input = inputHelper,
            screenshotProvider = screenshotProvider,
            ocrEngine = ocrEngine
        )

        videoCallFlow = WeChatVideoCallFlow(
            input = inputHelper,
            screenshotProvider = screenshotProvider,
            ocrEngine = ocrEngine
        )

        chatNavGuard = ChatNavigationGuard(
            input = inputHelper,
            screenshotProvider = screenshotProvider,
            ocrEngine = ocrEngine,
            searchContact = { name -> searchFlow.execute(name) },
            context = this,
            isWeChatForeground = { isWeChatForeground() }
        )

        intentHandler = IntentHandler(
            searchFlow = searchFlow,
            voiceFlow = voiceFlow,
            videoCallFlow = videoCallFlow,
            chatNavGuard = chatNavGuard,
            appContext = applicationContext
        )

        Log.e(TAG, "========================================")
        Log.e(TAG, "无障碍服务已连接！(纯坐标输入模式)")
        Log.e(TAG, "粘贴菜单坐标: x=$PASTE_MENU_X_RATIO, y=$PASTE_MENU_Y_RATIO")
        Log.e(TAG, "服务类名: ${this.javaClass.name}")
        Log.e(TAG, "包名: $packageName")
        Log.e(TAG, "========================================")
    }

    // ===================== 公开入口 =====================
    fun requestWechatSearch(contactName: String) {
        Log.e(TAG, "[REQUEST] contactName=$contactName")
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "[BUSY] 上一次操作尚未完成")
            return
        }
        scope.launch {
            try {
                val ok = searchFlow.execute(contactName)
                Log.e(TAG, "[FLOW_OCR] result=$ok")
            } catch (t: Throwable) {
                Log.e(TAG, "[FLOW_OCR_FAIL] ${t.message}", t)
            } finally {
                busy.set(false)
            }
        }
    }

    fun requestReadVisibleChat(onResult: (List<String>) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val result = chatReadFlow.readVisibleChat()
                onResult(result)
            } catch (t: Throwable) {
                Log.e(TAG, "[CHAT_READ_FAIL] ${t.message}", t)
            } finally {
                busy.set(false)
            }
        }
    }

    fun requestSendVoice(durationMs: Long = 2000L) {
        Log.e(TAG, "[VOICE] durationMs=$durationMs")
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "[BUSY] 上一次操作尚未完成")
            return
        }
        scope.launch {
            try {
                val ok = voiceFlow.sendVoiceMessage(durationMs)
                Log.e(TAG, "[VOICE] result=$ok")
            } catch (t: Throwable) {
                Log.e(TAG, "[VOICE_FAIL] ${t.message}", t)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * 直接发起通话（不导航，在当前页面操作）
     */
    suspend fun requestVideoCall(mode: CallMode = CallMode.AUDIO): Boolean = flowLock.withLock {
        Log.e(TAG, "[VIDEO_CALL] mode=$mode")
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "[BUSY] 上次操作未完成")
            return false
        }
        try {
            val ok = videoCallFlow.makeCall(mode)
            Log.e(TAG, "[VIDEO_CALL] result=$ok")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[VIDEO_CALL_FAIL] ${t.message}", t)
            false
        } finally {
            busy.set(false)
        }
    }

    fun ensureTargetChat(targetName: String) {
        Log.e(TAG, "[NAV] ensureTargetChat: $targetName")
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "[BUSY] 上次操作未完成")
            return
        }
        scope.launch {
            try {
                val ok = chatNavGuard.ensureChatWithTarget(targetName)
                Log.e(TAG, "[NAV] result=$ok")
            } catch (t: Throwable) {
                Log.e(TAG, "[NAV_FAIL] ${t.message}", t)
            } finally {
                busy.set(false)
            }
        }
    }

    suspend fun handleIntent(text: String): IntentHandler.RouteResult {
        return intentHandler.handle(text)
    }

    suspend fun confirmAction(pending: IntentHandler.PendingAction): Boolean {
        return intentHandler.executePending(pending)
    }

    fun requestSearchThenVoice(contactName: String, voiceDurationMs: Long = 3000L) {
        Log.e(TAG, "[SEARCH_VOICE] contactName=$contactName voiceDurationMs=$voiceDurationMs")
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "[BUSY] 上一次操作尚未完成")
            return
        }
        scope.launch {
            try {
                val searchOk = searchFlow.execute(contactName)
                if (!searchOk) {
                    Log.e(TAG, "[SEARCH_VOICE] search failed")
                    return@launch
                }
                delay(800) // 等待聊天界面完全加载
                voiceFlow.sendVoiceMessage(voiceDurationMs)
                Log.e(TAG, "[SEARCH_VOICE] done")
            } catch (t: Throwable) {
                Log.e(TAG, "[SEARCH_VOICE_FAIL] ${t.message}", t)
            } finally {
                busy.set(false)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
        AccessibilityServiceStateManager.setInstance(null)
        AccessibilityServiceStateManager.updateStatus(AccessibilityServiceStateManager.ServiceStatus.DISCONNECTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (::ocrEngine.isInitialized) ocrEngine.close()
        Log.d(TAG, "无障碍服务已销毁")
        AccessibilityServiceStateManager.setInstance(null)
        AccessibilityServiceStateManager.updateStatus(AccessibilityServiceStateManager.ServiceStatus.DISABLED)
    }

    // ===================== 微信前台检测 =====================
    private fun isWeChatForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.packageName == "com.tencent.mm"
    }

    // ===================== 通话状态检测 =====================
    suspend fun isWeChatInCall(): Boolean {
        if (!::screenshotProvider.isInitialized || !::ocrEngine.isInitialized) return false
        return try {
            val screenshot = screenshotProvider.capture()
            val bottomArea = ScreenCropper.cropBottomArea(screenshot)
            val text = normalizeOcrText(ocrEngine.recognize(bottomArea, preferChinese = true).text)
            text.contains("取消") &&
                text.contains("扬声器") &&
                text.contains("麦克风")
        } catch (e: Exception) {
            Log.d(TAG, "通话检测异常: ${e.message}")
            false
        }
    }

    private fun normalizeOcrText(text: String): String {
        return text.replace("\\s+".toRegex(), "")
    }

    // ===================== 手势/坐标操作 =====================
    private fun dispatchTap(x: Int, y: Int, durationMs: Long = 50L) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                val latch = CountDownLatch(1)
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        latch.countDown()
                    }
                }, null)
                latch.await(300, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                Log.e(TAG, "[GESTURE_FAIL] ${t.message}")
            }
        } else {
            Log.e(TAG, "[TAP_SKIP] gesture not supported on SDK ${Build.VERSION.SDK_INT}")
        }
    }

    private fun setClipboardText(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("wechat_search", text))
            Log.e(TAG, "[CLIPBOARD] text ready for paste")
        } catch (t: Throwable) {
            Log.e(TAG, "[CLIPBOARD_FAIL] ${t.message}")
        }
    }

    private fun clickSearchIconByPosition() {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * SEARCH_ICON_X_RATIO).toInt()
        val y = (dm.heightPixels * SEARCH_ICON_Y_RATIO).toInt()
        Log.e(TAG, "[TAP] searchIcon x=$x y=$y")
        dispatchTap(x, y)
    }

}
