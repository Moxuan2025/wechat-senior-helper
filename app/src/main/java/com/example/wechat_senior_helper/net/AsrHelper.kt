package com.example.wechat_senior_helper.net

import android.content.Context
import android.util.Log
import com.tencent.aai.AAIClient
import com.tencent.aai.auth.LocalCredentialProvider
import com.tencent.aai.exception.ClientException
import com.tencent.aai.exception.ServerException
import com.tencent.aai.listener.AudioRecognizeResultListener
import com.tencent.aai.listener.AudioRecognizeStateListener
import com.tencent.aai.model.AudioRecognizeConfiguration
import com.tencent.aai.model.AudioRecognizeRequest
import com.tencent.aai.model.AudioRecognizeResult
import com.tencent.aai.audio.data.AudioRecordDataSource
import com.tencent.cloud.qcloudasrsdk.filerecognize.QCloudFileRecognizer
import com.tencent.cloud.qcloudasrsdk.filerecognize.QCloudFileRecognizerListener
import com.tencent.cloud.qcloudasrsdk.filerecognize.common.QCloudSourceType
import com.tencent.cloud.qcloudasrsdk.filerecognize.param.QCloudFileRecognitionParams
import java.io.File
import java.util.Properties

object AsrHelper {

    private const val TAG = "AsrHelper"

    private var APP_ID: String = ""
    private var SECRET_ID: String = ""
    private var SECRET_KEY: String = ""

    private var keyLoaded = false
    private var aaiClient: AAIClient? = null
    private var cancelled = false

    private fun ensureKeysLoaded(context: Context) {
        if (keyLoaded) return
        try {
            val props = Properties()
            context.applicationContext.assets.open("asr.properties").use { stream ->
                props.load(stream)
            }
            APP_ID = props.getProperty("APP_ID", "")
            SECRET_ID = props.getProperty("SECRET_ID", "")
            SECRET_KEY = props.getProperty("SECRET_KEY", "")
            keyLoaded = true
            Log.d(TAG, "ASR密钥加载成功 APP_ID=$APP_ID")
        } catch (e: Exception) {
            Log.e(TAG, "ASR密钥加载失败，请检查 assets/asr.properties", e)
        }
    }

    fun startRecognize(
        context: Context,
        engineModelType: String = "16k_zh",
        onStart: () -> Unit = {},
        onRecognizing: (text: String) -> Unit = {},
        onComplete: (text: String) -> Unit = {},
        onError: (message: String) -> Unit = {}
    ) {
        ensureKeysLoaded(context)
        if (APP_ID.isEmpty() || SECRET_ID.isEmpty() || SECRET_KEY.isEmpty()) {
            onError("密钥未配置")
            return
        }

        stopRecognize()
        cancelled = false

        try {
            val credentialProvider = LocalCredentialProvider(SECRET_KEY)
            aaiClient = AAIClient(context.applicationContext, APP_ID.toInt(), 0, SECRET_ID, credentialProvider)

            val request = AudioRecognizeRequest.Builder()
                .pcmAudioDataSource(AudioRecordDataSource(false))
                .setEngineModelType(engineModelType)
                .build()

            var finalResultHandled = false

            val listener = object : AudioRecognizeResultListener {
                override fun onSliceSuccess(
                    request: AudioRecognizeRequest,
                    result: AudioRecognizeResult,
                    seq: Int
                ) {
                    val text = result.text ?: ""
                    if (text.isNotBlank()) {
                        Log.d(TAG, "实时识别: $text")
                        onRecognizing(text)
                    }
                }

                override fun onSegmentSuccess(
                    request: AudioRecognizeRequest,
                    result: AudioRecognizeResult,
                    seq: Int
                ) {
                    Log.d(TAG, "稳定片段: ${result.text}")
                }

                override fun onSuccess(request: AudioRecognizeRequest, result: String) {
                    if (finalResultHandled) return
                    finalResultHandled = true
                    Log.d(TAG, "识别完成: $result")
                    if (!cancelled) onComplete(result)
                }

                override fun onFailure(
                    request: AudioRecognizeRequest,
                    clientException: ClientException,
                    serverException: ServerException,
                    response: String
                ) {
                    if (finalResultHandled) return
                    finalResultHandled = true
                    val msg = serverException.message ?: clientException.message ?: "识别失败"
                    Log.e(TAG, "识别失败: $msg")
                    if (!cancelled) onError(msg)
                }
            }

            val stateListener = object : AudioRecognizeStateListener {
                override fun onStartRecord(request: AudioRecognizeRequest) {
                    Log.d(TAG, "开始录音")
                    onStart()
                }
                override fun onStopRecord(request: AudioRecognizeRequest) { Log.d(TAG, "停止录音") }
                override fun onVoiceVolume(request: AudioRecognizeRequest, volume: Int) {}
                override fun onVoiceDb(db: Float) {}
                override fun onNextAudioData(audioData: ShortArray?, dataLength: Int) {}
                override fun onSilentDetectTimeOut() {}
            }

            val config = AudioRecognizeConfiguration.Builder()
                .setSilentDetectTimeOut(false)
                .build()

            Thread {
                aaiClient?.startAudioRecognize(request, listener, stateListener, config)
            }.start()

        } catch (e: ClientException) {
            Log.e(TAG, "初始化异常", e)
            onError("初始化失败: ${e.message}")
        }
    }

    /**
     * 文件识别：将已录制的音频文件送 ASR 识别，拿到文本后回调 onComplete。
     */
    fun recognizeFile(
        audioFile: File,
        context: Context,
        onComplete: (text: String) -> Unit = {},
        onError: (message: String) -> Unit = {}
    ) {
        ensureKeysLoaded(context)
        if (APP_ID.isEmpty()) { onError("密钥未配置"); return }
        if (!audioFile.exists()) { onError("音频文件不存在"); return }

        Thread {
            try {
                val recognizer = QCloudFileRecognizer(APP_ID, SECRET_ID, SECRET_KEY)
                var completed = false

                recognizer.setCallback(object : QCloudFileRecognizerListener {
                    override fun recognizeResult(
                        r: QCloudFileRecognizer?,
                        requestId: Long,
                        result: String?,
                        status: Int,
                        exception: Exception?
                    ) {
                        if (completed) return
                        completed = true
                        if (exception != null) {
                            Log.e(TAG, "文件识别失败: ${exception.message}", exception)
                            onError("识别失败: ${exception.message}")
                            return
                        }
                        val text = result?.trim().orEmpty()
                        Log.e(TAG, "文件识别完成: '$text'")
                        onComplete(text)
                    }
                })

                val bytes = audioFile.readBytes()
                val params = QCloudFileRecognitionParams().apply {
                    sourceType = QCloudSourceType.QCloudSourceTypeData
                    data = bytes
                    engineModelType = "16k_zh"
                }

                recognizer.recognize(params)
                Log.e(TAG, "文件识别请求已发送，文件=${audioFile.name} (${bytes.size} bytes)")

            } catch (e: Exception) {
                Log.e(TAG, "文件识别异常", e)
                onError("识别异常: ${e.message}")
            }
        }.start()
    }

    fun stopRecognize() {
        Thread { aaiClient?.stopAudioRecognize() }.start()
    }

    fun cancelRecognize() {
        cancelled = true
        Thread { aaiClient?.cancelAudioRecognize() }.start()
        aaiClient = null
    }

    fun destroy() {
        cancelRecognize()
        aaiClient = null
    }
}
