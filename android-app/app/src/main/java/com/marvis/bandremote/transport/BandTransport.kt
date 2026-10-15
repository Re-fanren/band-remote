package com.marvis.bandremote.transport

import com.marvis.bandremote.protocol.Protocol
import java.util.concurrent.atomic.AtomicInteger

/**
 * 链路状态（label 为 UI 展示中文，全项目唯一数据源）
 */
enum class LinkState(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    MOCK("本地模拟")
}

/**
 * ============================================================
 *  手环指令接收通道抽象
 * ============================================================
 *
 *  实现类只需关注「怎么收发字节」，上层 CommandDispatcher 不感知通道差异。
 *  可替换实现：
 *    - MockTransport        ：本地模拟（默认，无需任何配对即可跑通全链路 UI）
 *    - BleGattTransport     ：BLE GATT 直连（通用兜底，无需小米账号）
 *    - InterconnectTransport：小米互联通道（java 反射接入官方穿戴 SDK com.xiaomi.xms.wearable，
 *                             编译期零依赖；SDK 缺失时优雅降级为 DISCONNECTED 提示）
 */
interface BandTransport {

    /** 通道名称，用于日志与 UI 展示 */
    val name: String

    /** 事件监听 */
    var listener: Listener?

    /** 启动通道 */
    fun start()

    /** 停止通道 */
    fun stop()

    /**
     * 下发一条指令
     * @param cmd 协议指令字
     * @param arg 可选参数
     */
    fun send(cmd: String, arg: String? = null): Boolean

    /** 当前状态 */
    fun state(): LinkState

    /** 状态中文文本 */
    fun stateText(): String

    /**
     * 通道事件回调（实现类应在主线程回调，或由上层自行切换线程）
     */
    interface Listener {
        fun onStateChanged(state: LinkState, detail: String)
        fun onFrame(frame: Protocol.Frame)
    }
}

/**
 * 通道基类：统一负责序号自增、状态广播、帧回调
 */
abstract class BaseTransport : BandTransport {

    private val seqCounter = AtomicInteger(0)

    @Volatile
    protected var currentState: LinkState = LinkState.DISCONNECTED

    override var listener: BandTransport.Listener? = null

    override fun state(): LinkState = currentState

    override fun stateText(): String = currentState.label

    override fun send(cmd: String, arg: String?): Boolean {
        val seq = seqCounter.incrementAndGet()
        return sendRaw(Protocol.encode(cmd, seq, arg))
    }

    /** 发送原始帧，由子类实现 */
    abstract fun sendRaw(frame: String): Boolean

    /** 广播状态变化 */
    protected fun notifyState(state: LinkState, detail: String = "") {
        currentState = state
        listener?.onStateChanged(state, detail)
    }

    /** 广播收到的一帧 */
    protected fun notifyFrame(frame: Protocol.Frame) {
        listener?.onFrame(frame)
    }

    /**
     * 下一个序号（需要自行控制序号时使用，如模拟注入 / 主动推帧）
     * 注：设为 public，便于同包内 MockTransport 的调试注入入口调用。
     */
    fun nextSeq(): Int = seqCounter.incrementAndGet()
}
