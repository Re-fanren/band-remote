package com.marvis.bandremote.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.marvis.bandremote.protocol.Cmd
import com.marvis.bandremote.protocol.Protocol
import com.marvis.bandremote.util.Prefs
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ============================================================
 *  小米互联（interconnect）通道 —— 官方穿戴开放能力 SDK 反射接入
 * ============================================================
 *
 *  目标 SDK：com.xiaomi.xms.wearable（小米穿戴第三方 APP 能力开放接口 v1.4）
 *
 *  为什么用反射：
 *    - 官方 SDK（AAR）不随本工程分发，也没有公开 Maven 坐标（需向小米申请）；
 *    - 本工程约定「不新增任何 AAR / 远程依赖」，因此编译期完全不引用该类库，
 *      运行时用 Class.forName 探测：SDK 存在即自动接入，不存在则优雅降级。
 *
 *  ★ 以下签名均以 xms-wearable-lib_1.4_release.aar 的 javap 字节码为准（2026-10-09 核实），
 *    不再依赖文档推测：
 *    Wearable.getNodeApi(Context)                     -> NodeApi
 *    Wearable.getAuthApi(Context)                     -> AuthApi
 *    Wearable.getMessageApi(Context)                  -> MessageApi
 *    NodeApi.getConnectedNodes()                      -> Task<List<Node>>
 *    Node.id / Node.name                              -> public String 字段（★无 getId()/getName()）
 *    AuthApi.checkPermission(String, Permission)      -> Task<Boolean>
 *    AuthApi.checkPermissions(String, Permission[])   -> Task<boolean[]>
 *    AuthApi.requestPermission(String, Permission...) -> Task<Permission[]>（★字节码为数组）
 *    MessageApi.sendMessage(String, byte[])           -> Task<Void>
 *    MessageApi.addListener(String, OnMessageReceivedListener) -> Task<Void>
 *    MessageApi.removeListener(String)                -> Task<Void>（★只有一个参数）
 *    OnMessageReceivedListener.onMessageReceived(String, byte[])
 *    Task.addOnSuccessListener(OnSuccessListener) / addOnFailureListener(OnFailureListener)
 *
 *  ★ 桥接关键点：SDK 的 OnSuccessListener / OnFailureListener / OnMessageReceivedListener
 *    都是接口，且编译期不可见，无法直接 implements；这里统一用
 *    java.lang.reflect.Proxy 动态代理（接口类运行时从 SDK 的 ClassLoader 取），
 *    因此不需要任何编译期依赖。
 *
 *  降级策略（任何一条都不会影响编译与其他通道）：
 *    - SDK 类不存在            -> DISCONNECTED + 提示「未检测到 SDK」；
 *    - 无已连接节点（未配对）   -> CONNECTING + 每 5s 重试（最多 12 次）；
 *    - 权限被拒 / 监听注册失败  -> DISCONNECTED + 提示改走 BLE / 本地模拟。
 *
 *  说明：官方 SDK 走的是「手机 App ↔ 穿戴设备 App」的应用内消息通道，
 *  由 nodeId + DEVICE_MANAGER 权限隐式寻址，与手环端快应用 @system.interconnect
 *  的服务名不是同一套寻址方式，故 SERVICE_NAME 仅作日志标识保留。
 */
class InterconnectTransport(private val context: Context) : BaseTransport() {

