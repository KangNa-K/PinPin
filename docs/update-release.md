# PinPin 更新发布

公开分发仓库：<https://github.com/KangNa-K/PinPin-updates>。
此仓库仅存放安装包和更新清单，源代码和签名资料保留在本地。

应用回到前台时自动检查更新，成功检查后间隔六小时再联网。
首页更多菜单提供手动检查，手动检查也会显示此前忽略或延后的版本。
“稍后”延后一天，“忽略此版本”只对当前版本生效。
下载由用户发起，完成后通过系统安装器确认安装。

## 构建

1. 递增 `app/build.gradle.kts` 的 `versionCode`，更新 `versionName`。
2. 运行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。
3. 用 Android SDK 的 `apksigner verify --print-certs` 验证发布 APK。
   必须与已安装版本使用同一签名，不应为了升级卸载旧应用。
4. 记录包名、版本、SHA-256 和字节大小。

签名目录通过 `-Ppinpin.keystoreDir` 或 `PINPIN_KEYSTORE_DIR` 提供；
项目当前本地配置回退到 `~/.android-keys/pockref`。
不要把密钥、密码、R8 映射或源代码上传到公开更新仓库。

## 发布顺序

1. 检查公开仓库的现有版本和资产，避免覆盖已有发布。
2. 在 GitHub Releases 创建草稿，上传已验证的 APK。
3. 下载草稿资产，核对其 SHA-256 和字节数。
4. 将草稿发布为正式版本，再匿名下载并核对安装包。
5. 最后提交 `update/version.json` 到公开仓库的 `main` 分支。
6. 从应用使用的 raw 地址回读清单，确认所有字段与发布资产一致。

清单字段为 `versionCode`、`versionName`、`apkUrl`、`sha256`、`size`、`notes`。
`apkUrl` 必须为该仓库的不可变资产 API 地址：
`https://api.github.com/repos/KangNa-K/PinPin-updates/releases/assets/<数字资产ID>`。

应用校验大小、SHA-256、包名、递增版本和安装签名；缓存的 APK 在每次安装前重新校验。
清单地址为：
<https://raw.githubusercontent.com/KangNa-K/PinPin-updates/main/update/version.json>。
本地 `update/version.json` 应与公开清单保持一致。

本地测试或 `adb install -r` 不等于应用内更新流程已完成验证。
应用内升级验证应分别确认下载、授权、系统安装和已有数据保留。
