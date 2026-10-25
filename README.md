# TimeTrace — Windows + Android 使用记录器（开源原型）

> **v0.1.0-dev · 可研究和试运行的代码原型，不是完整产品或预编译安装包。**

**目标：** 自动/手动采集 Windows、Android 上前台应用的使用区间，保存为一致的 JSONL，再离线合并，计算设备累计与跨设备去重使用时间。许可证：MIT。

## 30 秒理解现阶段完成度

| 模块 | 状态 | 说明 |
|---|---|---|
| Windows C++20 采集器 | ✅ 已写源码 | 1 秒采样、120 秒空闲阈值、每 30 秒落盘、Ctrl+C 停止 |
| Android Kotlin 原型 | ✅ 已写源码 | 手动授权、读取最近 24h 已结束应用事件、JSONL 导出 |
| 通用数据协议 | ✅ 已写源码 | `protocol/activity-segment.schema.json` |
| 跨设备数据分析 | ✅ 已测试 | 本地 Python 工具支持去重、按日查询和设备并集时长 |
| 实际 Windows 编译 / Android 真机测试 | ⏳ 待开发者在对应设备完成 | 生成环境无 Windows SDK 和 Android SDK，未声称已编译成功 |
| SQLite / 后台 Android WorkManager | 🔜 下一里程碑 | 目前 Windows JSONL 落盘；Android 手动读取 |
| 局域网自动配对同步 / Qt 图形界面 | 🔜 后续里程碑 | 尚未实现；当前通过文件手动传输 |

## 1. Windows 端立即运行

**前置：** Windows 10/11、Visual Studio 2022（安装“使用 C++ 的桌面开发”）、CMake 3.20+。

打开 PowerShell，在本项目根目录执行：

```powershell
cmake -S apps/windows -B apps/windows/build -G "Visual Studio 17 2022" -A x64
cmake --build apps/windows/build --config Release
.\apps\windows\build\Release\timetrace-collector.exe
```

运行后，在不同应用之间切换，至少持续使用 30 秒，然后按 `Ctrl+C` 停止采集。日志默认写入：

```
%LOCALAPPDATA%\TimeTrace\segments.jsonl
```

此原型默认识别 `.exe` 可执行文件名称，不记录窗口标题、网址、键盘内容、截图。没有前台窗口或超过 120 秒未输入时暂停计时。

**注意：** 阈值判断以采样时刻为准，视频播放等无键鼠输入场景会被标成空闲；某些 UWP / 高权限应用无法识别。电脑休眠、时钟跳变和关闭控制台窗口可能使少量边界时长不精确。

## 2. Android 端立即试运行

**前置：** Android 10+ 手机、Android Studio、JDK 17；首次构建需要联网下载 Gradle 插件与 Android SDK。

阅读 [`apps/android/README.md`](apps/android/README.md) 详细说明。Gradle 配置和 Kotlin 源码已附上；由于当前生成环境无法获取并验证 Gradle Wrapper JAR，**未打包 wrapper 脚本/JAR**。使用本机 Gradle 8.11.1 在 `apps/android` 执行：

```bash
gradle wrapper --gradle-version 8.11.1
```

然后 Android Studio 打开 `apps/android`，运行应用，在系统“使用情况访问”设置中给 TimeTrace 授权，点击“读取最近 24 小时”→“导出活动记录 JSONL”。

没有 Gradle 命令行时，可在 Android Studio 中新建 Empty Views Activity 项目，复制 `app/src/main` 下的 Kotlin 与 manifest，按 `app/build.gradle.kts` 适配配置。

使用 Android 文件管理器或 USB 将导出的 `timetrace-android.jsonl` 复制到电脑上。

## 3. 离线合并两台设备的活动

**前置：** Python 3.10+，不需要 pip 安装任何库。根目录 PowerShell 示例：

```powershell
python tools/merge_report.py "$env:LOCALAPPDATA\TimeTrace\segments.jsonl" ".\timetrace-android.jsonl" --tz-offset +08:00
```

筛选指定本地日期并保存结果：

```powershell
python tools/merge_report.py "$env:LOCALAPPDATA\TimeTrace\segments.jsonl" ".\timetrace-android.jsonl" --date 2026-10-26 --tz-offset +08:00 --json report.json
```

输出三种指标：**设备累计时长（各设备分别去重后求和）**、**跨设备并集时长** 和 **设备重叠时长**。相同设备的重复导入和同一设备内部重叠区间都会去重。

## 4. 运行已有测试

```bash
python -m unittest discover -s tests -v
```

本地已有 6 个合并与去重单元测试；GitHub Actions 配置了 Python 测试和 Windows CMake 构建。

## 5. 代码结构

```
apps/windows/          # Win32/C++20 控制台采集器（零第三方依赖）
apps/android/          # Kotlin/Android 平台采集原型
protocol/              # 规范化活动区间 JSON Schema
tools/                 # 离线跨设备统计脚本
tests/                 # Python 自动化测试
docs/                  # PRD、架构、路线图、Issues 模板
.github/workflows/     # CI
```

## 6. 当前原型的重要限制

- 没有 SQLite 和 HTTP 配对同步；数据通过 JSONL 交换。
- Windows 没有 Qt UI，也不是系统服务；关闭程序后就不再记录。
- Android 只在用户点击按钮时读取最近 24 小时的已完成事件；不保证完整历史，也不后台定期采集。
- `UsageStatsManager` 读取的生命周期事件**不等于真实目光关注或有效学习时间**。
- Windows 只收集可读到可执行文件路径的前台应用，未记录窗口名称（隐私优先）。
- 所有数据保存在设备本地；发送导出文件给别人就相当于分享你的使用记录，**请勿公开上传真实日志到 GitHub**。

## 7. 下一步建议

1. 在自己的 Windows 机器上先编译运行，验证记录是否可用。
2. 在 Android 真机执行“授权→读取→导出”，检查数据是否符合预期。
3. 运行离线合并脚本并检查重叠时间。
4. 将 Windows JSONL 持久化升级到 SQLite，并加入系统锁屏事件监听。
5. 将 Android 手动采集改成可恢复的 WorkManager 轮询/增量补采；再做安全局域网配对同步。

请参阅 [`docs/01_PRD.md`](docs/01_PRD.md) 和 [`docs/03_ROADMAP.md`](docs/03_ROADMAP.md) 了解完整愿景。两份规划文档描述的是**目标状态**，不表示这些功能已在本原型中实现。

## 8. 贡献和安全

欢迎提交 Issues 和 PR。请避免包含真实个人使用记录的数据样本。参见 `CONTRIBUTING.md`、`PRIVACY.md` 和 `SECURITY.md`。
