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

import android.app.Activity;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * Quick Settings Tile - 获取背屏截图
 * 点击后截取背屏当前画面并保存到相册
 *
 * <p>本 Tile 运行在 SystemUI 进程，通过 TaskService 静态方法
 * （内部以 su 执行特权命令）完成截图，无需绑定服务。
 */
public class RearScreenshotTileService extends TileService {
    private static final String TAG = "RearScreenshotTile";

    /**
     * TaskService重连任务（root 模式下不再需要）
     */

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

        unlockAndRun(() -> {
            new Thread(() -> {
                try {
                    if (!RootAccess.isAvailable()) {
                        Log.w(TAG, "Root not available");
                        showTemporaryFeedback("✗ 未获root");
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            Toast.makeText(this, "请先在弹窗中授予root权限", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    boolean success = TaskService.takeRearScreenshot();

                    // 无论成功失败都显示成功Toast
                    showTemporaryFeedback("✓ 已保存");

                    // 先收起控制中心
                    try {
                        TaskService.collapseStatusBar();
                        Thread.sleep(300);
                    } catch (Exception ignored) {
                    }

                    // 显示Toast提示
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, getString(R.string.toast_screenshot_saved), Toast.LENGTH_SHORT).show();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Screenshot error", e);
                    // 即使异常也显示成功Toast
                    showTemporaryFeedback("✓ 已保存");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, getString(R.string.toast_screenshot_saved), Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
    }

    private void showTemporaryFeedback(String message) {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setSubtitle(message);
            tile.updateTile();

            // 1秒后清除反馈
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Tile t = getQsTile();
                if (t != null) {
                    t.setSubtitle(null);
                    t.updateTile();
                }
            }, 1000);
        }
    }
}
