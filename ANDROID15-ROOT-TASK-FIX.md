# Android 15+ / 澎湃 OS 3 失效修复说明

> 适用机型实测：**小米 17 Pro Max**（`2509FPN0BC`）/ Android 16 / 澎湃 OS 3（V816，SDK 36）

## 一、现象

在 Android 15 及以上系统中，MRSS 的投屏能力**整体失效**：

- 控制中心「切换至背屏」快捷开关点了没反应
- `mrss://switch?current=1` 无效果
- 充电动画 / 背屏通知也不会被投送到背屏
- 背屏始终停在官方的 `SubScreenLauncher`

界面本身正常，Shizuku 授权也正常（应用内显示「一切就绪」），但就是不动。

## 二、根因

MRSS 的投屏动作是在 Shizuku 的 shell/root 进程里执行下面这条命令完成的：

```
service call activity_task 50 i32 <taskId> i32 <displayId>
```

其中 `50` 是**硬编码的 binder transaction code**。

AOSP 在 **Android 15（API 35）** 把 `ActivityTaskManager` 的 Task 体系重构为 RootTask，
接口随之改名，transaction code 也变了：

| 系统版本 | 接口 | code |
|---|---|---|
| Android 14 及以前 | `moveTaskToDisplay(int taskId, int displayId, boolean onTop)` | **50** |
| Android 15 及以后 | `moveRootTaskToDisplay(int rootTaskId, int displayId)` | **51** |

在 Android 15+ 上，code 50 已经指向**另一个**接口：命令既不报错、也不移动任务 ——
所以表现出来就是「点了毫无反应」。

### 复现 / 验证（本机实测）

```bash
# 取当前前台任务的 rootTaskId
am stack list | grep -E '^  taskId=' | head -1

# 旧 code：任务纹丝不动
service call activity_task 50 i32 <id> i32 1

# 新 code：任务立刻移到背屏
service call activity_task 51 i32 <id> i32 1
```

也可以用反射直接确认新系统上的接口与 code：

```java
Class<?> stub = Class.forName("android.app.IActivityTaskManager$Stub");
stub.getDeclaredField("TRANSACTION_moveRootTaskToDisplay").getInt(null);   // -> 51
stub.getDeclaredField("TRANSACTION_moveTaskToDisplay");                    // -> NoSuchFieldException
```

## 三、修复内容

新增 `DisplayCompat`（`android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/DisplayCompat.java`），
按系统版本返回正确的 code 与命令；把全部 7 处硬编码调用统一收敛到它：

| 文件 | 位置 | 用途 |
|---|---|---|
| `TaskService.java` | `moveTaskToDisplay` | 核心投屏 / 返回主屏 |
| `ChargingService.java` | 充电动画 | 把充电动画 Activity 投到背屏 |
| `MainActivity.java` | 通知动画 | 把通知 Activity 投到背屏 |
| `NotificationService.java` | 通知动画 | 同上 |
| `RearScreenChargingActivity.java` | 3 处 | 充电动画被抢占后的恢复投屏 |

关于参数语义：Android 15+ 传的是 **rootTaskId**。在单窗口（全屏）场景下，
`am stack list` 中 RootTask 的 id 与其下 task 的 id 相同，因此沿用原本解析出的
`taskId` 是安全的；只有在分屏时两者才可能不同。

## 四、其它已核对项

顺带在 Android 16 上逐条验证了其余特权命令，**均仍可用，无需修改**：

| 功能 | 命令 | 结论 |
|---|---|---|
| 取前台应用 / 任务 | `am stack list` | ✅ 输出格式与原解析逻辑兼容 |
| 背屏截图 | `screencap -d <uniqueId>` | ✅ |
| 背屏唤醒 | `input -d 1 keyevent KEYCODE_WAKEUP` | ✅ |
| 背屏 DPI | `wm density -d 1` / `wm density <n> -d 1` | ✅ |
| 背屏旋转 | `wm user-rotation -d 1 lock <n>` | ✅ |
| 跨屏启动 | `am start --display 1 ...` | ✅ |

> ⚠️ 容易踩的坑：`input -d` 与 `screencap -d` 接受的参数类型**正好相反** ——
> `input -d` 要整数 displayId（`1`），`screencap -d` 要 SurfaceFlinger 的 uniqueId
> （`local:4630946457447247252` 那种，取数字部分即可）。写错会报
> `Display Id '1' is not valid`。

## 五、已知残留问题（非本次修复范围）

- `TaskService.forceStatusBarToMainDisplay()` 里的 `wm set-display-type 0 home`
  在 Android 15+ 已被移除，执行只会打印 `Unknown command: set-display-type`。
  该分支对主流程没有实际影响，建议后续直接删掉。
- `TaskService.getDisplayRotation()` 在背屏未锁定时读到的是 `free`，
  现有解析会返回 0（当作 0°）。功能可用，只是 UI 显示可能不准。

## 六、实测结果（小米 17 Pro Max / Android 16 / 澎湃 OS 3）

| 路径 | 结果 |
|---|---|
| 控制中心「切换至背屏」快捷开关 | ✅ 任务移到背屏并稳定停留（连续观察 > 56 s） |
| `mrss://switch?current=1` 投屏 | ✅ |
| `mrss://return?current=1` 返回主屏 | ✅ |
| `mrss://screenshot` 背屏截图 | ✅ 生成 `Pictures/RearDisplay/RD_*.png` |

## 七、构建

正常构建（需要完整 Flutter 工具链）：

```bash
flutter pub get
flutter build apk --release --split-per-abi --target-platform android-arm64
```

当手边只有手机、没有 Flutter 工具链时，可用 `tools/android15-patch/` 里的脚本
对已安装的 APK 做 **dex 级最小补丁**（只改 transaction code，其余一切原样保留），
再用 `apksigner` 重新签名。
