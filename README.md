# MiRearScreenSwitcher (MRSS)

小米 17 Pro、17 Pro Max 等双屏设备的背屏一键切换工具。

本仓库是 [AntiOblivionis/MiRearScreenSwitcher](https://github.com/AntiOblivionis/MiRearScreenSwitcher) 的 fork。原仓库已归档停更，原作者声明：

> 鉴于小米3.8解锁节形势，加之shizuku限制实在过多，本项目无限期停更。

## 版本说明

本仓库提供两个版本。两者功能相同，获取特权的方式不同，按你的设备情况选一个安装。

| 版本 | 特权方式 | 前置条件 | 维护状态 |
| --- | --- | --- | --- |
| ROOT 版 | `su`，root 身份 | 设备已 root | 主力维护 |
| Shizuku 版 | Shizuku，shell 身份 | 安装 Shizuku 并用 ADB 启动 | 已修复，不再更新 |

下载地址：

- ROOT 版：[v3.3.0-root](https://github.com/APEIRlA/MiRearScreenSwitcher/releases/tag/v3.3.0-root)
- Shizuku 版：[v3.2.1-android15](https://github.com/APEIRlA/MiRearScreenSwitcher/releases/tag/v3.2.1-android15)

**ROOT 版**通过 `su` 以 root 身份执行特权命令。首次打开应用会弹出授权框，授予后不再提示。设备已经 root，就用这个版本。

**Shizuku 版**通过 Shizuku 拿到 shell 身份，设备不需要 root。需要先安装 Shizuku，再用 ADB 或无线调试启动它。Android 15 及以上系统导致快捷开关失效的问题已经修复，日常使用正常。

Shizuku 版今后不再更新，但会一直可用。新功能和后续的问题修复都进 ROOT 版。

两个版本的包名相同，安装第二个会覆盖第一个，不要同时安装。

## 功能特性

- 快捷切换：控制中心快捷开关，一键把应用切到背屏
- 背屏截图：一键截取背屏画面，存入相册
- 背屏录屏：悬浮窗控制，录制背屏画面并存入 Movies 目录
- 后台可用：应用在后台也能触发切换
- 背屏常亮：可选，防止背屏自动息屏
- 背屏旋转：支持 0°、90°、180° 和 270° 四个方向，旋转后应用自动恢复
- DPI 调整：实时调整背屏显示密度，推荐 260 到 350
- 背屏遮盖检测：可选，基于接近传感器
- 任务隐藏：主应用不出现在最近任务列表
- URI 调用：支持 `mrss://` 协议，可被 Tasker、MacroDroid 等应用调用
- 媒体库集成：截图和录制完成后自动刷新到相册

## 使用前提

1. 设备：支持背屏的小米手机，如小米 17 Pro、17 Pro Max
2. 权限：ROOT 版需要设备已 root；Shizuku 版需要安装并启动 Shizuku

## 使用方法

### 初次设置

1. 安装 MRSS
2. ROOT 版确认设备已 root；Shizuku 版确认 Shizuku 已启动
3. 打开 MRSS，授予权限
4. 等待状态显示“一切就绪”

### 添加快捷开关

1. 从屏幕顶部下拉，打开控制中心
2. 点击编辑按钮
3. 添加以下快捷开关：
   - 切换至背屏：把当前应用投放到背屏
   - 获取背屏截图：截取背屏画面并保存
   - 背屏录制：录制背屏画面，可选
4. 退出编辑

### 日常使用

切换应用到背屏：

1. 打开要切换的应用
2. 下拉控制中心
3. 点击“切换至背屏”
4. 应用切到背屏，控制中心自动收起
5. 翻转手机，在背屏查看

截取背屏画面：

1. 下拉控制中心
2. 点击“获取背屏截图”，控制中心自动收起
3. 截图存入相册

录制背屏画面：

1. 下拉控制中心
2. 点击“背屏录制”
3. 悬浮窗出现，点击红色圆形按钮开始录制
4. 录制中按钮变为方形，再次点击停止
5. 视频存入 Movies/MRSS_*.mp4

返回主屏：

- 点击通知“点击将应用切换回主屏”
- 或者在背屏退出应用，通知自动消失

调整背屏显示：

- DPI 调整：在应用内设置，推荐 260 到 350
- 旋转控制：支持 0°、90°、180° 和 270°
- 背屏常亮：在应用内开关
- 背屏遮盖检测：在应用内开关，基于接近传感器

几点说明：

- 应用在后台或已退出，快捷开关依然可用
- MRSS 不出现在最近任务列表，避免误清理
- 支持 URI 调用，例如 `mrss://switch?current=1`

## 技术实现

- Flutter：跨平台 UI 框架，Material 3 设计
- ROOT 版：`RootAccess` 检测 root 授权并缓存结果，`ShellExecutor` 维持常驻 root shell 会话执行命令
- Shizuku 版：通过 AIDL 服务在 Shizuku 进程中以 shell 身份执行命令
- DisplayCompat：按系统版本选择正确的 binder transaction code，适配 Android 15 及以上系统
- Quick Settings Tile：系统级快捷开关，承载切换、截图、录屏
- ActivityTaskManager：通过 system service 调用实现显示切换
- Foreground Service 加 WakeLock：前台服务持有唤醒锁，支撑背屏常亮
- Keycode Wakeup：用 `input keyevent KEYCODE_WAKEUP` 唤醒背屏
- Media Scanner：刷新媒体库，截图和录制出现在相册
- 3D 动画：自定义 Canvas 绘制，非线性动画，重力感应液体效果
- URI 协议：支持 `mrss://` 外部调用

## 权限说明

- `android.permission.WAKE_LOCK`：保持背屏常亮
- `android.permission.FOREGROUND_SERVICE`：前台服务权限
- `android.permission.SYSTEM_ALERT_WINDOW`：悬浮窗权限，录屏功能需要

Shizuku 版另外需要 `moe.shizuku.manager.permission.API_V23`，用于执行特权操作。

## 开发构建

```bash
# 安装依赖
flutter pub get

# 构建 Debug APK
flutter build apk --debug

# 构建 Release APK，arm64-v8a，含代码混淆和资源压缩
flutter build apk --release --split-per-abi --target-platform android-arm64
```

生成的 APK 位于 `build/app/outputs/flutter-apk/app-arm64-v8a-release.apk`。

## 技术细节

### 核心功能

1. 智能应用切换

   - 通过 Quick Settings Tile 快捷开关触发
   - 用 `am stack` 命令获取前台应用
   - 通过 `service call activity_task` 切换显示屏
   - 自动结束系统 Launcher，防止挤占
   - Toast 显示具体应用名

2. 背屏录屏

   - 悬浮窗控制，红色圆形和方形按钮
   - 用 screenrecord 录制背屏画面
   - 持续唤醒背屏，防止录制中断
   - 视频存入 Movies/MRSS_*.mp4
   - 悬浮窗位置可拖动

3. 前台 Service 保活

   - 统一的“MRSS 内核服务”前台通知
   - 可选的背屏常亮，基于 `FLAG_KEEP_SCREEN_ON`
   - 点击通知可快速返回主屏
   - 主应用隐藏于最近任务，防止误清理

4. 智能监控与清理

   - 每 2 秒检测背屏前台应用状态
   - 应用退出或切换时停止服务并清除通知
   - 防止多应用同时投放

5. DPI 动态调整

   - 实时调整背屏显示密度
   - 用 `wm density` 命令修改
   - 一键还原默认设置
   - 推荐范围 260 到 350

6. 背屏旋转控制

   - 支持 0°、90°、180° 和 270°
   - 用 `wm user-rotation -d 1` 独立控制背屏
   - 旋转后应用自动恢复
   - 实时显示当前旋转状态

7. 背屏遮盖检测

   - 可选的接近传感器检测
   - 在应用内开关

8. URI 协议

    - `mrss://switch?current=1`：切换当前应用
    - `mrss://switch?packageName=xxx`：切换指定应用
    - `mrss://return?current=1`：返回主屏
    - `mrss://screenshot`：截图
    - `mrss://config?dpi=xxx&rotation=x`：配置，rotation 取值 0 到 3，与 adb shell 一致

### 性能优化

- 代码混淆，ProGuard 和 R8
- 资源压缩
- 只包含 arm64-v8a 架构
- APK 体积优化

## 更新日志

### V3.7.0

删除功能：

- 删除充电动画与充电动画常亮。HyperOS 自带充电特效，功能重复，且常亮模式有烧屏风险。
- 删除背屏通知推送。HyperOS 已有背屏通知，不再重复实现。

调整：

- 首页底部的使用教程、作者信息、团队信息、打赏和交流群入口，合并为一个项目主页入口，统一跳转 GitHub。
- 移除通知权限 POST_NOTIFICATIONS、通知监听服务和 QUERY_ALL_PACKAGES 权限，安装时申请更少。
- 清理相应代码、资源和多语言文案，APK 体积下降。

验证机型：

- 小米 17 Pro Max（2509FPN0BC），HyperOS 4.0.2.0.XPBCNXM.D01（Android 17，安全更新 2026 年 10 月 1 日），arm64-v8a，KernelSU（ksud 3.3.0）

### V3.3.0（ROOT 版）

- 改为通过 `su` 以 root 身份执行特权命令，不再依赖 Shizuku
- 新增 `RootAccess`：检测 root 授权，缓存结果，避免重复启动 `su`
- 新增 `ShellExecutor`：维持常驻 root shell 会话。只在建立会话时触发一次授权提示，后台高频命令不再反复弹窗
- 原 AIDL 服务 `ITaskService` 的 21 个方法改为静态工具类，各进程直接调用
- 保留 Android 15 及以上系统的适配

### V3.2.1（Shizuku 版）

- 修复 Android 15 及以上系统快捷开关全部失效的问题
- 适配 Task 到 RootTask 的重构，binder transaction code 由 `50` 改为 `51`
- 新增 `DisplayCompat`，按系统版本自动选择正确的 code

### V3.2.0（2025）

新功能：

- 仅倒扣手机时通知：通过主屏接近传感器判断手机是否倒扣，仅在倒扣时显示背屏通知

优化：

- 将“仅在锁屏时通知”改为“仅倒扣手机时通知”，用接近传感器检测更准确
- 实时监听主屏接近传感器状态

### V3.1.3（2025）

新功能：

- 完整的多语言支持，简、繁、英三种语言
- 通知内容和 Toast 消息本地化

优化：

- 修复英文模式下 UI 文本溢出
- 优化多语言环境下的文本显示

### V3.1.2（2025）

优化：

- 应用选择列表中，选中的应用自动置顶
- 充电动画和通知动画的 launcher 结束逻辑，改为仅在动画启动前结束一次

技术优化：

- 移除充电动画常亮循环中的持续结束 launcher 逻辑
- 移除通知动画循环中的持续结束 launcher 逻辑
- 应用选择列表排序逻辑优化，切换选择时自动重排

### V3.1.1（2025）

问题修复：

- 修复 ChargingService 缺少前台服务保活导致的功能失效
- 修复 NotificationService 设置冲突导致的状态不一致
- 统一所有服务使用内核服务通知，避免通知栏堆积

技术优化：

- ChargingService 使用统一的内核服务通知保活
- NotificationService 设置加载逻辑优化，避免状态冲突
- 服务稳定性提升，减少被系统结束的概率

### V3.1.0（2025）

新功能：

- 充电动画图标从灯泡改为闪电
- 解除通知自动销毁时间的 60 秒上限

问题修复：

- 修复背屏通知开关状态持久化问题，重启后状态正确恢复
- 修复通知服务开关状态同步问题，关闭后再开启可正常工作
- 修复通知服务每次收到通知时重新加载开关状态

### V3.0.0（2025）

重大更新：

- 采用 GPL-3.0 开源协议
- 全新充电动画，3D 闪电容器加重力感应液体效果
- 通知推送，系统通知实时显示到背屏
- 背屏录屏，悬浮窗控制录制
- URI 协议支持，可被 Tasker 等应用控制
- 四色渐变加超椭圆圆角设计

## 开源协议

- V3.0.0 及以后版本：GPL-3.0，详见 [LICENSE](LICENSE)
- V3.0.0 以前版本：MIT

V3.0.0 以前的所有版本继续按 MIT 协议自由使用。

## 团队

作者：AntiOblivionis

- QQ：319641317
- Github：[APEIRlA](https://github.com/APEIRlA/)
- 项目主页：[MiRearScreenSwitcher](https://github.com/APEIRlA/MiRearScreenSwitcher)
- 问题反馈：[Issues](https://github.com/APEIRlA/MiRearScreenSwitcher/issues)
- Bilibili：[罗德岛T0驭械术师澄闪](https://space.bilibili.com/407059627)

首席测试官：汐木泽

- 提供关键测试反馈和功能建议

交流与支持：

问题反馈和建议统一走 GitHub Issues，不再维护 QQ 群和打赏渠道。

## AI 协作开发

本项目由作者与以下 AI 助手共同开发：

- Cursor
- Claude-4.5-Sonnet
- GPT-5
- Gemini-2.5-Pro

## 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku)：为 Shizuku 版提供特权 API
- Flutter 团队：跨平台框架
- Xiaomi HyperOS 小米澎湃 OS 团队：小米手机背屏功能

## 版权声明

### 图标归属

1. 应用图标：本应用图标直接使用小米 HyperOS 系统中的图标资源。根据[小米操作系统用户协议](https://terms.miui.com/doc/eula/cn.html)，小米、MIUI、Xiaomi HyperOS 等商标及相关图标的版权归小米科技有限责任公司所有。本应用为第三方辅助工具，与小米官方无关。如有侵权请联系删除。


### 免责声明

本应用为开源项目，基于 ROOT 权限或 Shizuku 实现背屏功能扩展，仅供学习交流使用。使用本应用即表示你理解并同意：

- 本应用非小米官方应用，与小米公司无关
- 使用本应用的风险由你自行承担
- 开发者不对使用本应用造成的损失负责
- 如有侵权，请联系删除
