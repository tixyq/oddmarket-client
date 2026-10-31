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

    private static volatile Context pendingContext;

    /** Cheap: only remembers the context. The WebView cookie stack is created on first real use. */
    public static void init(Context context) {
        pendingContext = context.getApplicationContext();
    }

    /**
     * Must be called on the UI thread BEFORE the first {@code new WebView(...)}.
     * WebView on Android < 4.4 (pre-Chromium) requires CookieSyncManager to already exist
     * when it is constructed, otherwise it throws IllegalStateException
     * ("CookieSyncManager::createInstance() needs to be called before ...getInstance()").
     */
    public static void prepare(Context context) {
        if (pendingContext == null && context != null) pendingContext = context.getApplicationContext();
        ensureInit();
    }

    private static synchronized void ensureInit() {
        if (initialized) return;
        Context context = pendingContext;
        if (context == null) return;

        try {
            if (Build.VERSION.SDK_INT < 21) {
                CookieSyncManager.createInstance(context.getApplicationContext());
            }

            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
        } catch (Throwable t) {
            // Never let cookie setup take the whole screen down.
        }

        initialized = true;
    }

    public static void enableCookiesForWebView(android.webkit.WebView webView) {
        if (pendingContext == null) pendingContext = webView.getContext().getApplicationContext();
        ensureInit();
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                java.lang.reflect.Method m = CookieManager.class.getMethod(
                        "setAcceptThirdPartyCookies", android.webkit.WebView.class, boolean.class);
                m.invoke(cookieManager, webView, true);
            } catch (Exception e) {
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
        ensureInit();
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                CookieManager cm = CookieManager.getInstance();
                java.lang.reflect.Method flushMethod = cm.getClass().getMethod("flush");
                flushMethod.invoke(cm);
            } catch (Exception e) {
            }
        } else {
            try {
                CookieSyncManager.getInstance().sync();
            } catch (IllegalStateException e) {

            }
        }
    }

    public static void clearSessionCookie(String url) {
        ensureInit();
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setCookie(url, "PHPSESSID=; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT");
        flush();
    }
}
