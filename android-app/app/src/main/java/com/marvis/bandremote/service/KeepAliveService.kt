package com.marvis.bandremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.marvis.bandremote.App
import com.marvis.bandremote.MainActivity
import com.marvis.bandremote.R
import com.marvis.bandremote.ui.FloatingStatusWindow

/**
 * ============================================================
 *  前台服务 —— 链路保活 + 悬浮窗状态提示
 * ============================================================
 *
 *  说明：
 *    - 前台服务保证进程常驻，长时间等待手环指令不被系统回收；
 *    - 启动时同步拉起传输通道与悬浮窗；
 *    - 通知点击可回到主界面。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "band_remote_link"
        private const val CHANNEL_NAME = "手环遥控链路"
        private const val NOTIFY_ID = 1001

        const val ACTION_START = "com.marvis.bandremote.action.START"
        const val ACTION_STOP = "com.marvis.bandremote.action.STOP"

        @Volatile
        var running: Boolean = false
            private set

        /** 启动保活服务 */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止保活服务 */
        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        running = true
        // 确保传输通道已在运行
        App.instance.ensureTransportStarted()
        // 展示悬浮窗状态
        FloatingStatusWindow.show(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        FloatingStatusWindow.hide()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.keep_alive_text))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFY_ID, notification, type)
        } catch (e: Exception) {
            // API 34 若未声明对应类型会抛异常，此处兜底为普通通知避免崩溃
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持手环与手机之间的遥控链路"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