    companion object {
        private const val TAG = "InterconnectTransport"

        /** 手环端 transport.js 中的服务名；SDK 侧按 nodeId 寻址，此常量仅作日志标识 */
        const val SERVICE_NAME = "com.marvis.bandremote"

        // ---- 运行时反射加载的 SDK 类名（编译期零依赖） ----
        private const val CLS_WEARABLE = "com.xiaomi.xms.wearable.Wearable"
        private const val CLS_NODE_API = "com.xiaomi.xms.wearable.node.NodeApi"
        private const val CLS_AUTH_API = "com.xiaomi.xms.wearable.auth.AuthApi"
        private const val CLS_PERMISSION = "com.xiaomi.xms.wearable.auth.Permission"
        private const val CLS_MESSAGE_API = "com.xiaomi.xms.wearable.message.MessageApi"
        private const val CLS_MSG_LISTENER = "com.xiaomi.xms.wearable.message.OnMessageReceivedListener"
        private const val CLS_ON_SUCCESS = "com.xiaomi.xms.wearable.tasks.OnSuccessListener"
        private const val CLS_ON_FAILURE = "com.xiaomi.xms.wearable.tasks.OnFailureListener"
        private const val CLS_TASK = "com.xiaomi.xms.wearable.tasks.Task"

        /** 启动时探测的类清单，缺失项写入日志，便于排查 SDK 版本差异 */
        private val PROBE_CLASSES = listOf(
            CLS_WEARABLE, CLS_NODE_API, CLS_AUTH_API, CLS_MESSAGE_API,
            CLS_MSG_LISTENER, CLS_ON_SUCCESS, CLS_ON_FAILURE
        )

        /** 未发现节点时的重试间隔（持续重试，不再设次数上限） */
        private const val RETRY_INTERVAL_MS = 5_000L

        /**
         * 手环端路由（launchWearApp 的页面路径）。
         * ★ 必须带前导斜杠 —— 参考已验证可行的 bandqq 项目：WEAR_ENTRY_ROUTE = "/pages/index"。
         */
        private const val WEAR_ENTRY_ROUTE = "/pages/index"

        /** 主动探活间隔：未收到手环回帧前，每 3s 发一帧 PING（参考 bandqq PING_INTERVAL_MS=3000） */
        private const val PING_PROBE_MS = 3_000L

        /** Task 看门狗：超过该时长既无成功也无失败回调，判定为 SDK 挂起并落盘留痕 */
        private const val TASK_TIMEOUT_MS = 8_000L
    }

    override val name: String = "XIAOMI-INTERCONNECT"

    private val main = Handler(Looper.getMainLooper())

    // ---- SDK 句柄：统一用 Any? 持有，避免编译期依赖 ----
    private var nodeApi: Any? = null
    private var authApi: Any? = null
    private var messageApi: Any? = null
    private var nodeId: String? = null
    private var nodeName: String? = null

    /** 消息接收代理（动态代理对象），stop() 时反注册 */
    private var messageListener: Any? = null

    @Volatile
    private var running = false
    private var retryCount = 0

    /** 周期探活计数（用于降低留痕频率，避免挤掉关键记录） */
    private var probeCount = 0L

    private val retryTask = Runnable { queryNodes() }

    /**
     * 主动探活任务：未打通前周期性下发 PING。
     * ★ 不能因为"还没确认连接"就不发 —— 参考 bandqq 的 sendToBand 注释：
     *   「不依赖 bandConnected（心跳 ping 需在未确认前也能发送），仅要求已发现节点」。
     *   一直不发，onopen 可能永远不触发，形成"等确认才发、不发又等不到确认"的死锁。
     */
    private val pingTask = object : Runnable {
        override fun run() {
            if (!running || nodeId == null) return
            if (currentState == LinkState.CONNECTED) return
            // ★ 探活很频繁（每 3s），若每条都写 trace 会把 WEARAPP / SEND_FAIL 等
            //   关键记录从 60 条窗口里挤掉。故只在第 1 次与每 10 次时留痕。
            probeCount += 1
            if (probeCount == 1L || probeCount % 10L == 0L) {
                Prefs(context).traceLink("PROBE | 周期探活 #$probeCount")
            }
            sendRaw(Protocol.encode(Cmd.PING, 0, ""))
            main.postDelayed(this, PING_PROBE_MS)
        }
    }

    /**
     * 统一状态上报：先落盘留痕，再通知上层。
     *
     * ★ 为什么必须落盘：本机 logcat 读不到，而界面只显示"未连接"这类笼统状态，
     *   无法区分「没发现节点 / 权限被拒 / 监听注册失败 / 对端应用未安装」。
     *   留痕后可用 adb `run-as com.marvis.bandremote cat` 读出确切原因。
     *
     * ★ 注意：末尾必须调用基类的 notifyState()。
     *   基类方法名是 notifyState 而不是 state —— 曾因写成 state(s, detail) 变成自递归，
     *   抛 StackOverflowError 被上层 catch(Throwable) 静默吞掉，表现为"永远未连接"。
     */
    private fun state(s: LinkState, detail: String) {
        try {
            Prefs(context).traceLink(s.name + " | " + detail)
        } catch (e: Throwable) {
            Log.w(TAG, "链路留痕失败：" + e.message)
        }
        notifyState(s, detail)
    }

    // ---------------- 生命周期 ----------------

