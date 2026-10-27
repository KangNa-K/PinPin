# PinPin（钉图）

Android 悬浮截图与图片固定工具。项目使用 Kotlin、Compose、Room、Hilt、Foreground Service 和 AccessibilityService。

## 环境要求

- JDK 17
- Android SDK 37（当前 compile/target SDK）
- Android Studio 或命令行 Android SDK 工具

建议在 Android Studio 的 Gradle 设置中明确选择 JDK 17。

## 本地验证

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

连接测试设备或隔离模拟器后运行：

```bash
./gradlew connectedDebugAndroidTest
```

仪器测试使用随机命名的数据库和临时目录，不修改正常使用的钉图数据。

Release 构建：

```bash
./gradlew assembleRelease
```

未配置发布密钥时会生成：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

## 性能诊断

先启动隔离 Android 模拟器，再运行：

```bash
./gradlew assembleDebug assembleDebugAndroidTest
python3 scripts/performance_probe.py \
  --serial emulator-5580 \
  --adb "$HOME/Library/Android/sdk/platform-tools/adb"
```

脚本只接受模拟器，会安装测试 APK 并执行整套仪器测试，采集启动、
数据库扩容、图片保存、标注及首页滚动数据，结果位于 `build/performance/`。
启动基线采集前会恢复此测试包的悬浮窗 app-op 为 default。
这是诊断工具，不是真机性能验收或 CI 时间阈值。
本次结果及测试边界见 [性能报告](docs/performance-report-2026-10-11.md)。

## 发布签名

签名目录中需要包含 `keystore.properties` 和对应 keystore。目录按以下优先级读取：

1. Gradle property：`-Ppinpin.keystoreDir=/path/to/keys`
2. 环境变量：`PINPIN_KEYSTORE_DIR=/path/to/keys`
3. 兼容旧环境：`~/.android-keys/pockref`

`keystore.properties` 示例：

```properties
storeFile=release.keystore
storePassword=***
keyAlias=pinpin
keyPassword=***
```

密钥和密码不得提交到 Git。

## 自动更新

应用在前台自动检查更新，首页更多菜单也可手动检查。
下载完成后先校验安装包，再交由系统安装器确认。
公开仓库仅发布 APK 和更新清单，详见 [更新发布流程](docs/update-release.md)。

## 数据存储

- Pin 长期图片：`filesDir/pin_assets/`
- 截图历史：`filesDir/screenshot_history/`
- Room schema：`app/schemas/`

Pin 图片不再依赖 `cacheDir` 或临时 Photo Picker/ACTION_SEND URI。

## 数据库

当前数据库版本为 v5，显式支持：

- v1 → v2：新增截图历史
- v2 → v3：新增旋转和翻转状态
- v3 → v4：新增标注图片 URI
- v4 → v5：新增序列号可见状态

不要重新启用 `fallbackToDestructiveMigration()`。

## 重要权限

应用使用：

- 悬浮窗权限：显示悬浮球和固定图片
- 通知权限：Foreground Service 通知
- 无障碍服务：用于用户主动截图，以及自动吸图时查询图片控件边界

自动吸图时读取可见窗口与图片控件的位置，不保存页面文字，不读取输入内容或模拟点击。

## 悬浮球吸图与拖放

- 将空球移到图片上并保持不动约 500ms，随后自动截图识别图片并吸入悬浮球；识别不可靠时会进入手动框选。
- 准备状态下移动手指仍走原有框选；工具栏新增“装入悬浮球”。
- 自动识别由用户主动长按触发；识别失败回退到手动框选后，不会因继续按住而重复自动截图。
- 吸图后保持按住可直接拖出 `image/png`，不必抬手再次按球；也可以松手后切换应用再拖出。接收应用必须支持系统拖放。系统报告目标接受后自动清空球内图片；取消、拒收则保留。传递使用独立只读 URI，清空球不立即删除导出副本；目标接受不代表已持久化。轻点保存到相册成功也会清空。
- 轻点装图的球可保存到 `Pictures/pinpin`、分享、单次移动球或清空。未验证三星相册原生拖入兼容性，保存入口是明确的备用路径。
- 携带图独立保存在 `filesDir/carried_images/`，服务重启后恢复；导出副本保存在 `cacheDir/exports/carry-*.png`，不主动撤销接收方正在使用的读取权限，新建导出时清理超过 7 天的旧副本。
- 吸入使用 280ms 原生动画；关闭系统动画时直接显示装图结果。不会尝试绕过禁止截图的安全窗口。
