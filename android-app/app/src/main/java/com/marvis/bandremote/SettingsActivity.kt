package com.marvis.bandremote

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.marvis.bandremote.databinding.ActivitySettingsBinding
import com.marvis.bandremote.service.RemoteAccessibilityService
import com.marvis.bandremote.ui.FloatingStatusWindow
import com.marvis.bandremote.util.Prefs

/**
 * 设置页：无障碍引导 / 通道选择 / 鼠标模式 / 快捷启动应用 / 自启动
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_BT_PERMISSION = 1001
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** 刷新 UI 时抑制通道监听回调，避免 check() 恢复选中项被误判为用户切换而触发通道重启 */
    private var suppressTransportCallback = false

    /**
     * 刷新 UI 时抑制开关回调。
     * 给 SwitchCompat 赋值 isChecked 同样会触发 OnCheckedChangeListener，
     * 若不抑制，每次 refreshStates() 都会重复执行一次开关动作（含悬浮窗 show/hide）。
     */
    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = App.instance.prefs

        setupAccessibilitySection()
        setupTransportSection()
        setupSwitchSection()
        setupShortcutSection()

        requestBluetoothPermissionIfNeeded()
    }

    // ---------------- 运行时权限（Android 12+） ----------------

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val needed = listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
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
            // 授权后重新拉起通道（此前因缺权限被优雅跳过）
            App.instance.ensureTransportStarted()
            refreshStates()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    // ---------------- 无障碍 ----------------

    private fun setupAccessibilitySection() {
        binding.btnOpenA11y.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnOverlayPermission.setOnClickListener {
            if (FloatingStatusWindow.canDraw(this)) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    // ---------------- 通道 ----------------

    private fun setupTransportSection() {
        binding.rgTransport.setOnCheckedChangeListener { _, checkedId ->
            // 程序化恢复选中状态时不得触发通道切换
            if (suppressTransportCallback) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rb_ble -> Prefs.TRANSPORT_BLE
                R.id.rb_interconnect -> Prefs.TRANSPORT_INTERCONNECT
                else -> Prefs.TRANSPORT_MOCK
            }
            App.instance.switchTransport(mode)
            refreshStates()
            Toast.makeText(this, "已切换通道：" + binding.rgTransport.checkedRadioButtonId, Toast.LENGTH_SHORT)
        }
    }

    // ---------------- 开关 ----------------

    private fun setupSwitchSection() {
        binding.swMouseMode.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            prefs.mouseMode = checked
            // 同步到无障碍服务：立即显示 / 隐藏屏幕虚拟指针。
            // 只写 Prefs 的话开关「看起来生效了」但指针不动，手环 CURSOR_ON/OFF 之外无法手动切换。
            val svc = RemoteAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "已记录，开启无障碍服务后生效", Toast.LENGTH_SHORT).show()
            } else {
                val ok = svc.setMouseMode(checked)
                if (checked && !ok) {
                    Toast.makeText(
                        this,
                        "指针显示失败：请先授予「显示在其他应用上层」权限",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        binding.swAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            prefs.autoStart = checked
        }
        binding.swFloatWindow.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            prefs.floatingWindow = checked
            if (checked) {
                FloatingStatusWindow.show(this)
            } else {
                FloatingStatusWindow.hide()
            }
        }
    }

    // ---------------- 快捷启动应用 ----------------

    private fun setupShortcutSection() {
        binding.btnPickApp.setOnClickListener { showAppPicker() }
        binding.btnClearApp.setOnClickListener {
            prefs.shortcutPackage = ""
            refreshStates()
        }
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { pm.getApplicationLabel(it).toString() }
        } catch (e: Exception) {
            emptyList()
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, "未获取到可启动应用（可在 Android 11+ 声明 QUERY_ALL_PACKAGES）", Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map {
            pm.getApplicationLabel(it).toString() + "\n" + it.packageName
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.label_pick_app)
            .setItems(labels) { _, which ->
                val pkg = apps[which].packageName
                prefs.shortcutPackage = pkg
                refreshStates()
                Toast.makeText(this, "已设置快捷启动：" + pkg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- 刷新 ----------------

    private fun refreshStates() {
        val a11yOn = RemoteAccessibilityService.isRunning()
        binding.tvA11ySummary.text = if (a11yOn) "无障碍服务：已开启" else "无障碍服务：未开启（必需）"
        binding.tvOverlaySummary.text =
            if (FloatingStatusWindow.canDraw(this)) "悬浮窗权限：已授予" else "悬浮窗权限：未授予（可选）"

        suppressTransportCallback = true
        try {
            when (prefs.transportMode) {
                Prefs.TRANSPORT_BLE -> binding.rgTransport.check(R.id.rb_ble)
                Prefs.TRANSPORT_INTERCONNECT -> binding.rgTransport.check(R.id.rb_interconnect)
                else -> binding.rgTransport.check(R.id.rb_mock)
            }
        } finally {
            suppressTransportCallback = false
        }

        // 恢复开关状态（必须抑制回调，否则会被当成用户操作重复执行开关动作）
        suppressSwitchCallback = true
        try {
            binding.swMouseMode.isChecked = prefs.mouseMode
            binding.swAutoStart.isChecked = prefs.autoStart
            binding.swFloatWindow.isChecked = prefs.floatingWindow
        } finally {
            suppressSwitchCallback = false
        }

        binding.tvShortcutPkg.text = if (prefs.shortcutPackage.isEmpty()) {
            "未设置"
        } else {
            prefs.shortcutPackage
        }
    }
}
