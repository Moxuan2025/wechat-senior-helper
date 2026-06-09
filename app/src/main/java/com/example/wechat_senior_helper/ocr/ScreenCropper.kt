package com.example.wechat_senior_helper.ocr

import android.graphics.Bitmap

object ScreenCropper {

    fun cropSearchResultArea(full: Bitmap): Bitmap {
        val top = (full.height * 0.16f).toInt().coerceAtLeast(0)
        val bottom = (full.height * 0.90f).toInt().coerceAtMost(full.height)
        return cropVertical(full, top, bottom)
    }

    fun cropChatMessageArea(full: Bitmap): Bitmap {
        val top = (full.height * 0.14f).toInt().coerceAtLeast(0)
        val bottom = (full.height * 0.88f).toInt().coerceAtMost(full.height)
        return cropVertical(full, top, bottom)
    }

    fun cropBottomArea(full: Bitmap): Bitmap {
        val top = (full.height * 0.78f).toInt().coerceAtLeast(0)
        val bottom = full.height
        return cropVertical(full, top, bottom)
    }

    private fun cropVertical(full: Bitmap, top: Int, bottom: Int): Bitmap {
        val safeTop = top.coerceIn(0, full.height - 1)
        val safeBottom = bottom.coerceIn(safeTop + 1, full.height)
        return Bitmap.createBitmap(
            full,
            0,
            safeTop,
            full.width,
            safeBottom - safeTop
        )
    }
}
