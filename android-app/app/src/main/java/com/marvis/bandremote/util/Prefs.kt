package com.marvis.bandremote.util

import android.content.Context

/**
 * 轻量配置存储（SharedPreferences 封装）
 */
class Prefs(context: Context) {

    companion object {
        private const val SP_NAME = "band_remote_prefs"

        private const val KEY_MOUSE_MODE = "mouse_mode"
        private const val KEY_SHORTCUT_PKG = "shortcut_package"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_TRANSPORT = "transport_mode"
        private const val KEY_FLOAT_WINDOW = "float_window"
        private const val KEY_A11Y_TRACE = "a11y_trace"
        private const val KEY_LINK_TRACE = "link_trace"
        private const val KEY_LINK_DETAIL = "link_detail"

        /** 通道模式常量 */
        const val TRANSPORT_MOCK = "mock"
        const val TRANSPORT_BLE = "ble"
        const val TRANSPORT_INTERCONNECT = "interconnect"
    }

    private val sp = context.applicationContext
        .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 鼠标（指针）模式开关 */
    var mouseMode: Boolean
        get() = sp.getBoolean(KEY_MOUSE_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_MOUSE_MODE, value).apply()

    /** 自定义快捷启动应用包名 */
    var shortcutPackage: String
        get() = sp.getString(KEY_SHORTCUT_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_SHORTCUT_PKG, value).apply()

    /** 开机自启动 */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 传输通道模式：mock / ble / interconnect */
    var transportMode: String
        // ★ 默认改为小米互联：SDK（xms-wearable-lib_1.4）已随包分发，
        //   这是手环 9 上唯一可用的通道；找不到 SDK 时 InterconnectTransport 会自动降级提示。
        get() = sp.getString(KEY_TRANSPORT, TRANSPORT_INTERCONNECT) ?: TRANSPORT_INTERCONNECT
        set(value) = sp.edit().putString(KEY_TRANSPORT, value).apply()

    /** 是否展示悬浮窗状态提示 */
    var floatingWindow: Boolean
        get() = sp.getBoolean(KEY_FLOAT_WINDOW, true)
        set(value) = sp.edit().putBoolean(KEY_FLOAT_WINDOW, value).apply()

    /** 无障碍服务事件追踪（诊断用，仅保留最近 12 条） */
    var a11yTrace: String
        get() = sp.getString(KEY_A11Y_TRACE, "") ?: ""
        set(value) = sp.edit().putString(KEY_A11Y_TRACE, value).apply()

    /** 追加一条无障碍事件记录 */
    fun traceA11y(event: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val lines = a11yTrace.split("\n").filter { it.isNotBlank() }
        a11yTrace = (lines + "$ts $event").takeLast(12).joinToString("\n")
    }

    /**
     * 链路状态追踪（诊断用，仅保留最近 15 条）。
     *
     * 为什么需要它：本机 logcat 读不到，且界面只显示"未连接"这类笼统状态，
     * 无法区分是「没发现节点 / 权限被拒 / 监听注册失败」等哪一种；
     * 落盘后可经 adb `run-as com.marvis.bandremote cat` 直接读出确切原因。
     */
    var linkTrace: String
        get() = sp.getString(KEY_LINK_TRACE, "") ?: ""
        set(value) = sp.edit().putString(KEY_LINK_TRACE, value).apply()

    /** 最近一次链路状态说明（界面直接展示，便于真机自查） */
    var linkDetail: String
        get() = sp.getString(KEY_LINK_DETAIL, "") ?: ""
        set(value) = sp.edit().putString(KEY_LINK_DETAIL, value).apply()

    fun traceLink(event: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val lines = linkTrace.split("\n").filter { it.isNotBlank() }
        // ★ 保留 60 条而非 15 条：周期探活每 3s 一条，15 条只能覆盖 45 秒，
        //   会把更早的 WEARAPP / SEND_FAIL / TASK_TIMEOUT 等关键记录全部冲掉，
        //   导致"看不到失败原因"，只能反复猜。
        linkTrace = (lines + "$ts $event").takeLast(60).joinToString("\n")
        linkDetail = event
    }
}
