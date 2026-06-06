package com.example.wechat_senior_helper.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截图调试存储器
 * 将截图保存到外部存储便于 adb pull / 文件管理器查看
 */
object ScreenshotDebugStore {

    private const val TAG = "ScreenshotDebugStore"

    fun save(context: Context, bitmap: Bitmap, tag: String = "debug"): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir

        val screenshotsDir = File(dir, "ocr_debug")
        if (!screenshotsDir.exists()) screenshotsDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(screenshotsDir, "${tag}_${timestamp}.png")

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        Log.e(TAG, "截图已保存: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }
}
