package com.marvis.bandremote

import android.app.Application
import android.util.Log
import com.marvis.bandremote.command.CommandDispatcher
import com.marvis.bandremote.transport.BandTransport
import com.marvis.bandremote.transport.BleGattTransport
import com.marvis.bandremote.transport.InterconnectTransport
import com.marvis.bandremote.transport.LinkState
import com.marvis.bandremote.transport.MockTransport
import com.marvis.bandremote.util.Prefs

/**
 * ============================================================
 *  Application —— 全局单例容器
 * ============================================================
 *
 *  持有：配置、传输通道、指令分发器，三者生命周期与应用一致。
 */
class App : Application() {

    companion object {
        private const val TAG = "BandRemoteApp"

        @Volatile
        lateinit var instance: App
            private set
    }

    lateinit var prefs: Prefs
        private set

    lateinit var dispatcher: CommandDispatcher
        private set

    lateinit var transport: BandTransport
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        prefs = Prefs(this)
        dispatcher = CommandDispatcher(this)
        transport = createTransport(prefs.transportMode)
        transport.listener = dispatcher
        dispatcher.bindTransport(transport)
        startTransportSafely(transport)

        Log.i(TAG, "初始化完成，通道 = ${transport.name} (${prefs.transportMode})")
    }

    /** 启动通道并兜底拦截异常：任何通道启动失败都不得拖垮进程（否则无障碍服务会随之掉线） */
    private fun startTransportSafely(t: BandTransport) {
        try {
            t.start()
        } catch (e: Throwable) {
            Log.e(TAG, "启动通道失败（已拦截，避免进程崩溃）：${t.name}", e)
        }
    }

    /** 若通道已断开则重新拉起 */
    fun ensureTransportStarted() {
        if (transport.state() == LinkState.DISCONNECTED) {
            startTransportSafely(transport)
        }
    }

    /**
     * 切换传输通道（mock / ble / interconnect）
     */
    fun switchTransport(mode: String) {
        prefs.transportMode = mode
        try {
            transport.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止旧通道失败", e)
        }
        transport = createTransport(mode)
        transport.listener = dispatcher
        dispatcher.bindTransport(transport)
        startTransportSafely(transport)
        Log.i(TAG, "已切换通道 -> ${transport.name}")
    }

    private fun createTransport(mode: String): BandTransport = when (mode) {
        Prefs.TRANSPORT_BLE -> BleGattTransport(this)
        Prefs.TRANSPORT_INTERCONNECT -> InterconnectTransport(this)
        else -> MockTransport()
    }
}