    override fun start() {
        if (running) {
            return
        }
        running = true
        retryCount = 0

        if (!ensureSdk()) {
            running = false
            state(
                LinkState.DISCONNECTED,
                "未检测到小米穿戴开放能力 SDK（com.xiaomi.xms.wearable），请改用 BLE 直连或本地模拟通道"
            )
            return
        }

        state(LinkState.CONNECTING, "正在查找已连接的手环节点…")
        queryNodes()
    }

    override fun stop() {
        running = false
        main.removeCallbacks(retryTask)
        main.removeCallbacks(pingTask)
        unregisterMessageListener()
        nodeId = null
        nodeName = null
        nodeApi = null
        authApi = null
        messageApi = null
        state(LinkState.DISCONNECTED, "互联通道已停止")
    }

    override fun sendRaw(frame: String): Boolean {
        val api = messageApi
        val id = nodeId
        if (!running || api == null || id == null) {
            Log.w(TAG, "通道未就绪，丢弃帧：" + frame.trim())
            return false
        }
        return try {
            val task = findMethod(api, "sendMessage", String::class.java, ByteArray::class.java)
                ?.invoke(api, id, frame.toByteArray(Charsets.UTF_8))
            if (task == null) {
                Log.w(TAG, "MessageApi.sendMessage 不可用，丢弃帧")
                false
            } else {
                taskBridge(task, "sendMessage",
                    onSuccess = { Log.d(TAG, "[TX OK] " + frame.trim()) },
                    onFailure = { e ->
                        Log.w(TAG, "sendMessage 失败：${e.message}")
                        // ★ 下行失败是关键证据：说明通道根本没建立（而非手环端没主动握手）
                        Prefs(context).traceLink("SEND_FAIL | ${e.message}")
                        // 发送失败通常意味着节点掉线：回到 CONNECTING 并重新发现节点
                        if (running) {
                            retryCount = 0
                            state(LinkState.CONNECTING, "发送失败，正在重新发现节点：${e.message}")
                            main.removeCallbacks(retryTask)
                            main.postDelayed(retryTask, RETRY_INTERVAL_MS)
                        }
                    })
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "sendMessage 异常", e)
            false
        }
    }

    // ---------------- SDK 探测 ----------------

