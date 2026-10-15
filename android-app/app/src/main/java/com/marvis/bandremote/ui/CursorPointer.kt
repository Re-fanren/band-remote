package com.marvis.bandremote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * ============================================================
 *  虚拟指针（鼠标模式）悬浮层
 * ============================================================
 *
 *  手环端在鼠标模式下用 CURSOR_MOVE 持续上报位移（见 docs/PROTOCOL.md 2.4），
 *  本对象把位移落到一个圆形悬浮窗上，形成手机屏幕上「可见、可移动、可点击」的指针。
 *
 *  设计要点：
 *    1. 只作展示：窗口带 FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE，
 *       不拦截触摸、不抢焦点；真正的点击由无障碍服务 dispatchGesture
 *       在指针坐标处注入，二者互不干扰；
 *    2. 增量语义：moveBy 在当前坐标上叠加增量，并裁剪到屏幕内，
 *       指针中心可到达屏幕任意一点（含四角），圆点允许被屏幕边缘裁掉一半；
 *    3. 帧内合并：手环每秒可能上报数十帧位移，本对象按约一帧（16ms）
 *       合并成一次 updateViewLayout，避免主线程被高频布局抖动打满；
 *    4. 权限降级：未授予「显示在其他应用上层」时 show() 返回 false，
 *       由调用方回 ERR:EXEC 并在日志中留痕，不影响其他指令。
 *
 *  线程模型：moveBy / warpTo 可从通道线程调用；内部对指针坐标加锁，
 *  视图更新统一 post 到主线程执行。
 */
object CursorPointer {

    private const val TAG = "CursorPointer"

    /** 指针直径（dp） */
    private const val DIAMETER_DP = 26

    /** 位移合并窗口（ms），约一个渲染帧 */
    private const val COALESCE_MS = 16L

    private val main = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 指针直径（像素），显示时确定 */
    @Volatile
    private var sizePx = 0

    /** 指针中心的屏幕坐标（物理像素）；< 0 表示尚未定位 */
    private var centerX = -1f
    private var centerY = -1f

    /** 是否已排入一次待执行的视图刷新 */
    private var flushScheduled = false

    // ---------------- 状态查询 ----------------

    fun isShowing(): Boolean = view != null

