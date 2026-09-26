package com.oddmarket;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

public final class CookieHelper {

    private static volatile boolean initialized = false;

    private static final long FLUSH_DEBOUNCE_MS = 300;
    private static Handler flushHandler;
    private static boolean flushPending = false;

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

    public static void enableCookiesForWebView(android.webkit.WebView webView) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                java.lang.reflect.Method m = CookieManager.class.getMethod(
                        "setAcceptThirdPartyCookies", android.webkit.WebView.class, boolean.class);
                m.invoke(cookieManager, webView, true);
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "Could not enable third-party WebView cookies", e);
            }
        }
    }

    public static void flush() {
        synchronized (CookieHelper.class) {
            if (flushPending) return;
            flushPending = true;
        }
        getFlushHandler().postDelayed(new Runnable() {
            public void run() {
                synchronized (CookieHelper.class) {
                    flushPending = false;
                }
                flushNow();
            }
        }, FLUSH_DEBOUNCE_MS);
    }

    private static Handler getFlushHandler() {
        Handler h = flushHandler;
        if (h == null) {
            synchronized (CookieHelper.class) {
                h = flushHandler;
                if (h == null) {

                    h = new Handler(Looper.getMainLooper());
                    flushHandler = h;
                }
            }
        }
        return h;
    }

    private static void flushNow() {
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
