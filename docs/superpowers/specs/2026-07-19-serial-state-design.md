# Pin 序列号显示状态设计

- 日期：2026-07-19
- 范围：将序列号可见性与 zOrder 解耦并持久化

## 设计

- 数据库升级到 v5，`pins` 增加 `serialVisible INTEGER NOT NULL DEFAULT 0`。
- `zOrder` 继续承担稳定排序和编号数值，不再用 0 表示隐藏编号。
- 工具栏序列号按钮只切换 `serialVisible`。
- 删除 Pin 后仍可重新归一化 zOrder；可见编号随新 zOrder 更新。
- Service 重建 PinWindowView 时恢复 `serialVisible`。

## 验收标准

- 隐藏序列号不会改变其他 Pin 的顺序。
- 应用/Service 重启后编号可见性保持。
- 删除 Pin 后可见编号连续更新。
- v4→v5 Migration 不丢失现有 Pin 数据。