    /**
     * 探测 SDK 是否可用，并初始化三个 API 入口。
     * @return true = SDK 可用（可继续节点发现）
     */
    private fun ensureSdk(): Boolean {
        val missing = PROBE_CLASSES.filter { !classExists(it) }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "未检测到小米穿戴 SDK，缺失类：$missing")
            return false
        }
        return try {
            val wearable = Class.forName(CLS_WEARABLE)
            nodeApi = newApi(wearable, "getNodeApi")
            authApi = newApi(wearable, "getAuthApi")
            messageApi = newApi(wearable, "getMessageApi")
            val ok = nodeApi != null && authApi != null && messageApi != null
            if (!ok) {
                Log.w(
                    TAG,
                    "SDK 存在但 API 入口获取失败：node=${nodeApi != null} " +
                        "auth=${authApi != null} msg=${messageApi != null}"
                )
            }
            ok
        } catch (e: Throwable) {
            // 覆盖 ClassNotFoundException / NoClassDefFoundError（SDK 残缺）
            Log.w(TAG, "SDK 初始化失败：${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun classExists(className: String): Boolean = try {
        Class.forName(className, false, InterconnectTransport::class.java.classLoader)
        true
    } catch (e: Throwable) {
        false
    }

    /** 调用 Wearable 的静态工厂方法，兼容 (Context) 与无参两种签名 */
    private fun newApi(wearable: Class<*>, method: String): Any? = try {
        val m: Method = try {
            wearable.getMethod(method, Context::class.java)
        } catch (e: NoSuchMethodException) {
            wearable.getMethod(method)
        }
        if (m.parameterTypes.isEmpty()) m.invoke(null) else m.invoke(null, context)
    } catch (e: Throwable) {
        Log.w(TAG, "调用 Wearable.$method 失败：${e.message}")
        null
    }

    // ---------------- 链路建立：节点发现 -> 权限 -> 监听 ----------------

    /** NodeApi.getConnectedNodes() -> 取第一个（手环） */
    private fun queryNodes() {
        if (!running) {
            return
        }
        val api = nodeApi ?: return
        try {
            val task = findMethod(api, "getConnectedNodes")?.invoke(api)
            if (task == null) {
                Log.w(TAG, "NodeApi.getConnectedNodes 不可用")
                onNoNode()
                return
            }
            taskBridge(task, "getConnectedNodes",
                onSuccess = { result ->
                    val list = result as? List<*>
                    val node = list?.firstOrNull()
                    // ★ Node.id / Node.name 是 public 字段，没有 getId()/getName()，
                    //   旧实现用 callString("getId") 恒返回 null，会导致无限重试找不到节点。
                    val id = node?.let { readString(it, "id") }
                    if (node == null || id == null) {
                        // 区分「SDK 返回空列表」与「返回了节点但读不到 id」，两种修法不同
                        try {
                            Prefs(context).traceLink("NODES | 返回节点数=${list?.size ?: -1}，取到 id=${id ?: "null"}")
                        } catch (e: Throwable) {
                            Log.w(TAG, "节点留痕失败：" + e.message)
                        }
                        onNoNode()
                    } else {
                        nodeId = id
                        nodeName = readString(node, "name") ?: "unknown"
                        Log.i(TAG, "发现节点：$nodeName ($id)")
                        state(LinkState.CONNECTING, "已发现手环「$nodeName」，正在校验权限…")
                        checkPermission()
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "getConnectedNodes 失败：${e.message}")
                    onNoNode()
                })
        } catch (e: Throwable) {
            Log.e(TAG, "查询节点异常", e)
            onNoNode()
        }
    }

    /** 未发现节点：限次重试，避免无限轮询 */
    private fun onNoNode() {
        if (!running) {
            return
        }
        retryCount += 1
        // ★ 不再"重试 N 次就永久放弃"：手环往往稍后才连上、互联服务也可能晚于 App 就绪。
        //   一旦放弃就只能靠用户切通道或重启 App 才能恢复 —— 这正是"永远显示未连接"的成因之一。
        //   改为持续重试（间隔 5s），直到 stop() 被调用。
        state(
            LinkState.CONNECTING,
            "未发现手环节点，${RETRY_INTERVAL_MS / 1000}s 后重试（第 $retryCount 次）"
        )
        main.removeCallbacks(retryTask)
        main.postDelayed(retryTask, RETRY_INTERVAL_MS)
    }

    /** AuthApi.checkPermission(nodeId, Permission.DEVICE_MANAGER)，未授权则发起申请 */
    private fun checkPermission() {
        val api = authApi ?: return
        val id = nodeId ?: return
        val permission = permissionDeviceManager() ?: run {
            state(LinkState.DISCONNECTED, "SDK 缺少 Permission.DEVICE_MANAGER 常量，无法完成权限校验")
            return
        }
        try {
            val task = findMethod(api, "checkPermission", String::class.java, permission.javaClass)
                ?.invoke(api, id, permission)
            if (task == null) {
                Log.w(TAG, "AuthApi.checkPermission 不可用，尝试直接申请权限")
                requestPermission(permission)
                return
            }
            taskBridge(task, "checkPermission",
                onSuccess = { result ->
                    if (result as? Boolean == true) {
                        Log.i(TAG, "DEVICE_MANAGER 已授权")
                        registerMessageListener()
                    } else {
                        Log.i(TAG, "DEVICE_MANAGER 未授权，发起申请")
                        requestPermission(permission)
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "checkPermission 失败，尝试直接申请：${e.message}")
                    requestPermission(permission)
                })
        } catch (e: Throwable) {
            Log.e(TAG, "checkPermission 异常", e)
            state(LinkState.DISCONNECTED, "权限校验失败：${e.message}")
        }
    }

    private fun requestPermission(permission: Any) {
        val api = authApi ?: return
        val id = nodeId ?: return
        try {
            // ★ requestPermission(String, Permission...) 在字节码上第二参是 Permission[]，
            //   反射调用必须显式构造数组，直接传单个对象会 NoSuchMethodException。
            // ★ 官方 demo 一次性申请 DEVICE_MANAGER + NOTIFY 两个权限，这里对齐：
            //   只申请 data_manager 可能导致授权不完整、后续 addListener 被拒。
            val perms = mutableListOf<Any>(permission)
            permissionNotify()?.let { perms.add(it) }
            val arr = ReflectArray.newInstance(permission.javaClass, perms.size)
            perms.forEachIndexed { i, p -> ReflectArray.set(arr, i, p) }
            Log.i(TAG, "申请权限：${perms.joinToString { it.toString() }}")
            val task = findMethod(api, "requestPermission", String::class.java, arr.javaClass)
                ?.invoke(api, id, arr)
            if (task == null) {
                state(LinkState.DISCONNECTED, "权限申请接口不可用，无法建立互联通道")
                return
            }
            taskBridge(task, "requestPermission",
                onSuccess = {
                    Log.i(TAG, "DEVICE_MANAGER 申请成功")
                    registerMessageListener()
                },
                onFailure = { e ->
                    Log.w(TAG, "权限申请失败：${e.message}")
                    state(
                        LinkState.DISCONNECTED,
                        "佩戴设备管理权限被拒绝或设备不支持（${e.message}），请改用 BLE / 本地模拟通道"
                    )
                })
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission 异常", e)
            state(LinkState.DISCONNECTED, "权限申请异常：${e.message}")
        }
    }

    /**
     * 探测手环端快应用是否已安装（NodeApi.isWearAppInstalled(nodeId)）。
     *
     * ★ 为什么需要：手机端 CONNECTED 只说明手机侧 addListener 成功，节点是「手环设备」，
     *   手环上的快应用可能根本没装或在后台被回收。这是"手机已连接、手环没连上"的
     *   首要区分点。未安装时自动尝试拉起。
     */
    private fun probeWearApp() {
        val api = nodeApi ?: return
        val id = nodeId ?: return
        try {
            val task = findMethod(api, "isWearAppInstalled", String::class.java)?.invoke(api, id)
            if (task == null) {
                Prefs(context).traceLink("WEARAPP | isWearAppInstalled 接口不可用")
                return
            }
            taskBridge(task, "isWearAppInstalled",
                onSuccess = { r ->
                    val installed = r as? Boolean
                    Prefs(context).traceLink("WEARAPP | 手环端应用已安装=$installed")
                    // ★ 无论装没装都尝试拉起一次：
                    //   isWearAppInstalled=true 只说明「已安装」，不代表「在运行」；
                    //   手环端应用若不在前台/未启动，它就不会来握手，手机端只能干等。
                    //   实测手机→手环的下行发送是成功的，缺的正是手环端被唤起。
                    state(LinkState.CONNECTING, "正在唤起手环端腕控应用…")
                    launchWearApp()
                    // ★ 周期探活：未打通前每 3s 主动发一帧 PING。
                    //   参考已验证可行的 bandqq 项目，其 sendToBand 注释写明
                    //   「不依赖 bandConnected（心跳 ping 需在未确认前也能发送），仅要求已发现节点」，
                    //   且 PING_INTERVAL_MS = 3000。我们原来只发一次，很可能等不到就放弃了。
                    main.removeCallbacks(pingTask)
                    main.postDelayed(pingTask, 2000L)
                },
                onFailure = { e ->
                    Prefs(context).traceLink("WEARAPP | 检测失败：${e.message}")
                })
        } catch (e: Throwable) {
            Log.w(TAG, "探测手环端应用异常", e)
        }
    }

    /**
     * 拉起手环上的腕控快应用（NodeApi.launchWearApp(nodeId, pagePath)）。
     *
     * ★ 页面路径必须带前导斜杠 "/pages/index"。
     *   参考已验证可行的 bandqq 项目：`const val WEAR_ENTRY_ROUTE = "/pages/index"`；
     *   我们原先是 "pages/index"（无斜杠），虽 Task 返回 success 但可能并未真正打开应用。
     */
    fun launchWearApp() {
        val api = nodeApi ?: return
        val id = nodeId ?: return
        try {
            val task = findMethod(api, "launchWearApp", String::class.java, String::class.java)
                ?.invoke(api, id, WEAR_ENTRY_ROUTE)
            if (task == null) {
                Prefs(context).traceLink("WEARAPP | launchWearApp 接口不可用")
                return
            }
            taskBridge(task, "launchWearApp",
                onSuccess = { Prefs(context).traceLink("WEARAPP | 已拉起手环端应用") },
                onFailure = { e -> Prefs(context).traceLink("WEARAPP | 拉起失败：${e.message}") })
        } catch (e: Throwable) {
            Log.w(TAG, "拉起手环端应用异常", e)
        }
    }

    /** 取静态常量 Permission.DEVICE_MANAGER */
    private fun permissionDeviceManager(): Any? = try {
        Class.forName(CLS_PERMISSION).getField("DEVICE_MANAGER").get(null)
    } catch (e: Throwable) {
        Log.w(TAG, "Permission.DEVICE_MANAGER 不可用：${e.message}")
        null
    }

    /** 取静态常量 Permission.NOTIFY（官方 demo 是与 DEVICE_MANAGER 一并申请的） */
    private fun permissionNotify(): Any? = try {
        Class.forName(CLS_PERMISSION).getField("NOTIFY").get(null)
    } catch (e: Throwable) {
        Log.w(TAG, "Permission.NOTIFY 不可用：${e.message}")
        null
    }

    /** MessageApi.addListener(nodeId, OnMessageReceivedListener) —— 动态代理桥接回调 */
    private fun registerMessageListener() {
        val api = messageApi ?: return
        val id = nodeId ?: return
        try {
            val iface = Class.forName(CLS_MSG_LISTENER)
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                if (method.name == "onMessageReceived" && args != null && args.size >= 2) {
                    val from = args[0] as? String
                    val payload = args[1] as? ByteArray
                    if (payload != null) {
                        onMessageBytes(from, payload)
                    }
                }
                null
            }
            messageListener = proxy
            val task = findMethod(api, "addListener", String::class.java, iface)?.invoke(api, id, proxy)
            if (task == null) {
                state(LinkState.DISCONNECTED, "消息监听接口不可用，无法建立互联通道")
                return
            }
            taskBridge(task, "addListener",
                onSuccess = {
                    Log.i(TAG, "消息监听已注册，nodeId=$id")
                    // ★★ 这里【不能】置 CONNECTED。
                    //   addListener 成功只说明「手机侧」注册了一个本地监听，整个
                    //   getConnectedNodes → 权限 → addListener 链路全部发生在手机本地，
                    //   没有任何一步需要手环上的腕控应用应答。
                    //   而 getConnectedNodes() 返回的 Node 是「手环设备」（蓝牙已配对即出现），
                    //   不是「手环上的应用」。所以手环上哪怕没装腕控、或者装了没打开，
                    //   手机端照样会"已连接" —— 这就是之前误判的根源。
                    //   真正的「已连接」必须等收到手环端发来的第一帧（见 onMessageBytes）。
                    state(LinkState.CONNECTING, "监听已就绪，等待手环端腕控应用接入…")
                    probeWearApp()
                },
                onFailure = { e ->
                    Log.w(TAG, "addListener 失败：${e.message}")
                    state(LinkState.DISCONNECTED, "消息监听注册失败：${e.message}")
                })
        } catch (e: Throwable) {
            Log.e(TAG, "注册消息监听异常", e)
            state(LinkState.DISCONNECTED, "消息监听注册异常：${e.message}")
        }
    }

    private fun unregisterMessageListener() {
        val api = messageApi ?: return
        val id = nodeId ?: return
        if (messageListener == null) {
            return
        }
        messageListener = null
        try {
            // ★ MessageApi.removeListener(String) 只有一个参数，SDK 按 nodeId 移除，不传 listener
            findMethod(api, "removeListener", String::class.java)?.invoke(api, id)
        } catch (e: Throwable) {
            Log.d(TAG, "removeListener 不可用（忽略）：${e.message}")
        }
    }

    /** SDK 回调（可能不在主线程）-> 拆帧 -> 交回上层分发 */
    private fun onMessageBytes(from: String?, payload: ByteArray) {
        var raw = String(payload, Charsets.UTF_8)
        if (raw.isBlank()) {
            return
        }
        // ★ 兼容手环端 r11 起的下发格式：data 用 { frame: "..." } JSON 对象包装
        //   （对齐已验证可行的 bandqq，它传的就是对象）。解析出 frame 字段则用之，
        //   否则按原样当纯文本帧处理（兼容旧版）。
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"frame\"")) {
            try {
                val obj = org.json.JSONObject(trimmed)
                if (obj.has("frame")) {
                    raw = obj.getString("frame")
                    Log.d(TAG, "[RX] JSON 解包出 frame 字段")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "JSON 解包失败，按纯文本处理：${e.message}")
            }
        }
        Log.d(TAG, "[RX] from=$from raw=${raw.trim()}")
        val frames = Protocol.decodeBatch(raw)
        if (frames.isEmpty()) {
            return
        }
        // ★ 收到手环端发来的第一帧 = 双向通道真正打通，这才是可以对外宣称「已连接」的时刻。
        //   （此前 addListener 成功只代表手机侧就绪，见 registerMessageListener 的注释）
        if (currentState != LinkState.CONNECTED) {
            main.removeCallbacks(pingTask) // 已打通，停止主动探活
            state(LinkState.CONNECTED, "已收到手环端数据，双向通道打通（节点：${nodeName ?: from}）")
        }
        main.post { frames.forEach { notifyFrame(it) } }
    }

    // ---------------- 反射工具 ----------------

    /**
     * 把 SDK 的 Task 桥接到 Kotlin 回调。
     *
     * SDK 形态：task.addOnSuccessListener(OnSuccessListener).addOnFailureListener(OnFailureListener)
     * 两个 Listener 接口编译期不可见，用 java.lang.reflect.Proxy 动态代理实现；
     * 任一接口缺失只记录日志，不抛异常，不影响通道以外的逻辑。
     */
    private fun taskBridge(
        task: Any,
        label: String = "",
        onSuccess: (Any?) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        // ★ 看门狗：本机读不到 logcat，若 SDK 的 Task 既不成功也不失败（挂起），
        //   界面只会一直显示"连接中"，无法定位。这里超时后落盘一条 TASK_TIMEOUT 留痕。
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Runnable {
            if (settled.compareAndSet(false, true)) {
                Prefs(context).traceLink("TASK_TIMEOUT | $label ${TASK_TIMEOUT_MS}ms 无回调（SDK 挂起或对端未响应）")
            }
        }
        main.postDelayed(watchdog, TASK_TIMEOUT_MS)
        val fired: () -> Unit = { settled.set(true); main.removeCallbacks(watchdog) }

        try {
            val iface = Class.forName(CLS_ON_SUCCESS)
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                if (method.name.startsWith("onSuccess")) {
                    val value = if (args != null && args.isNotEmpty()) args[0] else null
                    main.post { fired(); onSuccess(value) }
                }
                null
            }
            val m = findMethod(task, "addOnSuccessListener", iface)
            if (m == null) {
                Log.w(TAG, "Task 未暴露 addOnSuccessListener，跳过成功回调")
            } else {
                m.invoke(task, proxy)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "挂载 OnSuccessListener 失败：${e.message}")
        }

        try {
            val iface = Class.forName(CLS_ON_FAILURE)
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
                if (method.name.startsWith("onFailure")) {
                    val err = (if (args != null && args.isNotEmpty()) args[0] as? Throwable else null)
                        ?: RuntimeException("SDK 未提供失败原因")
                    main.post { fired(); onFailure(err) }
                }
                null
            }
            val m = findMethod(task, "addOnFailureListener", iface)
            if (m == null) {
                Log.w(TAG, "Task 未暴露 addOnFailureListener，跳过失败回调")
            } else {
                m.invoke(task, proxy)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "挂载 OnFailureListener 失败：${e.message}")
        }
    }

    /**
     * 查找方法：优先在公开的 Task 接口上找（SDK 实现类可能是 package-private，
     * 直接 Method.invoke 会抛 IllegalAccessException），找不到再沿类层级查。
     */
    private fun findMethod(target: Any, name: String, vararg params: Class<*>): Method? {
        try {
            return Class.forName(CLS_TASK).getMethod(name, *params).also { it.isAccessible = true }
        } catch (e: Throwable) {
            // Task 接口不存在或无此方法，继续按实现类查找
        }
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                return c.getMethod(name, *params).also { it.isAccessible = true }
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            } catch (e: Throwable) {
                return null
            }
        }
        return null
    }

    /**
     * 读取对象上的字符串属性。
     * ★ 优先 public 字段（Node.id / Node.name 就是 public String 字段，没有 getter），
     *   再兜底 getXxx() 方法，兼容不同 SDK 版本实现差异。
     */
    private fun readString(target: Any, prop: String): String? {
        try {
            val v = target.javaClass.getField(prop)
                .get(target)?.toString()?.takeIf { it.isNotBlank() }
            if (v != null) {
                return v
            }
        } catch (e: Throwable) {
            Log.d(TAG, "字段 $prop 读取失败，尝试 getter：${e.message}")
        }
        return try {
            val getter = "get" + prop.replaceFirstChar { it.uppercase() }
            target.javaClass.getMethod(getter).invoke(target)?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            Log.d(TAG, "$prop 的 getter 调用失败：${e.message}")
            null
        }
    }
}
