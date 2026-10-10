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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 特权 shell 命令执行器（常驻 root shell）。
 *
 * <p>所有需要 root 权限的命令统一通过一个常驻的 {@code su -c sh} 会话执行，
 * 命令由管道写入、按唯一结束标记同步读回。
 *
 * <p>相比每次命令 fork 一个 {@code su} 进程，常驻会话只在建立时触发一次
 * root 管理器（Magisk / KernelSU 等）的授权提示，避免后台高频命令（如防熄屏
 * 唤醒）反复弹出"已授予超级用户权限"的通知；同时省去反复 fork 的开销。
 *
 * <p>实现要点：
 * <ul>
 *   <li><b>独立读取线程 + 阻塞队列</b>：读取线程独占 {@code stdout} 并投递到
 *       队列，命令线程只从队列带超时取行。这样既避免了
 *       {@code BufferedReader.ready()} 在"数据尚无换行"时误判导致的阻塞，
 *       也让超时控制真正可靠。</li>
 *   <li><b>执行超时</b>：读结束标记带超时。若某条命令消耗 stdin、或输出中
 *       恰好出现与标记同前缀的行导致标记永不出现，命令线程不会被无限阻塞，
 *       从而避免"一条坏命令把全局锁永久占死、整个应用特权调用全部瘫痪"。</li>
 *   <li><b>超时/EOF 即重建会话</b>：避免被污染的会话状态被后续命令继承。</li>
 *   <li><b>stderr 保留</b>：由单独线程读走并写日志，既防管道写满阻塞，
 *       也保留排障信息。</li>
 * </ul>
 *
 * <p>注意：命令串行执行，<b>不要在持有本锁的路径上执行耗时很长的命令</b>
 * （例如前台运行数分钟的 screenrecord）。长时任务应自行后台化
 * （{@code cmd &}），参考 ScreenRecordService 的做法。
 */
public final class ShellExecutor {
    private static final String TAG = "ShellExecutor";

    /** 命令执行互斥锁（所有调用串行经过同一个 shell）。 */
    private static final Object LOCK = new Object();

    /** 结束标记序号，保证每条命令的标记全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /** 单条命令的读取超时（毫秒）。超时即判定会话异常并重建。 */
    private static final long READ_TIMEOUT_MS = 10_000L;

    /** 读取线程在流结束（EOF）时投递的哨兵，用于唤醒等待中的命令线程。 */
    private static final String EOF_SENTINEL = "\u0000MRSS_EOF\u0000";

    /** 读取线程投递的输出行队列。 */
    private static final LinkedBlockingQueue<String> LINE_QUEUE = new LinkedBlockingQueue<>();

    /** 最近一次命令的标准输出，供调用方在 {@link #run} 之后取用。 */
    private static volatile String lastOutput = "";

    /** 常驻 root shell 进程；null 表示尚未启动或已失效。 */
    private static Process shell;
    private static BufferedWriter toShell;
    private static BufferedReader fromShell;

    /** 异步会话（供 {@link #submit} 使用），与主会话彼此独立、互不阻塞。 */
    private static Process asyncShell;
    private static BufferedWriter asyncWriter;

    /** 异步写入线程是否已启动（只启动一次，长期存活）。 */
    private static boolean asyncWriterStarted;

    /**
     * 异步命令队列。容量有限，防止极端情况下无界堆积；
     * 队列满时丢弃新命令（保活类命令丢一两次无影响）。
     */
    private static final LinkedBlockingQueue<String> asyncQueue = new LinkedBlockingQueue<>(64);

    private ShellExecutor() {
    }

    /** 一条命令的执行结果。 */
    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * 执行命令，只关心退出码。
     *
     * @param cmd shell 命令
     * @return 退出码；超时或异常时返回 -1
     */
    public static int run(String cmd) {
        synchronized (LOCK) {
            return exec(cmd).exitCode;
        }
    }

    /**
     * 执行命令，返回标准输出。
     *
     * @param cmd shell 命令
     * @return 标准输出内容；异常时返回空字符串
     */
    public static String runWithResult(String cmd) {
        synchronized (LOCK) {
            return exec(cmd).output;
        }
    }

