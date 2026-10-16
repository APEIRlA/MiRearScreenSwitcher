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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.List;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

/**
 * 前台Service - 保持背屏常亮
 * 
 * 为什么用Service而不是Activity：
 * - Activity方案失败了3次（FLAG_NOT_FOCUSABLE、屏幕外、alpha=0都会被onStop）
 * - Service不会被onPause/onStop，系统很难杀死前台Service
 * - 可以直接持有WakeLock保持屏幕常亮
 * 
 * 注意：WakeLock可能会让两个屏幕都保持常亮（无法指定特定display）
 */
public class RearScreenKeeperService extends Service implements SensorEventListener {
    private static final String TAG = "RearScreenKeeperService";
    private static final String CHANNEL_ID = "rear_screen_keeper";
    private static final int NOTIFICATION_ID = 10001;

    private static RearScreenKeeperService instance = null;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;

    // V12.3: 初始杀进程策略 - 只杀1次，不持续监控
    private static final int INITIAL_KILL_COUNT = 1; // 初始杀1次
    private static final long KILL_INTERVAL_MS = 200; // 每次间隔200ms

    // V12.1: 接近传感器监听
    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private boolean isProximityCovered = false;
    private long lastProximityTime = 0;
    private static final long PROXIMITY_DEBOUNCE_MS = 1500; // 防抖动：1500ms内连续覆盖才触发（降低灵敏度）

    // V3.6: 常亮开启时，遮盖只暂停背屏唤醒的时长（毫秒）。
    // issue #22：遮盖检测与背屏常亮需要仲裁，不能无条件把任务拉回主屏。
    private static final long PROXIMITY_PAUSE_MS = 15000;

    // V3.6: 背屏唤醒是否因遮盖而暂停（防止重复排定时器）
    private boolean isProximityPaused = false;

    // V3.6: 连续多少次检查发现被投应用不在背屏前台就放弃保活。
    // 间隔 CHECK_TASK_INTERVAL_MS=2000，3 次约 6 秒——足够覆盖
    // 「应用正在崩溃重启」这类短暂空档，又不至于在应用真的被用户
    // 主动关掉后反复强行拉起（强行拉起 = 用户关不掉，体验更糟）。
    private static final int MAX_LOST_CHECKS = 3;
    private int lostCheckCount = 0;

    // V3.6: 服务正在销毁。后台恢复线程据此判断「服务已停，不要再拉起监控」。
    // volatile：工作线程写、主线程读（反过来也读）。
    private volatile boolean isStopping = false;

    // V3.6: 被投应用保活开关。关掉后 checkTaskRunnable 发现应用掉出背屏直接停服务，
    // 不再尝试拉回/重启（历史行为）。
    private boolean keepAliveEnabled = true;

    // V2.2: 接近传感器开关状态
    private boolean proximitySensorEnabled = true; // 默认启用

    // V14.5: 监听应用是否手动移回主屏
    private static final long CHECK_TASK_INTERVAL_MS = 2000; // 每2秒检查一次
    private String monitoredTaskInfo = null; // 格式: "packageName:taskId"

    // V2.3: 临时暂停监控（充电动画显示期间）
    private boolean monitoringPaused = false;

    // V2.4: 持续唤醒背屏（防止自动熄屏）
    private static final long WAKEUP_INTERVAL_MS = 100; // 持续发送，每0.1秒唤醒一次（对熄屏几乎无感）
    private boolean keepScreenOnEnabled = true; // 默认启用背屏常亮

    public static void pauseMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = true;

