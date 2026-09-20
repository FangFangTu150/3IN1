# 贡献指南

感谢关注 3IN1。当前项目仍处于面向精确 ColorOS/SystemUI 版本的兼容性测试阶段。

## 提交前

- 请先阅读 [README](README.md) 和 [兼容性与验收说明](docs/COMPATIBILITY.md)。
- 不要把设备序列号、UID、原始日志、提取的系统 APK、反编译内容、签名密钥或私有路径提交到公开仓库。
- 修改应保持在明确的功能范围内；不要把未完成的真机结果描述为全机型支持。

## 报告问题

请提供可公开的信息：版本、设备型号、Android/API、ColorOS 构建、SystemUI 版本、LSPosed 版本与作用域、复现步骤、预期/实际结果，以及是否能通过停用模块恢复。必要时只提供已脱敏的最小日志片段。

不要公开设备 serial、UID、账户信息、完整 logcat、系统 APK 或反编译产物。涉及安全问题请按 [SECURITY.md](SECURITY.md) 处理，不要直接发布利用细节。

## 修改与验证

请保持现有 Gradle/Kotlin 风格，并在相关修改后运行：

```powershell
.\scripts\build.ps1
```

本地测试通过不等于真实 ART/Xposed hook 或目标设备验收通过。涉及 SystemUI 的改动应明确说明未覆盖的真机验证项。

## 许可证

项目许可证暂未指定。维护者明确选择许可证前，提交贡献不代表项目已授予任意开源许可。
