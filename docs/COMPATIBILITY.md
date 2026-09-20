# 功能边界与验收状态

## 精确 gate

| 条件 | 值 |
| --- | --- |
| 设备型号 | `PLK110`（一加 15 / OnePlus 15） |
| Android/API | 16 / API 36 |
| 系统构建 | `PLK110_16.0.3.502(CN01)` |
| SystemUI | `16.00.12` |
| 框架验证环境 | LSPosed Irena `1.9.2 (7249)`，API 100 |
| Xposed 接口 | legacy `minversion 93` |
| 作用域 | 仅 `com.android.systemui` |

任一条件不匹配，都不能宣称支持。模块会 fail closed，保留原图标；`minSdk=34` 只是安装边界，不扩大 SystemUI gate。

## 覆盖与排除

绘制位于 SystemUI 原有电池宿主内，覆盖主状态栏和锁屏状态栏的电量、Wi-Fi、蜂窝组合状态；锁屏可单独关闭。组合图标继承父级防烧屏移动和系统布局协调，但项目不增加独立移动计时器，不能保证 OLED 不老化。

明确排除：QS/控制中心独立图标、AOD、其他应用、Android Framework 全局注入和 SystemUI 之外的进程。

## 版本修复

- **0.1.5**：有效电量百分比不再因充放电 status 为 UNKNOWN 而丢失；仍可驱动进度、低电量颜色和自动数字，但不伪造充电/充满/暂停标记。消费初始 sticky 电量；关于入口读取运行时版本并打开作者主页。
- **0.1.6 P2-1**：Wi-Fi 互联网状态只使用当前 Wi-Fi Network capabilities；缺少当前 capabilities 时暂时显示连接中，避免混入蜂窝验证。
- **0.1.6 P2-2**：辅助功能描述只在替换 active 时接管，释放时恢复最新原生描述和重要性（包括 null）；非 active 宿主不改写。
- **0.1.6 P2-3**：刷新广播使用 signature permission；合并刷新请求不推迟首次 deadline，执行期间的新请求最多安排一次后续刷新。

## 证据边界

| 项目 | 状态 |
| --- | --- |
| 0.1.6 本地构建与 Lint | 已通过 |
| 0.1.6 本地 JUnit/Robolectric | 55 项通过 |
| 0.1.1 目标设备真实替换 | 用户截图证明曾运行 |
| 0.1.1 外观 | 截图显示 tint/spacing 问题 |
| 0.1.2-0.1.6 后续修复 | 尚无完整真机验收闭环 |
| 全机型/全固件支持 | 未声明 |
| 真实 hook、锁屏、重启恢复、网络切换 | 仍需设备逐项验收 |

公开文档不得把 55 项本地测试描述成全量真机验收，也不得宣传全机型支持。

## 源码入口

- `app/src/main/java/io/github/threeinone/config/IndicatorConfig.kt`：默认值和边界。
- `app/src/main/java/io/github/threeinone/hook/ModuleEntry.kt`：gate、作用域宿主和回退。
- `app/src/main/java/io/github/threeinone/model/BatterySnapshot.kt`：电量解析。
- `app/src/main/java/io/github/threeinone/model/WifiStatus.kt`：Wi-Fi 判定。
- `app/src/main/AndroidManifest.xml`：Xposed 声明和刷新权限。
