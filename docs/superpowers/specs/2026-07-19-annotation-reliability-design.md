# 标注图片可靠性修复设计

- 日期：2026-07-19
- 范围：标注结果恢复、分享/保存使用有效图片、保存 Bitmap 生命周期修复
- 不包含：持久化目录迁移、Room Migration、标注编辑器重构

## 目标

1. PinOverlayService 重新创建 PinWindowView 时恢复数据库中的 `annotatedImageUri`。
2. 普通分享、保存、收起操作使用当前有效图片，而不是永远使用原始 `imageUri`。
3. “保存标注”在一个串行 IO 流程中完成内部文件保存、可选 MediaStore 导出和 Room 更新。
4. Bitmap 只在所有使用方完成后回收，避免并发协程使用已回收 Bitmap。

## 实现原则

- `imageUri` 保留原图，`annotatedImageUri` 表示当前标注版本。
- 增加有效图片选择逻辑：`PinWindowView.spec.annotatedImageUri` → `PinEntity.annotatedImageUri` → `PinEntity.imageUri`。
- `ACTION_SAVE_ANNOTATE` 不再把同一 Bitmap 分发给两个协程；改为一个函数统一写入文件并从文件复制到 MediaStore。
- 标注保存失败时记录日志，不更新 Room 为无效 URI。
- 本轮保持现有 cacheDir 路径不变，目录迁移另行处理。

## 验收标准

- Service 重启后标注图仍显示。
- 标注后点击普通分享/保存，导出的内容包含标注。
- “保存标注”不再存在 recycled Bitmap 竞态。
- 原图仍可通过标注编辑流程恢复。
- `git diff --check` 通过，除目标文件外不修改其他功能。
