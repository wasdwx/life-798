# WaterWidget

WaterWidget 是一个面向慧生活798用户的第三方 Android 设备控制客户端，提供多账户管理、饮水设备启动、钱包充值、用水消费统计、桌面小部件、快捷设置磁贴与设备二维码添加功能。

> 非官方项目，与慧生活798服务提供方无关。请仅使用你本人有权访问的账户和设备，并遵守相关服务规则。

## 功能

- 饮水设备一键启动，接水结束后通知本次消费与估算水量
- 自动同步设备名称，支持设备别名、快捷移除和控制中心默认设备切换
- 短信登录与账户管理：设备登录用于同步和启动设备、钱包充值及 App 端任务；补充积分登录可完成支付宝端任务并获得更多积分
- 查看校园钱包余额，并通过支付宝完成充值
- 扫描二维码或手动添加设备编号
- 每日签到、积分任务与任务执行记录，通知栏实时显示任务进度与累计积分
- 每日定时运行全部账号任务，支持仅设备登录的账号；提供精确闹钟与后台运行设置入口
- 启动后可停止设备并查询结算，通知上的“结束提醒”仍只停止本地监测
- 检查个人版仓库更新，支持自动检查频率与预发布开关，不自动安装 APK
- 本地优先的今日 / 本月 / 本年消费与预计饮水量统计
- 桌面小部件、设备启动快捷设置磁贴
- 浅色、深色和跟随系统的显示模式

## 运行环境

- Android 13（API 33）及以上
- 仅提供 `arm64-v8a` 安装包，适用于现代 64 位 Android 设备

## 构建

项目采用 Gradle Kotlin DSL、Gradle 9.5、JDK 21、Kotlin 2.4 和 Jetpack Compose。源码通过 Gradle `sourceSets` 直接使用仓库根目录的 `src/main` 和 `src/test`。

### 配置参数

项目中的 API 地址、签名盐值等信息通过 `secrets.properties` 注入，**不会提交到版本控制**。构建前需手动创建：

1. 复制项目根目录的 `secrets.properties.example` 为 `secrets.properties`
2. 将其中的占位值替换为实际值

```bash
cp secrets.properties.example secrets.properties
# 然后编辑 secrets.properties 填入实际的 API_GATEWAY / SIGN_SALT / MINI_SIGN_SALT / API_CID
```

> **说明**：未配置 `secrets.properties` 时项目仍可正常编译，但构建出的 APK 因缺少必要参数无法连接服务端。

`SIGN_SALT` 用于 App 任务（客户端版本 `3.1.9`），`MINI_SIGN_SALT` 用于支付宝任务（`2.0.178`），不可混用。
GitHub Actions 也需配置同名的两个 secret；缺少任何发布配置时工作流会在生成正式 APK 前失败。

### 个人版发布规则

Tag 必须等于 `v` 加 `app/build.gradle.kts` 中的 `versionName`。
`v5.4.6` 这样的无后缀 tag 发布为正式版；带 `-personal.1`、`-rc.1` 等后缀的 tag 发布为预发布。
工作流在 APK 构建、签名验证、上传成功后才解除草稿状态，重新运行时也会按 tag 修正预发布标记。
应用检查 `wasdwx/life-798` 的已发布版本，忽略草稿及没有就绪 APK 的 release；预发布默认不提示。

自动任务默认关闭。在“我的 → 任务与更新”启用并允许精确闹钟后生效；重启只重排闹钟，不直接执行任务。
是否能准时运行仍受系统后台策略影响，可选择解除电池优化。任务不会自动启动饮水设备。

### 构建命令

```bash
# JVM 单元测试
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug

# 正式 Release（需设置签名环境变量）
./gradlew assembleRelease
```

APK 默认输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 隐私与数据

应用的账户和设备配置保存在本机。请不要将设备控制登录信息、账户数据或二维码内容分享给他人。

## 致谢与第三方项目

- [miuix](https://github.com/compose-miuix-ui/miuix)：使用 `miuix-ui` 的下拉刷新组件和 `miuix-blur` 的模糊能力，并在应用内适配刷新布局与状态衔接；Apache-2.0 License。
- [QuickieExtended](https://github.com/T8RIN/QuickieExtended)：提供基于 CameraX 与 ML Kit 的二维码扫描能力；MIT License。

上述项目及其代码继续遵循各自的许可证，本项目的 MIT License 不替代其许可证。

## 免责声明

本项目按现状提供，不对可用性、准确性或使用结果作任何保证，使用风险由使用者自行承担。

## 许可证

本项目采用 [MIT License](LICENSE)。
