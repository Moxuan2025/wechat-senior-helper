package com.example.wechat_senior_helper.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine {

    private val chineseRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private val latinRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, preferChinese: Boolean = true): Text {
        val image = InputImage.fromBitmap(bitmap, 0)
        val first = if (preferChinese) chineseRecognizer else latinRecognizer
        val second = if (preferChinese) latinRecognizer else chineseRecognizer

        val firstResult = runCatching { first.process(image).await() }.getOrNull()
        if (firstResult != null && firstResult.text.isNotBlank()) return firstResult

        val secondResult = second.process(image).await()
        return secondResult
    }

    fun close() {
        chineseRecognizer.close()
        latinRecognizer.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
