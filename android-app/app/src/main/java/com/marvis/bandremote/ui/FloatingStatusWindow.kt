package com.marvis.bandremote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.marvis.bandremote.R
import com.marvis.bandremote.command.CommandDispatcher
import com.marvis.bandremote.transport.LinkState

/**
 * ============================================================
 *  悬浮窗状态提示
 * ============================================================
 *
 *  - 常驻桌面小条：显示链路状态 + 最近一条手环指令；
 *  - 支持拖动（按住小条移动）；
 *  - 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW）。
 */
object FloatingStatusWindow {

    private const val TAG = "FloatingStatusWindow"

    private val main = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null

    private val callback = object : CommandDispatcher.StatusCallback {
        override fun onLinkState(state: LinkState, detail: String) {
            update("链路：" + stateLabel(state) + if (detail.isNotEmpty()) " · $detail" else "")
        }

        override fun onCommand(cmdText: String, ok: Boolean) {
            update("指令：" + cmdText + if (ok) " ✓" else " ✗")
        }
    }

    /** 是否已展示 */
    fun isShowing(): Boolean = rootView != null

    /** 是否有悬浮窗权限 */
    fun canDraw(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (rootView != null) {
            return
        }
        if (!canDraw(context)) {
            Log.w(TAG, "缺少悬浮窗权限，跳过展示")
            return
        }
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val view = LayoutInflater.from(app).inflate(R.layout.view_floating_status, null)
        statusText = view.findViewById(R.id.tv_float_status)
        statusText?.text = "链路：未连接"

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 140
        }

        // 拖动支持
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    try {
                        wm.updateViewLayout(view, lp)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateViewLayout 失败", e)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, lp)
            rootView = view
            windowManager = wm
            layoutParams = lp
            // 注册状态回调，实时刷新
            com.marvis.bandremote.App.instance.dispatcher.addStatusCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮窗失败", e)
            rootView = null
        }
    }

    fun hide() {
        val view = rootView ?: return
        try {
            com.marvis.bandremote.App.instance.dispatcher.removeStatusCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "反注册回调失败", e)
        }
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "移除悬浮窗失败", e)
        }
        rootView = null
        windowManager = null
        layoutParams = null
        statusText = null
    }

    /** 直接更新文本（主线程安全） */
    fun update(text: String) {
        main.post { statusText?.text = text }
    }

    private fun stateLabel(state: LinkState): String = when (state) {
        LinkState.CONNECTED -> "已连接"
        LinkState.CONNECTING -> "连接中"
        LinkState.MOCK -> "本地模拟"
        LinkState.DISCONNECTED -> "未连接"
    }
}
