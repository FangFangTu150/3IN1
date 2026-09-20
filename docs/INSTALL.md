# 安装、更新与恢复

> 本模块会进入 `com.android.systemui`。Root、LSPosed、SystemUI 注入和重启存在风险，安装者应准备好现有框架的恢复方式。

## 首次安装

当前适配目标为以下组合，仍需完整真机验收：一加 15 / `PLK110`、Android 16/API 36、ColorOS `PLK110_16.0.3.502(CN01)`、SystemUI `16.00.12`。当前验证框架为 LSPosed Irena `1.9.2 (7249)` API 100；legacy Xposed 声明为 `minversion 93`。

1. 从 [v0.1.6 预发布页面](https://github.com/FangFangTu150/3IN1/releases/tag/v0.1.6) 下载并校验 `3IN1-0.1.6-compat.apk`，然后安装。本地构建输出为 `dist/3IN1-0.1.6-compat.apk`。
2. 打开 3IN1 检查预览；“启用三合一”默认关闭。
3. 在 LSPosed 启用模块，并且只选择 `com.android.systemui`。不要选择 Android Framework、所有应用或无关进程。
4. 启用作用域后重新打开 3IN1，使激活前保存的设置有机会迁移到共享目录；不要清除数据。
5. 重启 SystemUI 或设备。应用不会自动重启。
6. 刷新诊断，确认不是“固件不在已分析范围”，再阅读确认对话框并启用替换。

诊断回执只证明 SystemUI 读到了配置或报告了状态，不等于人工真机验收。状态不完整时模块会保留原图标。

## 更新

能打开应用时先关闭替换；不能打开时先在 LSPosed 停用模块。使用相同包名和相同的现有 debug/test 签名覆盖安装，不要清除数据：

```powershell
adb install -r dist/3IN1-0.1.6-compat.apk
```

更新后确认作用域仍只有 `com.android.systemui`，重启 SystemUI 或设备，再刷新诊断。签名不一致时停止，不要用卸载或清除数据绕过更新限制；项目不发布签名密钥。

## 恢复

- 应用可用：关闭“启用三合一”或点击“恢复原图标”，然后重启 SystemUI。
- 应用不可用/SystemUI 异常：在 LSPosed 停用 3IN1，再重启 SystemUI 或设备。
- 仍然循环崩溃：使用现有 Root/LSPosed 的安全模式或恢复流程，在系统启动前停用模块。
- OTA 或 SystemUI 版本变化后，gate 不匹配会优先保留原图标，直到适配器重新验证。

模块不自动获取 Root、不自动重启，不替换 QS/AOD，也不会把预览状态写入真实网络、电量或 SIM 状态。
