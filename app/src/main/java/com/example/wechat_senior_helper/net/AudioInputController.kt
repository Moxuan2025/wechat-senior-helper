package com.example.wechat_senior_helper.net

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioInputController(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File {
        val dir = File(context.cacheDir, "voice_inputs")
        if (!dir.exists()) dir.mkdirs()

        outputFile = File(dir, "voice_${System.currentTimeMillis()}.m4a")

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }

        recorder = mediaRecorder
        return outputFile!!
    }

    fun stopRecording(): File? {
        val r = recorder ?: return outputFile
        return try {
            r.stop()
            outputFile
        } catch (_: Exception) {
            outputFile?.delete()
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {}
            recorder = null
        }
    }

    fun isRecording(): Boolean = recorder != null
}
