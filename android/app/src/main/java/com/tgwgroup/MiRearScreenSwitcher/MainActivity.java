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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "com.display.switcher/task";
    private static final String TAG = "MainActivity";

    private MethodChannel methodChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        methodChannel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        methodChannel.setMethodCallHandler((call, result) -> {
                switch (call.method) {
                    case "checkRoot": {
                        // 首次检测会 fork su（若弹出授权框可达数秒），
                        // 放到后台线程执行，避免阻塞 Flutter 平台线程造成界面卡顿。
                        new Thread(() -> {
                            boolean available;
                            try {
                                available = RootAccess.isAvailable();
                            } catch (Exception e) {
                                available = false;
                            }
                            final boolean res = available;
                            try {
                                result.success(res);
                            } catch (Exception ignored) {
                                // 通道可能已关闭
                            }
                        }).start();
                        break;
                    }

                    case "requestRootPermission": {
                        // 执行一次 su 命令触发系统授权弹窗
                        new Thread(() -> {
                            RootAccess.resetCache();
                            RootAccess.isAvailable();
                        }).start();
                        result.success(null);
                        break;
                    }

                    case "getRootInfo": {
                        new Thread(() -> {
                            String info;
                            try {
                                boolean available = RootAccess.isAvailable();
                                info = "Running: " + available + "\n" +
                                       "Mode: root (su)";
                            } catch (Exception e) {
                                info = "Error: " + e.getMessage();
                            }
                            try {
                                result.success(info);
                            } catch (Exception ignored) {
                                // 通道可能已关闭
                            }
                        }).start();
                        break;
                    }

                    case "getCurrentApp": {
                        try {
                            String currentApp = TaskService.getCurrentForegroundApp();
                            result.success(currentApp);
                        } catch (Exception e) {
                            Log.e(TAG, "TaskService error: " + e.getMessage(), e);
                            result.success(null);
                        }
                        break;
                    }


                    case "getCurrentRearDpi": {
                        try {
                            int dpi = TaskService.getCurrentRearDpi();
                            result.success(dpi);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to get rear DPI", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "setRearDpi": {
                        try {
                            int dpi = (int) call.argument("dpi");
                            boolean success = TaskService.setRearDpi(dpi);
                            result.success(success);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set rear DPI", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "resetRearDpi": {
                        try {
                            boolean success = TaskService.resetRearDpi();
                            result.success(success);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to reset rear DPI", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "openGithub": {
                        // 打开项目 GitHub 页面
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(android.net.Uri.parse("https://github.com/APEIRlA/MiRearScreenSwitcher"));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            result.success(null);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open GitHub page", e);
                            result.error("ERROR", "打开失败: " + e.getMessage(), null);
                        }
                        break;
                    }

                    case "ensureRootAccess": {
                        // 确保root权限可用
                        try {
                            RootAccess.resetCache();
                            result.success(RootAccess.isAvailable());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to ensure root access", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "setDisplayRotation": {
                        try {
                            int displayId = (int) call.argument("displayId");
                            int rotation = (int) call.argument("rotation");
                            boolean success = TaskService.setDisplayRotation(displayId, rotation);
                            result.success(success);
                        } catch (Exception e) {
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "getDisplayRotation": {
                        try {
                            int displayId = (int) call.argument("displayId");
                            int rotation = TaskService.getDisplayRotation(displayId);
                            result.success(rotation);
                        } catch (Exception e) {
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "returnRearAppAndRestart": {
                        // 重启前先拉回背屏应用
                        try {
                            // 获取最后移动的任务信息
                            String lastTask = SwitchToRearTileService.getLastMovedTask();

                            if (lastTask != null && lastTask.contains(":")) {
                                String[] parts = lastTask.split(":");
                                int taskId = Integer.parseInt(parts[1]);

                                // 检查任务是否还在背屏
                                boolean onRear = TaskService.isTaskOnDisplay(taskId, 1);

                                if (onRear) {
                                    // 拉回主屏
                                    TaskService.moveTaskToDisplay(taskId, 0);

                                    // 恢复官方Launcher
                                    TaskService.enableSubScreenLauncher();

                                    result.success(true);
                                } else {
                                    // 没有应用在背屏
                                    result.success(false);
                                }
                            } else {
                                // 没有记录
                                result.success(false);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to return rear app", e);
                            result.error("ERROR", e.getMessage(), null);
                        }
                        break;
                    }

                    case "setProximitySensorEnabled": {
                        // V2.2: 设置接近传感器开关
                        boolean enabled = (boolean) call.argument("enabled");

                        // 通知RearScreenKeeperService更新状态
                        Intent intent = new Intent(this, RearScreenKeeperService.class);
                        intent.setAction("ACTION_SET_PROXIMITY_ENABLED");
                        intent.putExtra("enabled", enabled);
                        startService(intent);

                        result.success(true);
                        break;
                    }

                    case "setKeepScreenOnEnabled": {
                        // V2.5: 设置背屏常亮开关
                        boolean enabled = (boolean) call.argument("enabled");

                        // 通知RearScreenKeeperService更新状态
                        Intent intent = new Intent(this, RearScreenKeeperService.class);
                        intent.setAction("ACTION_SET_KEEP_SCREEN_ON_ENABLED");
                        intent.putExtra("enabled", enabled);
                        startService(intent);

                        result.success(true);
                        break;
                    }

                    case "setKeepAliveEnabled": {
                        // V3.6: 设置被投应用保活开关
                        Object keepAliveArg = call.argument("enabled");
                        boolean keepAlive = keepAliveArg != null && (boolean) keepAliveArg;

                        Intent keepAliveIntent = new Intent(this, RearScreenKeeperService.class);
                        keepAliveIntent.setAction("ACTION_SET_KEEP_ALIVE_ENABLED");
                        keepAliveIntent.putExtra("enabled", keepAlive);
                        startService(keepAliveIntent);

                        result.success(true);
                        break;
                    }
                    case "setAlwaysWakeUpEnabled": {
                        // V3.5: 设置未投放应用时常亮开关
                        boolean enabled = (boolean) call.argument("enabled");

                        SharedPreferences prefs = getSharedPreferences("mrss_settings", MODE_PRIVATE);
                        prefs.edit().putBoolean("always_wakeup_enabled", enabled).apply();

                        Intent intent = new Intent(this, AlwaysWakeUpService.class);
                        if (enabled) {
                            startService(intent);
                            Log.d(TAG, "AlwaysWakeUpService started");
                        } else {
                            stopService(intent);
                            Log.d(TAG, "AlwaysWakeUpService stopped");
                        }

                        result.success(true);
                        break;
                    }
                }
            });
    }
}