# 双端通信协议说明 · BandRemote v1（BR1）

本文件是手环端（`band-quickapp`）与手机端（`android-app`）之间通信协议的**唯一依据**。
两端常量实现：

- 手环端：`band-quickapp/src/common/protocol.js`
- 手机端：`android-app/app/src/main/java/com/marvis/bandremote/protocol/Protocol.kt`

---

## 一、帧格式

```
BR1|<CMD>|<SEQ>|<ARG>\n
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `BR1` | 是 | 协议魔数 + 版本号（BandRemote v1）。解析时不匹配则整帧丢弃 |
| `<CMD>` | 是 | 指令字，全大写英文 `动词_宾语`，见第二节对照表 |
| `<SEQ>` | 是 | 自增序号（从 1 开始，十进制整数），用于 `ACK` 回执匹配 |
| `<ARG>` | 否 | 参数，无参时留空；**可含 `\|`**，解析方按 `split(SEP)` 后 `slice(3).join(SEP)` 还原 |
| `\n` | 是 | 帧结束符（LF）。接收侧同时容忍 `\r\n` 与多帧粘连（`decodeBatch` 按行切分） |

其它约定：

- 编码：**UTF-8 纯文本**，非二进制，便于手环端日志直读与抓包调试。
- 分隔符：`|`（`SEP`），魔数：`BR1`（`MAGIC`），结束符：`\n`（`EOL`）。
- 解析失败（字段 < 3、魔数不符）返回 `null` / 丢弃，不影响后续帧。

### 示例

```
BR1|SWIPE_UP|12|\n          手环 → 手机：上滑
BR1|VOLUME_UP|13|\n         手环 → 手机：音量 +
BR1|APP_LAUNCH|14|com.tencent.mm\n   手机：启动微信（手机端已实现；手环端暂无入口）
BR1|CLICK|15|n:0.512300,0.412300\n  手环 → 手机：点击屏幕 (51.23%, 41.23%) 处（归一化，推荐写法）
BR1|CLICK|16|540,1200\n     手环 → 手机：点击物理像素 (540, 1200)（旧写法，兼容）
BR1|CURSOR_ON|17|\n         手环 → 手机：开启鼠标模式（显示指针）
BR1|CURSOR_MOVE|18|12,-8\n   手环 → 手机：指针右下方向移动（相对上次上报的增量）
BR1|CLICK|19|\n             手环 → 手机：鼠标模式下在指针当前位置单击
BR1|ACK|12|OK\n             手机 → 手环：第 12 号指令执行成功
BR1|PING|15|\n              手环 → 手机：心跳
BR1|PONG|15|\n              手机 → 手环：心跳应答
```

---

## 二、指令字格式对照表（全量）

### 2.1 手势滑屏（手环 → 手机）

| 指令字 | 中文 | ARG | 手机端行为 | 手环侧入口 |
|---|---|---|---|---|
| `SWIPE_UP` | 上滑 | 空 | `dispatchGesture`：屏幕 (0.5w, 0.85h) → (0.5w, 0.15h) | 手势区上滑 |
| `SWIPE_DOWN` | 下滑 | 空 | 屏幕 (0.5w, 0.15h) → (0.5w, 0.85h) | 手势区下滑 |
| `SWIPE_LEFT` | 左滑 | 空 | 屏幕 (0.85w, 0.5h) → (0.15w, 0.5h) | 手势区左滑 |
| `SWIPE_RIGHT` | 右滑 | 空 | 屏幕 (0.15w, 0.5h) → (0.85w, 0.5h) | 手势区右滑 |

> 滑动坐标以**屏幕宽高比例**计算（横纵各留 15% 边缘留白，避免误触系统边缘手势区），
> 自动适配不同分辨率的物理像素；手势时长固定 240ms，可调 `performSwipe()` 的 `durationMs` 参数。

### 2.2 系统导航（手环 → 手机）

| 指令字 | 中文 | ARG | 手机端行为 | 手环侧入口 |
|---|---|---|---|---|
| `BACK` | 返回 | 空 | 全局动作 `GLOBAL_ACTION_BACK` | 按钮「返回」 |
| `HOME` | 返回桌面 | 空 | 全局动作 `GLOBAL_ACTION_HOME` | 按钮「桌面」/ 手势区长按 |
| `RECENTS` | 多任务 | 空 | 全局动作 `GLOBAL_ACTION_RECENTS` | 按钮「多任务」 |
| `LOCK` | 锁屏 | 空 | 全局动作 `GLOBAL_ACTION_LOCK_SCREEN`（API 28+） | 按钮「锁屏」 |

> 注：以上均为系统 `performGlobalAction` 直接执行，**无需 Root、无需设备管理器**。
> `GLOBAL_ACTION_LOCK_SCREEN` 要求 Android 9.0（API 28）及以上；低版本 `lockScreen()` 返回 false，
> 并回执 `ACK ERR:EXEC`。

### 2.3 音量（手环 → 手机）

| 指令字 | 中文 | ARG | 手机端行为 |
|---|---|---|---|
| `VOLUME_UP` | 音量 + | 空 | `AudioManager.adjustStreamVolume(STREAM_MUSIC, RAISE)` |
| `VOLUME_DOWN` | 音量 - | 空 | `AudioManager.adjustStreamVolume(STREAM_MUSIC, LOWER)` |
| `VOLUME_MUTE` | 静音切换 | 空 | `isStreamMute` 取反后 `ADJUST_MUTE / ADJUST_UNMUTE` |

### 2.4 指针 / 鼠标模式（手环 → 手机）

| 指令字 | 中文 | ARG | 手机端行为 |
|---|---|---|---|
| `CURSOR_ON` | 鼠标模式开 | 空 | 无障碍服务 `setMouseMode(true)`：创建悬浮指针层并显示在屏幕中心，后续 `CURSOR_MOVE` 生效 |
| `CURSOR_OFF` | 鼠标模式关 | 空 | `setMouseMode(false)`：隐藏并移除悬浮指针层 |
| `CURSOR_MOVE` | 指针移动 | `dx,dy` | 按增量位移悬浮指针：`dx/dy` 为**相对上一次上报**的增量（手环手势区像素，可负），乘以增益后落到手机像素；指针位置由 `CursorPointer` 悬浮层实时绘制 |
| `CLICK` | 单击 | 空（鼠标模式）/ `n:nx,ny`（推荐）/ `x,y`（兼容） | `ARG` 为空时在**指针当前位置**单击；带坐标时见 2.7 |

> **`CURSOR_MOVE` 采用增量语义，而非累计位移**：手机端只按 `dx,dy` 做相对位移，
> 手环端必须上报「自上次上报以来」的新增位移（`GestureDetector.getPendingDelta()` +
> `advanceReported()`），若重复上报同一段位移，指针会成倍漂移。
> 单帧增量绝对值上限 `MAX_DELTA_STEP = 400`（两端一致），超限帧视为异常直接丢弃。
> 手环端增量按整数像素发送，不足 1px 的余量留到下一帧，既保证位移不丢，也天然抑制手指静止时的空帧。
> 鼠标模式下 `CLICK` 无需带坐标（`ARG` 留空，手机端在指针处点击）；非鼠标模式仍使用归一化坐标写法。

### 2.5 业务（手环 → 手机）

| 指令字 | 中文 | ARG | 手机端行为 |
|---|---|---|---|
| `APP_LAUNCH` | 启动应用 | 包名，如 `com.tencent.mm` | `getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`（手环端暂无入口，无 UI 发送该指令） |
| `NOTIFY_DISMISS` | 通知栏 | 空 | 全局动作 `GLOBAL_ACTION_NOTIFICATIONS`（下拉通知栏；骨架手环端未接按钮） |

### 2.6 链路控制（双向）

| 指令字 | 方向 | 中文 | ARG | 行为 |
|---|---|---|---|---|
| `PING` | 手环 → 手机 | 心跳 | 空 | 手机收到后立即回 `PONG`（携带同一 `SEQ`） |
| `PONG` | 手机 → 手环 | 心跳应答 | 空 | 手环收到后不处理，仅刷新链路时间戳 |
| `ACK` | 手机 → 手环 | 指令回执 | `OK` / `ERR:xxx` | 手环按 `SEQ` 匹配待确认回调 |

#### ACK 结果码

| ARG | 含义 | 触发条件 |
|---|---|---|
| `OK` | 执行成功 | 对应动作返回 true |
| `ERR:EXEC` | 执行失败 | 动作返回 false（如未找到包名、坐标非法） |
| `ERR:NO_A11Y` | 无障碍未开启 | `RemoteAccessibilityService.instance == null` |
| `ERR:UNKNOWN` | 未知指令 | `CMD` 不在支持列表 |
| `TIMEOUT` | 超时（手环侧本地生成，非线上传输） | 3 秒内未收到 `ACK` |

### 2.7 坐标参数约定（`CLICK` 归一化映射）

手环屏（192×490）与手机屏尺寸、比例都不同，因此 `CLICK` 统一使用**归一化坐标**：

```
BR1|CLICK|14|n:0.512300,0.412300
```

| 写法 | 示例 | 含义 | 手机端处理 |
|---|---|---|---|
| 归一化（推荐） | `n:0.512300,0.412300` | 手势区点按位置：`nx` 沿手机**自然竖屏宽**、`ny` 沿**自然竖屏高**，取值 0~1，固定 6 位小数 | 按屏幕物理尺寸 + 屏幕旋转换算为像素后点击 |
| 绝对坐标（兼容） | `540,1200` | 手机物理像素坐标 | **不做换算**，直接点击 |

坐标换算规则（`RemoteAccessibilityService.normalizedToPixel()`；`w` / `h` 为**当前旋转方向下**的屏幕宽高）：

| 屏幕旋转 | 换算后的像素坐标 |
|---|---|
| `ROTATION_0` | `(nx*w, ny*h)` |
| `ROTATION_90` | `((1-ny)*w, nx*h)` |
| `ROTATION_180` | `((1-nx)*w, (1-ny)*h)` |
| `ROTATION_270` | `(ny*w, (1-nx)*h)` |

其它约定：

- **越界即非法**：归一化坐标 `<0` 或 `>1`、字段不足、非数字，手机端回执 `ACK ERR:EXEC` 且不执行点击；服务端另做一次屏幕边界裁剪兜底。
- **可自检**：手机端日志形如 `CLICK 归一化(0.5123, 0.4123) -> 像素(553.2, 806.5) rotation=0`，据此可判断换算是否符合预期。
- **旋转方向适配**：若某机型横屏点击落点呈镜像，说明该 ROM 的旋转方向与常规相反，只需把 `ROTATION_90` 与 `ROTATION_270` 两个分支的表达式对调（协议与手环端均无需改动）。
- **手环端取点方式**：把**手势区**（`id="gesturePad"` 矩形区域）视作手机屏幕缩略图，触点先减去手势区左上角、再除以手势区宽高，得到 0~1 比例后由 `protocol.encodeNormalizedPoint()` 格式化。
- **精度**：固定 6 位小数（约 1/1000000 屏宽）；手环手势区宽 172px，单像素对应约 0.0058，精度远超实际需要。

### 2.8 鼠标模式（指针遥控）行为约定

手环屏 192×490，手机屏通常 1080×2400 级别，两者像素量级差约 5 倍，因此指针位移需要**增益换算**：

```
手机像素位移 = 手环手势区增量 × (手机屏幕宽 / BAND_DESIGN_WIDTH)
BAND_DESIGN_WIDTH = 192   // 手环设计宽度，两端一致
```

| 约定 | 说明 |
|---|---|
| 增益基准 | 以**屏幕宽度**为基准等比放大，横竖方向用同一增益，避免斜向拖动方向被拉偏 |
| 指针起点 | `CURSOR_ON` 时指针出现在屏幕中心 |
| 边界钳制 | 指针坐标始终裁剪在 `[0, 屏宽] × [0, 屏高]` 内，不会移出屏幕 |
| 绘制方式 | 使用 `TYPE_APPLICATION_OVERLAY` 悬浮窗（`FLAG_NOT_TOUCHABLE`），指针**不拦截触摸**，仅作视觉指示 |
| 权限降级 | 无「显示在其他应用上层」权限时，指针无法显示，鼠标模式的移动 / 单击均失败（回执 `ERR:EXEC`）；非鼠标模式的坐标点击走手势注入，不受影响 |
| 帧率 | 指针位置更新做**帧内合并**（同一帧内多次位移只重绘一次），减少悬浮窗刷新压力 |
| 单击语义 | 鼠标模式下 `CLICK` 的 `ARG` 为空，在指针**当前位置**注入一次点击手势 |

> **注意**：鼠标模式下指针位置由手环增量累加得到，与手机自身触摸事件无关。
> 若两端指针位置出现累积偏差，优先检查手环端是否存在重复上报（`advanceReported()` 缺失）。

---

## 三、交互时序

### 3.1 建立链路

```
手环(页面 .ux onReady)          手机(App + KeepAliveService)
   │ setInterconnectModule(interconnect)  │
   │ transport.init()                     │
   │ ── interconnect.instance() ──────►   │ 无参数，返回连接实例 conn
   │ ◄── conn（onmessage/onopen 等） ──   │
   │ 主动探活发送 PING，不等 onopen        │
   │ ── BR1|PING|1| ───────────────────►   │
   │ ◄──────────────────── BR1|PONG|1| ──  │
