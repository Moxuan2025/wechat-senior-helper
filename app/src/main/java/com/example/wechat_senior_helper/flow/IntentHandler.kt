package com.example.wechat_senior_helper.flow

import android.content.Context
import android.util.Log
import com.example.wechat_senior_helper.net.HunyuanHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

typealias SuspendAction = suspend () -> Boolean

enum class CallMode { AUDIO, VIDEO, UNKNOWN }

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

    // ===================== 动作类型（规则层只识动作，不碰对象） =====================
    private enum class ActionType {
        AUDIO_CALL,
        VIDEO_CALL,
        VOICE_MESSAGE,
        CONFIRM,
        CANCEL,
        UNKNOWN
    }

    // ===================== 数据类 =====================
    sealed class PendingAction {
        data class Voice(val target: String) : PendingAction()
        data class Call(val target: String, val mode: CallMode) : PendingAction()
    }

    sealed class RouteResult {
        data class DirectExecute(
            val action: SuspendAction,
            val message: String
        ) : RouteResult()

        data class NeedConfirm(
            val pending: PendingAction,
            val message: String
        ) : RouteResult()

        data class Reply(
            val message: String
        ) : RouteResult()
    }

    // ===================== 主入口：动作命中 → 全部交给大模型 =====================
    suspend fun handle(input: String): RouteResult {
        val text = input.trim()
        Log.e(TAG, "========================================")
        Log.e(TAG, "[意图输入] '$text'")
        if (text.isEmpty()) return RouteResult.Reply("您想做什么？")

        val actionType = parseActionOnly(text)
        Log.e(TAG, "[动作识别] actionType=$actionType")

        return handleByLLM(userText = text, hintedAction = actionType)
    }

    // ===================== 规则层：只识别动作，不抽取对象 =====================
    private fun parseActionOnly(text: String): ActionType {
        return when {
            text.contains("视频电话") || text.contains("视频通话")
                -> ActionType.VIDEO_CALL
            text.contains("语音电话") || text.contains("语音通话")
                -> ActionType.AUDIO_CALL
            text.contains("打电话")
                -> ActionType.AUDIO_CALL
            text.contains("发语音")
                -> ActionType.VOICE_MESSAGE
            text.contains("确认")
                -> ActionType.CONFIRM
            text.contains("取消")
                -> ActionType.CANCEL
            else -> ActionType.UNKNOWN
        }
    }

    // ===================== 联系人归一化（只在 LLM 输出后做） =====================
    private fun normalizeTarget(raw: String): String {
        val s = raw.trim().replace(" ", "")
        return when (s) {
            "我爸", "我爸爸", "爸爸" -> "爸爸"
            "我妈", "我妈妈", "妈妈" -> "妈妈"
            "我儿子", "儿子" -> "儿子"
            "我女儿", "女儿" -> "女儿"
            "我老公", "老公" -> "老公"
            "我老婆", "老婆" -> "老婆"
            else -> s
        }
    }

    // ===================== LLM 路径：提示词 + 调用 + 解析 + 修复 =====================
    private suspend fun handleByLLM(userText: String, hintedAction: ActionType): RouteResult {
        Log.e(TAG, "[LLM路由] userText='$userText' hintedAction=$hintedAction")

        val systemPrompt = buildIntentPrompt(userText, hintedAction)
        val messages = listOf(mapOf("role" to "user", "content" to userText))

        val rawJson = askLLMRaw(systemPrompt, messages) ?: run {
            Log.e(TAG, "[LLM路由] 大模型返回空")
            return RouteResult.Reply("抱歉，我没听明白，请再说一遍")
        }

        val rawResult = parseLlmResult(rawJson)
        Log.e(TAG, "[LLM原始] actionType=${rawResult.actionType} target='${rawResult.target}' callMode=${rawResult.callMode} needConfirm=${rawResult.needConfirm}")

        // 本地动作优先：即使 LLM 乱回，也强制改回 hintedAction
        val rawResult2 = repairLlmResult(rawResult, hintedAction)

        val actionType = rawResult2.actionType
        val target = normalizeTarget(rawResult2.target ?: "")
        val needConfirm = rawResult2.needConfirm
        val reply = rawResult2.reply?.trim()?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
            ?: defaultReply(actionType, target)

        Log.e(TAG, "[LLM解析] actionType=$actionType target='$target' needConfirm=$needConfirm reply='$reply'")

        if (target.isBlank()) {
            return RouteResult.Reply(reply.ifBlank { "请告诉我要操作谁" })
        }

        return when (actionType) {
            ActionType.VOICE_MESSAGE -> {
                if (needConfirm) {
                    RouteResult.NeedConfirm(
                        PendingAction.Voice(target),
                        "要给${target}发语音吗？"
                    )
                } else {
                    RouteResult.DirectExecute(
                        action = {
                            if (!chatNavGuard.ensureChatWithTarget(target)) return@DirectExecute false
                            delay(200)
                            voiceFlow.sendVoiceMessage()
                        },
                        message = "已给${target}发语音"
                    )
                }
            }

            ActionType.AUDIO_CALL -> {
                // 只要不是"语音电话"这种明确表达，就先确认
                val explicitAudio = userText.contains("语音电话") || userText.contains("语音通话")
                if (!explicitAudio) {
                    return RouteResult.NeedConfirm(
                        PendingAction.Call(target, CallMode.UNKNOWN),
                        "要给${target}打语音电话还是视频电话？"
                    )
                }

                if (needConfirm) {
                    RouteResult.NeedConfirm(
                        PendingAction.Call(target, CallMode.AUDIO),
                        "要给${target}打语音电话吗？"
                    )
                } else {
                    RouteResult.DirectExecute(
                        action = {
                            if (!chatNavGuard.ensureChatWithTarget(target)) return@DirectExecute false
                            delay(800)
                            videoCallFlow.makeCall(CallMode.AUDIO)
                        },
                        message = "已给${target}打语音电话"
                    )
                }
            }

            ActionType.VIDEO_CALL -> {
                if (needConfirm) {
                    RouteResult.NeedConfirm(
                        PendingAction.Call(target, CallMode.VIDEO),
                        "要给${target}打视频电话吗？"
                    )
                } else {
                    RouteResult.DirectExecute(
                        action = {
                            if (!chatNavGuard.ensureChatWithTarget(target)) return@DirectExecute false
                            delay(800)
                            videoCallFlow.makeCall(CallMode.VIDEO)
                        },
                        message = "已给${target}打视频电话"
                    )
                }
            }

            ActionType.CONFIRM -> RouteResult.Reply("请点击确认按钮来执行操作")
            ActionType.CANCEL -> RouteResult.Reply("已取消")
            ActionType.UNKNOWN, null -> RouteResult.Reply("我没理解清楚，请再说一遍")
        }
    }

    // ===================== LLM 提示词 =====================
    private fun buildIntentPrompt(userText: String, hintedAction: ActionType): String {
        return """
你是微信意图解析器，只输出JSON。

本地动作已识别为：$hintedAction
你只能补充 target、callMode、needConfirm、reply。
如果用户只是说"打电话"，但没有明确语音电话或视频电话，必须返回：
needConfirm=true，并提示用户确认是语音电话还是视频电话。

callMode 只能是：
- AUDIO
- VIDEO
- UNKNOWN

输出示例：
{
  "actionType": "AUDIO_CALL",
  "target": "爸爸",
  "callMode": "UNKNOWN",
  "needConfirm": true,
  "reply": "要给爸爸打语音电话还是视频电话？"
}

用户输入：$userText
""".trimIndent()
    }

    // ===================== LLM 原始调用 =====================
    private suspend fun askLLMRaw(
        systemPrompt: String,
        messages: List<Map<String, String>>
    ): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            Log.e(TAG, "[混元请求]")
            HunyuanHelper.getChatResponse(messages, systemPrompt, appContext) { raw ->
                if (!cont.isActive) return@getChatResponse
                Log.e(TAG, "[混元响应] raw=${raw.take(300)}")
                cont.resume(raw)
            }
        }
    }

    // ===================== LLM 结果解析 =====================
    private data class LlmIntentResult(
        val actionType: ActionType? = null,   // LLM 返回的动作（不可信，会被修复）
        val target: String? = null,
        val callMode: CallMode = CallMode.UNKNOWN,
        val needConfirm: Boolean = false,
        val reply: String? = null
    )

    private fun parseLlmResult(raw: String): LlmIntentResult {
        return try {
            val jsonStr = stripCodeFence(raw)

            if (jsonStr.isEmpty()) {
                return LlmIntentResult(reply = "我没听明白，请再说一遍")
            }

            val json = JSONObject(jsonStr)
            val actionType = parseActionType(json.optString("actionType", ""))
            val target = json.optString("target", "").takeIf { it.isNotBlank() }
            val callMode = parseCallMode(json.optString("callMode", ""))
            val needConfirm = json.optBoolean("needConfirm", false)
            val reply = json.optString("reply", "").takeIf { it.isNotBlank() }

            LlmIntentResult(actionType, target, callMode, needConfirm, reply)
        } catch (e: Exception) {
            Log.e(TAG, "[LLM解析异常] ${e.message}", e)
            LlmIntentResult(reply = "抱歉，我没听明白，请再说清楚些")
        }
    }

    private fun parseCallMode(s: String): CallMode {
        return try {
            CallMode.valueOf(s.uppercase())
        } catch (_: Exception) {
            CallMode.UNKNOWN
        }
    }

    /** 剥离 LLM 响应的 ```json 外壳 */
    private fun stripCodeFence(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return if (cleaned.startsWith("{")) {
            cleaned
        } else {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) cleaned.substring(start, end + 1) else ""
        }
    }

    /** 本地动作优先：hintedAction 非 UNKNOWN 时，LLM 不可改 */
    private fun forceActionIfHinted(hintedAction: ActionType, llmAction: ActionType?): ActionType {
        return if (hintedAction != ActionType.UNKNOWN) hintedAction else (llmAction ?: ActionType.UNKNOWN)
    }

    /** 修复 LLM 结果：本地动作永远优先 */
    private fun repairLlmResult(raw: LlmIntentResult, hintedAction: ActionType): LlmIntentResult {
        val finalAction = forceActionIfHinted(hintedAction, raw.actionType)
        return raw.copy(
            actionType = finalAction,
            target = raw.target?.trim(),
            reply = raw.reply?.trim()
        )
    }

    private fun parseActionType(s: String): ActionType? {
        return try {
            ActionType.valueOf(s)
        } catch (_: Exception) {
            null
        }
    }

    // ===================== 兜底回复 =====================
    private fun defaultReply(actionType: ActionType?, target: String): String {
        return when (actionType) {
            ActionType.AUDIO_CALL -> "已给${target}打电话"
            ActionType.VOICE_MESSAGE -> "已给${target}发语音"
            ActionType.VIDEO_CALL -> "已给${target}打视频电话"
            else -> "已完成"
        }
    }

    // ===================== 确认后执行 =====================
    suspend fun executePending(action: PendingAction): Boolean {
        Log.e(TAG, "[执行确认] action=$action")
        return try {
            when (action) {
                is PendingAction.Voice -> {
                    if (!chatNavGuard.ensureChatWithTarget(action.target)) return false
                    delay(200)
                    voiceFlow.sendVoiceMessage()
                }
                is PendingAction.Call -> {
                    val mode = action.mode
                    if (mode == CallMode.UNKNOWN) {
                        Log.e(TAG, "[执行确认] CallMode.UNKNOWN，拒绝执行")
                        return false
                    }
                    if (!chatNavGuard.ensureChatWithTarget(action.target)) return false
                    delay(800)
                    videoCallFlow.makeCall(mode)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行确认操作失败", e)
            false
        }
    }
}
