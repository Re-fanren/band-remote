package com.marvis.bandremote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.marvis.bandremote.util.Prefs

/**
 * 开机自启接收器：根据「自启动」设置拉起保活服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val prefs = Prefs(context)
        if (!prefs.autoStart) {
            Log.i(TAG, "未开启自启动，忽略")
            return
        }
        Log.i(TAG, "开机完成，拉起保活服务")
        try {
            KeepAliveService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "拉起保活服务失败", e)
        }
    }
}
