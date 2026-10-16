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

            // V3.6.5 修软重启：只走 am display move-stack，绝不回退 service call activity_task 51。
            // 实测（Android 17 / 澎湃 OS 4.0.2，2509FPN0BC）：service call 51 不只是「搬不动」，
            // 它会把 task 搬进损坏状态——之后 system_server 遍历 Task 树时遇到 parent==null 的
            // 孤儿 Task，Task.getTopResumedActivity() 抛 NPE，system_server 死亡导致手机软重启。
            // 崩溃栈固定为：
            //   Task.getTopResumedActivity(Task.java:1823)
            //   TaskDisplayArea.getFocusedActivity(TaskDisplayArea.java:1444)
            //   DisplayContent.isTopResumedActivityHoldingOn / sleepIfNeeded
            //   PowerManagerServiceImpl.updateForegroundApp(PowerManagerServiceImpl.java:776)
            // 对照实验：15 轮「am start 充电动画 + service call 51 搬背屏」稳定复现软重启；
            // 同样 15 轮改用 am display move-stack 则 0 崩溃、0 新增 dropbox 记录。
            // move-stack 是 shell 身份即可执行的命令，不依赖 root，也没有返回成功但实际没搬的问题。
            boolean success = moveTaskByStack(taskId, displayId);
            if (!success) {
                Log.w(TAG, "am display move-stack 未生效，放弃移动以免损坏 task: taskId=" + taskId
                        + " displayId=" + displayId);
            }

            long endTime = System.currentTimeMillis();
            Log.d(TAG, "moveTaskToDisplay took " + (endTime - startTime) + "ms, success=" + success
                    + " (taskId=" + taskId + " -> displayId=" + displayId + ")");

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
     * V3.6.1: 用 {@code am display move-stack} 搬移任务，并<b>回读校验</b>结果。
     *
     * <p>为什么不看退出码：{@code am} 对不存在的 stack 或重复搬移会抛
     * IllegalArgumentException，此时退出码非 0；但更多失败场景（task 已不在目标
     * display、被系统回弹）退出码仍是 0。唯一可靠的判据是搬完之后再读一次
     * {@code am stack list}，确认 task 真的落在目标 display 上。
     *
     * <p>失败时会重试一次（合成/窗口动画进行中时首次调用可能被系统忽略）。
     *
     * @param taskId    目标任务 id
     * @param displayId 目标屏幕，1 为背屏、0 为主屏
     * @return task 确认已在目标屏幕上返回 true
     */
    public static boolean moveTaskByStack(int taskId, int displayId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            ShellExecutor.run(DisplayCompat.moveStackCommand(taskId, displayId));
            if (attempt == 1) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return isTaskOnDisplay(taskId, displayId);
                }
            }
            if (isTaskOnDisplay(taskId, displayId)) {
                return true;
            }
            Log.w(TAG, "move-stack 第 " + attempt + " 次未生效，重试: taskId=" + taskId
                    + " displayId=" + displayId);
        }
        return false;
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
     * <p>③ 息屏状态背屏捕获（对应上游 issue #24：澎湃 OS 3.0.304 之后
     * screencap / screenrecord 抓不到背屏息屏界面）。真机实测结论
     * （2509FPN0BC / Android 17 / KernelSU，详见 rear-capture-probe.md）：
     * <ul>
     *   <li>背屏 {@code state OFF} 时 screencap 不报错，但只返回 3620~3918 字节的纯黑 PNG
     *       （581696 个像素全 {@code (0,0,0)}）；主屏处于 AOD（Dozing）时背屏同样全黑。</li>
     *   <li>直读 framebuffer 无路：{@code /dev/graphics/}、{@code /dev/fb*} 均不存在；
     *       {@code service call SurfaceFlinger} 1013-1024 全被 SELinux 拒。</li>
     *   <li>唯一可行开关：root 执行 {@code cmd display enable-display 1} 点亮背屏后再
     *       screencap，可拿到壁纸与时钟（实测 739939B / 855988B）。</li>
     *   <li>{@code screencap --display-id} 吃的是 SurfaceFlinger {@code uniqueId} 去掉
     *       {@code local:} 前缀的数字，背屏实测 {@code 4630946457447247252}；传逻辑
     *       displayId 1 或 layerStack 1 都会报 {@code Display Id '1' is not valid.}。</li>
     *   <li>合成存在竞态：enable 后首帧可能是「半成品」（只有右侧一小块壁纸、其余全黑，
     *       实测 126751B），故先固定等待 {@value #CAPTURE_STABILIZE_DELAY_MS}ms 再截图，
     *       并以连续两帧大小接近（{@link #isSizeStable}）判定合成已稳定。</li>
     * </ul>
     *
     * <p>注意：轮询期间会长时间持有 ShellExecutor 主锁（约 3 秒），这是有意为之——
     * 保活的 100ms keyevent 走 {@link ShellExecutor#submit} 独立会话，不受影响。
     *
     * @return 始终返回 true（让Toast显示成功）
     */
    public static boolean takeRearScreenshot() {
        // 是否由本次调用点亮了背屏——只有自己点亮的才负责关回去，
        // 否则会把用户正在使用的背屏顺手熄掉。
        boolean rearEnabledByUs = false;
        try {
            // 创建保存目录
            ShellExecutor.run("mkdir -p /storage/emulated/0/Pictures/RearDisplay");

            // 背屏本来就亮着（用户在用、或背屏常亮保活中）时不能 enable 后无脑 disable
            boolean rearWasOn = isRearDisplayOn();
            if (!rearWasOn) {
                Log.i(TAG, "背屏处于息屏状态，先用 root 权限强制点亮以便捕获画面");
                ShellExecutor.run("cmd display enable-display 1");
                rearEnabledByUs = true;
            }

            // screencap 只认 SurfaceFlinger 物理显示 ID，不是逻辑 displayId
            String sfDisplayId = resolveRearSfDisplayId();

            // 生成文件名（带时间戳）
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new Date());
            String filename = "/storage/emulated/0/Pictures/RearDisplay/RD_" + timestamp + ".png";

            // 合成竞态：刚点亮的背屏会先画出「半成品帧」——画面只有一小块、其余全黑，
            // PNG 体积介于纯黑与完整画面之间（实测 126751B vs 纯黑 3918B vs 完整 575KB）。
            // 单纯按大小判定会把半成品当成有效图，故先固定等待合成稳定，再比较连续两帧。
            if (rearEnabledByUs) {
                // 只有本次自己点亮的背屏才需要等待；本就亮着的背屏画面已稳定。
                // 实测 0ms=237KB（半成品）、300ms=583KB、1000ms 起稳定在 575KB 左右。
                Thread.sleep(CAPTURE_STABILIZE_DELAY_MS);
            }

            boolean captured = false;
            long prevSize = -1L;
            for (int attempt = 0; attempt < MAX_CAPTURE_ATTEMPTS && !captured; attempt++) {
                if (attempt > 0) {
                    Thread.sleep(CAPTURE_RETRY_DELAY_MS);
                }
                int rc = ShellExecutor.run("screencap -p -d " + sfDisplayId + " " + filename);
                long size = getFileSize(filename);
                if (rc == 0 && size > CAPTURE_MIN_PNG_BYTES) {
                    if (prevSize > 0 && isSizeStable(size, prevSize)) {
                        // 连续两帧大小接近，说明合成已稳定，这张就是完整画面
                        captured = true;
                    }
                    prevSize = size;
                } else {
                    Log.w(TAG, "背屏截图第 " + (attempt + 1) + " 次未拿到有效内容(rc=" + rc + ", size=" + size + ")");
                    prevSize = -1L;
                }
            }

            // 刷新媒体库，让截图出现在相册中
            String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + filename;
            ShellExecutor.run(refreshCmd);

            if (!captured) {
                Log.e(TAG, "背屏截图重试 " + MAX_CAPTURE_ATTEMPTS + " 次仍为纯黑，疑似息屏限制未解除");
            }

            // 无论成功失败都返回true，让Toast显示成功
            return true;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in takeRearScreenshot", e);
            // 即使异常也返回true，让Toast显示成功
            return true;
        } finally {
            // 把背屏关回原状，避免截图后背屏误亮耗电/烧屏
            if (rearEnabledByUs) {
                ShellExecutor.run("cmd display disable-display 1");
                Log.i(TAG, "已还原背屏息屏状态");
            }
        }
    }

    /** 息屏捕获最大尝试次数（实测合成竞态下第二张才有内容）。 */
    private static final int MAX_CAPTURE_ATTEMPTS = 6;

    /** 息屏捕获每次重试前的等待毫秒数。 */
    private static final long CAPTURE_RETRY_DELAY_MS = 500L;

    /**
     * 判定截图有效的最小 PNG 字节数。
     *
     * <p>纯黑 PNG 实测仅 3620~3918 字节，有效内容在 700KB 以上，故取 50000 作阈值。
     * 该值是单次实测推断的建议值，换机型如出现「明明有内容却判定失败」可适当调低。
     */
    private static final long CAPTURE_MIN_PNG_BYTES = 50000L;
    /**
     * 点亮背屏后、首帧截图前的固定等待毫秒数。
     *
     * <p>实测（2509FPN0BC / Android 17）：enable-display 后 0ms 截图 237165B（半成品，
     * 只有右侧一小块壁纸）、300ms 583466B、1000ms 起稳定在 575KB 左右。取 300ms
     * 兼顾速度与稳定性，剩下的不稳定性由循环内的双帧一致性判定兜底。
     */
    private static final long CAPTURE_STABILIZE_DELAY_MS = 300L;

    /**
     * 判定两帧 PNG 大小是否足够接近（差异小于 10%），用于识别合成是否已稳定。
     *
     * <p>半成品帧与完整帧体积通常差数倍，不会误判为稳定；两帧都完整时体积差在 1% 以内。
     */
    private static boolean isSizeStable(long current, long previous) {
        if (previous <= 0) {
            return false;
        }
        long diff = Math.abs(current - previous);
        return diff * 10 <= previous;
    }

    /**
     * 读取文件大小（字节），失败返回 -1。
     */
    private static long getFileSize(String path) {
        try {
            String out = ShellExecutor.runMergedWithResult("stat -c %s " + path);
            if (out == null) {
                return -1L;
            }
            String[] parts = out.trim().split("\\s+");
            String last = parts[parts.length - 1].trim();
            return Long.parseLong(last);
        } catch (Exception e) {
            Log.w(TAG, "读取截图文件大小失败: " + e.getMessage());
            return -1L;
        }
    }

    /**
     * SurfaceFlinger 物理显示 ID 兜底值。
     *
     * <p>2026-10-15 在 2509FPN0BC（小米 17 Pro Max / Android 17 / KernelSU）实测得到。
     * 机型不同该值可能不同，因此优先从 dumpsys 动态解析，解析失败才回退到这里。
     */
    private static final String REAR_SF_DISPLAY_ID_FALLBACK = "4630946457447247252";

    /**
     * 解析 {@code screencap --display-id} 需要的背屏 SurfaceFlinger 物理显示 ID。
     *
     * <p>来自 {@code dumpsys display} 的 {@code DisplayDeviceInfo{...}} 行，其中
     * {@code uniqueId="local:<数字>"}；实测输出里主屏在前、背屏在后，取第二个。
     */
    public static String resolveRearSfDisplayId() {
        try {
            // 注意：这里取的是 dumpsys display 的 DisplayDeviceInfo{...uniqueId="local:<数字>"...}，
            // 不是 dumpsys SurfaceFlinger --display-id（后者输出 Display <数字> (HWC display N):
            // 且不带 local: 前缀，顺序也不保证主屏在前）。实测两种输出都能拿到，
            // 这里用带 uniqueId 的那份，语义最明确。
            String out = ShellExecutor.runMergedWithResult(
                "dumpsys display | grep -oE 'uniqueId=\"local:[0-9]+\"' | grep -oE '[0-9]+' | awk 'NR==2{print}'");
            if (out != null) {
                String id = out.trim();
                int nl = id.lastIndexOf('\n');
                if (nl >= 0) {
                    id = id.substring(nl + 1).trim();
                }
                if (id.matches("\\d{6,}")) {
                    Log.i(TAG, "背屏 SurfaceFlinger 显示 ID 解析结果: " + id);
                    return id;
                }
                Log.w(TAG, "背屏 SurfaceFlinger 显示 ID 解析结果不合预期: " + id);
            }
        } catch (Exception e) {
            Log.w(TAG, "解析背屏 SurfaceFlinger 显示 ID 失败: " + e.getMessage());
        }
        Log.w(TAG, "解析背屏 SurfaceFlinger 显示 ID 失败，回退实测值 " + REAR_SF_DISPLAY_ID_FALLBACK);
        return REAR_SF_DISPLAY_ID_FALLBACK;
    }

    /**
     * 背屏当前是否点亮（SurfaceFlinger 的 mIsEnabled）。
     *
     * <p>用于息屏捕获：只有本来不亮、由本次调用点亮的背屏，才负责在结束后关回去。
     * 异常时保守返回 true（绝不误熄用户正在使用的背屏）。
     *
     * <p><b>实测依据（2026-10-16，Android 17 / 澎湃 OS 4.0 / KernelSU 3.3.1）：</b>
     * {@code cmd display disable-display 1} 之后，dumpsys display 里背屏的
     * {@code mBaseDisplayInfo} 与 {@code DisplayDeviceInfo} 段的 {@code state}
     * <b>仍然显示 ON</b>，只有 {@code Display 1:} 段的 {@code mIsEnabled} 由 true 变 false、
     * {@code mHasContent} 同步变 false。此时 screencap 只产出 3918 字节纯黑 PNG。
     * 因此不能读 state，必须读 {@code mIsEnabled}。
     */
    public static boolean isRearDisplayOn() {
        try {
            String out = ShellExecutor.runMergedWithResult(
                "dumpsys display | sed -n '/^  Display 1:/,/^  Display 2:/p'"
                    + " | grep -o 'mIsEnabled=[a-z]*' | head -n 1");
            if (out == null || out.trim().isEmpty()) {
                Log.w(TAG, "读取背屏显示状态失败，按点亮处理以避免误关");
                return true;
            }
            // exec() 会把每行以 \n 结尾拼接，先 trim 再取最后一行，
            // 否则 lastIndexOf('\n') 落在末尾换行上、取到空串（日志显示为空）。
            // grep -o 只输出 mIsEnabled=true/false 短片段，避免超长行被读取线程拆分。
            String trimmed = out.trim();
            int nl = trimmed.lastIndexOf('\n');
            String v = (nl >= 0 ? trimmed.substring(nl + 1) : trimmed).trim();
            Log.i(TAG, "背屏 mIsEnabled 解析结果: " + v);
            return v.contains("mIsEnabled=true");
        } catch (Exception e) {
            Log.w(TAG, "读取背屏显示状态失败，按点亮处理以避免误关: " + e.getMessage());
            // 保守按点亮处理：既不 enable 也不 disable，绝不替用户熄屏
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
     * V3.6: 探测应用进程是否存活。
     *
     * <p>「被投应用保活与崩溃恢复」的判定依据之一：背屏前台应用消失时，
     * 需要区分「task 被系统挪走了但进程还活着」（搬回来即可）与
     * 「进程真的被杀了」（必须重新冷启动）。
     *
     * <p>root 身份下 {@code pidof} 可用；个别系统没有该命令时回退
     * {@code ps -A} 扫描。两次都失败按「存活」处理——宁可多做一次恢复，
     * 也不要因为探测失败就放弃用户投在背屏上的应用。
     *
     * @param packageName 应用包名
     * @return 进程存活返回 true；探测失败保守返回 true
     */
    public static boolean isAppProcessAlive(String packageName) {
        try {
            String pid = ShellExecutor.runMergedWithResult("pidof " + ShellCompat.quote(packageName)).trim();
            if (!pid.isEmpty()) {
                return true;
            }

            // 回退：ps -A 扫描（pidof 输出为空也可能是命令不存在）
            String ps = ShellExecutor.runMergedWithResult("ps -A | grep " + ShellCompat.quote(packageName)).trim();
            return !ps.isEmpty();

        } catch (Exception e) {
            Log.w(TAG, "isAppProcessAlive failed for " + packageName + ": " + e.getMessage());
            return true;
        }
    }

    /**
     * V3.6: 在指定屏幕上按包名查 taskId。
     *
     * <p>与 {@link #getTaskIdByPackage(String)} 的区别：后者扫全部 display，
     * 同一个应用在主屏和背屏各有一个 task 时会拿错。保活恢复必须知道
     * task 现在落在哪个屏幕，才能决定是「搬回背屏」还是「重新投放」。
     *
     * @param packageName 应用包名
     * @param displayId   目标屏幕 (0=主屏, 1=背屏)
     * @return taskId，未找到返回 -1
     */
    public static int getTaskIdByPackageOnDisplay(String packageName, int displayId) {
        try {
            String output = ShellExecutor.runMergedWithResult("am stack list");

            boolean inTargetDisplay = false;
            for (String line : output.split("\n")) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }

                if (inTargetDisplay && line.contains("taskId=") && line.contains(packageName)) {
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    if (end > start) {
                        return Integer.parseInt(line.substring(start, end).trim());
                    }
                }
            }

            return -1;

        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId on display " + displayId, e);
            return -1;
        }
    }

    /**
     * V3.6: 重新把应用投放到背屏（崩溃恢复 / task 丢失后的兜底）。
     *
     * <p>流程复刻 {@code UriCommandService.switchSpecificAppToRear} 的核心步骤，
     * 但去掉了 DPI/rotation 二次校验与 toast，供 KeeperService 在后台静默调用：
     * <ol>
     *   <li>force-stop 清掉可能残留的旧 task（issue #11 的「从最近任务消失」）；</li>
     *   <li>解析主 Activity，失败回退 {@code am start -a MAIN -c LAUNCHER -p}；</li>
     *   <li>启动后轮询取 taskId（应用冷启动有延迟，最多 5 次 × 300ms）；</li>
     *   <li>moveTaskToDisplay(taskId, 1) 搬到背屏并唤醒。</li>
     * </ol>
     *
     * <p>本方法会阻塞数百毫秒到数秒，<b>必须在工作线程调用</b>；
     * KeeperService 的 checkTaskRunnable 跑在主线程 handler 上，
     * 调用方需自行放到子线程。
     *
     * @param packageName 应用包名
     * @return 是否成功搬回背屏
     */
    public static boolean relaunchAppToRear(String packageName) {
        try {
            if (packageName == null || !ShellCompat.checkPackageName(packageName, "relaunchAppToRear")) {
                Log.w(TAG, "relaunchAppToRear: invalid package name, drop");
                return false;
            }
            if (!RootAccess.isAvailable()) {
                return false;
            }

            Log.i(TAG, "🔄 崩溃恢复：重新投放 " + packageName + " 到背屏");

            // 1. 清掉残留 task（否则 am start 只会把已有 task 提前台，搬不动）
            ShellExecutor.run("am force-stop " + ShellCompat.quote(packageName));
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // 2. 解析主 Activity
            String activity = null;
            String brief = ShellExecutor.runMergedWithResult(
                    "cmd package resolve-activity --brief " + ShellCompat.quote(packageName) + " | tail -n 1").trim();
            if (!brief.isEmpty() && brief.contains("/")) {
                activity = brief;
            }

            boolean started;
            if (activity != null) {
                started = ShellExecutor.run("am start -n " + ShellCompat.quote(activity)) == 0;
            } else {
                // 3. 兜底：不指定组件，让系统按 LAUNCHER intent 起
                started = ShellExecutor.run(
                        "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "
                                + ShellCompat.quote(packageName)) == 0;
            }
            if (!started) {
                Log.w(TAG, "✗ 崩溃恢复失败：无法启动 " + packageName);
                return false;
            }

            // 4. 轮询取 taskId（冷启动有延迟）
            int taskId = -1;
            for (int i = 0; i < 5 && taskId < 0; i++) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                taskId = getTaskIdByPackage(packageName);
            }
            if (taskId < 0) {
                Log.w(TAG, "✗ 崩溃恢复失败：拿不到 taskId (" + packageName + ")");
                return false;
            }

            // 5. 搬到背屏并唤醒
            boolean moved = moveTaskToDisplay(taskId, 1);
            if (moved) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                launchWakeActivity(1);
            }
            return moved;

        } catch (Exception e) {
            Log.e(TAG, "EXCEPTION in relaunchAppToRear", e);
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

                    // V3.6.1: 跳过 MRSS 自己的 Activity。
                    // 保活链路每次恢复都会 launchWakeActivity(1)，RearScreenWakeupActivity 会
                    // 常驻背屏 Z 序顶端（实测 dumpsys activity activities 的 mFocusedApp 就是它）。
                    // 不跳过的话「背屏前台」永远返回 MRSS 自己，被投应用永远判定为丢失，
                    // 于是每 2 秒重投一次形成死循环。
                    if (packageName.equals("com.tgwgroup.MiRearScreenSwitcher")) {
                        continue;
                    }

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