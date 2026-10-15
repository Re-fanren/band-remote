package com.marvis.bandremote.command

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.marvis.bandremote.protocol.Cmd
import com.marvis.bandremote.protocol.Protocol
import com.marvis.bandremote.service.RemoteAccessibilityService
import com.marvis.bandremote.transport.BandTransport
import com.marvis.bandremote.transport.BaseTransport
import com.marvis.bandremote.transport.LinkState
import com.marvis.bandremote.util.Prefs
import com.marvis.bandremote.util.VolumeController
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ============================================================
 *  手环指令接收 / 分发模块
 * ============================================================
 *
 *  作为 BandTransport.Listener 挂在通道上：
 *    收到一帧 -> 解析 -> 路由到无障碍服务 / 音量控制 / 应用启动 -> 回 ACK
 *
 *  所有需要系统能力的动作都做「服务未开启」兜底，避免指令丢失后无反馈。
 */
class CommandDispatcher(private val context: Context) : BandTransport.Listener {

    companion object {
        private const val TAG = "CommandDispatcher"
    }

    /** 状态回调（悬浮窗 / Activity 注册以刷新 UI） */
    interface StatusCallback {
        fun onLinkState(state: LinkState, detail: String)
        fun onCommand(cmdText: String, ok: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)

    /** 支持多个订阅者（Activity / 悬浮窗）同时监听状态 */
    private val callbacks = CopyOnWriteArrayList<StatusCallback>()

    fun addStatusCallback(cb: StatusCallback) {
        if (!callbacks.contains(cb)) {
            callbacks.add(cb)
        }
    }

    fun removeStatusCallback(cb: StatusCallback) {
        callbacks.remove(cb)
    }

    @Volatile
    private var transport: BandTransport? = null

    /** 最近一次指令（供 UI 显示） */
    @Volatile
    var lastCommandText: String = "-"
        private set

    fun bindTransport(t: BandTransport) {
        this.transport = t
    }

    // ---------------- BandTransport.Listener ----------------

    override fun onStateChanged(state: LinkState, detail: String) {
        main.post { callbacks.forEach { it.onLinkState(state, detail) } }
    }

    override fun onFrame(frame: Protocol.Frame) {
        when (frame.cmd) {
            Cmd.ACK, Cmd.PONG -> {
                // 回执 / 心跳应答：仅记录
                Log.d(TAG, "recv ${frame.cmd} seq=${frame.seq} arg=${frame.arg}")
            }
            Cmd.PING -> {
                // 手环心跳，回复 PONG 维持链路。
                // 协议要求：BR1|PONG|<与 PING 相同的 SEQ>|<空 ARG>（见 docs/PROTOCOL.md 2.6 / 3.2）。
                // 不能走 transport.send()——它会自增一个新的 SEQ，并把原 SEQ 当成 ARG 塞进帧里，
                // 导致对端按 SEQ 配对时永远匹配不上。故与 ack() 一样直接走 sendRaw。
                val t = transport ?: return
                if (t is BaseTransport) {
                    t.sendRaw(Protocol.encode(Cmd.PONG, frame.seq, ""))
                } else {
                    // 非 BaseTransport 实现：退回旧写法，至少保证链路有应答
                    t.send(Cmd.PONG, frame.seq.toString())
                }
            }
            else -> execute(frame)
        }
    }

    // ---------------- 指令执行 ----------------

