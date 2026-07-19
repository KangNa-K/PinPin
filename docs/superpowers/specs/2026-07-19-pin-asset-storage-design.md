# Pin 图片资产持久化设计

- 日期：2026-07-19
- 范围：导入图片、截图、历史重新钉图、标注结果的长期文件存储
- 不包含：Room Migration、zOrder 拆分、Overlay Service 重构

## 问题

当前 Pin 和标注结果部分使用 `cacheDir`，外部导入图片还可能直接保存临时 `content://` URI。缓存目录可以被系统清理，外部 URI 的读取授权也可能过期，导致 Room 中的 Pin 记录无法恢复图片。

## 设计

新增单例 `PinAssetStore`，统一管理：

```text
filesDir/pin_assets/
```

所有进入 Pin 系统的图片都复制到该目录，文件名使用随机 UUID，避免 URI 冲突。Store 提供：

- `copyFromUri(uri, prefix)`：从外部 content/file URI 复制到持久目录
- `copyFileToPin(file, prefix)`：把历史或截图文件复制为独立 Pin 资产
- `newPinFile(prefix, extension)`：为标注结果创建持久文件

数据库继续保存 `Uri.fromFile(file)`，本轮不改变 Entity 字段，后续可再迁移为相对路径 assetId。

## 接入点

1. `HomeViewModel`：Photo Picker 和 ACTION_SEND 先复制到 PinAssetStore，再创建 Pin；历史记录仍独立保存。
2. `PinAccessibilityService`：截图保存历史后，把历史文件复制到 PinAssetStore，不再写入 cacheDir。
3. `ScreenshotHistoryRepository`：历史重新钉图写入 PinAssetStore。
4. `PinOverlayService`：标注结果写入 PinAssetStore。

## 失败处理

- 输入流无法打开、复制失败或目标文件为空时不写入 Room。
- 写文件使用临时文件，成功后再 rename，避免产生半文件。
- 复制外部 URI 失败时，Pin 创建失败并记录日志。

## 验收标准

- 新导入 Pin、截图 Pin、历史重新钉图和标注结果均位于 `filesDir/pin_assets`。
- 清理 `cacheDir` 后，Pin 图片仍可正常显示、分享和保存。
- 外部 Photo Picker/ACTION_SEND URI 授权过期后，已有 Pin 仍可显示。
- `git diff --check` 通过，现有功能调用方式保持不变。
