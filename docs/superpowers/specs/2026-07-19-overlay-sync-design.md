# Overlay 同步可靠性修复设计

- 日期：2026-07-19
- 范围：第一阶段 P0，仅处理 Overlay 同步的重复订阅、快照倒序和启动竞态
- 不包含：数据库结构、图片存储、UI、Service 架构重构

## 1. 背景与问题

当前 `PinRepository.startOverlaySync()` 每调用一次都会创建一个新的 Room Flow collector。`MainActivity` 和 `OnboardingActivity` 都可能调用该方法，Activity 重建或重复进入流程后可能出现多个永久 collector。每次 Pin 数据变化都会被重复转发到 `PinOverlayService`。

`PinOverlayService` 当前使用 `pendingSync: MutableList<List<PinEntity>>` 暂存同步快照，但存在两个问题：

1. 启动 Service 后才写入队列，存在 Service 先 drain、快照后入队的竞态。
2. drain 时从列表尾部移除，多个快照会按新到旧应用，旧快照可能覆盖最新状态。

## 2. 目标

1. `startOverlaySync()` 在进程生命周期内最多创建一个有效 collector。
2. Overlay 同步只保留最新待处理快照，不再排队处理过期中间状态。
3. 写入快照先于启动 Service，避免首次启动时丢失同步请求。
4. Service drain 后清空快照，并保证并发更新不会让旧状态覆盖新状态。
5. 保持当前公开调用方式不变，减少对其他未提交改动的影响。

## 3. 非目标

本步骤不处理：

- Room Migration 和数据库事务
- Pin 图片 URI 或 cacheDir 存储问题
- 标注图片恢复
- Foreground Service 策略调整
- `PinOverlayService` 的职责拆分
- 新增 UI 功能

## 4. 推荐实现

### 4.1 PinRepository：唯一同步 Job

在 `PinRepository` 内增加一个保存同步 Job 的字段，例如 `overlaySyncJob`。

`startOverlaySync()` 的行为：

- 如果已有 active Job，直接返回。
- 否则创建唯一 collector。
- collector 继续调用现有 `PinOverlayService.syncPinList(context, pins)`。
- 使用 `collectLatest`，当 Room 产生新快照时取消尚未完成的旧转发工作。

由于 Repository 是 Hilt Singleton，该 Job 的生命周期与进程内 Repository 实例一致。当前步骤不引入新的 Coordinator。

### 4.2 PinOverlayService：单一最新快照

将普通 List 队列改为线程安全的单值容器，例如 `AtomicReference<List<PinEntity>?>`。

`syncPinList()` 的顺序：

1. 更新 `PinWindowManager` 的 Compose 投影。
2. 将最新 Pin 列表写入单值容器，覆盖旧快照。
3. 启动或唤醒 `PinOverlayService`。

`drainPendingSync()`：

- 使用原子 `getAndSet(null)` 读取并清空快照。
- 如果存在快照，只调用一次 `applyPinList()`。
- 不再使用 while 循环和历史队列。

### 4.3 并发语义

如果短时间内产生多个 Room 快照：

```text
A → B → C
```

允许 Service 直接跳过 A、B，最终应用 C。Overlay 的目标是反映当前数据库状态，不需要逐个重放中间状态。

如果在 Service drain 的同时又写入新快照：

- 本次 drain 读取旧的最新值并清空容器。
- 新值会留在容器中。
- 下一次 Service start/onStartCommand 负责再次 drain。

实现时需要确保已有 Service 运行时的同步调用仍会触发一次处理；如果当前启动调用无法可靠触发，需要在 Service 内通过主线程 Handler 追加一次 drain，而不是恢复普通队列。

## 5. 验收标准

### 行为验收

- 连续调用 `startOverlaySync()` 多次，只存在一个 collector。
- 连续写入多个 Pin 状态，Service 最终显示最新状态。
- Service 第一次启动时，快照不会因为入队时机丢失。
- 多个待处理快照不会出现旧状态覆盖新状态。
- 删除 Pin、移动 Pin、调整尺寸等已有同步行为保持不变。

### 代码验收

- `pendingSync` 不再是可累积的普通 List。
- 不新增对其他模块的无关修改。
- `git diff --check` 通过。
- 在依赖可用的环境中执行 `:app:compileDebugKotlin` 或完整 Debug 构建。

## 6. 风险与回滚

风险主要是 Service 已运行时，新的最新快照没有及时触发 drain。实现后需要检查 `onStartCommand()` 和现有 Service 启动路径，必要时让 `syncPinList()` 在主线程安全地触发 drain。

回滚方式：恢复 `PinRepository.kt` 和 `PinOverlayService.kt` 的本次改动即可；不涉及数据库迁移和文件格式变化。
