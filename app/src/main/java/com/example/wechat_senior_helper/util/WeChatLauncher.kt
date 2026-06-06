package com.example.wechat_senior_helper.util

import android.content.Context
import android.content.Intent
import android.util.Log

object WeChatLauncher {
    private const val TAG = "WeChatLauncher"
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /**
     * 通过 Intent 启动微信
     */
    fun launchWeChat(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.e(TAG, "启动微信")
            } else {
                Log.e(TAG, "未找到微信")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动微信失败", e)
        }
    }
}
