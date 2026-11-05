# 功能边界与验收状态

## ColorOS 16 兼容性范围

| 条件 | 值 |
| --- | --- |
| 开发范围 | ColorOS 16 / Android 16 / API 36 |
| 历史 0.1.6 真机验证 | `PJZ110`（一加 13），`PJZ110_16.0.10.501(CN01)` |
| 0.1.9 目标设备 | `PLK110`，`PLK110_16.0.3.502(CN01)`；验收范围见下文 |
| 框架验证环境 | LSPosed Irena `1.9.2 (7249)`，API 100 |
| Xposed 接口 | legacy `minversion 93` |
| 作用域 | 仅 `com.android.systemui` |

运行时不再按精确型号、固件构建号或 SystemUI 版本阻断。项目仅基于 ColorOS 16 机型开发，其他 Android/ColorOS 版本不作保证；不同 ColorOS 16 机型仍需自行真机验收。反射契约不满足或运行时异常时，模块仍会回退并保留原图标；`minSdk=34` 只是安装边界。

## 覆盖与排除

绘制位于 SystemUI 原有电池宿主内，覆盖主状态栏和锁屏状态栏的电量、Wi-Fi、蜂窝组合状态；锁屏可单独关闭。0.1.9 包含通知栏/控制中心下拉头部覆盖，使用同一总开关及配置，不受锁屏开关控制。组合图标继承父级防烧屏移动和系统布局协调，但项目不增加独立移动计时器，不能保证 OLED 不老化。

下拉仅接管结构契约匹配的头部状态图标组：普通头部与横屏/分栏头部中，`quick_qs_status_icons` 包含原生 `StatBatteryMeterView`（`batteryRemainingIcon`）和 `StatusIconContainer`（`statusIcons`）。按 SystemUI 资源名称、类及父子关系识别，不硬编码数字资源 ID；契约不匹配的区域保留原图标。

下拉图标容器还必须通过接管能力检查；未知图标分支使该组完整保留原生，不影响其他组。已验证基类的派生类可以识别，缺失网络图标不单独构成失败。VoLTE、VoWiFi、未注册提示保留原生显示，不属于合并范围。

明确排除：QS/控制中心快捷设置磁贴及详情页、AOD、其他应用、Android Framework 全局注入和 SystemUI 之外的进程。已发布 0.1.8 的 QS 头部排除属于历史边界，本轮仅定向扩展头部覆盖，不扩大为全部 QS 图标替换。

## 版本修复

- **0.1.5**：有效电量百分比不再因充放电 status 为 UNKNOWN 而丢失；仍可驱动进度、低电量颜色和自动数字，但不伪造充电/充满/暂停标记。消费初始 sticky 电量；关于入口读取运行时版本并打开作者主页。
- **0.1.6 P2-1**：Wi-Fi 互联网状态只使用当前 Wi-Fi Network capabilities；缺少当前 capabilities 时暂时显示连接中，避免混入蜂窝验证。
- **0.1.6 P2-2**：辅助功能描述只在替换 active 时接管，释放时恢复最新原生描述和重要性（包括 null）；非 active 宿主不改写。
- **0.1.6 P2-3**：刷新广播使用 signature permission；合并刷新请求不推迟首次 deadline，执行期间的新请求最多安排一次后续刷新。
- **本次修订**：新增机型判断适配优化，解决部分反馈不生效的问题；一加 13 真机验证通过。

## 证据边界

| 项目 | 状态 |
| --- | --- |
| 0.1.6 本地构建与 Lint | 已通过 |
| 0.1.6 本地 JUnit/Robolectric | 55 项通过 |
| 0.1.1 目标设备真实替换 | 用户截图证明曾运行 |
| 0.1.1 外观 | 截图显示 tint/spacing 问题 |
| 0.1.2-0.1.6 后续修复 | 一加 13 真机验证已通过，其他机型仍待验收 |
| 全机型/全固件支持 | 未声明 |
| 0.1.9 本地测试、构建与Lint | 102项测试通过，Lint为0 errors / 12项原有warnings |
| 0.1.9 目标设备正常路径 | 覆盖安装、SystemUI重建、主栏/锁屏/两组下拉接管、回执及原生恢复通过 |
| 0.1.9 展开收起 | 20次操作期间SystemUI进程稳定，结束时未见重复图标 |
| 真实切卡、运营商IMS及故障注入 | 未测 |
| 真机横屏/分栏、TalkBack及定量性能 | 未测 |

公开文档不得把 55 项本地测试描述成全量真机验收，也不得宣传全机型支持。

0.1.9 的正常路径结果仅适用于上述目标设备与固件。中心常显250%已检查主栏、下拉及预览，锁屏仅检查原设置模式；锁屏下拉未获得可验收的展开头部。真实未知固件、其他模块改写视图、全部电量/充电阶段、监听数量及内存增长仍需单独验证。未测项目不视为通过，帧率与功耗没有严格同场景对比结论。原始截图、日志和设备材料仅在本地保留。

## 源码入口

- `app/src/main/java/io/github/threeinone/config/IndicatorConfig.kt`：默认值和边界。
- `app/src/main/java/io/github/threeinone/hook/ModuleEntry.kt`：gate、作用域宿主和回退。
- `app/src/main/java/io/github/threeinone/model/BatterySnapshot.kt`：电量解析。
- `app/src/main/java/io/github/threeinone/model/WifiStatus.kt`：Wi-Fi 判定。
- `app/src/main/AndroidManifest.xml`：Xposed 声明和刷新权限。
