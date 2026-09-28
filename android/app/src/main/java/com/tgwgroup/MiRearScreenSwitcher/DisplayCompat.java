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

import android.os.Build;

/**
 * 跨 Android 版本的显示 / 任务兼容层。
 *
 * <p>Android 15（API 35）起 AOSP 把 ActivityTaskManager 的 Task 体系重构成 RootTask，
 * binder 接口随之改名，transaction code 也变了：
 *
 * <pre>
 *   Android 14 及以前 : moveTaskToDisplay(int taskId, int displayId, boolean onTop)   code = 50
 *   Android 15 及以后 : moveRootTaskToDisplay(int rootTaskId, int displayId)          code = 51
 * </pre>
 *
 * <p>本应用历史上直接拼接 {@code service call activity_task 50 ...} 来投屏。
 * 在 Android 15+（澎湃 OS 3 / 小米 17 系列等）上，code 50 已经指向别的接口：
 * 命令既不报错也不会移动任务，表现出来就是"点了切换毫无反应"。
 * 这里按系统版本选择正确的 code。
 *
 * <p>关于参数语义：Android 15+ 传的是 rootTaskId。在单窗口（全屏）场景下，
 * {@code am stack list} 里 RootTask 的 id 与其下 task 的 id 相同，因此沿用
 * 原本解析出来的 taskId 是安全的；分屏时两者才可能不同。
 */
public final class DisplayCompat {

    /** Android 15 (VANILLA_ICE_CREAM)：Task → RootTask 重构的版本号。 */
    private static final int API_ANDROID_15 = 35;

    /** Android 14 及以前：IActivityTaskManager.moveTaskToDisplay。 */
    private static final int TRANSACTION_MOVE_TASK_TO_DISPLAY = 50;

    /** Android 15 及以后：IActivityTaskManager.moveRootTaskToDisplay。 */
    private static final int TRANSACTION_MOVE_ROOT_TASK_TO_DISPLAY = 51;

    private DisplayCompat() {
    }

    /**
     * 把任务移动到指定屏幕所需的 binder transaction code。
     *
     * @return Android 15+ 返回 51，其余返回 50
     */
    public static int moveTaskTransactionCode() {
        return Build.VERSION.SDK_INT >= API_ANDROID_15
                ? TRANSACTION_MOVE_ROOT_TASK_TO_DISPLAY
                : TRANSACTION_MOVE_TASK_TO_DISPLAY;
    }

    /**
     * 构造"把任务移动到指定屏幕"的 shell 命令。
     *
     * @param taskId    目标任务 id（Android 15+ 的语义是 rootTaskId）
     * @param displayId 目标屏幕，1 为背屏、0 为主屏
     * @return 可直接交给 {@code sh -c} 执行的命令
     */
    public static String moveTaskCommand(int taskId, int displayId) {
        return "service call activity_task " + moveTaskTransactionCode()
                + " i32 " + taskId + " i32 " + displayId;
    }
}
