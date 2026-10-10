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

import java.util.regex.Pattern;

/**
 * Shell 参数安全工具。
 *
 * <p>改造为 root 模式后，所有命令都以 {@code su -c sh}（UID 0）执行。若把外部
 * 传入的字符串（{@code mrss://} 查询参数、通知标题与内容等）直接拼接进命令，
 * 攻击者可用 {@code $(...)}、反引号、{@code ;}、换行等构造任意 root 命令。
 *
 * <p>本类提供两道防线：
 * <ol>
 *   <li>{@link #isValidPackageName} / {@link #isValidComponent}：白名单校验，
 *       只放行合法包名与 {@code 包名/类名} 组件；</li>
 *   <li>{@link #quote}：把任意文本安全地包成 shell 单引号字面量，
 *       用于必须传自由文本的场合（通知标题、内容）。</li>
 * </ol>
 *
 * <p>校验不过时调用方必须**丢弃**该输入，而不是"清理后继续"——后者容易
 * 漏掉边角情况。
 */
public final class ShellCompat {
    private static final String TAG = "ShellCompat";

    /** Android 包名：至少两段，字母数字下划线，段内不以数字开头。 */
    private static final Pattern PACKAGE_NAME =
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    /** 组件名：包名 + '/' + 类名（允许 '$' 用于内部类）。 */
    private static final Pattern COMPONENT =
        Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"
                      + "/[a-zA-Z_$][a-zA-Z0-9_$.]*$");

    /** 数值参数：可选负号 + 数字，限定长度防止超长输入。 */
    private static final Pattern INTEGER = Pattern.compile("^-?\\d{1,6}$");

    private ShellCompat() {
    }

    /**
     * 校验包名是否合法。
     *
     * @param packageName 待校验包名
     * @return 合法返回 true
     */
    public static boolean isValidPackageName(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.length() > 255) {
            return false;
        }
        return PACKAGE_NAME.matcher(packageName).matches();
    }

    /**
     * 校验组件名（{@code 包名/类名}）是否合法。
     *
     * @param component 待校验组件名
     * @return 合法返回 true
     */
    public static boolean isValidComponent(String component) {
        if (component == null || component.isEmpty() || component.length() > 255) {
            return false;
        }
        return COMPONENT.matcher(component).matches();
    }

    /**
     * 校验整数参数是否合法（用于 DPI、旋转等）。
     *
     * @param value 待校验字符串
     * @return 是合法整数返回 true
     */
    public static boolean isValidInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return INTEGER.matcher(value).matches();
    }

    /**
     * 把任意文本包成 shell 安全的单引号字面量。
     *
     * <p>单引号内除 {@code '} 本身外一切字符都是字面量，因此只需把内部的
     * {@code '} 替换为 {@code '\''}（结束引号 + 转义单引号 + 重新开始引号）。
     * 新行、{@code $}、反引号、{@code ;} 在单引号内都不会被解释。
     *
     * @param raw 原始文本，null 视为空串
     * @return 可直接拼进 shell 命令的字面量
     */
    public static String quote(String raw) {
        String s = raw == null ? "" : raw;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 从组件名中取出包名部分。
     *
     * @param component 组件名，如 {@code com.foo/.Bar}
     * @return 包名；无法解析时返回 null
     */
    public static String packageOfComponent(String component) {
        if (component == null) {
            return null;
        }
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : null;
    }

    /**
     * 校验 URI 传入的包名，并记录拒绝日志。
     *
     * @param packageName 待校验包名
     * @param source      调用来源，仅用于日志
     * @return 合法返回 true
     */
    public static boolean checkPackageName(String packageName, String source) {
        if (isValidPackageName(packageName)) {
            return true;
        }
        Log.w(TAG, "拒绝非法包名[" + source + "]: " + packageName);
        return false;
    }

    /**
     * 校验 URI 传入的组件名，并记录拒绝日志。
     *
     * @param component 待校验组件名
     * @param source    调用来源，仅用于日志
     * @return 合法返回 true
     */
    public static boolean checkComponent(String component, String source) {
        if (isValidComponent(component)) {
            return true;
        }
        Log.w(TAG, "拒绝非法组件[" + source + "]: " + component);
        return false;
    }
}
