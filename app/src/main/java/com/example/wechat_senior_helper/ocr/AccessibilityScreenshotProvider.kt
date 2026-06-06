package com.example.wechat_senior_helper.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AccessibilityScreenshotProvider(
    private val service: AccessibilityService
) {
    suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resumeWithException(
                UnsupportedOperationException("takeScreenshot requires API 30+")
            )
            return@suspendCancellableCoroutine
        }

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(service),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?: throw IllegalStateException("wrapHardwareBuffer returned null")

                        val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()

                        val file = ScreenshotDebugStore.save(service, bitmap, "debug")
                        Log.e("OCR_DEBUG", "路径: ${file.absolutePath}")

                        if (cont.isActive) cont.resume(bitmap)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("takeScreenshot failed: $errorCode")
                        )
                    }
                }
            }
        )
    }
}
