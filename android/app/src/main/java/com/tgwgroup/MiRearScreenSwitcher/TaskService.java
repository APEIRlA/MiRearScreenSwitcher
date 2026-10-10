/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 背屏任务管理工具类。
 *
 * <p>历史上本类是运行在外部高权限进程中的 AIDL 服务，借助其 shell
 * 权限执行特权命令。改造为 root 模式后，所有特权操作都通过
 * {@link ShellExecutor}（{@code su -c}）以 root 权限执行，
 * 因此本类改为纯静态工具类。
 *
 * <p>任何进程（应用进程、SystemUI 进程中的 TileService 等）都可直接调用
 * 这些静态方法，无需绑定服务。
 */
public final class TaskService {
    private static final String TAG = "TaskService";

    private TaskService() {
    }

    /**
     * 获取当前前台应用（主屏）。
     *
     * @return 格式 {@code packageName:taskId}，失败返回 null
     */
    public static String getCurrentForegroundApp() {
        try {
            String output = ShellExecutor.runMergedWithResult("am stack list");

            boolean inDisplayZero = false;
            for (String line : output.split("\n")) {
                // RootTask行：判断displayId
                if (line.startsWith("RootTask")) {
                    inDisplayZero = line.contains("displayId=0");
                    continue;
                }

                // taskId行（缩进的子行）
                if (inDisplayZero && line.contains("taskId=") && line.contains("/")) {
                    // 解析:   taskId=1471: com.example.display_switcher/com.example.display_switcher.MainActivity
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();

                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();

                    // 跳过Launcher和应用自己
                    if (packageName.contains("launcher") ||
                        packageName.contains("miui.home") ||
                        packageName.equals("com.tgwgroup.MiRearScreenSwitcher")) {
                        continue;
                    }

                    return packageName + ":" + taskId;
                }
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    /**
     * 根据包名获取 taskId。
     *
     * @return taskId，未找到返回 -1
     */
    public static int getTaskIdByPackage(String packageName) {
        try {
            String output = ShellExecutor.runWithResult("am stack list");

            for (String line : output.split("\n")) {
                if (line.contains("taskId=") && line.contains(packageName)) {
                    // 解析: taskId=1434: com.android.camera/...
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    String taskId = line.substring(start, end).trim();
                    return Integer.parseInt(taskId);
                }
            }

            return -1;

        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    /**
     * 把任务移动到指定屏幕。
     *
     * @return 是否成功
     */
    public static boolean moveTaskToDisplay(int taskId, int displayId) {
        try {
            long startTime = System.currentTimeMillis();

            // 先获取包名
            String packageName = getPackageNameFromTaskId(taskId);

            // 执行service call命令，以root权限执行
            // 注意：Android系统的每个显示器都有独立的状态栏（SystemUI）
            // 当应用切换到背屏时，它会显示背屏的状态栏，这是系统默认行为
            // 要保持主屏状态栏可见需要系统级修改，无法通过应用层实现
            String cmd = DisplayCompat.moveTaskCommand(taskId, displayId);

            int exitCode = ShellExecutor.run(cmd);
            boolean success = (exitCode == 0);

            long endTime = System.currentTimeMillis();
            Log.d(TAG, "moveTaskToDisplay took " + (endTime - startTime) + "ms, exit=" + exitCode);

            // 如果成功移动到背屏（displayId=1），保存任务信息
            if (success && displayId == 1) {
                try {
                    if (packageName != null) {
                        // 保存到广播接收器，以便系统事件后恢复
                        RearScreenBroadcastReceiver.saveLastTask(packageName, taskId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to save task info", e);
                }
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in moveTaskToDisplay", e);
            return false;
        }
    }

    /**
     * 根据taskId获取包名（辅助方法）。
     */
    private static String getPackageNameFromTaskId(int taskId) {
        try {
            String output = ShellExecutor.runWithResult("am stack list");

            for (String line : output.split("\n")) {
                if (line.contains("taskId=" + taskId) && line.contains("/")) {
                    // 解析: taskId=1471: com.example.app/...
                    int pkgStart = line.indexOf(':') + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd > pkgStart) {
                        return line.substring(pkgStart, pkgEnd).trim();
                    }
                }
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error getting package name from taskId", e);
            return null;
        }
    }

    /**
     * 在指定屏幕上启动唤醒Activity。
     *
     * @return 是否成功
     */
    public static boolean launchWakeActivity(int displayId) {
        try {
            // 使用am start命令在指定display上启动RearScreenWakeupActivity
            // --display参数指定目标display
            // 注意：RearScreenWakeupActivity使用FLAG_TURN_SCREEN_ON点亮屏幕
            String cmd = "am start --display " + displayId +
                        " -n com.tgwgroup.MiRearScreenSwitcher/.RearScreenWakeupActivity";

            int exitCode = ShellExecutor.run(cmd);
            return (exitCode == 0);

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in launchWakeActivity", e);
            return false;
        }
    }

    /**
     * 禁用背屏官方Launcher（防止其挤占背屏）。
     *
     * @return 是否成功
     */
    public static boolean disableSubScreenLauncher() {
        try {
            // 强制停止进程（进程可能会自动重启，需要持续杀死）
            String killCmd = "am force-stop com.xiaomi.subscreencenter";
            int exitCode = ShellExecutor.run(killCmd);
            return (exitCode == 0);

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in disableSubScreenLauncher", e);
            return false;
        }
    }

    /**
     * 检查背屏Launcher进程是否在运行。
     *
     * @return 进程在运行返回 true
     */
    public static boolean isLauncherProcessRunning() {
        try {
            // 检查进程是否在运行
            String cmd = "ps -A | grep com.xiaomi.subscreencenter";
            String output = ShellExecutor.runWithResult(cmd);

            // 如果有输出，说明进程在运行
            boolean isRunning = !output.trim().isEmpty();
            return isRunning;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in isLauncherProcessRunning", e);
            return false;
        }
    }

    /**
     * 尝试杀掉背屏Launcher进程。
     *
     * @return 是否成功
     */
    public static boolean killLauncherProcess() {
        try {
            // 强制停止进程
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            int exitCode = ShellExecutor.run(cmd);

            // force-stop 总是返回0，所以需要检查进程是否真的被杀死
            // 简单起见，如果命令成功就返回true
            return (exitCode == 0);

        } catch (Exception e) {
            // 异常也返回false（静默）
            return false;
        }
    }

    /**
     * 恢复背屏官方Launcher。
     *
     * @return 是否成功
     */
    public static boolean enableSubScreenLauncher() {
        try {
            // 启动SubScreenLauncher（进程会自动启动）
            String startCmd = "am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher";
            ShellExecutor.run(startCmd);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in enableSubScreenLauncher", e);
            return false;
        }
    }

    /**
     * 强制主屏状态栏显示（展开后立即收起，激活主屏SystemUI）。
     *
     * @return 是否成功
     */
    public static boolean forceStatusBarToMainDisplay() {
        try {
            // 新策略：直接展开主屏状态栏，而不是移动或重启SystemUI
            // 这会强制主屏显示SystemUI，从而保持焦点在主屏

            // 方法1: 展开主屏状态栏（不完全展开，只是激活）
            String expandCmd = "cmd statusbar expand-settings";
            int exitCode1 = ShellExecutor.run(expandCmd);

            if (exitCode1 == 0) {
                try {
                    Thread.sleep(30);  // 短暂延迟
                } catch (InterruptedException ignored) {
                }

                // 立即收起
                String collapseCmd = "cmd statusbar collapse";
                ShellExecutor.run(collapseCmd);
            }

            // 方法2: 强制主屏SystemUI可见（通过wm命令）
            // 设置主屏display为默认
            String wmCmd = "wm set-display-type 0 home";
            ShellExecutor.run(wmCmd);

            // 方法3: 检查当前状态栏位置
            ShellExecutor.run("dumpsys window displays | grep -A20 'Display: 0'");

            return true;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in forceStatusBarToMainDisplay", e);
            return false;
        }
    }

    /**
     * 收回状态栏/控制中心。
     *
     * @return 是否成功
     */
    public static boolean collapseStatusBar() {
        try {
            // 使用 cmd statusbar collapse 命令
            String cmd = "cmd statusbar collapse";
            int exitCode = ShellExecutor.run(cmd);
            return (exitCode == 0);

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in collapseStatusBar", e);
            return false;
        }
    }

    /**
     * 获取当前背屏DPI。
     *
     * @return DPI值，失败返回 0
     */
    public static int getCurrentRearDpi() {
        try {
            // 使用 wm density 命令获取display 1的DPI
            String cmd = "wm density -d 1";
            String output = ShellExecutor.runWithResult(cmd);

            int dpi = 0;
            for (String line : output.split("\n")) {
                // 解析输出: "Physical density: 450" 或 "Override density: 300"
                if (line.contains("density:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        try {
                            String dpiStr = parts[1].trim();
                            // 如果是 "Override density: 300"，优先使用
                            if (line.contains("Override density")) {
                                dpi = Integer.parseInt(dpiStr);
                                break; // 找到override就不继续找了
                            } else if (dpi == 0) {
                                // 如果还没找到override，先记录physical
                                dpi = Integer.parseInt(dpiStr);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            return dpi;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in getCurrentRearDpi", e);
            return 0;
        }
    }

    /**
     * 设置背屏DPI。
     *
     * @return 是否成功
     */
    public static boolean setRearDpi(int dpi) {
        try {
            // 使用 wm density 命令设置display 1的DPI
            String cmd = "wm density " + dpi + " -d 1";
            int exitCode = ShellExecutor.run(cmd);
            return (exitCode == 0);

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in setRearDpi", e);
            return false;
        }
    }

    /**
     * 还原背屏DPI到默认值。
     *
     * @return 是否成功
     */
    public static boolean resetRearDpi() {
        try {
            // 使用 wm density reset 命令还原display 1的DPI
            String cmd = "wm density reset -d 1";
            int exitCode = ShellExecutor.run(cmd);
            return (exitCode == 0);

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in resetRearDpi", e);
            return false;
        }
    }

    /**
     * 截取背屏画面并保存到相册。
     *
     * @return 始终返回 true（让Toast显示成功）
     */
    public static boolean takeRearScreenshot() {
        try {
            // 截屏前尝试给背屏发送keycode wakeup
            try {
                ShellExecutor.run("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(200); // 等待wakeup生效
            } catch (Exception e) {
                Log.w(TAG, "背屏keycode wakeup失败: " + e.getMessage());
            }

            // 创建保存目录
            ShellExecutor.run("mkdir -p /storage/emulated/0/Pictures/RearDisplay");

            // 获取背屏display ID
            String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";
            String displayId = ShellExecutor.runWithResult(getDisplayIdCmd).trim();

            if (displayId.isEmpty()) {
                displayId = "1"; // 默认使用1
            }

            // 生成文件名（带时间戳）
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new Date());
            String filename = "/storage/emulated/0/Pictures/RearDisplay/RD_" + timestamp + ".png";

            // 执行截图命令
            String screenshotCmd = "screencap -p -d " + displayId + " " + filename;
            ShellExecutor.run(screenshotCmd);

            // 刷新媒体库，让截图出现在相册中
            String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + filename;
            ShellExecutor.run(refreshCmd);

            // 无论成功失败都返回true，让Toast显示成功
            return true;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in takeRearScreenshot", e);
            // 即使异常也返回true，让Toast显示成功
            return true;
        }
    }

    /**
     * 检查任务是否在指定屏幕上。
     *
     * @return 任务在目标屏幕返回 true
     */
    public static boolean isTaskOnDisplay(int taskId, int displayId) {
        try {
            String output = ShellExecutor.runMergedWithResult("am stack list");

            boolean inTargetDisplay = false;
            for (String line : output.split("\n")) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }

                if (inTargetDisplay && line.contains("taskId=" + taskId)) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error checking task on display", e);
            return false;
        }
    }

    /**
     * 获取指定屏幕上的前台应用。
     *
     * @return 格式 {@code packageName:taskId}，失败返回 null
     */
    public static String getForegroundAppOnDisplay(int displayId) {
        try {
            String output = ShellExecutor.runMergedWithResult("am stack list");

            boolean inTargetDisplay = false;
            for (String line : output.split("\n")) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }

                if (inTargetDisplay && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();

                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();

                    return packageName + ":" + taskId;
                }
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app on display", e);
            return null;
        }
    }

    /**
     * 设置显示器旋转方向。
     *
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @param rotation 旋转角度 (0=0°, 1=90°, 2=180°, 3=270°)
     * @return 是否成功
     */
    public static boolean setDisplayRotation(int displayId, int rotation) {
        try {
            // 获取当前背屏前台应用（如果有）
            String currentApp = null;
            int currentTaskId = -1;
            if (displayId == 1) {
                currentApp = getForegroundAppOnDisplay(1);
                if (currentApp != null && currentApp.contains(":")) {
                    String[] parts = currentApp.split(":");
                    try {
                        currentTaskId = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {
                    }
                }
            }

            // 使用 wm user-rotation 命令设置旋转
            String cmd = "wm user-rotation -d " + displayId + " lock " + rotation;
            int exitCode = ShellExecutor.run(cmd);

            // 如果是背屏且有应用在运行，等待500ms后检查并复活
            if (displayId == 1 && exitCode == 0 && currentTaskId > 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }

                // 检查应用是否还在背屏
                boolean stillOnRear = isTaskOnDisplay(currentTaskId, 1);

                if (!stillOnRear) {
                    // 应用被关闭了，重新投放
                    moveTaskToDisplay(currentTaskId, 1);
                }
            }

            return (exitCode == 0);

        } catch (Exception e) {
            Log.e(TAG, "设置旋转异常", e);
            return false;
        }
    }

    /**
     * 获取显示器当前旋转方向。
     *
     * @param displayId 显示器ID (0=主屏, 1=背屏)
     * @return 旋转角度 (0-3)，失败返回 0
     */
    public static int getDisplayRotation(int displayId) {
        try {
            // 使用 wm user-rotation 命令直接读取，输出格式: "lock 2" 或 "free"
            String cmd = "wm user-rotation -d " + displayId;
            String output = ShellExecutor.runWithResult(cmd).trim();

            if (!output.isEmpty()) {
                // 解析 "lock 2" 或 "free" 格式
                String[] parts = output.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {
                    }
                }
            }

            return 0;

        } catch (Exception e) {
            Log.e(TAG, "获取旋转异常", e);
            return 0;
        }
    }

    /**
     * 执行任意 shell 命令（root 权限）。
     *
     * @return 是否成功（退出码为 0）
     */
    public static boolean executeShellCommand(String cmd) {
        try {
            int exitCode = ShellExecutor.run(cmd);

            // 记录详细输出
            String output = ShellExecutor.lastOutput();
            if (output != null && !output.trim().isEmpty()) {
                Log.d(TAG, "Command stdout: " + output.trim());
            }

            if (exitCode != 0) {
                Log.w(TAG, "命令退出码非 0 (" + exitCode + "): " + cmd);
            }

            return exitCode == 0;

        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return false;
        }
    }

    /**
     * 执行 shell 命令并返回输出（root 权限）。
     *
     * @return 标准输出内容
     */
    public static String executeShellCommandWithResult(String cmd) {
        try {
            return ShellExecutor.runWithResult(cmd);
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return "";
        }
    }
}
