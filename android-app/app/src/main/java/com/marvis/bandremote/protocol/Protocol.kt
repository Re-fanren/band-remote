package com.marvis.bandremote.protocol

import java.util.Locale

/**
 * ============================================================
 *  双端通信协议（手机端）—— 与手环端 common/protocol.js 保持一致
 * ============================================================
 *
 * 帧格式（UTF-8 文本，行分隔）：
 *
 *      BR1|<CMD>|<SEQ>|<ARG>\n
 *
 * 完整指令字对照表见 docs/PROTOCOL.md
 */
object Protocol {

    /** 协议魔数 + 版本 */
    const val MAGIC = "BR1"

    /** 字段分隔符 */
    const val SEP = "|"

    /** 帧结束符 */
    const val EOL = "\n"

    /**
     * 解析后的单帧
     */
    data class Frame(
        val magic: String,
        val cmd: String,
        val seq: Int,
        val arg: String
    ) {
        /** 中文说明，便于日志与悬浮窗展示 */
        val text: String get() = CmdText.of(cmd)
    }

    /**
     * 组装一帧
     */
    fun encode(cmd: String, seq: Int, arg: String? = null): String =
        MAGIC + SEP + cmd + SEP + seq + SEP + (arg ?: "") + EOL

    /**
     * 解析单帧，失败返回 null
     */
    fun decode(line: String): Frame? {
        val s = line.trim()
        if (s.isEmpty()) return null
        val parts = s.split(SEP)
        if (parts.size < 3) return null
        if (parts[0] != MAGIC) return null
        val seq = parts[2].toIntOrNull() ?: 0
        val arg = if (parts.size > 3) parts.subList(3, parts.size).joinToString(SEP) else ""
        return Frame(parts[0], parts[1], seq, arg)
    }

    /**
     * 批量解析（容忍粘包 / 半包重组后的多行）
     */
    fun decodeBatch(raw: String): List<Frame> =
        raw.split('\n', '\r')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { decode(it) }
            .toList()

    // ---------------- 坐标参数（CLICK / CURSOR_MOVE） ----------------

    /** 归一化坐标参数前缀：ARG = "n:<nx>,<ny>"，nx / ny ∈ [0,1] */
    const val NORMALIZED_PREFIX = "n:"

    /** 归一化坐标保留小数位（与手环端 protocol.js 的 NORMALIZED_DIGITS 一致） */
    const val NORMALIZED_DIGITS = 6

    /**
     * 坐标型参数
     *
     * @param x 横坐标；normalized = true 时为 0~1 比例，false 时为物理像素
     * @param y 纵坐标；含义同 [x]
     * @param normalized true = 归一化坐标（基准为手机**自然竖屏**方向，需按旋转换算）；
     *                   false = 历史绝对坐标（物理像素），直接使用
     */
    data class PointArg(val x: Float, val y: Float, val normalized: Boolean)

    /**
     * 组装归一化坐标参数，例如 "n:0.512300,0.412300"
     */
    fun encodeNormalizedPoint(nx: Float, ny: Float): String =
        NORMALIZED_PREFIX + String.format(Locale.US, "%.6f,%.6f", clamp01(nx), clamp01(ny))