```

- 注册失败 / 模块缺失 / 未配对 → 手环 `enterMock()`，`mock = true`，状态显示「本地模拟」。
- 手环端 `interconnect.instance()` **无参数**（以官方互联 demo 为基准），返回连接实例 `conn`，
  收发均走该实例（`conn.send({ data: { frame } })` / `conn.onmessage`），不依赖服务名寻址。
- 手机端走官方穿戴 SDK（`com.xiaomi.xms.wearable`）时，由 `nodeId` + `Permission.DEVICE_MANAGER` 隐式寻址。
  SDK 的接入链路（节点发现 → 权限校验 → 注册消息监听 → 收发）与「SDK 缺失自动降级」策略，
  见根目录 `README.md`「准备工作」一节。

### 3.2 下发一条指令（带回执）

```
手环 remote.ux                手机 CommandDispatcher
   │ 用户上滑 → send(SWIPE_UP)          │
   │ seq = this.seq + 1 = 12            │
   │ ── BR1|SWIPE_UP|12| ──────────►    │
   │                                    │ 无障碍未开启？
   │                                    │   ↓ 是 → ACK ERR:NO_A11Y
   │                                    │   ↓ 否 → dispatchGesture(...)
   │ ◄────────────── BR1|ACK|12|OK ───  │ ack(seq, "OK")
   │ pending[12](true, "OK")            │
   │                                    │
   │ 3s 内未收到 ACK → pending[12](false, "TIMEOUT")
