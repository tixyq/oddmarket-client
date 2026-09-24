package com.oddmarket;
// Shared WebView cookies.

import android.content.Context;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

public final class CookieHelper {

    private static volatile boolean initialized = false;

    private CookieHelper() {}

    public static synchronized void init(Context context) {
        if (initialized) return;

        if (Build.VERSION.SDK_INT < 21) {
            CookieSyncManager.createInstance(context.getApplicationContext());
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        initialized = true;
    }

    public static void flush() {
        if (Build.VERSION.SDK_INT >= 21) {

            try {
                CookieManager cm = CookieManager.getInstance();
                java.lang.reflect.Method flushMethod = cm.getClass().getMethod("flush");
                flushMethod.invoke(cm);
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "CookieManager.flush() via reflection failed", e);
            }
        } else {
            try {
                CookieSyncManager.getInstance().sync();
            } catch (IllegalStateException e) {

                FileLogger.w(Utils.TAG, "CookieSyncManager not initialized", e);
            }
        }
    }

    public static void clearSessionCookie(String url) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setCookie(url, "PHPSESSID=; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT");
        flush();
    }
}
