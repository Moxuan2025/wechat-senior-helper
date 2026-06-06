package com.example.wechat_senior_helper.flow

import android.content.Context
import android.util.Log
import com.example.wechat_senior_helper.net.HunyuanHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class IntentHandler(
    private val searchFlow: WeChatSearchContactFlow,
    private val voiceFlow: WeChatVoiceFlow,
    private val videoCallFlow: WeChatVideoCallFlow,
    private val chatNavGuard: ChatNavigationGuard,
    private val appContext: Context
) {
    companion object {
        private const val TAG = "IntentHandler"
    }

    // ===================== 数据类 =====================
    data class PendingAction(
        val action: String,         // "voice" / "video" / "text" / "none"
        val target: String,
        val friendlyMessage: String // 给用户看的提示，如"给儿子发语音"
    )

    sealed class HandleResult {
        data class Executed(val message: String) : HandleResult()      // 已执行（关键词）
        data class NeedConfirm(val pending: PendingAction) : HandleResult()  // 需确认
        data class Reply(val message: String) : HandleResult()         // 闲聊/追问
    }

    // ===================== 关键词映射 =====================
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val keywordActions: Map<Set<String>, suspend () -> Unit> = mapOf(
        setOf("儿子", "孙子") to { sendVoiceTo("儿子", 2000) },
        setOf("老公") to { sendVoiceTo("老公", 2000) },
        setOf("老婆") to { sendVoiceTo("老婆", 2000) },
        setOf("视频", "通话") to { videoCallFlow.makeCall(false) },
        setOf("打视频") to { videoCallFlow.makeCall(false) },
        setOf("语音通话") to { videoCallFlow.makeCall(true) },
        setOf("打电话") to { videoCallFlow.makeCall(true) },
        setOf("首页") to { chatNavGuard.goToHome() },
        setOf("返回") to { chatNavGuard.goToHome() }
    )

    // ===================== 主入口 =====================
    suspend fun handle(text: String): HandleResult {
        val input = text.trim()
        Log.e(TAG, "========================================")
        Log.e(TAG, "[意图输入] '$input'")
        if (input.isEmpty()) return HandleResult.Reply("您想做什么？")

        // 关键词匹配 → 直接执行
        val keywordResult = matchKeyword(input)
        if (keywordResult != null) {
            Log.e(TAG, "[意图结果] 关键词 → ${keywordResult}")
            Log.e(TAG, "========================================")
            return keywordResult
        }

        // 交给大模型
        Log.e(TAG, "[意图路由] 转交混元大模型")
        val llmResult = askLLM(input)
        Log.e(TAG, "[意图结果] 大模型 → ${llmResult}")
        Log.e(TAG, "========================================")
        return llmResult
    }

    /** 执行用户已确认的操作 */
    suspend fun executeConfirmed(pending: PendingAction): String {
        Log.e(TAG, "[执行确认] action='${pending.action}' target='${pending.target}'")
        return try {
            val action: suspend () -> Unit = when (pending.action) {
                "voice" -> { { voiceFlow.sendVoiceMessage() } }
                "video" -> { { videoCallFlow.makeCall(true) } }
                else -> return "不支持的操作"
            }
            chatNavGuard.navigateAndExecute(pending.target, action)
        } catch (e: Exception) {
            Log.e(TAG, "执行确认操作失败", e)
            "执行失败，请重试"
        }
    }

    // ===================== 关键词匹配 =====================
    private suspend fun matchKeyword(input: String): HandleResult? {
        for ((keywords, action) in keywordActions) {
            if (keywords.all { input.contains(it) }) {
                Log.e(TAG, "[关键词命中] 规则=${keywords.joinToString(",")}")
                action.invoke()
                val msg = when {
                    keywords.any { it in setOf("儿子", "孙子") } -> "好的奶奶，正在给儿子发语音..."
                    keywords.contains("老公") -> "好的，正在给老公发语音..."
                    keywords.contains("老婆") -> "好的，正在给老婆发语音..."
                    keywords.any { it in setOf("视频", "打视频") } -> "好的，正在发起视频通话..."
                    keywords.any { it in setOf("语音通话", "打电话") } -> "好的，正在发起语音通话..."
                    keywords.any { it in setOf("首页", "返回") } -> "好的，已帮您回到首页"
                    else -> "好的，已执行"
                }
                return HandleResult.Executed(msg)
            }
        }
        return null  // 交给 LLM
    }

    private suspend fun sendVoiceTo(name: String, durationMs: Long) {
        Log.e(TAG, "[执行] sendVoiceTo name='$name' durationMs=$durationMs")
        val ok = chatNavGuard.ensureChatWithTarget(name)
        if (ok) {
            voiceFlow.sendVoiceMessage(durationMs)
            Log.e(TAG, "[执行] sendVoiceTo 完成")
        } else {
            Log.e(TAG, "[执行] sendVoiceTo 导航失败")
        }
    }

    // ===================== 混元大模型 =====================
    private suspend fun askLLM(userInput: String): HandleResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val systemPrompt = """
                你是微信助手，请严格按以下 JSON 格式输出，不要添加任何其他文字：
                {
                  "action": "voice" | "video" | "text" | "none",
                  "target": "联系人姓名（没有则为空字符串）",
                  "reply": "回复给用户的话"
                }
                规则：
                1. 如果用户说"想某人""发语音"且给出了对象（如"儿子"），action 对应 "voice"，target 填对象名，reply 为"好的，正在给[对象]发语音"。
                2. 如果用户说"视频通话"且有对象，action="video"，target 填对象，reply 同前。
                3. 如果缺少对象（如"想"、"发语音"），action="none"，target 留空，reply 追问"您想谁了？"或"您想发给谁呢？"。
                4. 如果是闲聊或无法识别，action="none"，target 留空，reply 为自然友好的回复。
                5. 务必只输出合法 JSON，不要带任何解释、标点之外的文字。
            """.trimIndent()

            val messages = listOf(mapOf("role" to "user", "content" to userInput))

            Log.e(TAG, "[混元请求] model=hunyuan-lite")
            HunyuanHelper.getChatResponse(messages, systemPrompt, appContext) { raw ->
                if (!cont.isActive) return@getChatResponse
                Log.e(TAG, "[混元响应] raw=${raw.take(300)}")

                try {
                    // 清洗：提取 JSON
                    val cleaned = raw.trim()
                        .removePrefix("```json")
                        .removeSuffix("```")
                        .removePrefix("```")
                        .trim()

                    val jsonStr = if (cleaned.startsWith("{")) {
                        cleaned
                    } else {
                        val start = cleaned.indexOf('{')
                        val end = cleaned.lastIndexOf('}')
                        if (start >= 0 && end > start) cleaned.substring(start, end + 1) else ""
                    }

                    if (jsonStr.isEmpty()) {
                        Log.e(TAG, "[混元解析失败] 未提取到 JSON，当作闲聊")
                        cont.resume(HandleResult.Reply(raw.take(100)))
                        return@getChatResponse
                    }

                    val llmResult = JSONObject(jsonStr)
                    val action = llmResult.optString("action", "none")
                    val target = llmResult.optString("target", "")
                    val reply = llmResult.optString("reply", "好的")

                    Log.e(TAG, "[混元解析] action='$action' target='$target' reply='$reply'")

                    if (action == "none" || action == "text") {
                        cont.resume(HandleResult.Reply(reply))
                    } else {
                        val friendlyMsg = when (action) {
                            "voice" -> "给${target}发语音"
                            "video" -> "和${target}视频通话"
                            else -> "执行：${action} ${target}"
                        }
                        Log.e(TAG, "[混元确认] 待确认: $friendlyMsg")
                        cont.resume(HandleResult.NeedConfirm(PendingAction(action, target, friendlyMsg)))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[混元解析异常] ${e.message}", e)
                    cont.resume(HandleResult.Reply("抱歉，我没听明白，请再说清楚些"))
                }
            }
        }
    }
}
