package com.example.wechat_senior_helper.net

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tencent.cloud.realtime.tts.RealTimeSpeechSynthesizer
import com.tencent.cloud.realtime.tts.RealTimeSpeechSynthesizerListener
import com.tencent.cloud.realtime.tts.RealTimeSpeechSynthesizerRequest
import com.tencent.cloud.realtime.tts.SpeechSynthesizerResponse
import com.tencent.cloud.realtime.tts.core.ws.Credential
import com.tencent.cloud.realtime.tts.core.ws.SpeechClient
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue

object TencentSpeechHelper {

    // 密钥变量（不再硬编码）
    private var APP_ID: String = ""
    private var SECRET_ID: String = ""
    private var SECRET_KEY: String = ""

    // 是否已加载密钥
    private var keyLoaded = false

    /**
     * 从 assets 加载密钥（第一次调用时自动执行）
     */
    private fun ensureKeysLoaded(context: Context) {
        if (keyLoaded) return
        try {
            val props = Properties()
            context.applicationContext.assets.open("tencent_tts.properties").use { stream ->
                props.load(stream)
            }
            APP_ID = props.getProperty("APP_ID", "")
            SECRET_ID = props.getProperty("SECRET_ID", "")
            SECRET_KEY = props.getProperty("SECRET_KEY", "")
            keyLoaded = true
            Log.d("TTS", "密钥加载成功 APP_ID=$APP_ID")
        } catch (e: Exception) {
            Log.e("TTS", "密钥加载失败，请检查 assets/tencent_tts.properties", e)
        }
    }

    // ---- SDK 实例 ----
    private var ttsProxy: SpeechClient? = null
    private var synthesizer: RealTimeSpeechSynthesizer? = null
    private var exoPlayer: ExoPlayer? = null

    // ---- 按标点切分 + 队列顺序播放 ----
    private val SENTENCE_SPLITTER = Regex("""[^。！？!?；;\n]+[。！？!?；;\n]*""")

    // 用 ArrayBlockingQueue 做线程安全的 FIFO 队列
    private val sentenceQueue = ArrayBlockingQueue<String>(128)
    private var queueProcessing = false

    /**
     * 长文本入口：按标点拆句 → 入队 → 顺序合成+播放。
     * 第一句合成完成后立即播放，同时下一句已在合成中。
     */
    fun speak(text: String, context: Context) {
        Log.e("TTS", ">>> speak 被调用, text='${text.take(80)}'")
        val segments = SENTENCE_SPLITTER.findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (segments.isEmpty()) {
            Log.e("TTS", "拆句结果为空，跳过")
            return
        }
        Log.e("TTS", "拆句完成，共 ${segments.size} 段: ${segments.joinToString(" | ") { it.take(20) }}")

        sentenceQueue.addAll(segments)
        startQueueProcessor(context.applicationContext)
    }

    /** 停止当前朗读队列（新消息到达时打断旧消息） */
    fun stop() {
        sentenceQueue.clear()
        synthesizer?.cancel()
        exoPlayer?.stop()
    }

    private fun startQueueProcessor(context: Context) {
        synchronized(this) {
            if (queueProcessing) return
            queueProcessing = true
        }
        processNext(context)
    }

    private fun processNext(context: Context) {
        val segment = sentenceQueue.poll()
        if (segment == null) {
            synchronized(this) { queueProcessing = false }
            Log.e("TTS", "队列播放完毕")
            return
        }
        Log.e("TTS", ">>> 队列播放: '${segment.take(40)}' (剩余 ${sentenceQueue.size})")
        synthesisAndPlay(segment, context) {
            processNext(context)
        }
    }

    // ---- 底层：单句合成+播放 ----
    private fun synthesisAndPlay(text: String, context: Context, onComplete: () -> Unit) {
        ensureKeysLoaded(context)
        if (APP_ID.isEmpty()) {
            Log.e("TTS", "密钥未配置，跳过")
            onComplete()
            return
        }
        if (text.isBlank()) {
            onComplete()
            return
        }

        Thread {
            try {
                if (ttsProxy == null) {
                    Log.e("TTS", "初始化 SpeechClient...")
                    ttsProxy = SpeechClient()
                    Log.e("TTS", "SpeechClient 初始化完成")
                }
                val credential = Credential(APP_ID, SECRET_ID, SECRET_KEY, null)
                val request = RealTimeSpeechSynthesizerRequest().apply {
                    this.text = text
                    volume = 0f
                    speed = 0f
                    codec = "mp3"
                    sampleRate = 16000
                    voiceType = 101021
                    enableSubtitle = false
                    emotionCategory = "neutral"
                    emotionIntensity = 100
                    sessionId = UUID.randomUUID().toString()
                }

                val listener = object : RealTimeSpeechSynthesizerListener() {
                    private var fullAudio = ByteArray(0)

                    override fun onSynthesisStart(response: SpeechSynthesizerResponse?) {
                        fullAudio = ByteArray(0)
                        Log.d("TTS", "合成开始: ${text.take(30)}")
                    }

                    override fun onAudioResult(buffer: ByteBuffer?) {
                        buffer?.let {
                            val chunk = ByteArray(it.remaining())
                            it.get(chunk)
                            val newArray = ByteArray(fullAudio.size + chunk.size)
                            System.arraycopy(fullAudio, 0, newArray, 0, fullAudio.size)
                            System.arraycopy(chunk, 0, newArray, fullAudio.size, chunk.size)
                            fullAudio = newArray
                        }
                    }

                    override fun onSynthesisEnd(response: SpeechSynthesizerResponse?) {
                        Log.d("TTS", "合成结束，音频大小：${fullAudio.size}")
                        if (fullAudio.isNotEmpty()) {
                            playAudioBytes(fullAudio, context, onComplete)
                        } else {
                            synthesizer = null
                            runOnUiThread(context, onComplete)
                        }
                    }

                    override fun onSynthesisFail(response: SpeechSynthesizerResponse?) {
                        Log.e("TTS", "合成失败: ${response?.message}")
                        synthesizer = null
                        runOnUiThread(context, onComplete)
                    }

                    override fun onSynthesisCancel() {
                        synthesizer = null
                        runOnUiThread(context, onComplete)
                    }

                    override fun onTextResult(response: SpeechSynthesizerResponse?) {}
                }

                synthesizer?.cancel()
                synthesizer = RealTimeSpeechSynthesizer(ttsProxy!!, credential, request, listener)
                synthesizer?.start()

            } catch (e: Throwable) {
                Log.e("TTS", "合成异常", e)
                synthesizer = null
                runOnUiThread(context, onComplete)
            }
        }.start()
    }

    /**
     * 播放音频字节数组，播放完毕后回调 onComplete
     */
    private fun playAudioBytes(bytes: ByteArray, context: Context, onComplete: () -> Unit) {
        try {
            val tempFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { it.write(bytes) }

            runOnUiThread(context) {
                exoPlayer?.release()
                exoPlayer = ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                Log.d("TTS", "播放结束")
                                synthesizer = null
                                onComplete()
                            }
                        }
                    })
                    setMediaItem(MediaItem.fromUri(tempFile.toURI().toString()))
                    prepare()
                    play()
                }
            }
        } catch (e: Exception) {
            Log.e("TTS", "播放失败", e)
            synthesizer = null
            onComplete()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun runOnUiThread(context: Context, action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            android.os.Handler(context.mainLooper).post { action() }
        }
    }
}
