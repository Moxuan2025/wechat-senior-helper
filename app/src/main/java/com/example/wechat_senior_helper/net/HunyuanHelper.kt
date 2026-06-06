package com.example.wechat_senior_helper.net

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object HunyuanHelper {

    private const val TAG = "HunyuanHelper"
    private const val HOST      = "hunyuan.tencentcloudapi.com"
    private const val SERVICE   = "hunyuan"
    private const val REGION    = "ap-guangzhou"
    private const val ACTION    = "ChatCompletions"
    private const val VERSION   = "2023-09-01"
    private const val ALGORITHM = "TC3-HMAC-SHA256"

    // 密钥变量（不再硬编码，也不再需要外部 init）
    private var SECRET_ID: String = ""
    private var SECRET_KEY: String = ""
    private var loaded = false

    /**
     * 懒加载密钥，与 TTS / 口语评测完全相同的模式
     */
    private fun loadKeys(context: Context) {
        if (loaded) return
        try {
            val props = Properties()
            context.applicationContext.assets.open("tencent_tts.properties").use {
                props.load(it)
            }
            // 混元使用独立的密钥键名，避免与 TTS 混淆（如果混元与 TTS 是同一套密钥，则改为 "SECRET_ID" 和 "SECRET_KEY"）
            SECRET_ID = props.getProperty("HUNYUAN_SECRET_ID", "")
            SECRET_KEY = props.getProperty("HUNYUAN_SECRET_KEY", "")
            loaded = true
            Log.d("HunyuanHelper", "密钥加载成功 SECRET_ID=${SECRET_ID.take(6)}...")
        } catch (e: Exception) {
            Log.e("HunyuanHelper", "密钥加载失败", e)
        }
    }

    /**
     * 句子相似度计算入口（新增 context 参数，与 TTS 调用方式一致）
     */
    /**
     * 通用聊天接口（用于智能助手）
     */
    fun chat(prompt: String, context: Context): String {
        loadKeys(context)
        if (SECRET_ID.isEmpty()) return "密钥配置错误"

        var result = "请求失败"
        val latch = java.util.concurrent.CountDownLatch(1)

        thread {
            try {
                val messagesArray = org.json.JSONArray()
                val messageObj = org.json.JSONObject()
                messageObj.put("Role", "user")
                messageObj.put("Content", prompt)
                messagesArray.put(messageObj)

                val payloadJson = JSONObject().apply {
                    put("Model", "hunyuan-lite")
                    put("Messages", messagesArray)
                }
                val payload = payloadJson.toString()

                val headers = sign(ACTION, payload)
                val response = httpPost(payload, headers)
                
                // 解析响应
                val json = JSONObject(response)
                val respObj = json.getJSONObject("Response")
                if (respObj.has("Error")) {
                    result = "API错误: " + respObj.getJSONObject("Error").getString("Message")
                } else {
                    val choices = respObj.getJSONArray("Choices")
                    val message = choices.getJSONObject(0).getJSONObject("Message")
                    result = message.getString("Content")
                }
            } catch (e: Exception) {
                result = "异常: ${e.message}"
            } finally {
                latch.countDown()
            }
        }
        
        latch.await()
        return result
    }

    /**
     * 结构化消息聊天接口（防止 LLM 续写）
     */
    fun getChatResponse(
        messages: List<Map<String, String>>,
        systemPrompt: String,
        context: Context,
        onResult: (String) -> Unit
    ) {
        loadKeys(context)
        if (SECRET_ID.isEmpty()) {
            onResult("密钥配置错误")
            return
        }

        thread {
            try {
                val messagesArray = org.json.JSONArray()
                
                // 如果有系统提示词，作为第一条 system 消息加入
                if (systemPrompt.isNotEmpty()) {
                    val sysMsg = org.json.JSONObject()
                    sysMsg.put("Role", "system")
                    sysMsg.put("Content", systemPrompt)
                    messagesArray.put(sysMsg)
                }

                // 加入对话历史
                for (msg in messages) {
                    val msgObj = org.json.JSONObject()
                    msgObj.put("Role", msg["role"] ?: "user")
                    msgObj.put("Content", msg["content"] ?: "")
                    messagesArray.put(msgObj)
                }

                val payloadJson = JSONObject().apply {
                    put("Model", "hunyuan-lite")
                    put("Messages", messagesArray)
                }
                val payload = payloadJson.toString()

                val headers = sign(ACTION, payload)
                val response = httpPost(payload, headers)

                // 解析响应
                val json = JSONObject(response)
                val respObj = json.getJSONObject("Response")
                if (respObj.has("Error")) {
                    onResult("API错误: " + respObj.getJSONObject("Error").getString("Message"))
                } else {
                    val choices = respObj.getJSONArray("Choices")
                    val message = choices.getJSONObject(0).getJSONObject("Message")
                    onResult(message.getString("Content"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI 请求异常", e)
                onResult("异常: ${e.message}")
            }
        }
    }

    /**
     * 句子相似度计算入口。
     * @param text1 句子1
     * @param text2 句子2
     * @param callback 回调，返回 0~1 的相似度浮点数（-1 表示失败）
     */
    fun getSimilarity(text1: String, text2: String, context: Context, callback: (Float) -> Unit) {
        // 每次调用先确保密钥已加载
        loadKeys(context)
        if (SECRET_ID.isEmpty()) {
            Log.e("HunyuanHelper", "密钥为空，请检查 tencent_tts.properties 中的 HUNYUAN_SECRET_ID/HUNYUAN_SECRET_KEY")
            callback(-1f)
            return
        }

//    fun getSimilarity(text1: String, text2: String, callback: (Float) -> Unit) {
        thread {
            try {
                // 1. 构建提示词和请求体
                val prompt = "请判断以下两个句子的语义相似度，返回0到1之间的小数，" +
                        "1表示完全相同，0表示完全不同，只返回数字，不要任何解释：\n" +
                        "句子1：$text1\n" +
                        "句子2：$text2"

                val messagesArray = org.json.JSONArray()
                val messageObj = org.json.JSONObject()
                messageObj.put("Role", "user")
                messageObj.put("Content", prompt)
                messagesArray.put(messageObj)

                val payloadJson = JSONObject().apply {
                    put("Model", "hunyuan-lite")
                    put("Messages", messagesArray)
                }
                val payload = payloadJson.toString()

                // 2. 生成签名并发起请求
                val headers = sign(ACTION, payload)
                val response = httpPost(payload, headers)
                Log.d("HunyuanHelper", "原始响应: $response")

                // 3. 解析相似度分数
                val similarity = parseSimilarityFromResponse(response)
                callback(similarity)
            } catch (e: Exception) {
                Log.e("HunyuanHelper", "请求失败", e)
                callback(-1f)
            }
        }
    }

    /**
     * 从混元 API 的响应中提取相似度数字，若失败返回 -1。
     */
    private fun parseSimilarityFromResponse(response: String): Float {
        return try {
            val json = JSONObject(response)
            val respObj = json.getJSONObject("Response")

            // 优先判断错误
            if (respObj.has("Error")) {
                val error = respObj.getJSONObject("Error")
                val code = error.getString("Code")
                val message = error.getString("Message")
                Log.e("HunyuanHelper", "API错误: $code - $message")
                return -1f
            }

            val choices = respObj.getJSONArray("Choices")
            val message = choices.getJSONObject(0).getJSONObject("Message")
            val content = message.getString("Content").trim()
            Log.d("HunyuanHelper", "解析出的内容: $content")
            content.toFloat()
        } catch (e: Exception) {
            Log.e("HunyuanHelper", "解析响应失败", e)
            -1f
        }
    }

    // ---------- TC3-HMAC-SHA256 签名（严格对齐官方 Java 示例） ----------
    private fun sign(action: String, payload: String): Map<String, String> {
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestamp = now.time / 1000   // 秒级时间戳
        val date = dateFormat.format(now)

        val credentialScope = "$date/$SERVICE/tc3_request"

        // 1. 规范请求串（Canonical Request）
        //    注意：X-TC-* 头部必须按字典序加入签名
        val canonicalHeaders = "content-type:application/json\n" +
                "host:$HOST\n" +
                "x-tc-action:${action.lowercase()}\n" +
                "x-tc-region:$REGION\n" +
                "x-tc-timestamp:$timestamp\n" +
                "x-tc-version:$VERSION\n"
        val signedHeaders = "content-type;host;x-tc-action;x-tc-region;x-tc-timestamp;x-tc-version"
        val hashedPayload = sha256Hex(payload)
        val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"

        // 2. 待签名字符串（StringToSign）
        val hashedCanonicalRequest = sha256Hex(canonicalRequest)
        val stringToSign = "$ALGORITHM\n$timestamp\n$credentialScope\n$hashedCanonicalRequest"

        // 3. 计算签名
        val secretDate = hmacSHA256(("TC3$SECRET_KEY").toByteArray(Charsets.UTF_8), date)
        val secretService = hmacSHA256(secretDate, SERVICE)
        val secretSigning = hmacSHA256(secretService, "tc3_request")
        val signature = bytesToHex(hmacSHA256(secretSigning, stringToSign))

        // 4. 拼接 Authorization
        val authorization = "$ALGORITHM Credential=$SECRET_ID/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        // 5. 返回请求头（Content-Type 必须与签名时的值完全一致）
        return mapOf(
            "Authorization"  to authorization,
            "Content-Type"   to "application/json",
            "Host"           to HOST,
            "X-TC-Action"    to action,
            "X-TC-Version"   to VERSION,
            "X-TC-Timestamp" to timestamp.toString(),
            "X-TC-Region"    to REGION
        )
    }

    // ---------- 工具方法 ----------
    private fun httpPost(payload: String, headers: Map<String, String>): String {
        val url = URL("https://$HOST")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        OutputStreamWriter(conn.outputStream).use { it.write(payload) }
        
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            // 4xx/5xx 时从错误流读取响应体
            conn.errorStream?.bufferedReader()?.readText() ?: throw e
        }
    }

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun hmacSHA256(key: ByteArray, msg: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}