# 安装、更新与恢复

> 本模块会进入 `com.android.systemui`。Root、LSPosed、SystemUI 注入和重启存在风险，安装者应准备好现有框架的恢复方式。

## 首次安装

本项目仅基于 ColorOS 16 / Android 16/API 36 机型开发，其他 Android/ColorOS 版本不作保证。历史 0.1.6 已在一加 13 / `PJZ110` / `PJZ110_16.0.10.501(CN01)` 上通过真机验证；0.1.9 的 `PLK110` 验收范围见 [兼容说明](COMPATIBILITY.md)，其他机型仍需单独验收。验证框架为 LSPosed Irena `1.9.2 (7249)` API 100；legacy Xposed 声明为 `minversion 93`。

1. 当前版本为 0.1.9 / versionCode 10，从 [v0.1.9 Release](https://github.com/FangFangTu150/3IN1/releases/tag/v0.1.9) 下载 `3IN1-0.1.9-compat.apk` 及同名 `.sha256`，校验后安装；自行构建的产物位于 `dist/`。
2. 打开 3IN1 检查预览；“启用三合一”默认关闭。
3. 在 LSPosed 启用模块，并且只选择 `com.android.systemui`。不要选择 Android Framework、所有应用或无关进程。
4. 启用作用域后重新打开 3IN1，使激活前保存的设置有机会迁移到共享目录；不要清除数据。
5. 重启 SystemUI 或设备。应用不会自动重启。
6. 刷新诊断，确认没有回退或状态异常，再阅读确认对话框并启用替换。

诊断回执只证明 SystemUI 读到了配置或报告了状态，不等于人工真机验收。状态不完整时模块会保留原图标。

## 更新

能打开应用时先关闭替换；不能打开时先在 LSPosed 停用模块。使用相同包名和相同的现有 debug/test 签名覆盖安装，不要清除数据：

```powershell
adb install -r dist/3IN1-0.1.9-compat.apk
```

更新后确认作用域仍只有 `com.android.systemui`，重启 SystemUI 或设备，再刷新诊断。签名不一致时停止，不要用卸载或清除数据绕过更新限制；项目不发布签名密钥。

## 恢复

- 应用可用：关闭“启用三合一”或点击“恢复原图标”，然后重启 SystemUI。
- 应用不可用/SystemUI 异常：在 LSPosed 停用 3IN1，再重启 SystemUI 或设备。
- 仍然循环崩溃：使用现有 Root/LSPosed 的安全模式或恢复流程，在系统启动前停用模块。
- OTA 或 SystemUI 版本变化后需重新进行真机验收；如果反射契约或运行时不兼容，模块会回退并保留原图标。

模块不自动获取 Root、不自动重启，不替换 QS 快捷磁贴、详情页或 AOD，也不会把预览状态写入真实网络、电量或 SIM 状态。0.1.9 包含下拉头部状态图标覆盖，结构或接管能力不匹配时该组保持原生；原生 IMS 图标保留。目标设备验收范围见 [兼容说明](COMPATIBILITY.md)。
