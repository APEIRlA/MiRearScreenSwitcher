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
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root 权限检测。
 *
 * <p>通过执行 {@code su -c id} 并检查输出是否包含 {@code uid=0} 来判断
 * root 是否可用，结果缓存以避免每次调用都 fork su 进程。
 *
 * <p>注意：首次执行 {@code su} 时系统会弹出授权框（Magisk / KernelSU 等），
 * 用户授权后结果才会变为 true。
 */
public final class RootAccess {
    private static final String TAG = "RootAccess";

    /** 缓存的检测结果；null 表示尚未检测。 */
    private static Boolean cached;

    private RootAccess() {
    }

    /**
     * root 是否可用（结果缓存）。
     *
     * @return 已获得 root 授权返回 true，否则返回 false
     */
    public static synchronized boolean isAvailable() {
        if (cached != null) {
            return cached;
        }
        try {
            Process process = new ProcessBuilder("su", "-c", "id").start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 256
            );
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            cached = (line != null && line.contains("uid=0"));
        } catch (Exception e) {
            Log.w(TAG, "Root check failed", e);
            cached = false;
        }
        return cached;
    }

    /** 重置缓存（例如用户刚刚授权后需要重新检测）。 */
    public static synchronized void resetCache() {
        cached = null;
    }
}
