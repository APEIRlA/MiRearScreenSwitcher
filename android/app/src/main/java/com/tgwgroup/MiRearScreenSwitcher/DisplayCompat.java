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
     * 构造「把任务移动到指定屏幕」的 shell 命令。
     *
     * <p><b>V3.6.5 起已废弃，禁止再调用。</b>实测（Android 17 / 澎湃 OS 4.0.2，2509FPN0BC）：
     * {@code service call activity_task 51} 不只是「搬不动 task」，它会把 task 搬进损坏状态，
     * 之后 system_server 遍历 Task 树时遇到 {@code parent == null} 的孤儿 Task，
     * {@code Task.getTopResumedActivity()} 抛 NPE → system_server 死亡 → 手机软重启。
     * 崩溃栈固定为 {@code TaskDisplayArea.getFocusedActivity} / {@code DisplayContent.sleepIfNeeded} /
     * {@code PowerManagerServiceImpl.updateForegroundApp}。
     *
     * <p>请改用 {@link #moveStackCommand(int, int)} 或 {@code TaskService.moveTaskToDisplay}
     * （后者带回读校验，不会出现返回成功但 task 没搬的情况）。
     *
     * <p>历史：V3.6.1 曾把它判定为「无效但无害」而保留为 move-stack 的兜底路径，
     * 后被对照实验证实为软重启根因，兜底路径已移除。
     *
     * @deprecated 会导致 system_server NPE 崩溃与手机软重启，请改用 {@link #moveStackCommand(int, int)}
     */
    @Deprecated
    public static String moveTaskCommand(int taskId, int displayId) {
        // 澎湃 OS / MIUI 定制的 IActivityTaskManager 上 transaction 51 的参数顺序是 (displayId, rootTaskId)，
        // 与 AOSP 的 moveRootTaskToDisplay(int rootTaskId, int displayId) 相反。
        // 实测（Android 17 / OS4.0，2509FPN0BC）：i32 <rootTaskId> i32 <displayId> 报
        // "moveTaskToRootTask: No rootTask for taskId=<displayId>"，反序才成功。
        return "service call activity_task " + moveTaskTransactionCode()
                + " i32 " + displayId + " i32 " + taskId;
    }

    /**
     * 构造"把任务移动到指定屏幕"的 shell 命令（Android 10+ 的 am 子命令）。
     *
     * <p><b>V3.6.1 起这是首选路径。</b>实测（Android 17 / 澎湃 OS 4.0，机型 2509FPN0BC）：
     * {@code service call activity_task 51} 在 MIUI 定制的 IActivityTaskManager 上
     * 两种参数顺序都搬不动 task——
     * <ul>
     *   <li>正序 {@code (rootTaskId, displayId)}：binder 返回错误串
     *       {@code moveTaskToRootTask: No rootTask for taskId=<displayId>}；</li>
     *   <li>反序 {@code (displayId, rootTaskId)}：返回 {@code Parcel(00000000)}，
     *       进程退出码 0，但 task 仍在原屏幕上（退出码只反映 service call 本身，
     *       binder 业务失败同样 exit=0，极易误判为成功）。</li>
     * </ul>
     * 而 {@code am display move-stack} 稳定生效，且 shell 身份即可执行、无需 root。
     *
     * @param taskId    目标任务 id（全屏场景下与 RootTask id 相同）
     * @param displayId 目标屏幕，1 为背屏、0 为主屏
     * @return 可直接交给 {@code sh -c} 执行的命令
     */
    public static String moveStackCommand(int taskId, int displayId) {
        return "am display move-stack " + taskId + " " + displayId;
    }
}