    private fun execute(frame: Protocol.Frame) {
        val text = frame.text
        lastCommandText = text
        Log.i(TAG, "执行指令 $text (${frame.cmd}) arg=${frame.arg}")

        val svc = RemoteAccessibilityService.instance
        prefs.traceA11y("exec $text svc=${if (svc == null) "null" else RemoteAccessibilityService.instanceTag()}")
        if (svc == null) {
            ack(frame.seq, "ERR:NO_A11Y")
            postResult(text, false)
            return
        }

        val ok: Boolean = when (frame.cmd) {
            // ---- 手势滑屏 ----
            Cmd.SWIPE_UP -> svc.performSwipe(RemoteAccessibilityService.SwipeDirection.UP)
            Cmd.SWIPE_DOWN -> svc.performSwipe(RemoteAccessibilityService.SwipeDirection.DOWN)
            Cmd.SWIPE_LEFT -> svc.performSwipe(RemoteAccessibilityService.SwipeDirection.LEFT)
            Cmd.SWIPE_RIGHT -> svc.performSwipe(RemoteAccessibilityService.SwipeDirection.RIGHT)

            // ---- 系统导航 ----
            Cmd.BACK -> svc.performBack()
            Cmd.HOME -> svc.performHome()
            Cmd.RECENTS -> svc.performRecents()
            Cmd.LOCK -> svc.lockScreen()

            // ---- 音量 ----
            Cmd.VOLUME_UP -> VolumeController.volumeUp(context)
            Cmd.VOLUME_DOWN -> VolumeController.volumeDown(context)
            Cmd.VOLUME_MUTE -> VolumeController.toggleMute(context)

            // ---- 指针 / 鼠标模式 ----
            Cmd.CLICK -> handleClick(svc, frame.arg)
            Cmd.CURSOR_MOVE -> handleCursorMove(svc, frame.arg)
            Cmd.CURSOR_ON -> {
                prefs.mouseMode = true
                // 开启鼠标模式 = 显示屏幕虚拟指针（需「显示在其他应用上层」权限）
                svc.setMouseMode(true)
            }
            Cmd.CURSOR_OFF -> {
                prefs.mouseMode = false
                svc.setMouseMode(false)
            }

            // ---- 业务 ----
            Cmd.APP_LAUNCH -> launchApp(frame.arg)
            Cmd.NOTIFY_DISMISS -> svc.performNotifications() // 下拉通知栏（GLOBAL_ACTION_NOTIFICATIONS）

            else -> {
                // 未知指令：按 docs/PROTOCOL.md 的 ACK 结果码表回 ERR:UNKNOWN
                Log.w(TAG, "未知指令：${frame.cmd}")
                ack(frame.seq, "ERR:UNKNOWN")
                postResult(text, false)
                return
            }
        }

        if (frame.cmd == Cmd.CURSOR_MOVE) {
            // 位移帧高频（一次滑动可达数十帧），不逐帧回 ACK —— 回执会把互联通道占满，
            // 手环端也不为该指令登记回执回调（见 docs/PROTOCOL.md 2.4）。
            // 仅在失败（指针不可用）时回执，便于手环侧定位。
            if (!ok) {
                ack(frame.seq, "ERR:EXEC")
            }
            return
        }

        ack(frame.seq, if (ok) "OK" else "ERR:EXEC")
        postResult(text, ok)
    }

    /**
     * 指针位移：ARG = "dx,dy"（相对上一次上报的增量，单位手环手势区像素，可负）
     *
     * 手机端把增量按「屏幕宽度 / 手环设计宽度」等比换算为屏幕像素后叠加到虚拟指针，
     * 详见 docs/PROTOCOL.md 2.4。
     */
    private fun handleCursorMove(svc: RemoteAccessibilityService, arg: String): Boolean {
        val delta = Protocol.decodeDeltaArg(arg)
        if (delta == null) {
            Log.w(TAG, "CURSOR_MOVE 位移参数非法（应为 dx,dy，非零且不超单帧上限）：$arg")
            return false
        }
        return svc.moveCursor(delta.dx, delta.dy)
    }

    /**
     * 点击：ARG 支持三种写法（见 docs/PROTOCOL.md 2.4 / 2.7）
     *   ""             空参 —— 鼠标模式：在虚拟指针当前位置单击（指针遥控路径）
     *   "n:<nx>,<ny>"  归一化坐标（0~1，6 位小数）—— 手环手势区点按位置，
     *                  由无障碍服务按真实屏幕尺寸与屏幕旋转换算为物理像素；
     *   "<x>,<y>"      历史绝对坐标（物理像素），保持向后兼容。
     */
    private fun handleClick(svc: RemoteAccessibilityService, arg: String): Boolean {
        // 鼠标模式：手环只报「点按」而不报坐标，落点就是虚拟指针所在位置
        if (arg.isBlank()) {
            return svc.clickAtCursor()
        }
        val point = Protocol.decodePointArg(arg)
        if (point == null) {
            Log.w(TAG, "CLICK 坐标参数非法（应为 n:nx,ny 或 x,y）：$arg")
            return false
        }
        return if (point.normalized) {
            svc.performClickNormalized(point.x, point.y)
        } else {
            // 绝对坐标点击时同步指针位置，保持「指针所见即点击处」
            svc.syncCursorTo(point.x, point.y)
            svc.performClick(point.x, point.y)
        }
    }

    /**
     * 启动自定义快捷应用
     */
    private fun launchApp(pkg: String): Boolean {
        if (pkg.isBlank()) {
            return false
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                Log.w(TAG, "未找到可启动的包：$pkg")
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败：$pkg", e)
            false
        }
    }

    // ---------------- 回执 ----------------

    private fun ack(seq: Int, result: String) {
        // 通过 Protocol.encode 走通道原始发送（ACK 是协议级回执）
        val t = transport ?: return
        val raw = Protocol.encode(Cmd.ACK, seq, result)
        // 借助 sendRaw 通道能力：这里复用 send 的序号机制会污染序号，故直接调用 sendRaw
        if (t is BaseTransport) {
            t.sendRaw(raw)
        } else {
            t.send(Cmd.ACK, result)
        }
    }

    private fun postResult(text: String, ok: Boolean) {
        main.post { callbacks.forEach { it.onCommand(text, ok) } }
    }
}
