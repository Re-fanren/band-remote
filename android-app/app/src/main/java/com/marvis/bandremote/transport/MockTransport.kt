package com.marvis.bandremote.transport

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.marvis.bandremote.protocol.Cmd
import com.marvis.bandremote.protocol.Protocol

/**
 * ============================================================
 *  本地模拟通道（默认启用）
 * ============================================================
 *
 *  作用：
 *    - 在没有手环 / 没有配对 / 快应用未安装的情况下，仍可跑通「指令 -> 无障碍服务执行」全链路；
 *    - 自动回 ACK，便于验证协议与 UI 逻辑；
 *    - 可用 DevTools / 本机 adb 广播注入指令（见 README 联调章节）。
 */
class MockTransport : BaseTransport() {

    companion object {
        private const val TAG = "MockTransport"

        /** 供 adb / 调试注入指令的静态入口 */
        @Volatile
        private var current: MockTransport? = null

        /**
         * 模拟「手环下发一条指令」
         * adb 示例：
         *   adb shell am broadcast -a com.marvis.bandremote.MOCK_INJECT --es cmd SWIPE_UP
         */
        fun inject(cmd: String, arg: String = "") {
            val t = current ?: return
            val frame = Protocol.Frame(Protocol.MAGIC, cmd, t.nextSeq(), arg)
            t.notifyFrame(frame)
        }

        fun isAlive(): Boolean = current != null
    }

    override val name: String = "MOCK"

    private val main = Handler(Looper.getMainLooper())

    override fun start() {
        current = this
        notifyState(LinkState.MOCK, "本地模拟通道已启动")
        Log.i(TAG, "MockTransport started")
    }

    override fun stop() {
        current = null
        notifyState(LinkState.DISCONNECTED, "模拟通道已停止")
    }

    override fun sendRaw(frame: String): Boolean {
        Log.i(TAG, "[MOCK TX] " + frame.trim())
        val parsed = Protocol.decode(frame) ?: return true
        // 模拟手机端 ACK 回执
        main.postDelayed({
            notifyFrame(Protocol.Frame(Protocol.MAGIC, Cmd.ACK, parsed.seq, "OK"))
        }, 60)
        return true
    }
}
