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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.6: URI接收Activity
 * 完全透明，只负责转发URI到UriCommandService，然后立即finish
 * 不会显示任何UI，避免跳到MRSS页面
 *
 * V3.6: 新增 ACTION_SEND 支持（issue #20「贴背屏只能走超级小爱」）。
 * 系统分享菜单接入后，其它 App 的「分享 → MRSS」即可把来源 App 投到背屏：
 *   - 纯文本分享：优先解析文本中的 mrss:// 链接，拿到就走原有命令面；
 *   - 拿不到链接：用分享来源包名构造 mrss://switch?packageName=<来源包>；
 *   - 图片/视频分享：只借用来源包名，不搬运文件本身。
 * 来源包名一律过 ShellCompat.checkPackageName 校验，不通过即丢弃。
 */
public class UriReceiverActivity extends Activity {
    private static final String TAG = "UriReceiverActivity";

    /** 从分享文本里提取 mrss:// 命令链接。 */
    private static final Pattern MRSS_LINK = Pattern.compile("mrss://\\S+");

    /** 从 referrer 里提取包名（android-app://<package>/... 形式）。 */
    private static final Pattern REFERRER_PKG = Pattern.compile("android-app://([^/]+)");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏/息屏时也要能接收 URI 命令。澎湃 OS 的 ActivityStarterImpl 会做
        // "rearDisplay check locked" 拦截：不声明这两个 flag 时，主屏锁屏状态下
        // 本 Activity 直接被 abort（logcat: "aborted activity = ... show on rear display"），
        // 导致息屏截图、分享投放等命令根本不会执行。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        // 不设置任何布局，保持透明

        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            if (Intent.ACTION_VIEW.equals(action)) {
                handleView(intent);
            } else if (Intent.ACTION_SEND.equals(action)) {
                // 单条分享；ACTION_SEND_MULTIPLE（多选分享）语义不明，忽略
                handleSend(intent);
            }
        }

        // 立即finish，不显示任何UI
        finish();
    }

    private void handleView(Intent intent) {
        Uri uri = intent.getData();
        if (uri != null && "mrss".equals(uri.getScheme())) {
            Log.d(TAG, "🔗 URI接收: " + uri.toString());

            // 转发到UriCommandService处理
            Intent serviceIntent = new Intent(this, UriCommandService.class);
            serviceIntent.setData(uri);
            startService(serviceIntent);

            Log.d(TAG, "✓ 已转发到UriCommandService");
        }
    }

    /**
     * V3.6: 处理系统分享。
     *
     * @param intent ACTION_SEND intent
     */
    private void handleSend(Intent intent) {
        String type = intent.getType();
        boolean isText = type != null && type.startsWith("text/");

        // 1) 纯文本分享：先看文本里有没有现成的 mrss:// 命令链接
        if (isText) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Uri embedded = extractMrssUri(text.toString());
                if (embedded != null) {
                    Log.d(TAG, "🔗 分享文本内嵌URI: " + embedded);
                    Intent serviceIntent = new Intent(this, UriCommandService.class);
                    serviceIntent.setData(embedded);
                    startService(serviceIntent);
                    return;
                }
            }
        }

        // 2) 没有可解析的链接（或非文本分享）：用来源包名投放来源 App
        String sourcePkg = resolveSourcePackage(intent);
        if (sourcePkg == null) {
            Log.w(TAG, "✗ 分享缺少来源包名，已忽略");
            return;
        }
        if (!ShellCompat.checkPackageName(sourcePkg, "UriReceiverActivity/ACTION_SEND")) {
            Log.w(TAG, "✗ 分享来源包名校验失败，已丢弃");
            return;
        }
        if (getPackageName().equals(sourcePkg)) {
            Log.w(TAG, "✗ 分享来源是 MRSS 自身，忽略");
            return;
        }

        Uri command = Uri.parse("mrss://switch?packageName=" + Uri.encode(sourcePkg));
        Log.d(TAG, "🔗 分享投放: " + sourcePkg);
        Intent serviceIntent = new Intent(this, UriCommandService.class);
        serviceIntent.setData(command);
        startService(serviceIntent);
    }

    /**
     * 从分享文本中提取第一条 mrss:// 命令链接。
     *
     * @param text 分享文本
     * @return 解析出的 Uri，没有则 null
     */
    private Uri extractMrssUri(String text) {
        Matcher m = MRSS_LINK.matcher(text);
        if (!m.find()) {
            return null;
        }
        String raw = m.group();
        // 链接常被引号/括号包裹，去掉尾部常见标点
        while (!raw.isEmpty()) {
            char c = raw.charAt(raw.length() - 1);
            if (c == '.' || c == ',' || c == ';' || c == '!' || c == '?'
                    || c == ')' || c == ']' || c == '}') {
                raw = raw.substring(0, raw.length() - 1);
            } else {
                break;
            }
        }
        if (raw.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(raw);
            if ("mrss".equals(uri.getScheme())) {
                return uri;
            }
        } catch (Exception e) {
            Log.w(TAG, "分享文本URI解析失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 解析分享的来源包名。
     *
     * <p>系统分享面板会保留发起分享的应用身份，用 {@link Activity#getReferrer()}
     * 取到的形如 {@code android-app://<package>/...}。
     *
     * @param intent ACTION_SEND intent
     * @return 包名，取不到则 null
     */
    private String resolveSourcePackage(Intent intent) {
        Uri referrer = getReferrer();
        if (referrer != null) {
            Matcher m = REFERRER_PKG.matcher(referrer.toString());
            if (m.find()) {
                String pkg = m.group(1);
                if (pkg != null && !pkg.isEmpty()) {
                    return pkg;
                }
            }
        }
        return null;
    }

    @Override
    public void finish() {
        super.finish();
        // 禁用转场动画，完全透明
        overridePendingTransition(0, 0);
    }
}