    /** 是否具备「显示在其他应用上层」权限 */
    fun canDraw(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            Log.w(TAG, "canDrawOverlays 查询失败", e)
            false
        }
    }

    /** 当前指针中心坐标；指针未显示时返回 null */
    @Synchronized
    fun position(): PointF? = if (view != null && centerX >= 0f) PointF(centerX, centerY) else null

    // ---------------- 显示 / 隐藏 ----------------

    /**
     * 显示指针（幂等）。重复调用不会重复添加窗口。
     *
     * @return 是否已处于显示状态；缺少悬浮窗权限或添加窗口失败时返回 false
     */
    @Synchronized
    fun show(context: Context): Boolean {
        if (view != null) {
            return true
        }
        val app = context.applicationContext
        if (!canDraw(app)) {
            Log.w(TAG, "缺少「显示在其他应用上层」权限，虚拟指针不可用")
            return false
        }
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            Log.w(TAG, "WindowManager 不可用，虚拟指针不可用")
            return false
        }

        val size = dpToPx(app, DIAMETER_DP)
        val (screenW, screenH) = screenSize(windowManager)
        // 首次显示放在屏幕正中；再次显示（如无障碍重启）沿用上次位置
        if (centerX < 0f || centerY < 0f) {
            centerX = screenW / 2f
            centerY = screenH / 2f
        }
        clampCenter(screenW, screenH)

        val params = buildLayoutParams(size)
        params.x = (centerX - size / 2f).toInt()
        params.y = (centerY - size / 2f).toInt()

        val v = PointerView(app, size)
        return try {
            windowManager.addView(v, params)
            view = v
            wm = windowManager
            lp = params
            sizePx = size
            Log.i(TAG, "虚拟指针已显示 center=($centerX, $centerY) size=${size}px")
            true
        } catch (e: Exception) {
            Log.e(TAG, "添加虚拟指针窗口失败", e)
            false
        }
    }

    /** 隐藏指针（幂等）。保留最后坐标，便于下次显示时原地恢复 */
    @Synchronized
    fun hide() {
        val v = view ?: return
        val windowManager = wm
        view = null
        wm = null
        lp = null
        try {
            windowManager?.removeView(v)
            Log.i(TAG, "虚拟指针已隐藏")
        } catch (e: Exception) {
            Log.w(TAG, "移除虚拟指针窗口失败", e)
        }
    }

    // ---------------- 移动 ----------------

    /**
     * 指针按增量移动
     *
     * @param dx 横向增量（屏幕物理像素，可负）
     * @param dy 纵向增量（屏幕物理像素，可负）
     * @return 移动后的指针中心坐标；指针未显示时返回 null
     */
    @Synchronized
    fun moveBy(dx: Float, dy: Float): PointF? {
        if (view == null) {
            return null
        }
        val windowManager = wm
        if (windowManager != null) {
            val (screenW, screenH) = screenSize(windowManager)
            centerX += dx
            centerY += dy
            clampCenter(screenW, screenH)
        }
        scheduleFlush()
        return PointF(centerX, centerY)
    }

    /**
     * 把指针直接挪到指定屏幕坐标。
     * 用于「带坐标的 CLICK」：先让指针落到点击处，保证指针所见即点击处。
     *
     * @return 移动后的指针中心坐标；指针未显示时返回 null
     */
    @Synchronized
    fun warpTo(x: Float, y: Float): PointF? {
        if (view == null) {
            return null
        }
        val windowManager = wm ?: return null
        val (screenW, screenH) = screenSize(windowManager)
        centerX = x
        centerY = y
        clampCenter(screenW, screenH)
        scheduleFlush()
        return PointF(centerX, centerY)
    }

    // ---------------- 内部实现 ----------------

    @SuppressLint("RtlHardcoded")
    private fun buildLayoutParams(size: Int): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            size,
            size,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /**
     * 取当前旋转方向下的真实屏幕尺寸（与 dispatchGesture 坐标系一致，
     * 旋转时宽高自动交换）
     */
    @Suppress("DEPRECATION")
    private fun screenSize(windowManager: WindowManager): Pair<Float, Float> {
        return try {
            val dm = DisplayMetrics()
            val display = windowManager.defaultDisplay
            if (display != null) {
                display.getRealMetrics(dm)
                dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
            } else {
                1080f to 1920f
            }
        } catch (e: Exception) {
            Log.w(TAG, "取屏幕尺寸失败，使用兜底值", e)
            1080f to 1920f
        }
    }

    /**
     * 指针中心裁剪到屏幕内。
     * 这里以「中心可到达任意一点」为准则（含状态栏、导航栏与四角），
     * 圆点被屏幕边缘裁掉一半属于预期表现。
     */
    private fun clampCenter(screenW: Float, screenH: Float) {
        centerX = centerX.coerceIn(0f, screenW)
        centerY = centerY.coerceIn(0f, screenH)
    }

    /** 合并同一帧内的多次位移请求：一帧只做一次 updateViewLayout */
    private fun scheduleFlush() {
        if (flushScheduled) {
            return
        }
        flushScheduled = true
        main.postDelayed({ flush() }, COALESCE_MS)
    }

    @Synchronized
    private fun flush() {
        flushScheduled = false
        val v = view ?: return
        val params = lp ?: return
        val windowManager = wm ?: return
        val half = sizePx / 2f
        params.x = (centerX - half).toInt()
        params.y = (centerY - half).toInt()
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: Exception) {
            Log.w(TAG, "指针位置刷新失败", e)
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)

    /**
     * 指针外观：深色外环（浅色背景可见）+ 白色内环 + 品牌蓝实心点
     */
    private class PointerView(context: Context, private val diameter: Int) : View(context) {

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66000000")
            style = Paint.Style.STROKE
            strokeWidth = diameter * 0.18f
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = diameter * 0.10f
        }

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4F8EF7")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            val c = diameter / 2f
            val outer = c - shadowPaint.strokeWidth / 2f
            canvas.drawCircle(c, c, outer, shadowPaint)
            canvas.drawCircle(c, c, outer * 0.82f, ringPaint)
            canvas.drawCircle(c, c, outer * 0.40f, dotPaint)
        }
    }
}