    /**
     * 解析坐标参数，兼容两种写法：
     *   "n:0.512300,0.412300"  归一化坐标（0~1），来自手环手势区点按位置
     *   "540,1200"             历史绝对坐标（物理像素）
     *
     * @return 解析失败或越界返回 null
     */
    fun decodePointArg(arg: String?): PointArg? {
        val raw = arg?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = raw.startsWith(NORMALIZED_PREFIX, ignoreCase = true)
        val body = if (normalized) raw.substring(NORMALIZED_PREFIX.length) else raw
        val parts = body.split(",")
        if (parts.size < 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        if (x.isNaN() || y.isNaN()) return null
        if (normalized) {
            // 归一化坐标严格限定 0~1，越界视为非法，避免点到屏幕外
            if (x < 0f || x > 1f || y < 0f || y > 1f) return null
        } else if (x < 0f || y < 0f) {
            return null
        }
        return PointArg(x, y, normalized)
    }

    /** 归一化坐标裁剪到 [0,1] */
    private fun clamp01(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    // ---------------- 位移参数（CURSOR_MOVE） ----------------

    /** 手环设计宽度（快应用 manifest.json 的 designWidth），位移按此等比换算到手机像素 */
    const val BAND_DESIGN_WIDTH = 192f

    /** 单帧位移上限（手环手势区像素），超过视为异常帧直接丢弃 */
    const val MAX_DELTA_STEP = 400f

    /**
     * 位移型参数（CURSOR_MOVE）
     *
     * @param dx 横向增量，单位：手环手势区像素，可为负
     * @param dy 纵向增量，单位：手环手势区像素，可为负
     */
    data class DeltaArg(val dx: Float, val dy: Float)

    /**
     * 组装位移参数，例如 "12,-8"
     */
    fun encodeDelta(dx: Float, dy: Float): String =
        String.format(Locale.US, "%d,%d", Math.round(dx), Math.round(dy))

    /**
     * 解析位移参数 "dx,dy"。
     * 语义为「相对上一次上报的增量」（手环端做了整数像素量化与零位移丢弃，见 docs/PROTOCOL.md 2.4）。
     *
     * @return 空参 / 格式非法 / 非有限数 / 超单帧上限 / 全零 均返回 null
     */
    fun decodeDeltaArg(arg: String?): DeltaArg? {
        val raw = arg?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split(",")
        if (parts.size < 2) return null
        val dx = parts[0].trim().toFloatOrNull() ?: return null
        val dy = parts[1].trim().toFloatOrNull() ?: return null
        if (dx.isNaN() || dy.isNaN() || dx.isInfinite() || dy.isInfinite()) return null
        if (Math.abs(dx) > MAX_DELTA_STEP || Math.abs(dy) > MAX_DELTA_STEP) return null
        if (dx == 0f && dy == 0f) return null
        return DeltaArg(dx, dy)
    }
}

/**
 * 指令字常量（与手环端 CMD 一一对应）
 */
object Cmd {
    // 滑屏
    const val SWIPE_UP = "SWIPE_UP"
    const val SWIPE_DOWN = "SWIPE_DOWN"
    const val SWIPE_LEFT = "SWIPE_LEFT"
    const val SWIPE_RIGHT = "SWIPE_RIGHT"

    // 系统导航
    const val BACK = "BACK"
    const val HOME = "HOME"
    const val RECENTS = "RECENTS"
    const val LOCK = "LOCK"

    // 音量
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val VOLUME_MUTE = "VOLUME_MUTE"

    // 指针 / 鼠标模式
    const val CURSOR_ON = "CURSOR_ON"
    const val CURSOR_OFF = "CURSOR_OFF"
    /** 指针移动，ARG = "dx,dy"（相对上一次上报的增量，单位手环手势区像素） */
    const val CURSOR_MOVE = "CURSOR_MOVE"

    /** 单击；ARG = "n:nx,ny"（推荐）/ "x,y"（兼容）/ 空（鼠标模式：在指针当前位置单击） */
    const val CLICK = "CLICK"

    // 业务
    const val APP_LAUNCH = "APP_LAUNCH"
    const val NOTIFY_DISMISS = "NOTIFY_DISMISS"

    // 链路控制
    const val PING = "PING"
    const val PONG = "PONG"
    const val ACK = "ACK"
}

/**
 * 指令字 -> 中文说明
 */
object CmdText {
    private val MAP = mapOf(
        Cmd.SWIPE_UP to "上滑",
        Cmd.SWIPE_DOWN to "下滑",
        Cmd.SWIPE_LEFT to "左滑",
        Cmd.SWIPE_RIGHT to "右滑",
        Cmd.BACK to "返回",
        Cmd.HOME to "返回桌面",
        Cmd.RECENTS to "多任务",
        Cmd.LOCK to "锁屏",
        Cmd.VOLUME_UP to "音量+",
        Cmd.VOLUME_DOWN to "音量-",
        Cmd.VOLUME_MUTE to "静音",
        Cmd.CURSOR_ON to "鼠标模式开",
        Cmd.CURSOR_OFF to "鼠标模式关",
        Cmd.CURSOR_MOVE to "指针移动",
        Cmd.CLICK to "单击",
        Cmd.APP_LAUNCH to "启动应用",
        Cmd.NOTIFY_DISMISS to "通知栏",
        Cmd.PING to "心跳",
        Cmd.PONG to "心跳应答",
        Cmd.ACK to "回执"
    )

    fun of(cmd: String): String = MAP[cmd] ?: cmd
}