    /**
     * 执行命令，返回输出（兼容原合并输出语义）。
     *
     * <p>常驻会话中错误输出被读入日志，因此本方法在语义上与
     * {@link #runWithResult} 等价，保留它是为了不改动既有调用点。
     *
     * @param cmd shell 命令
     * @return 输出内容；异常时返回空字符串
     */
    public static String runMergedWithResult(String cmd) {
        synchronized (LOCK) {
            return exec(cmd).output;
        }
    }

    /**
     * 取最近一次命令的标准输出。
     *
     * <p>供 {@code executeShellCommand} 这类只关心退出码、但仍想记录输出的
     * 调用方使用。
     *
     * @return 最近一次输出；无则为空串
     */
    public static String lastOutput() {
        return lastOutput;
    }

    /**
     * 在<b>独立会话</b>中异步执行命令：投递后立即返回，不等结果。
     *
     * <p>用于后台高频、彼此幂等、无需关心返回值的命令。典型场景是背屏保活：
     * 每 0.1~0.5 秒一次的 {@code input -d 1 keyevent KEYCODE_WAKEUP}。这类命令
     * 若走主锁，会和用户主动触发的切屏命令抢同一把锁，造成切屏延迟与命令积压；
     * 若每次都 fork 一个 {@code su}，又会因调用过于密集而堆积进程。
     *
     * <p>本方法维护一个<b>独立的常驻 shell 会话</b>与独立写入线程：
     * 调用方只是把命令丢进队列后立刻返回（几乎零开销），由后台线程顺序写入该
     * 会话。它不参与主锁、不阻塞调用方、也不会堆积进程。会话异常时会自动重建。
     *
     * <p>由于不读取输出，本方法<b>不提供退出码</b>，只适合"发了就行"的命令。
     * 需要结果的命令请用 {@link #runWithResult}。
     *
     * @param cmd shell 命令
     */
    public static void submit(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return;
        }
        ensureAsyncShell();
        if (asyncShell == null) {
            return;
        }
        if (!asyncQueue.offer(cmd)) {
            Log.w(TAG, "异步命令队列已满，丢弃: " + cmd);
        }
    }

    /** 确保异步会话与写入线程就绪。 */
    private static synchronized void ensureAsyncShell() {
        // 写入线程只启动一次，它长期存活、按需取用当前会话句柄
        if (!asyncWriterStarted) {
            asyncWriterStarted = true;
            Thread writer = new Thread(() -> {
                while (true) {
                    try {
                        String c = asyncQueue.take();
                        BufferedWriter w;
                        synchronized (ShellExecutor.class) {
                            w = asyncWriter;
                        }
                        if (w == null) {
                            // 会话尚未建立或已失效，命令丢弃（保活类命令可丢）
                            continue;
                        }
                        try {
                            w.write(c);
                            w.newLine();
                            w.flush();
                        } catch (IOException e) {
                            // 会话坏了：丢弃句柄，下次 submit 会触发重建
                            Log.w(TAG, "异步 shell 写入失败，稍后重建: " + e.getMessage());
                            synchronized (ShellExecutor.class) {
                                asyncShell = null;
                                asyncWriter = null;
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "mrss-su-async-writer");
            writer.setDaemon(true);
            writer.start();
        }

        if (asyncShell != null) {
            try {
                asyncShell.exitValue();
                // 进程已结束，走重建
                asyncShell = null;
                asyncWriter = null;
            } catch (IllegalThreadStateException stillRunning) {
                return;
            }
        }
        try {
            Process process = new ProcessBuilder("su", "-c", "sh").start();
            asyncShell = process;
            asyncWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()), 4096);

            // 输出无人读取，必须排空，否则管道写满会让 shell 阻塞
            Thread drainOut = new Thread(() -> drainQuietly(process.getInputStream()), "mrss-su-async-out");
            drainOut.setDaemon(true);
            drainOut.start();
            Thread drainErr = new Thread(() -> drainQuietly(process.getErrorStream()), "mrss-su-async-err");
            drainErr.setDaemon(true);
            drainErr.start();

            Log.i(TAG, "异步 root shell 会话已建立");
        } catch (Exception e) {
            Log.w(TAG, "启动异步 root shell 失败：后台保活将降级", e);
            asyncShell = null;
            asyncWriter = null;
        }
    }

    /** 持续读走并丢弃流内容，避免子进程因管道写满而阻塞。 */
    private static void drainQuietly(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream), 1024)) {
            while (reader.readLine() != null) {
                // 丢弃
            }
        } catch (IOException ignored) {
        }
    }

    // ==================== 内部实现 ====================

    /** 在已持有 {@link #LOCK} 的前提下执行一条命令。 */
    private static Result exec(String cmd) {
        if (!ensureShell()) {
            lastOutput = "";
            return new Result(-1, "");
        }

        // 上一条命令已完整读完（超时路径会重建会话），此处清空只为保险
        LINE_QUEUE.clear();

        String marker = "MRSS_CMD_" + SEQ.incrementAndGet() + "_END";
        try {
            toShell.write(cmd);
            toShell.newLine();
            toShell.write("echo " + marker + " $?");
            toShell.newLine();
            toShell.flush();
        } catch (IOException e) {
            Log.e(TAG, "写入命令失败: " + cmd, e);
            destroyShell();
            lastOutput = "";
            return new Result(-1, "");
        }

        StringBuilder sb = new StringBuilder();
        int exitCode = -1;
        long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;

        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                Log.w(TAG, "命令执行超时(" + READ_TIMEOUT_MS + "ms)，重建 shell 会话: " + cmd);
                destroyShell();
                lastOutput = sb.toString();
                return new Result(-1, sb.toString());
            }

            String line;
            try {
                line = LINE_QUEUE.poll(remain, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                lastOutput = sb.toString();
                return new Result(-1, sb.toString());
            }

            if (line == null) {
                Log.w(TAG, "等待命令输出超时，重建 shell 会话: " + cmd);
                destroyShell();
                lastOutput = sb.toString();
                return new Result(-1, sb.toString());
            }

            if (EOF_SENTINEL.equals(line)) {
                // 会话已结束：重建后再报告失败
                Log.w(TAG, "root shell 已结束，重建会话");
                destroyShell();
                lastOutput = "";
                return new Result(-1, "");
            }

            if (line.startsWith(marker)) {
                try {
                    exitCode = Integer.parseInt(line.substring(marker.length()).trim());
                } catch (NumberFormatException ignored) {
                    exitCode = -1;
                }
                break;
            }

            sb.append(line).append("\n");
        }

        lastOutput = sb.toString();
        return new Result(exitCode, sb.toString());
    }

    /** 确保常驻 root shell 可用；必要时重建。调用方需持有 {@link #LOCK}。 */
    private static boolean ensureShell() {
        if (shell != null) {
            try {
                shell.exitValue();
                // 能取到退出码说明进程已结束，走重建
                Log.w(TAG, "root shell 已退出，重建会话");
                destroyShell();
            } catch (IllegalThreadStateException stillRunning) {
                return true;
            }
        }
        try {
            Process process = new ProcessBuilder("su", "-c", "sh").start();
            shell = process;
            toShell = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()), 8192);
            final BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192);
            fromShell = stdout;

            // 读取线程：独占 stdout，逐行投递到队列，EOF 时投递哨兵
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = stdout.readLine()) != null) {
                        LINE_QUEUE.put(line);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "读取 root shell 输出结束: " + e.getMessage());
                } finally {
                    LINE_QUEUE.offer(EOF_SENTINEL);
                }
            }, "mrss-su-reader");
            reader.setDaemon(true);
            reader.start();

            // 错误输出单独读走并写日志，避免管道写满导致 shell 阻塞
            Thread errDrain = new Thread(() -> drainStderr(process.getErrorStream()), "mrss-su-stderr");
            errDrain.setDaemon(true);
            errDrain.start();

            Log.i(TAG, "root shell 会话已建立");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "启动 root shell 失败", e);
            destroyShell();
            return false;
        }
    }

    /** 关闭常驻会话（尽力而为）。调用方需持有 {@link #LOCK}。 */
    private static void destroyShell() {
        if (shell != null) {
            try {
                if (toShell != null) toShell.close();
            } catch (Exception ignored) {
            }
            try {
                if (fromShell != null) fromShell.close();
            } catch (Exception ignored) {
            }
            try {
                shell.destroy();
            } catch (Exception ignored) {
            }
        }
        shell = null;
        toShell = null;
        fromShell = null;
        LINE_QUEUE.clear();
    }

    /** 持续读走子进程的错误输出并写入日志，避免因管道写满而阻塞。 */
    private static void drainStderr(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream), 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.w(TAG, "[stderr] " + line);
            }
        } catch (IOException ignored) {
        }
    }
}
