# Magpie 接收回执与清空验证（2026-10-26）

## 当前结论

- Magpie 的 externalMediaDropTarget 将导入回调的 Boolean 原样返回给系统；importDroppedMedia 接受任务后返回 true，没有找到丢失成功回执的代码。
- PinPin 仅在 ACTION_DRAG_ENDED.result=true 时清空缩略图、携带文件引用和持久化状态；拒收、取消保留。导出副本独立于球内文件，不随清空删除。
- 本轮没有复现“Magpie 已接收但球不清空”，未修改生产代码、版本号或发布。此前重复长按修复仍是未发布的本地代码。

## 本轮测试

- 扩展 Magpie ExternalMediaDragDropSmokeTest：验证接受回执为 true、内部拖拽和拒收为 false、连续三轮接受都有成功回执。模拟器运行通过。
- 新增 Magpie PinPinDropReceiptDeviceTest 和 PinPin BallCarryWindowTest.externalMagpieDropClearsThreeConsecutiveImages。
- 同时运行两个不同 UID 的应用进程：真实悬浮窗源、系统全局拖放、PinPin FileProvider、Magpie 生产 Compose 接收修饰器和 MediaImportService。连续三次拖放均确认球内与持久化状态清空；Magpie 延迟 600ms 后仍可读取并提交到隔离测试素材库，最终有三条素材。
- 首轮第三张遇到权限撤销，原因是 PinPin 仪器测试先结束，Android 强制停止发送进程并撤销授权；为测试增加发送端存活等待后，三次全部通过。这不是球清空删除导出文件造成的。
- 日志：/tmp/magpie-drag-result-test.log、/tmp/pinpin-cross-sender.log、/tmp/magpie-cross-receiver.log。

## 重跑方式

先安装两项目的 Debug 应用和 androidTest APK。启动 Magpie 的仪器测试并带 -e externalPinPin true，等待 logcat 的 PinPinReceiptTest: READY；然后在另一终端启动 PinPin 仪器测试，带 -e externalMagpie true，选择 BallCarryWindowTest#externalMagpieDropClearsThreeConsecutiveImages。接收端类为 com.agle.app.PinPinDropReceiptDeviceTest。两个测试均为显式启用，普通测试套件不会等待跨应用搭档。

## 验证边界

- Android 15 模拟器，不是三星 One UI 8.5 真机。
- 接收界面是隔离的测试 Compose 容器，复用真实接收器和导入服务；不是完整 LibraryBrowserScreen/ViewModel 页面，也未测试任务栏切换应用。
- 输入为测试 PNG；未执行小红书页面识别截图。
- 不同 UID 的真实 URI 授权已覆盖，但不能据此断言用户已安装版本或三星系统没有问题。
