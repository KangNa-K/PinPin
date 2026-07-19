# 数据可靠性改进设计

- 日期：2026-07-19
- 范围：Room Migration、关键写入事务、图片尺寸计算、历史导入内存优化

## Room

- 开启 schema 导出。
- 增加 v1→v2、v2→v3、v3→v4 Migration。
- 移除 `fallbackToDestructiveMigration()`。
- Repository 注入 `PinDatabase`，新增、删除和 zOrder 归一化在事务中执行。

## 图片尺寸

使用统一缩放比例 `min(1, maxW/srcW, maxH/srcH)`，同时限制宽高，避免超长图被拉伸。最小尺寸策略保持比例，不分别强制两个维度。

## 历史导入

- 先用 `inJustDecodeBounds` 读取尺寸。
- 不再把整张外部图片解码进 Bitmap；直接流式复制原始字节到历史目录。
- 使用临时文件和 rename，失败时不产生历史数据库记录或零字节文件。

## 验收标准

- 从数据库 v1/v2/v3 升级到 v4 时不丢数据。
- 新增和删除 Pin 的 zOrder 更新具备事务边界。
- 纵向、横向、超长图片保持原始比例。
- 导入大图时不再进行全尺寸 Bitmap 解码。
- Debug Kotlin 编译通过，Lint 无 error。
