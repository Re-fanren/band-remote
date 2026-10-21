---
name: Bug 报告 · Bug report
about: 描述一个问题，帮助改进 · Report a problem to help improve
title: '[Bug] '
labels: bug
assignees: ''
---

## 环境 · Environment

- 手环型号 / 固件 · Band model/firmware: （如 小米手环 9）
- 手机型号 + Android 版本 · Phone model + Android version: 
- APK 版本 · APK version（BUILD_TAG）: 
- rpk 版本 · rpk version（BUILD_TAG）: 
- 手机系统 · Phone OS: （如 HyperOS / MIUI / 原生）

## 问题描述 · Describe the bug

发生了什么？期望是什么？· What happened, and what did you expect?

## 复现步骤 · To reproduce

1. 
2. 
3. 

## 日志 / 截图 · Logs / screenshots

（手环端 `probeConnCapability()` 的方法列表 dump、错误码、截图等）

## 备注 · Notes

- 是否两个包都用同一套 keystore 签名？（签名不一致会静默失败）
- App 是否留在前台？（HyperOS 后台管控会冻结后台进程，表象与断连相同）
- 是否已先搜过已有 issue？
