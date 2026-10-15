package com.marvis.bandremote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.marvis.bandremote.transport.MockTransport

/**
 * ============================================================
 *  联调注入入口：用 adb 广播模拟「手环下发一条指令」
 * ============================================================
 *
 *  用途：手环不在手边 / 快应用尚未安装时，仍可在真机上验证
 *       「指令 -> 协议解析 -> 无障碍执行 -> ACK」全链路。
 *
 *  adb 用法（显式指定组件，规避 Android 8+ 隐式广播限制）：
 *    adb shell am broadcast -n com.marvis.bandremote/.service.MockInjectReceiver \
 *        --es cmd SWIPE_UP
 *    adb shell am broadcast -n com.marvis.bandremote/.service.MockInjectReceiver \
 *        --es cmd CLICK --es arg n:0.500000,0.500000
 *
 *  支持的 CMD 见 protocol/Protocol.kt 的 Cmd 常量表。
 */
class MockInjectReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MockInjectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")
        if (cmd.isNullOrBlank()) {
            Log.w(TAG, "缺少 --es cmd 参数，忽略")
            return
        }
        val arg = intent.getStringExtra("arg").orEmpty()
        Log.i(TAG, "adb 注入指令：$cmd arg=$arg")
        MockTransport.inject(cmd, arg)
    }
}
