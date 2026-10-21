---
name: 功能建议 · Feature request
about: 提议一个新能力 · Suggest an idea for this project
title: '[Feature] '
labels: enhancement
assignees: ''
---

## 想解决什么问题 · What problem does this solve

（使用场景 · use case）

## 期望的行为 · Desired behavior

## 备选方案 · Alternatives considered

## 手环端可行性备注 · Feasibility note (band side)

小米手环 9 的 Vela 快应用**不支持 BLE 与网络**（`system.bluetooth.ble` / `system.fetch` / `system.request` 均无），新功能需基于现有 `system.interconnect` 通道或本地逻辑实现。涉及手环端能力边界的需求请先读 README「通道选型」一节。
