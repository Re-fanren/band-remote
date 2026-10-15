package com.marvis.bandremote.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.marvis.bandremote.protocol.Protocol
import com.marvis.bandremote.ui.CursorPointer
import com.marvis.bandremote.util.Prefs

/**
 * ============================================================
 *  无障碍服务 —— 手势 / 系统操作执行核心
 * ============================================================
 *
 *  能力来源：AccessibilityService.dispatchGesture（API 24+，本工程 minSdk = 24）
 *
 *  支持动作：
 *    上滑 / 下滑 / 左滑 / 右滑 / 点击（坐标）
 *    返回 / 返回桌面 / 多任务 / 锁屏（API 28+）
 *    指针遥控（鼠标模式）：虚拟指针悬浮层 + 增量位移 + 指针处单击
 *
 *  ★ 该类被 android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" 保护，
 *    只能由系统绑定；外部通过 [instance] 取用（进程内单例）。
 *
 *  ★ 鼠标模式的可见指针由 [CursorPointer] 承载（需要「显示在其他应用上层」权限）；
 *    本类负责位移换算与点击注入，二者共同构成完整的指针遥控。
 */
class RemoteAccessibilityService : AccessibilityService() {

    /**
     * 滑动方向
     */
    enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

    companion object {
        private const val TAG = "RemoteA11yService"

        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        /** 无障碍服务是否已开启 */
        fun isRunning(): Boolean = instance != null

        /** 当前实例身份标识（诊断用） */
        fun instanceTag(): String =
            instance?.let { "live@" + Integer.toHexString(System.identityHashCode(it)) } ?: "null"
    }

    // ---------------- 生命周期 ----------------

    private fun trace(event: String) {
        try {
            Prefs(applicationContext).traceA11y(event)
        } catch (e: Exception) {
            Log.w(TAG, "trace 写入失败", e)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        trace("onServiceConnected pid=${android.os.Process.myPid()} tag=${instanceTag()}")
        Log.i(TAG, "无障碍服务已连接，dispatchGesture 可用")
        // 鼠标模式若处于开启状态（无障碍服务重启、系统回收后重连），恢复虚拟指针
        try {
            if (Prefs(applicationContext).mouseMode) {
                val ok = CursorPointer.show(this)
                trace("mouseMode 恢复指针 ok=$ok")
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复虚拟指针失败", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        trace("onUnbind 之前 tag=${instanceTag()}")
        CursorPointer.hide()
        instance = null
        Log.i(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        trace("onDestroy 之前 tag=${instanceTag()}")
        CursorPointer.hide()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 骨架暂不消费事件流；后续做「元素级点击 / 焦点跳转」时在此解析
        // 注意：服务配置里不放 android:accessibilityFlags，避免系统持续回灌事件流
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    // ---------------- 手势 ----------------

    /**
     * 按方向执行滑屏
     * @param durationMs 手势时长，过短可能被系统判定为滑动而非点击
     */
    fun performSwipe(direction: SwipeDirection, durationMs: Long = 240L): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        // 边缘留白，避免触发系统手势区（如边缘返回）
        val mx = w * 0.15f
        val my = h * 0.15f

        val p: FloatArray = when (direction) {
            SwipeDirection.UP -> floatArrayOf(w * 0.5f, h - my, w * 0.5f, my)
            SwipeDirection.DOWN -> floatArrayOf(w * 0.5f, my, w * 0.5f, h - my)
            SwipeDirection.LEFT -> floatArrayOf(w - mx, h * 0.5f, mx, h * 0.5f)
            SwipeDirection.RIGHT -> floatArrayOf(mx, h * 0.5f, w - mx, h * 0.5f)
        }
        return dispatchSwipe(p[0], p[1], p[2], p[3], durationMs)
    }

    /**
     * 指定起止点滑屏（鼠标模式 / 自定义手势使用）
     *
     * ★ 注意：dispatchGesture 的回调必须真的接住。
     *   原来传 (gesture, null, null)，只能知道"调用发出去了"，无法知道系统是否真的
     *   接收并执行了该手势 —— 表现为"日志显示 exec 成功但屏幕毫无反应"，无法定位。
     *   现在把 completed / cancelled 都落盘到 a11y_trace。
     */
    fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 240L): Boolean {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return try {
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    trace(
                        "gesture completed (${startX.toInt()},${startY.toInt()})->" +
                            "(${endX.toInt()},${endY.toInt()}) ${durationMs}ms"
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    trace(
                        "gesture CANCELLED (${startX.toInt()},${startY.toInt()})->" +
                            "(${endX.toInt()},${endY.toInt()}) ${durationMs}ms"
                    )
                }
            }
            dispatchGesture(gesture, cb, null)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchSwipe 失败", e)
            trace("gesture EXCEPTION ${e.message}")
            false
        }
    }

    /**
     * 单击指定坐标
     */
    fun performClick(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "performClick 失败", e)
            false
        }
    }

