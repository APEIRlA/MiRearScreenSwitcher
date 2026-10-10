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

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * Quick Settings Tile - 切换至背屏
 * 点击后将当前前台应用切换到背屏
 *
 * <p>本 Tile 运行在 SystemUI 进程，通过 TaskService 静态方法
 * （内部以 su 执行特权命令）完成切换，无需绑定服务。
 */
public class SwitchToRearTileService extends TileService {
    private static final String TAG = "SwitchToRearTile";

    // 静态变量：保存最后移动到背屏的任务信息（用于接近传感器恢复）
    private static String lastMovedTask = null; // 格式: "packageName:taskId"

    /**
     * 静态辅助方法：恢复指定任务到背屏
     * 由 RearScreenBroadcastReceiver 调用
     */
    public static void restoreTaskToRearDisplay(int taskId) {
        // 这个方法留空，实际恢复逻辑由广播接收器直接启动Activity来触发
        // Activity会自动应用FLAG_KEEP_SCREEN_ON
    }

    /**
     * 获取最后移动到背屏的任务信息
     *
     * @return 格式: "packageName:taskId"，如果没有则返回null
     */
    public static String getLastMovedTask() {
        return lastMovedTask;
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        switchCurrentAppToRearDisplay();
    }

    private void switchCurrentAppToRearDisplay() {
        if (!RootAccess.isAvailable()) {
            Log.w(TAG, "Root not available!");
            showTemporaryFeedback("✗ 未获root");

            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Toast.makeText(this, "请先在弹窗中授予root权限", Toast.LENGTH_LONG).show();
            }, 300);
            return;
        }

        performSwitch();
    }

    private void performSwitch() {
        // 显示执行中状态 - 保持按钮外观，只改变副标题
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE); // 保持熄灭状态
            tile.setSubtitle("切换中...");
            // 不显示"已开启"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        try {
            // 步骤0: 检查背屏是否已有应用在运行
            if (lastMovedTask != null && lastMovedTask.contains(":")) {
                try {
                    String[] oldParts = lastMovedTask.split(":");
                    String oldPackageName = oldParts[0];
                    int oldTaskId = Integer.parseInt(oldParts[1]);

                    // 检查旧应用是否还在背屏
                    String rearForegroundApp = TaskService.getForegroundAppOnDisplay(1);
                    if (rearForegroundApp != null && rearForegroundApp.equals(lastMovedTask)) {
                        // 背屏已有应用在运行，禁止操作
                        String oldAppName = getAppName(oldPackageName);

                        // 先收起控制中心，Toast才能显示
                        try {
                            TaskService.collapseStatusBar();
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to collapse for toast: " + e.getMessage());
                        }

                        // 延迟显示Toast，确保控制中心已收起
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            Toast.makeText(this, getString(R.string.toast_please_switch_back, oldAppName),
                                    Toast.LENGTH_LONG).show();
                        }, 300);

                        showTemporaryFeedback("✗ 背屏已占用");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to check previous app: " + e.getMessage());
                }
            }

            // 步骤1: 禁用系统背屏Launcher（关键！防止挤占）
            try {
                TaskService.disableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "Failed to disable SubScreenLauncher", e);
            }

            // 步骤2: 获取当前前台应用
            String currentApp = TaskService.getCurrentForegroundApp();

            // 步骤3: 立即启动前台Service（不延迟，让通知快速出现）
            Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
            serviceIntent.putExtra("lastMovedTask", currentApp);

            // V2.5: 传递背屏常亮开关状态
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences",
                        MODE_PRIVATE);
                boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
            } catch (Exception e) {
                // 默认为开启
                serviceIntent.putExtra("keepScreenOnEnabled", true);
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            if (currentApp != null && currentApp.contains(":")) {
                String[] parts = currentApp.split(":");
                String packageName = parts[0];
                int taskId = Integer.parseInt(parts[1]);

                // 获取应用名
                String appName = getAppName(packageName);

                // 步骤4: 切换到display 1 (背屏)
                boolean success = TaskService.moveTaskToDisplay(taskId, 1);

                if (success) {
                    // 保存最后移动的任务信息（用于接近传感器恢复）
                    lastMovedTask = currentApp;

                    // 自动收回控制中心（提升用户体验）
                    try {
                        new Thread(() -> {
                            try {
                                TaskService.collapseStatusBar();
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to collapse: " + e.getMessage());
                            }
                        }).start();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to start collapse thread: " + e.getMessage());
                    }

                    // 延迟显示Toast，确保控制中心已收起
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, appName + " " + getString(R.string.toast_cast_to_rear), Toast.LENGTH_SHORT)
                                .show();
                    }, 300);

                    // 步骤5: 主动点亮背屏 (通过TaskService启动Activity，绕过BAL限制)
                    try {
                        boolean launchResult = TaskService.launchWakeActivity(1);
                        if (!launchResult) {
                            Log.w(TAG, "TaskService launch failed");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "launchWakeActivity exception: " + e.getMessage());
                    }

                    showTemporaryFeedback("✓ 已切换");
                } else {
                    // 先收起控制中心
                    try {
                        TaskService.collapseStatusBar();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to collapse: " + e.getMessage());
                    }

                    // 延迟显示Toast
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        Toast.makeText(this, getString(R.string.toast_switch_failed), Toast.LENGTH_SHORT).show();
                    }, 300);

                    showTemporaryFeedback("✗ 失败");
                }
            } else {
                Log.w(TAG, "No foreground app found");
                showTemporaryFeedback("✗ 未找到应用");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error switching app", e);
            showTemporaryFeedback("✗ 操作失败");
        }
    }

    private void showTemporaryFeedback(String message) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(message);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.setStateDescription("");
            }
            tile.updateTile();
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Tile resetTile = getQsTile();
            if (resetTile != null) {
                resetTile.setState(Tile.STATE_INACTIVE);
                resetTile.setSubtitle(null);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    resetTile.setStateDescription("");
                }
                resetTile.updateTile();
            }
        }, 1500);
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
}