```

### 3.3 粘包 / 半包处理

- 手机端 `Protocol.decodeBatch(raw)`：按 `\n` 与 `\r` 切分 → `trim` → 逐行 `decode`，容忍一次收到多帧。
- 手环端 `handleIncoming(data)`：兼容 `data` 为 `string` / `{data: string}` / `{message: string}` 三种回调形态。

---

## 四、扩展新指令的步骤

1. **协议层（两端同步）**
   - 手环：`protocol.js` 的 `CMD` 与 `CMD_TEXT` 新增条目。
   - 手机：`Protocol.kt` 的 `Cmd` 与 `CmdText.MAP` 新增同名条目。
   - **两端字符串必须完全一致**（大小写敏感），否则手机端落到 `else -> ERR:UNKNOWN`。
2. **执行层（手机）**：`CommandDispatcher.execute()` 的 `when (frame.cmd)` 补分支，返回 `Boolean` 作为 ACK 结果。
3. **触发层（手环）**：`remote.ux` 中新增按钮 / 手势映射，调用 `transport.send(protocol.CMD.XXX, arg)`。
4. **带参指令**：ARG 建议使用 `,` 分隔多值（如 `dx,dy`），避免与帧分隔符 `|` 冲突；若必须包含 `|`，接收侧已支持 `slice(3).join(SEP)` 还原。

---

## 五、字段约束与安全

| 约束 | 说明 |
|---|---|
| 单帧长度 | 建议 ≤ 512 字节（BLE 单包 / 手环内存友好）；超长请分片 |
| SEQ 范围 | 1 ~ 2^31-1，溢出后可回绕；两端均为本地自增，不要求全局一致 |
| 超时 | 手环侧 ACK 等待 3s（见 `transport.js` 的 `setTimeout`） |
| 敏感信息 | **禁止**在 ARG 中传输账号、密码、authkey、token 等凭据 |
| 解析失败 | 一律静默丢弃并打日志，**不抛异常**，避免单帧异常打断整条链路 |
