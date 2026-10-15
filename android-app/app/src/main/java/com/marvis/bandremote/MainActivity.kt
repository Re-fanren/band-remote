package com.marvis.bandremote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.marvis.bandremote.command.CommandDispatcher
import com.marvis.bandremote.databinding.ActivityMainBinding
import com.marvis.bandremote.service.KeepAliveService
import com.marvis.bandremote.service.RemoteAccessibilityService
import com.marvis.bandremote.transport.LinkState
import com.marvis.bandremote.transport.MockTransport
import com.marvis.bandremote.ui.FloatingStatusWindow
import com.marvis.bandremote.util.Prefs

/**
 * 主界面：链路状态总览 + 一键开启无障碍 / 保活服务
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_BT_PERMISSION = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val main = Handler(Looper.getMainLooper())

    private val statusCallback = object : CommandDispatcher.StatusCallback {
        override fun onLinkState(state: LinkState, detail: String) = refresh()

        override fun onCommand(cmdText: String, ok: Boolean) = refresh()
    }

    /** 1 秒轮询刷新（无障碍服务是否开启无法通过广播感知） */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnReconnect.setOnClickListener { reconnectLink() }
        binding.btnTrace.setOnClickListener { showLinkTrace() }
        binding.btnToggleService.setOnClickListener { toggleKeepAlive() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnMock.setOnClickListener { injectMockCommand() }
        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }

        requestBluetoothPermissionIfNeeded()
    }

    /**
     * 重新连接：销毁当前通道并重建（等价于重跑一遍节点发现 → 权限 → 监听注册）。
     * 之前只能靠"设置页切通道"或重启 App 触发，很容易被误判成"永远连不上"。
     */
    private fun reconnectLink() {
        val mode = Prefs(this).transportMode
        Toast.makeText(this, "正在重新连接（$mode）…", Toast.LENGTH_SHORT).show()
        try {
            App.instance.switchTransport(mode)
        } catch (e: Exception) {
            Toast.makeText(this, "重连失败：" + e.message, Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    /**
     * 链路日志弹窗：把最近 60 条留痕直接显示出来，并支持一键复制。
     * ★ 为什么必须有这个入口：HyperOS 禁掉了 adb logcat，排查只能靠落盘留痕；
     *   而读留痕要么连电脑跑 adb run-as，要么——更方便的——让用户在手机上直接看并发给我。
     */
    private fun showLinkTrace() {
        val trace = Prefs(this).linkTrace.takeIf { it.isNotBlank() } ?: "（暂无记录）"
        val tv = android.widget.TextView(this).apply {
            text = trace
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(48, 32, 48, 32)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle("链路日志（最近 60 条）")
            .setView(scroll)
            .setNeutralButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("link_trace", trace))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---------------- 运行时权限（Android 12+） ----------------

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val needed = listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_BT_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMISSION) {
            // 授权成功后重新拉起传输通道
            App.instance.ensureTransportStarted()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        App.instance.dispatcher.addStatusCallback(statusCallback)
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
        App.instance.dispatcher.removeStatusCallback(statusCallback)
    }

    // ---------------- 状态刷新 ----------------

    private fun refresh() {
        val app = App.instance
        val link = app.transport.state()
        val a11y = RemoteAccessibilityService.isRunning()

        binding.tvLinkState.text = getString(R.string.label_link_state) + "：" +
            link.label + "（" + app.transport.name + "）"

        binding.tvA11yState.text = getString(R.string.label_a11y_state) + "：" +
            (if (a11y) "已开启" else "未开启") + " [" + RemoteAccessibilityService.instanceTag() + "]"

        // 失败原因直显：让用户自己在真机上就能看到卡在哪一步，不必依赖 adb 抓日志
        binding.tvLinkDetail.text = Prefs(this).linkDetail

        binding.tvLastCmd.text = getString(R.string.label_last_cmd) + "：" +
            app.dispatcher.lastCommandText

        binding.btnToggleService.text =
            if (KeepAliveService.running) getString(R.string.btn_stop_service)
            else getString(R.string.btn_start_service)
    }

    // ---------------- 交互 ----------------

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_enable_a11y, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置：" + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        if (FloatingStatusWindow.canDraw(this)) {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun toggleKeepAlive() {
        if (KeepAliveService.running) {
            KeepAliveService.stop(this)
            Toast.makeText(this, "保活服务已停止", Toast.LENGTH_SHORT).show()
        } else {
            KeepAliveService.start(this)
            Toast.makeText(this, "保活服务已启动", Toast.LENGTH_SHORT).show()
        }
        main.postDelayed({ refresh() }, 300L)
    }

    /** 本地模拟：无需手环即可验证无障碍动作链路 */
    private fun injectMockCommand() {
        if (App.instance.transport.name != "MOCK") {
            Toast.makeText(this, "当前非模拟通道，请在设置中切换为「本地模拟」", Toast.LENGTH_LONG).show()
            return
        }
        MockTransport.inject("SWIPE_UP")
        Toast.makeText(this, "已注入模拟指令：上滑", Toast.LENGTH_SHORT).show()
    }
}
