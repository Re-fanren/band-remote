# 腕控 · band-remote

用**小米手环 9** 当遥控器，控制**安卓手机**的系统级操作——上滑、返回、桌面、多任务、锁屏、音量，以及一个虚拟鼠标指针。

双端实现：

- **手环端**：Xiaomi Vela JS 快应用（rpk），负责手势识别与指令下发
- **手机端**：Kotlin + AccessibilityService，负责收帧并执行 `dispatchGesture()`

---

## 功能

| 分类 | 能力 |
|---|---|
| 手势 | 上滑 / 下滑 / 左滑 / 右滑 |
| 导航 | 返回、桌面、多任务、锁屏 |
| 音量 | 音量增、音量减、静音 |
| 鼠标模式 | 屏幕虚拟指针、位移、点击（支持归一化坐标与绝对像素） |
| 其他 | 启动指定应用、下拉通知栏、PING/PONG 心跳 |

---

## 架构

```
┌──────────────────┐   小米互联通道（interconnect）   ┌──────────────────────┐
│  小米手环 9       │  ────────────────────────────►  │  安卓无障碍遥控 App    │
│  Vela JS 快应用  │      BR1|CMD|SEQ|ARG\n           │  (Kotlin + Gradle)    │
│  band-quickapp/  │  ◄────────────────────────────  │  android-app/         │
└──────────────────┘        ACK / PONG / LOG          └──────────┬───────────┘
   192×490 胶囊屏                                                  │ dispatchGesture
   手势识别 + 指令下发                                              ▼
                                                           系统 UI（滑动 / 导航 / 锁屏）
```

手环端在通道不可用时**自动降级为本地模拟**，UI 交互链路仍可跑通，便于先验证前端。

协议为 UTF-8 文本行协议 `BR1|<CMD>|<SEQ>|<ARG>\n`，两端常量一一对应，详见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)。

---

## 通道选型：为什么是「互联」

这是本项目最关键的一条结论，**先读它能省掉大量无效尝试**。

小米手环 9 的 Vela 快应用能力边界（依据官方「支持明细」表，模块级）：

| 模块 | 手环 9 / 9 Pro |
|---|---|
| `system.bluetooth.ble` | 不支持 |
| `system.fetch` | 不支持 |
| `system.request` | 不支持 |
| `system.interconnect` | 支持（无阉割标记） |

也就是说，**手环 9 上的自研快应用没有任何 BLE 与网络能力**。手环做 GATT Server、手环做 GATT Client 两条 BLE 路线都是死路，不是缺代码，是固件不支持。

`system.interconnect` 是唯一可用通道，它借道手机端「小米运动健康」与手环已有的连接，不需要额外硬件权限。

---

## 环境要求

| 项 | 要求 |
|---|---|
| 手环 | 小米手环 9 / 9 Pro，已与手机「小米运动健康」配对 |
| 手机 | Android 7.0+（minSdk 24），需手动开启无障碍服务 |
| 构建 | JDK 17、Android SDK 34、Node.js（手环端打包） |
| 工具链 | 手环端使用 `@aiot-toolkit/aiotpack`（aiot） |

---

## 准备工作

### 1. 下载小米穿戴互联 SDK（AAR）

AAR 是小米官方产物，**未随本仓库分发**。从小米官方 CDN 下载互联 demo 包：

```
https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip
```

解压后取 `libs/xms-wearable-lib_1.4_release.aar`，放到：

```
android-app/app/libs/
```

不放置该文件时，手机端编译会报互联相关类无法解析——这是预期行为，`build.gradle.kts` 会给出明确警告。

### 2. 配置签名

互联 SDK 会校验 APK 签名（AAR 内含 `SignatureVerifyFailedException`）。**正式联调时，APK 必须与手环端 rpk 使用同一套签名**，否则通信会被静默拒绝。

```bash
cp android-app/keystore.properties.example android-app/keystore.properties
# 编辑 keystore.properties，填入你自己的 keystore 信息
```

`keystore.properties` 与 keystore 文件均已被 `.gitignore` 排除。

未配置时，debug 包回退到 Android 默认 debug 签名，仅可用于本地开发。

---

## 构建与安装

### 手机端（APK）

```bash
cd android-app
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
adb install app/build/outputs/apk/debug/app-debug.apk
```

安装后需手动开启无障碍服务：

```
设置 → 更多设置 → 无障碍 → 已下载的应用 → 腕控 → 开启
```

若使用鼠标模式，还需授予「显示在其他应用上层」权限。

### 手环端（rpk）

```bash
cd band-quickapp
npm install
npm run release                # 产物为 .rpk
```

rpk 通过「小米运动健康」安装到手环。安装方式随小米运动健康版本变化较大，若找不到入口，可借助第三方表盘/文件传输工具，或参考小米官方互联 demo 的安装说明。

---

## 联调：互联授权

这是链路上最容易卡住的一步。按顺序执行：

1. 两端都装上，且**签名同源**
2. 手机端注册消息监听前，**必须先申请权限**：`data_manager` 与 `notify`
   - 官方 demo 的做法是一次性申请两个：`requestPermission(node.id, DEVICE_MANAGER, NOTIFY)`
   - 该申请是异步的，回调可能需要数秒，不要同步等待后立刻判失败
3. 授权成功后再注册消息监听
4. 在手环端点「发送消息」，手机端应收到数据

诊断码参考：`0` 正常、`204` 超时、`1001` 对端应用未安装。

---

## 目录结构

```
band-remote/
├── android-app/                  手机端（Kotlin + Gradle）
│   ├── app/src/main/java/com/marvis/bandremote/
│   │   ├── transport/            InterconnectTransport / BleGattTransport / MockTransport
│   │   ├── command/              指令分发与 ACK 回执
│   │   ├── service/              无障碍服务、保活、开机自启
│   │   ├── ui/                   悬浮窗、虚拟指针
│   │   └── protocol/             协议编解码与指令字常量
│   └── keystore.properties.example   签名配置模板
├── band-quickapp/                手环端（Vela JS 快应用）
│   └── src/
│       ├── common/               protocol.js / transport.js / gesture.js
│       └── pages/                index（连接）/ remote（遥控）
└── docs/
    └── PROTOCOL.md               协议格式与指令字对照表
```

---

## 已知限制

- **HyperOS 会清理后台进程**，可能导致无障碍服务被系统回收，表现为遥控突然失效。根治需要前台服务 + 常驻通知，目前未实现。
- 手环端屏幕为 192×490 胶囊屏，交互以手势为主，复杂菜单不现实。
- 部分系统操作（如锁屏）要求 API 28+。
- 交付单的后续维护成本需自行评估，本项目不提供长期支持承诺。

---

## 免责声明

本项目为个人学习与技术验证用途，按「原样」提供，不附带任何明示或暗示的担保。使用本项目产生的一切后果由使用者自行承担。项目与小米、Android 无任何关联。

---

## License

[MIT](LICENSE)
