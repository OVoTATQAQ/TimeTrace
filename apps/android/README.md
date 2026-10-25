# Android 采集原型（Kotlin）

## 功能

- Android 10 (API 29) 或更高版本
- 通过系统“使用情况访问”页面让用户手动授权
- 读取最近 24 小时应用活动事件并解析**已结束**的使用区间
- 统计各应用累计时长（原始包名）
- 写入应用私有存储并借助系统文件选择器导出 JSONL
- 不申请 INTERNET、无障碍、录屏、位置或录音权限

## 用 Android Studio 打开

本示例提供完整 Gradle 项目源码，但不包含 Gradle Wrapper 可执行 JAR 和脚本（当前环境无法可信获取官方二进制）。先安装 Android Studio、JDK 17 与 Gradle 8.11.1；在 `apps/android` 文件夹执行：

```bash
gradle wrapper --gradle-version 8.11.1
```

然后用 Android Studio 打开 `apps/android`，等待首次下载 Android Gradle Plugin 8.9.2、Kotlin 2.1.20 和 Android SDK 35。选择设备并点击 Run。

**如电脑未安装 Gradle 命令行：** 在 Android Studio 中新建一个 *Empty Views Activity* 工程，使用 `org.timetrace.app` 包名，然后将本目录 `app/src/main/` 下的清单与 Kotlin 代码复制进去，再将对应 `app/build.gradle.kts` 配置合并到新工程。也可先安装 Gradle CLI 再执行上面指令。

## 权限及局限

使用情况访问属于 Android 的特殊访问权限，不是运行时授权弹窗。使用者需要手动在系统设置中开启。对于屏幕多窗口、系统事件丢失、查询窗口起始/末尾的未完成活动，不保证能够推断完整前台区间。为了避免重复计算，程序仅导出已结束区间；本原型不会在后台定时采集，尚不适合连续多日无遗漏的追踪。

`ACTIVITY_RESUMED` 和 `ACTIVITY_PAUSED` 对应 Activity 生命周期而非精确的用户注视或互动，统计结果需谨慎解释。
