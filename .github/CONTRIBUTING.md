# 贡献指南 · Contributing

欢迎参与 **band-remote** 的开发。项目很小，规则也尽量简单——先读 `README.md` 的「通道选型」和 `docs/PROTOCOL.md`，再动手。

## 报告问题 · Reporting issues

- 先搜已有 issue，避免重复。
- 使用 `bug_report` 模板，**至少包含**：
  - 手环型号 / 固件版本（如 9 / 9 Pro）
  - 手机型号 + Android 版本
  - APK / rpk 的版本（`BUILD_TAG`）
  - 复现步骤与预期/实际行为
- 附日志或截图（手环端 `transport.js` 的 `probeConnCapability()` dump 尤其有用）。
- 功能建议用 `feature_request` 模板。

## 提 PR · Submitting pull requests

1. Fork 本仓库，在单独的分支上开发。
2. 构建验证：手机端 `cd android-app && ./gradlew assembleDebug`，手环端 `cd band-quickapp && npm install && npm run release`。
3. 提交信息用中文描述改动（`feat:` / `fix:` / `docs:` / `chore:` 前缀）。
4. 不要提交任何密钥/证书文件（keystore、`*.jks`、`*.pem`、`*.p12`）。

## 签名与 SDK 提醒 · Signing & SDK

互联 SDK 校验两端签名一致性，APK 与 rpk 必须用**同一套 keystore** 签名，否则通信静默失败（手机端一直显示「未连接」）。

- SDK AAR（小米官方产物）**不入库**，从 README 的官方 CDN 链接下载后放到 `android-app/app/libs/`。
- keystore 与 `keystore.properties` **不入库**（`.gitignore` 已排除），从 `keystore.properties.example` 复制后自行填写。

## 环境 · Environment

| 项 | 要求 |
|---|---|
| JDK | 17 |
| Android SDK | 34（`minSdk 24`） |
| Node.js | 用于手环端打包（`@aiot-toolkit/aiotpack`） |

## 行为准则 · Code of conduct

友善、就事论事。本项目是个人学习/技术验证项目，作者业余维护，响应可能不快。