    // ---------------- 归一化坐标 -> 屏幕像素（CLICK 坐标映射） ----------------

    /**
     * 取当前旋转方向下的「真实屏幕」尺寸（含状态栏 / 导航栏）。
     *
     * 说明：Display.getRealMetrics 返回的 widthPixels / heightPixels 会随屏幕旋转交换，
     * 即始终是「当前所见方向」的宽高；dispatchGesture 的坐标系同样是整屏物理像素坐标系，
     * 因此这里直接使用该尺寸。
     */
    private fun currentDisplaySize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display: Display? = wm?.defaultDisplay
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
        } else {
            dm.setTo(resources.displayMetrics)
        }
        return dm.widthPixels to dm.heightPixels
    }

    /** 当前屏幕旋转（Surface.ROTATION_0 / 90 / 180 / 270） */
    private fun currentRotation(): Int {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display: Display = wm?.defaultDisplay ?: return Surface.ROTATION_0
        @Suppress("DEPRECATION")
        return display.rotation
    }

    /**
     * 归一化坐标（0~1）-> 当前屏幕物理像素坐标
     *
     * 入参基准：手环手势区被视作手机**自然竖屏**方向的缩略图（nx 沿自然宽、ny 沿自然高），
     * 因此需要按屏幕旋转做一次坐标系换算：
     *
     *   ROTATION_0   : ( nx*w,     ny*h     )
     *   ROTATION_90  : ( (1-ny)*w, nx*h     )
     *   ROTATION_180 : ( (1-nx)*w, (1-ny)*h )
     *   ROTATION_270 : ( ny*w,     (1-nx)*h )
     *
     * 其中 w / h 为**当前旋转方向下**的屏幕宽高（已随旋转交换）。
     * 若个别 ROM 的旋转方向相反（点击落点呈镜像），只需把 ROTATION_90 与
     * ROTATION_270 两个分支的表达式对调。
     *
     * 返回值已按屏幕边界裁剪，避免 dispatchGesture 因坐标越界抛异常。
     */
    fun normalizedToPixel(nx: Float, ny: Float): PointF {
        val cx = nx.coerceIn(0f, 1f)
        val cy = ny.coerceIn(0f, 1f)
        val (w, h) = currentDisplaySize()
        val wf = w.toFloat()
        val hf = h.toFloat()
        val p = when (currentRotation()) {
            Surface.ROTATION_90 -> PointF((1f - cy) * wf, cx * hf)
            Surface.ROTATION_180 -> PointF((1f - cx) * wf, (1f - cy) * hf)
            Surface.ROTATION_270 -> PointF(cy * wf, (1f - cx) * hf)
            else -> PointF(cx * wf, cy * hf)
        }
        p.x = p.x.coerceIn(0f, wf - 1f)
        p.y = p.y.coerceIn(0f, hf - 1f)
        return p
    }

    /**
     * 在归一化坐标处点击（手环 CLICK 默认路径，非鼠标模式）
     */
    fun performClickNormalized(nx: Float, ny: Float): Boolean {
        val p = normalizedToPixel(nx, ny)
        Log.i(TAG, "CLICK 归一化($nx, $ny) -> 像素(${p.x}, ${p.y}) rotation=${currentRotation()}")
        syncCursorTo(p.x, p.y)
        return performClick(p.x, p.y)
    }

    // ---------------- 指针遥控（鼠标模式） ----------------

    /**
     * 开关鼠标模式。
     *
     * 开启：显示屏幕虚拟指针；关闭：隐藏指针。
     * 「鼠标模式」标志位由 CommandDispatcher 写入 Prefs，此处只负责指针的显示 / 隐藏。
     *
     * @return 开启时需要悬浮窗权限，权限缺失返回 false（调用方按 ERR:EXEC 回执）
     */
    fun setMouseMode(on: Boolean): Boolean {
        return if (on) {
            val ok = CursorPointer.show(this)
            if (ok) {
                Log.i(TAG, "鼠标模式已开启，虚拟指针就绪")
            } else {
                Log.w(TAG, "鼠标模式开启失败：缺少「显示在其他应用上层」权限")
            }
            ok
        } else {
            CursorPointer.hide()
            Log.i(TAG, "鼠标模式已关闭，虚拟指针已隐藏")
            true
        }
    }

    /** 鼠标模式是否处于激活状态（虚拟指针正在显示） */
    fun isMouseModeActive(): Boolean = CursorPointer.isShowing()

    /**
     * 指针按增量移动（手环 CURSOR_MOVE）。
     *
     * 手环上报的是「自上次上报以来的位移增量」，单位为手环手势区像素；
     * 这里按 [cursorGain] 等比换算成手机屏幕像素后叠加到当前指针位置。
     *
     * 指针尚未显示时会尝试自动显示（手环在鼠标模式下本就会先发 CURSOR_ON，
     * 这里是丢帧 / 状态漂移时的兜底）。
     *
     * @return 指针是否成功移动
     */
    fun moveCursor(bandDx: Float, bandDy: Float): Boolean {
        if (!CursorPointer.isShowing() && !CursorPointer.show(this)) {
            Log.w(TAG, "CURSOR_MOVE 被忽略：虚拟指针不可用（缺「显示在其他应用上层」权限）")
            return false
        }
        val gain = cursorGain()
        val p = CursorPointer.moveBy(bandDx * gain, bandDy * gain) ?: return false
        Log.d(TAG, "指针移动 手环($bandDx, $bandDy) x$gain -> (${p.x}, ${p.y})")
        return true
    }

    /**
     * 在指针当前位置单击（鼠标模式下的手环点按）。
     *
     * @return 指针未显示或注入失败返回 false
     */
    fun clickAtCursor(): Boolean {
        val p = CursorPointer.position()
        if (p == null) {
            Log.w(TAG, "鼠标模式单击失败：指针未显示")
            return false
        }
        Log.i(TAG, "鼠标模式单击 @ (${p.x}, ${p.y})")
        return performClick(p.x, p.y)
    }

    /**
     * 坐标型点击时把指针同步到点击处，保证「指针所见即点击处」。
     * 非鼠标模式下为无操作。
     */
    fun syncCursorTo(x: Float, y: Float) {
        if (CursorPointer.isShowing()) {
            CursorPointer.warpTo(x, y)
        }
    }

    /**
     * 位移增益：把「手环手势区像素」等比映射到「手机屏幕像素」。
     *
     *   gain = 当前屏幕宽度 / 手环设计宽度(192)
     *
     * 即手指滑满手环手势区宽度 ≈ 指针滑满手机屏幕宽度；若按 1:1 映射，
     * 手环 192px 的设计像素落到 1220px 的屏幕上会让指针爬行、难以操作。
     * 系数做了上下限裁剪，防止极端分辨率下增益失控。
     */
    private fun cursorGain(): Float {
        val (w, _) = currentDisplaySize()
        return (w.toFloat() / Protocol.BAND_DESIGN_WIDTH).coerceIn(0.5f, 20f)
    }

    // ---------------- 系统导航 ----------------

    /** 返回 */
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 返回桌面 */
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 多任务（最近任务） */
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** 下拉通知栏 */
    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * 锁屏（需要 Android 9.0 / API 28 及以上）
     */
    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        Log.w(TAG, "锁屏需要 Android 9.0 及以上，当前 API = ${Build.VERSION.SDK_INT}")
        return false
    }
}
