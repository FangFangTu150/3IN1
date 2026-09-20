# 构建说明

## 环境

- JDK 17 或更高版本
- Android SDK Platform 36
- 首次构建需要网络下载 Gradle 和依赖
- Android Gradle Plugin 8.10.1、Kotlin 2.1.20、Gradle Wrapper 8.11.1

`compileSdk=36`、`targetSdk=36`、`minSdk=34`。legacy Xposed 声明在 `app/src/main/AndroidManifest.xml`，`xposedminversion=93`。Xposed API 仅作为 compile-only 依赖，不会把 Root 或框架打包进 APK。

## 构建命令

先在 Android Studio 中安装 Android SDK Platform 36，并配置项目 SDK 路径。
也可在仓库根目录建立仅保存在本机的 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

Windows 路径可使用正斜杠；请替换为自己的 SDK 路径。该文件已被 Git 排除。

推荐使用 Windows PowerShell：

```powershell
.\scripts\build.ps1
```

脚本执行 `assembleCompat`、`lintCompat`、`testCompatUnitTest`，并复制非 debug APK 到：

```text
dist/3IN1-0.1.6-compat.apk
```

跳过本地测试：

```powershell
.\scripts\build.ps1 -SkipTests
```

直接调用 Gradle：

```powershell
.\gradlew.bat :app:assembleCompat :app:testCompatUnitTest :app:lintCompat --console=plain
```

Linux/macOS：

```sh
./gradlew :app:assembleCompat :app:testCompatUnitTest :app:lintCompat --console=plain
```

直接调用 Gradle 的 APK 位于 `app/build/outputs/apk/compat/app-compat.apk`。
本地 Robolectric 图形测试使用 API 34；应用本身仍以 API 36 编译并面向 API 36。
离线渲染结果写入被忽略的 `DevDoc/qa/`，不会自动更新公开文档图片。

## 变体和签名

`compat` 为非 debug、未混淆变体，使用现有 debug/test signing config 以兼容已有测试安装的覆盖更新。不要提交、上传或公开签名密钥；这不是生产发布签名。

自行构建使用你本机的开发签名密钥，并不包含维护者使用的密钥，因此不保证能覆盖维护者提供的 APK。
保留自己的签名密钥以便后续更新，但不要提交到仓库。`debuggable=true` 构建不等价于 compat，
也不应替代目标 Irena 环境的预发布包。

## 验证边界

本地测试覆盖配置、状态机、离线渲染、迁移和部分清单契约，但不执行真实 ART/Xposed hook，不能替代目标设备上的 LSPosed、SystemUI、旋转、锁屏、网络切换和恢复验收。0.1.6 的 55 项本地测试通过不等于全量真机通过。

候选发布前可运行维护者提供的公开检查：

```powershell
.\scripts\check-publication.ps1
```