            // ✅ 取消所有pending的检查任务
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                Log.d(TAG, "⏸️ Monitoring paused, all checks cancelled");
            } else {
                Log.d(TAG, "⏸️ Monitoring paused");
            }
        }
    }

    public static void resumeMonitoring() {
        if (instance != null) {
            instance.monitoringPaused = false;
            Log.d(TAG, "▶️ Monitoring resumed");

            // ✅ 延迟5秒后才开始检查，给投送app足够时间恢复到前台
            if (instance.handler != null) {
                instance.handler.removeCallbacks(instance.checkTaskRunnable);
                instance.handler.postDelayed(instance.checkTaskRunnable, 5000);
                Log.d(TAG, "⏰ Next check scheduled in 5 seconds");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 创建通知渠道
        createNotificationChannel();

        // 创建Handler用于定时任务
        handler = new Handler(Looper.getMainLooper());

        // V2.2: 从SharedPreferences恢复传感器开关状态
        loadProximitySensorSetting();
        loadKeepAliveSetting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // V14.6: 处理点击通知返回主屏的事件
        if (intent != null && "ACTION_RETURN_TO_MAIN".equals(intent.getAction())) {

            // 将监控的任务移回主屏
            if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":") && RootAccess.isAvailable()) {
                try {
                    String[] parts = monitoredTaskInfo.split(":");
                    String packageName = parts[0];
                    int taskId = Integer.parseInt(parts[1]);

                    // 获取应用名
                    String appName = getAppName(packageName);

                    TaskService.moveTaskToDisplay(taskId, 0);

                    // 先移除前台通知
                    stopForeground(Service.STOP_FOREGROUND_REMOVE);

                    // 延迟显示Toast提示
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " 已返回主屏", Toast.LENGTH_SHORT).show();
                    }, 100);

                    // 停止服务
                    stopSelf();
                    return START_NOT_STICKY;

                } catch (Exception e) {
                    Log.w(TAG, "Failed to return task to main", e);
                }
            }
        }

        // V2.2: 处理接近传感器开关设置
        if (intent != null && "ACTION_SET_PROXIMITY_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            proximitySensorEnabled = enabled;

            Log.d(TAG, "🔧 传感器开关状态已更新: " + enabled);

            // 如果关闭了传感器，且当前正在监听，则注销监听
            if (!enabled && sensorManager != null && proximitySensor != null) {
                sensorManager.unregisterListener(this);
                Log.d(TAG, "⏸️ 传感器监听器已注销");
            }
            // 如果打开了传感器，且当前没有监听，则注册监听
            else if (enabled && sensorManager != null && proximitySensor != null) {
                boolean registered = sensorManager.registerListener(this, proximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                if (registered) {
                    Log.d(TAG, "✅ 传感器监听器已注册");
                } else {
                    Log.w(TAG, "⚠ 传感器监听器注册失败");
                }
            }

            return START_STICKY;
        }

        // V3.6: 被投应用保活开关设置
        if (intent != null && "ACTION_SET_KEEP_ALIVE_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            keepAliveEnabled = enabled;

            Log.d(TAG, "🛟 保活开关已" + (enabled ? "开启" : "关闭"));
            if (!enabled) {
                lostCheckCount = 0;
            }

            return START_STICKY;
        }
        // V2.5: 处理背屏常亮开关设置
        if (intent != null && "ACTION_SET_KEEP_SCREEN_ON_ENABLED".equals(intent.getAction())) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            keepScreenOnEnabled = enabled;

            Log.d(TAG, "🔆 背屏常亮开关已" + (enabled ? "开启" : "关闭"));

            // 如果关闭了常亮，停止发送WAKEUP
            if (!enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                Log.d(TAG, "⏸️ 背屏WAKEUP发送已停止");
            }
            // 如果打开了常亮，启动发送WAKEUP
            else if (enabled && handler != null) {
                handler.removeCallbacks(wakeupRearScreenRunnable);
                startRearScreenWakeup();
            }

            return START_STICKY;
        }

        try {
            // V14.7: 先从Intent获取要监控的任务信息
            if (intent != null) {
                String newMonitoredTask = intent.getStringExtra("lastMovedTask");
                if (newMonitoredTask != null) {
                    monitoredTaskInfo = newMonitoredTask;
                }
            }

            // V2.5: 从Intent获取背屏常亮开关状态
            if (intent != null) {
                keepScreenOnEnabled = intent.getBooleanExtra("keepScreenOnEnabled", true);
                Log.d(TAG, "🔆 背屏常亮开关状态: " + (keepScreenOnEnabled ? "开启" : "关闭"));

                // V3.6: 被投应用保活开关。启动方没带这个 extra 时不能盲目用默认值，
                // 因为 hasExtra 为 false 说明是老调用方（QS 磁贴等），此时应以
                // 用户持久化设置为准，回落到 loadKeepAliveSetting() 已读到的值。
                if (intent.hasExtra("keepAliveEnabled")) {
                    keepAliveEnabled = intent.getBooleanExtra("keepAliveEnabled", true);
                    Log.d(TAG, "🛟 保活开关状态: " + (keepAliveEnabled ? "开启" : "关闭"));
                }
            }

            // V15.1: 立即显示通知，不等待其他操作
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);

            // 在后台线程执行耗时操作，不阻塞通知显示
            new Thread(() -> {
                // 初始化接近传感器
                initProximitySensor();
            }).start();

            // 2. 获取WakeLock保持屏幕常亮
            if (wakeLock == null || !wakeLock.isHeld()) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

                // 使用SCREEN_BRIGHT_WAKE_LOCK保持屏幕亮起
                // 注意：这会让屏幕保持亮起，但可能无法指定是哪个display
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK, // 移除ACQUIRE_CAUSES_WAKEUP避免唤醒主屏
                        "MRSS::RearScreenKeeper");

                // 持续持有WakeLock（不设置超时）
                wakeLock.acquire();

            } else {
            }

            // 3. V12.2: 初始杀进程（只杀几次，不持续监控）
            performInitialKills();

            // 4. V14.5: 启动定期检查任务
            if (monitoredTaskInfo != null) {
                startTaskMonitoring();
            }

            // 5. V2.5: 启动持续唤醒背屏（每0.5秒，根据开关状态）
            startRearScreenWakeup();

        } catch (Exception e) {
            Log.e(TAG, "✗ Error starting service", e);
        }

        // START_STICKY: 如果被系统杀死，会自动重启
        return START_STICKY;
    }

    /**
     * V15.2: 启动任务监听 - 检测应用是否在前台
     * 监控被投放到背屏的应用，如果不在前台了（被关闭或切换），自动停止服务并清除通知
     */
    private final Runnable checkTaskRunnable = new Runnable() {
        @Override
        public void run() {
            // V2.3: 如果监控已暂停（充电动画显示中），跳过本次检查
            if (monitoringPaused) {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                return;
            }

            if (monitoredTaskInfo != null && RootAccess.isAvailable()) {
                try {
                    // V15.2: 检查背屏(displayId=1)的前台应用是否还是我们监控的应用
                    String rearForegroundApp = TaskService.getForegroundAppOnDisplay(1);


                    // 如果背屏前台应用不是我们监控的应用
                    if (rearForegroundApp == null || !rearForegroundApp.equals(monitoredTaskInfo)) {
                        // V3.6: 「被投应用的保活与崩溃恢复」。
                        // 旧逻辑直接停服务——用户投到背屏的 App 被系统/内存回收弹回主屏后，
                        // 保活就永久失效，必须重新手动切一次，这是社区反馈最大的痛点之一。
                        // 新逻辑：先判断能否恢复，恢复不了才停服务。
                        handleMonitoredAppLost();
                        return;
                    }

                    // 应用仍在背屏前台：重置连续丢失计数，继续监听
                    lostCheckCount = 0;
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);

                } catch (Exception e) {
                    Log.w(TAG, "Task check failed: " + e.getMessage());
                    handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
                }
            } else {
                handler.postDelayed(this, CHECK_TASK_INTERVAL_MS);
            }
        }
    };

    private void startTaskMonitoring() {
        if (monitoredTaskInfo != null && handler != null) {
            handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
        }
    }

    /**
     * V3.6: 被投应用从背屏前台消失了。
     *
     * <p>旧逻辑在这里直接 stopSelf，导致「背屏常亮/漫游保活」一旦遇到
     * 系统回收或应用崩溃就永久失效，用户必须重新手动切一次。
     *
     * <p>新逻辑按下面顺序判定，只在确认救不回来时才停服务：
     * <ol>
     *   <li>root 不可用 → 直接停服务（没有恢复手段，别空转）；</li>
     *   <li>进程还活着 → task 只是被挪走了，moveTaskToDisplay 搬回背屏即可；</li>
     *   <li>进程已死 → relaunchAppToRear 冷启动后重新投放；</li>
     *   <li>连续 {@link #MAX_LOST_CHECKS} 次都救不回来 → 认为用户已主动关掉，
     *       停止服务（避免强行反复拉起用户关不掉的应用）。</li>
     * </ol>
     *
     * <p>注意：本方法跑在主线程 handler 上，恢复动作涉及数百毫秒的
     * shell 轮询，必须丢到工作线程，否则 ANR。
     */
    private void handleMonitoredAppLost() {
        // V3.6: 用户关掉保活开关时走历史行为——直接停服务，不拉回不重启
        if (!keepAliveEnabled) {
            Log.i(TAG, "🛟 保活开关已关闭，按原逻辑停止服务");
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        if (monitoredTaskInfo == null || !monitoredTaskInfo.contains(":")) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        final String packageName = monitoredTaskInfo.substring(0, monitoredTaskInfo.indexOf(':'));

        if (!RootAccess.isAvailable()) {
            Log.w(TAG, "✗ Root 不可用，停止保活");
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        lostCheckCount++;
        Log.w(TAG, "⚠️ 被投应用 " + packageName + " 不在背屏前台（第 " + lostCheckCount
                + "/" + MAX_LOST_CHECKS + " 次）");

        if (lostCheckCount >= MAX_LOST_CHECKS) {
            Log.w(TAG, "✗ 连续 " + MAX_LOST_CHECKS + " 次无法恢复，停止保活");
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        final RearScreenKeeperService self = this;
        Thread worker = new Thread(() -> {
            boolean recovered = false;
            try {
                if (TaskService.isAppProcessAlive(packageName)) {
                    // 进程活着：先看背屏前台是不是目标应用（只有 task 在背屏但被
                    // 别的界面盖住时不等于保活成功，否则会陷入「搬回→又被盖住→
                    // 再搬回」的自我循环，lostCheckCount 永远清零、撞不到上限）。
                    String rearForeground = TaskService.getForegroundAppOnDisplay(1);
                    boolean onRearForeground = rearForeground != null
                            && rearForeground.startsWith(packageName + ":");
                    int taskOnRear = TaskService.getTaskIdByPackageOnDisplay(packageName, 1);
                    if (onRearForeground && taskOnRear > 0) {
                        // 真的在背屏前台，只是 taskId 变了（重建过）：更新监控目标
                        Log.i(TAG, "🔧 task 仍在背屏前台，taskId 变化，更新监控目标: " + taskOnRear);
                        monitoredTaskInfo = packageName + ":" + taskOnRear;
                        RearScreenBroadcastReceiver.saveLastTask(packageName, taskOnRear);
                        recovered = true;
                    } else {
                        int taskOnMain = TaskService.getTaskIdByPackageOnDisplay(packageName, 0);
                        if (taskOnMain > 0) {
                            Log.i(TAG, "🔧 task 被挪到主屏，搬回背屏: " + taskOnMain
                                    + "（背屏当前前台: " + rearForeground + "）");
                            recovered = TaskService.moveTaskToDisplay(taskOnMain, 1);
                        } else {
                            // 两个屏幕都没 task：进程活着但没有 UI 承载，必须重投
                            Log.i(TAG, "🔧 进程存活但无 task，重新投放");
                            recovered = TaskService.relaunchAppToRear(packageName);
                        }
                    }
                } else {
                    Log.i(TAG, "🔧 进程已被杀，冷启动后重新投放");
                    recovered = TaskService.relaunchAppToRear(packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ 保活恢复异常", e);
            }

            final boolean ok = recovered;
            // 服务可能在本线程恢复过程中被销毁：此时 onDestroy 已
            // removeCallbacksAndMessages(null)，post 进去的检查会把监控重新拉起，
            // 造成「服务已停却还在跑」的幽灵监控，必须挡住。
            if (handler != null && !isStopping) {
                handler.post(() -> {
                    if (ok) {
                        Log.i(TAG, "✅ 保活恢复成功，继续监控");
                        lostCheckCount = 0;
                        // 重新投放后 taskId 可能变了，从背屏前台取最新值
                        String latest = TaskService.getForegroundAppOnDisplay(1);
                        if (latest != null && latest.contains(packageName)) {
                            monitoredTaskInfo = latest;
                            RearScreenBroadcastReceiver.saveLastTask(packageName,
                                    Integer.parseInt(latest.substring(latest.indexOf(':') + 1)));
                        }
                        TaskService.launchWakeActivity(1);
                        handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
                    } else {
                        Log.w(TAG, "✗ 本次恢复失败，等下一轮检查");
                        handler.postDelayed(checkTaskRunnable, CHECK_TASK_INTERVAL_MS);
                    }
                });
            }
        });
        worker.setName("MRSS-KeepAlive");
        worker.start();
    }

    /**
     * V2.5: 持续唤醒背屏任务 - 每0.5秒发送WAKEUP，防止背屏自动熄屏
     */
    private final Runnable wakeupRearScreenRunnable = new Runnable() {
        @Override
        public void run() {
            // 检查开关状态
            if (keepScreenOnEnabled && RootAccess.isAvailable()) {
                try {
                    // 向背屏(displayId=1)发送WAKEUP唤醒信号
                    // 走 submit（独立常驻会话、投递即返回）：这是 0.5 秒一次的高频
                    // 保活命令，若与用户切屏共用主锁会造成切屏延迟与命令积压。
                    ShellExecutor.submit("input -d 1 keyevent KEYCODE_WAKEUP");
                    // Log.d(TAG, "✨ 背屏保活唤醒已发送"); // 注释掉以减少日志
                } catch (Exception e) {
                    Log.w(TAG, "背屏唤醒失败: " + e.getMessage());
                }
            }

            // 持续发送，每0.5秒执行一次
            if (keepScreenOnEnabled) {
                handler.postDelayed(this, WAKEUP_INTERVAL_MS);
            }
        }
    };

    private void startRearScreenWakeup() {
        if (handler != null && keepScreenOnEnabled) {
            // 立即执行第一次唤醒，然后开始持续发送
            handler.post(wakeupRearScreenRunnable);
            Log.d(TAG, "⏰ 背屏持续唤醒已启动 (0.5秒间隔)");
        }
    }

    /**
     * V12.3: 初始杀进程 - 只杀1次，不持续监控
     */
    private void performInitialKills() {

        for (int i = 0; i < INITIAL_KILL_COUNT; i++) {
            final int killNumber = i + 1;

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (RootAccess.isAvailable()) {
                        try {
                            TaskService.killLauncherProcess();
                        } catch (Exception e) {
                            Log.w(TAG, "⚠ Kill #" + killNumber + " failed: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "⚠ Root not available for kill #" + killNumber);
                    }

                    // 最后一次杀完后的总结
                    if (killNumber == INITIAL_KILL_COUNT) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                            }
                        }, 100);
                    }
                }
            }, i * KILL_INTERVAL_MS);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // V3.6: 先置停止标志，挡住后台恢复线程里尚未执行的 handler.post
        isStopping = true;

        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "⚠ Service onDestroy called");

        // 立即移除前台通知
        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        // 清理所有待执行的任务
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // V12.2: 恢复并主动唤醒Launcher
        if (RootAccess.isAvailable()) {
            try {

                // 1. 恢复Launcher（unsuspend）
                TaskService.enableSubScreenLauncher();

                // 2. 短暂延迟，确保unsuspend生效
                Thread.sleep(300);

                // 3. 主动启动Launcher的Activity来唤醒它

            } catch (Exception e) {
                Log.w(TAG, "Failed to restore launcher", e);
            }
        }

        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 注销接近传感器
        unregisterProximitySensor();

        instance = null;
        Log.w(TAG, "═══════════════════════════════════════");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不支持绑定
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+必需）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW // 低重要性，减少干扰
            );
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            channel.setShowBadge(false); // 不显示角标
            channel.enableLights(false); // 不闪烁LED
            channel.enableVibration(false); // 不振动

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);

        }
    }

    /**
     * 获取应用名称
     */
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get app name: " + e.getMessage());
        }
        return packageName; // 失败时返回包名
    }

    /**
     * V2.4: 创建通用的Service前台通知（供多个Service共用）
     */
    public static Notification createServiceNotification(Context context) {
        // 创建通知渠道
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_kernel_service),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("com.xiaomi.subscreencenter.SubScreenLauncher真是高高在上呢");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notif_kernel_service))
                .setContentText(context.getString(R.string.notif_mrss_running))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 构建前台通知
     */
    private Notification buildNotification() {
        // 获取应用名称
        String appName = "应用";

        if (monitoredTaskInfo != null && monitoredTaskInfo.contains(":")) {
            String packageName = monitoredTaskInfo.split(":")[0];
            appName = getAppName(packageName);
        } else {
            Log.w(TAG, "⚠ Invalid monitored task info: " + monitoredTaskInfo);
        }

        // 点击通知切换回主屏
        Intent returnIntent = new Intent(this, RearScreenKeeperService.class);
        returnIntent.setAction("ACTION_RETURN_TO_MAIN");
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, returnIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appName + " " + getString(R.string.notif_running_on_rear))
                .setContentText(getString(R.string.notif_click_to_return, appName))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // 低优先级
                .setOngoing(true) // 持续通知，不可滑动清除
                .setShowWhen(false) // 不显示时间
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 检查Service是否正在运行
     */
    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 停止Service
     */
    public static void stop() {
        if (instance != null) {
            instance.stopSelf();
        }
    }

    // ========================================
    // 接近传感器相关方法
    // ========================================

    /**
     * V3.6: 加载被投应用保活开关状态。
     *
     * <p>键名与 Flutter 侧保持一致：Flutter 的 SharedPreferences 会给自己写入的每个 key
     * 加 `flutter.` 前缀，因此这里读 `flutter.keep_alive_enabled`。
     */
    private void loadKeepAliveSetting() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            keepAliveEnabled = prefs.getBoolean("flutter.keep_alive_enabled", true);
            Log.d(TAG, "🛟 保活开关状态已恢复: " + keepAliveEnabled);
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载保活设置失败", e);
            keepAliveEnabled = true;
        }
    }
    /**
     * 从SharedPreferences加载传感器开关状态
     */
    private void loadProximitySensorSetting() {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            proximitySensorEnabled = prefs.getBoolean("flutter.proximity_sensor_enabled", true);
            Log.d(TAG, "🔧 传感器开关状态已恢复: " + proximitySensorEnabled);
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载传感器设置失败", e);
            proximitySensorEnabled = true; // 默认启用
        }
    }

    /**
     * 初始化接近传感器（背屏接近传感器）
     */
    private void initProximitySensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            if (sensorManager != null) {
                // 获取所有传感器列表
                List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);

                // 查找背屏接近传感器（名称包含 "Back" 和 "Proximity"）
                // 优先选择 Wakeup 版本，如果没有则选择 Non-wakeup 版本
                Sensor wakeupSensor = null;
                Sensor nonWakeupSensor = null;

                for (Sensor sensor : allSensors) {
                    String name = sensor.getName();
                    if (name.contains("Proximity") && name.contains("Back")) {
                        if (name.contains("Wakeup")) {
                            wakeupSensor = sensor;
                        } else {
                            nonWakeupSensor = sensor;
                        }
                    }
                }

                // 优先使用 Wakeup 版本
                if (wakeupSensor != null) {
                    proximitySensor = wakeupSensor;
                } else if (nonWakeupSensor != null) {
                    proximitySensor = nonWakeupSensor;
                    Log.w(TAG, "→ Using NON-WAKEUP sensor (may not provide continuous data)");
                }

                // 如果找不到背屏传感器，回退到默认传感器
                if (proximitySensor == null) {
                    Log.w(TAG, "⚠ Rear proximity sensor not found, using default");
                    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                }

                if (proximitySensor != null) {
                    // V2.2: 只有在传感器开关启用时才注册监听器
                    if (proximitySensorEnabled) {
                        boolean registered = sensorManager.registerListener(
                                this,
                                proximitySensor,
                                SensorManager.SENSOR_DELAY_NORMAL);

                        if (registered) {
                            Log.d(TAG, "✅ 接近传感器已注册 (开关状态: " + proximitySensorEnabled + ")");
                        } else {
                            Log.w(TAG, "⚠ Failed to register proximity sensor");
                        }
                    } else {
                        Log.d(TAG, "⏸️ 接近传感器已禁用，跳过注册");
                    }
                } else {
                    Log.w(TAG, "⚠ No proximity sensor available");
                }
            } else {
                Log.w(TAG, "⚠ SensorManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing proximity sensor", e);
        }
    }

    /**
     * 注销接近传感器
     */
    private void unregisterProximitySensor() {
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error unregistering proximity sensor", e);
        }
    }

    /**
     * 传感器数据变化回调
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        // V2.2: 如果传感器已关闭，不处理事件
        if (!proximitySensorEnabled) {
            return;
        }

        // 检查是否是我们的背屏接近传感器
        if (event.sensor == proximitySensor) {
            float distance = event.values[0];
            float maxRange = proximitySensor.getMaximumRange();

            // 详细日志 - 每次传感器变化都记录

            // 当距离接近0（被覆盖）时触发
            boolean isCovered = (distance < maxRange * 0.2f); // 小于最大距离的20%视为覆盖

            long currentTime = System.currentTimeMillis();

            if (isCovered && !isProximityCovered) {
                // 从未覆盖到覆盖
                isProximityCovered = true;
                lastProximityTime = currentTime;

                Log.w(TAG, "👋 PROXIMITY COVERED! Distance: " + distance + " cm");
                Log.w(TAG, "👋 Starting debounce timer (" + PROXIMITY_DEBOUNCE_MS + "ms)...");

                // 防抖动：延迟检查
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isProximityCovered &&
                                (System.currentTimeMillis() - lastProximityTime >= PROXIMITY_DEBOUNCE_MS)) {
                            // 确认覆盖超过500ms，触发拉回主屏
                            Log.w(TAG, "👋 Debounce timer expired - triggering return to main display!");
                            handleProximityCovered();
                        } else {
                        }
                    }
                }, PROXIMITY_DEBOUNCE_MS);

            } else if (!isCovered && isProximityCovered) {
                // 从覆盖到未覆盖
                isProximityCovered = false;

                // V3.6: 常亮开启时，遮盖期间只是暂停了唤醒，抬手应立即恢复保活
                if (isProximityPaused) {
                    isProximityPaused = false;
                    Log.i(TAG, "▶️ 遮盖解除，立即恢复背屏保活");
                    resumeRearScreenWakeup();
                }
            } else if (isCovered && isProximityCovered) {
                // 持续覆盖中
            } else {
                // 持续未覆盖
            }
        } else {
            // 其他传感器的数据，也记录一下
        }
    }

    /**
     * 传感器精度变化回调（不需要处理）
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要处理
    }
    /**
     * V3.6: 处理接近传感器覆盖事件。
     *
     * <p>issue #22：「Always Wake Up 背屏常亮」在遮盖检测开启时失效。
     * 根因是本方法<b>无条件</b>把任务拉回主屏并停掉 Service，与 keepScreenOnEnabled
     * （背屏常亮开关）没有任何仲裁：常亮开着，被投应用却被拉回主屏，
     * 用户视角就是「常亮功能坏了」。
     *
     * V3.6 起改为显式仲裁，优先级：
     * <ol>
     *   <li>常亮关闭（keepScreenOnEnabled=false）：维持历史行为——拉回主屏并停服务；</li>
     *   <li>常亮开启（keepScreenOnEnabled=true）：<b>不拉回</b>，改为暂停背屏唤醒
     *       一段时间（{@link #PROXIMITY_PAUSE_MS}），到点自动恢复保活。
     *       用户要的是「背屏常亮」，遮盖只是省电信号，不能反悔投放。</li>
     * </ol>
     *
     * <p>两条分支都会同步暂停/恢复 100ms WAKEUP 循环，避免「屏幕已拉回还在给
     * display 1 发 keyevent」的空转。
     */
    private void handleProximityCovered() {
        Log.w(TAG, "═══════════════════════════════════════");
        Log.w(TAG, "🤚 PROXIMITY TRIGGER - keepScreenOnEnabled=" + keepScreenOnEnabled);
        Log.w(TAG, "═══════════════════════════════════════");

        try {
            if (!RootAccess.isAvailable()) {
                Log.w(TAG, "⚠ Root 不可用，跳过遮盖处理");
                return;
            }

            if (!keepScreenOnEnabled) {
                // ── 分支 A：常亮关闭 → 历史行为，拉回主屏并停止服务 ──
                Log.i(TAG, "🔚 常亮已关闭：遮盖即拉回主屏（历史行为）");
                pauseRearScreenWakeup();
                returnLastTaskToMain();
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }

            // ── 分支 B：常亮开启 → 不拉回，只暂停唤醒，到点自动恢复 ──
            Log.i(TAG, "🔆 常亮已开启：遮盖仅暂停背屏唤醒 " + PROXIMITY_PAUSE_MS + "ms，不拉回主屏");

            if (isProximityPaused) {
                Log.d(TAG, "↻ 已在背屏唤醒暂停态，顺延恢复时刻");
                return;
            }
            isProximityPaused = true;
            pauseRearScreenWakeup();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    isProximityPaused = false;
                    if (keepScreenOnEnabled && !isProximityCovered) {
                        Log.i(TAG, "▶️ 背屏唤醒暂停结束，恢复保活");
                        resumeRearScreenWakeup();
                    } else {
                        Log.i(TAG, "⏸️ 暂停结束但状态未就绪，等待下次事件");
                    }
                }
            }, PROXIMITY_PAUSE_MS);
        } catch (Exception e) {
            Log.e(TAG, "✗ Error handling proximity event", e);
        }
    }

    /**
     * 把最后投放的任务移回主屏（不停止服务，由调用方决定后续）。
     *
     * @return true 表示移动命令成功发出
     */
    private boolean returnLastTaskToMain() {
        String lastTask = SwitchToRearTileService.getLastMovedTask();
        if (lastTask == null || !lastTask.contains(":")) {
            Log.w(TAG, "⚠ No active rear screen task found");
            return false;
        }
        try {
            String[] parts = lastTask.split(":");
            String packageName = parts[0];
            int taskId = Integer.parseInt(parts[1]);
            String appName = getAppName(packageName);

            boolean success = TaskService.moveTaskToDisplay(taskId, 0);
            if (success) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    Toast.makeText(RearScreenKeeperService.this,
                            appName + " 已返回主屏", Toast.LENGTH_SHORT).show();
                }, 100);
            } else {
                Log.w(TAG, "⚠ Failed to return task (may already be on main display)");
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "✗ Error returning task to main", e);
            return false;
        }
    }

    /** 暂停背屏 WAKEUP 循环。 */
    private void pauseRearScreenWakeup() {
        if (handler != null) {
            handler.removeCallbacks(wakeupRearScreenRunnable);
        }
    }

    /** 恢复背屏 WAKEUP 循环。 */
    private void resumeRearScreenWakeup() {
        if (handler != null && keepScreenOnEnabled) {
            handler.removeCallbacks(wakeupRearScreenRunnable);
            handler.post(wakeupRearScreenRunnable);
        }
    }
}

