package com.marvis.bandremote.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.marvis.bandremote.protocol.Protocol
import java.util.UUID

/**
 * ============================================================
 *  BLE GATT 直连通道（通用兜底）
 * ============================================================
 *
 *  说明：
 *    - 小米互联（interconnect）为系统级私有通道，第三方 APK 需要官方 SDK + authkey 才能接入；
 *      在拿到授权前，可用 BLE GATT 作为替代链路（手环侧由快应用 / 原生服务作为 GATT Server）。
 *    - 本类仅实现「已配对手环 -> 连接 -> 发现服务 -> 订阅通知 -> 写指令」的最小骨架，
 *      扫描 / 重连退避 / MTU 协商等可按需补全。
 *
 *  ★ 替换点：SERVICE_UUID / CHAR_TX_UUID / CHAR_RX_UUID 需与手环端 GATT Server 保持一致。
 */
@SuppressLint("MissingPermission")
class BleGattTransport(private val context: Context) : BaseTransport() {

    companion object {
        private const val TAG = "BleGattTransport"

        // ★ 手环端 GATT Server 的 UUID（示例值，需按实际实现替换）
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

        // 手机 -> 手环（写）
        val CHAR_TX_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

        // 手环 -> 手机（通知）
        val CHAR_RX_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

        // 标准 CCCD
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override val name: String = "BLE-GATT"

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var ready = false

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                notifyState(LinkState.CONNECTING, "GATT 已连接，正在发现服务…")
                g.discoverServices()
            } else {
                ready = false
                writeChar = null
                try {
                    g.close()
                } catch (e: Exception) {
                    Log.w(TAG, "close gatt failed", e)
                }
                gatt = null
                notifyState(LinkState.DISCONNECTED, "GATT 连接已断开")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                notifyState(LinkState.DISCONNECTED, "未找到目标 GATT 服务")
                return
            }
            writeChar = svc.getCharacteristic(CHAR_TX_UUID)

            val notifyChar = svc.getCharacteristic(CHAR_RX_UUID)
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                notifyChar.getDescriptor(CCCD_UUID)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            ready = true
            notifyState(LinkState.CONNECTED, "GATT 通道就绪")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val raw = c.value?.toString(Charsets.UTF_8) ?: return
            Protocol.decodeBatch(raw).forEach { notifyFrame(it) }
        }
    }

    /** Android 12+ 读取已配对设备必须持有运行时权限 BLUETOOTH_CONNECT，缺失时直接调用会抛 SecurityException */
    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun start() {
        notifyState(LinkState.CONNECTING, "查找已配对手环…")

        // 前置权限校验：无权限时优雅退出，绝不抛出导致进程崩溃
        if (!hasConnectPermission()) {
            Log.w(TAG, "缺少 BLUETOOTH_CONNECT 运行时权限，跳过 BLE 启动")
            notifyState(LinkState.DISCONNECTED, "缺少蓝牙权限 BLUETOOTH_CONNECT，请在系统弹窗中允许后重试")
            return
        }

        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (t: Throwable) {
            Log.w(TAG, "获取 BluetoothAdapter 失败", t)
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            notifyState(LinkState.DISCONNECTED, "蓝牙未开启或设备不支持")
            return
        }

        val device: BluetoothDevice? = try {
            adapter.bondedDevices?.firstOrNull { d ->
                val n = d.name ?: ""
                n.contains("Band", ignoreCase = true) ||
                    n.contains("Mi Band", ignoreCase = true) ||
                    n.contains("手环", ignoreCase = true) ||
                    n.contains("Xiaomi", ignoreCase = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取已配对设备失败", t)
            notifyState(LinkState.DISCONNECTED, "读取已配对设备失败：缺少蓝牙权限")
            return
        }
        if (device == null) {
            notifyState(LinkState.DISCONNECTED, "未找到已配对的小米手环，请先在系统蓝牙中配对")
            return
        }

        gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            Log.w(TAG, "connectGatt 失败", t)
            notifyState(LinkState.DISCONNECTED, "建立 GATT 连接失败：缺少蓝牙权限")
            null
        }
    }

    override fun stop() {
        ready = false
        writeChar = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        gatt = null
        notifyState(LinkState.DISCONNECTED, "GATT 通道已关闭")
    }

    override fun sendRaw(frame: String): Boolean {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || !ready) {
            Log.w(TAG, "通道未就绪，丢弃帧：" + frame.trim())
            return false
        }
        return try {
            val bytes = frame.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            Log.i(TAG, "[BLE TX] " + frame.trim())
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入特征值失败", e)
            false
        }
    }
}
