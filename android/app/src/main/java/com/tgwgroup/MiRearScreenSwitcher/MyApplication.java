/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import io.flutter.app.FlutterApplication;

/**
 * 自定义Application
 */
public class MyApplication extends FlutterApplication {

    private static final String TAG = "MyApplication";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // HiddenAPI豁免（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Class<?> hiddenApiBypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
                hiddenApiBypass.getMethod("addHiddenApiExemptions", String.class)
                    .invoke(null, "L");
            } catch (Exception e) {
                Log.w(TAG, "HiddenApiBypass not available", e);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
