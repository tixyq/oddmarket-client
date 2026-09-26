package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.regex.Pattern;

public final class AccountManager {

    private static final String PREFS_NAME = "prefs";
    private static final String KEY_LOGGED_IN = "account_logged_in";
    private static final String KEY_NICKNAME = "account_nickname";

    private static final int WEBVIEW_TIMEOUT_MS = 25000;

    private static final int QUIET_PERIOD_MS = 400;

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{1,25}$");

    private static final Pattern DELACC_RESPONSE_PATTERN =
            Pattern.compile("^(ok|error)$");

    private static final String KEY_CACHED_RATINGS_BODY = "cached_ratings_body";

    private static void saveRatingsCache(Context context, String rawBody) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Utils.savePrefs(prefs.edit().putString(KEY_CACHED_RATINGS_BODY, rawBody));
    }

    public static java.util.Map<String, Double> cachedRatings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String rawBody = prefs.getString(KEY_CACHED_RATINGS_BODY, null);
        return rawBody == null ? null : parseRatings(rawBody);
    }

    public interface Callback {
        void onResult(boolean loggedIn, String nickname);
    }

    public interface DeleteCallback {
        void onResult(boolean success);
    }

    public interface RatingsCallback {
        void onResult(java.util.Map<String, Double> ratings);
    }

    public interface Cancelable {
        void cancel();
    }

    private AccountManager() {}

    public static boolean isLoggedIn(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOGGED_IN, false);
    }

    public static String cachedNickname(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NICKNAME, null);
    }

    private static void saveResult(Context context, boolean loggedIn, String nickname) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Utils.savePrefs(prefs.edit()
                .putBoolean(KEY_LOGGED_IN, loggedIn)
                .putString(KEY_NICKNAME, nickname));
    }

    public static void forceLoggedOut(Context context) {
        saveResult(context, false, null);
    }

    private static final String KEY_CONSECUTIVE_CHECK_FAILURES = "account_consecutive_check_failures";

    private static final int UNREACHABLE_THRESHOLD = 4;

    public static boolean isAccountSystemReachable(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CONSECUTIVE_CHECK_FAILURES, 0) < UNREACHABLE_THRESHOLD;
    }

    private static void recordCheckOutcome(Context context, boolean succeeded) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int failures = succeeded ? 0 : prefs.getInt(KEY_CONSECUTIVE_CHECK_FAILURES, 0) + 1;
        Utils.savePrefs(prefs.edit().putInt(KEY_CONSECUTIVE_CHECK_FAILURES, failures));
    }

    private interface BodyCallback {

        void onBody(String body);
    }

    // Off-screen 2x2 params for hidden WebView.
    private static ViewGroup.LayoutParams hiddenLayoutParams(ViewGroup host, Activity activity) {
        if (!(host instanceof FrameLayout)) {
            return new ViewGroup.LayoutParams(2, 2);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(2, 2);
        params.leftMargin = 0;
        params.topMargin = 0;
        return params;
    }

    // Alpha 0 via reflection, API 11+.
    private static void applyInvisibleAppearance(WebView webView) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                java.lang.reflect.Method m = View.class.getMethod("setAlpha", float.class);
                m.invoke(webView, 0f);
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "Could not set WebView alpha", e);
            }
        }
    }

    // Hidden WebView fetch with DOM read and timeout.
    private static Cancelable fetchViaWebView(final Activity activity, final ViewGroup hostView, final String url, final String label, final BodyCallback callback) {
        FileLogger.w(Utils.TAG, label + ": starting fetch for " + url);
        if (activity == null || activity.isFinishing() || hostView == null) {
            if (callback != null) callback.onBody(null);
            return new Cancelable() {
                public void cancel() {}
            };
        }

        final WebView webView = new WebView(activity);
        Utils.configureWebViewCompat(webView);
        CookieHelper.enableCookiesForWebView(webView);
        applyInvisibleAppearance(webView);
        hostView.addView(webView, hiddenLayoutParams(hostView, activity));

        final Handler handler = new Handler(activity.getMainLooper());

        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};
        final int[] gen = new int[]{0};
        final String[] lastUrl = new String[]{url};
        final Runnable[] fetchRun = new Runnable[1];

        final BodyCallback[] activeExtractionCallback = new BodyCallback[1];
        final Object domBridge = new Object() {
            @android.webkit.JavascriptInterface
            public void onHtml(final String html) {
                new Handler(activity.getMainLooper()).post(new Runnable() {
                    public void run() {
                        BodyCallback cb = activeExtractionCallback[0];
                        if (cb != null) cb.onBody(html);
                    }
                });
            }
        };
        try {
            webView.addJavascriptInterface(domBridge, LEGACY_JS_BRIDGE_NAME);
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, label + ": could not register DOM bridge", e);
        }

        final Runnable cleanup = new Runnable() {
            public void run() {
                handler.removeCallbacksAndMessages(null);
                CookieHelper.flush();
                webView.stopLoading();
                webView.setWebViewClient(null);
                try {
                    java.lang.reflect.Method m = WebView.class.getMethod("removeJavascriptInterface", String.class);
                    m.invoke(webView, LEGACY_JS_BRIDGE_NAME);
                } catch (Exception ignored) {}
                hostView.removeView(webView);
                webView.destroy();
            }
        };

        fetchRun[0] = new Runnable() {
            public void run() {
                synchronized (lock) {
                    if (done[0]) return;
                }
                final int myGen;
                synchronized (lock) {
                    myGen = gen[0];
                }

                extractRenderedBody(webView, activeExtractionCallback, new BodyCallback() {
                    public void onBody(final String body) {
                        synchronized (lock) {
                            if (done[0] || myGen != gen[0]) return;
                            if (body == null || body.contains("toNumbers")) {
                                FileLogger.w(Utils.TAG, label + ": still a challenge page or empty DOM, retrying ("
                                        + (body == null ? "no body" : "challenge markup present") + ")");
                                handler.removeCallbacks(fetchRun[0]);
                                handler.postDelayed(fetchRun[0], QUIET_PERIOD_MS);
                                return;
                            }
                            FileLogger.w(Utils.TAG, label + ": got clean body, done");
                            done[0] = true;
                        }
                        cleanup.run();
                        if (callback != null) callback.onBody(body);
                    }
                });
            }
        };

        webView.setWebViewClient(new WebViewClient() {
            public void onPageFinished(WebView view, String finishedUrl) {

                CookieHelper.flush();

                synchronized (lock) {
                    if (done[0]) return;
                    gen[0]++;
                    if (finishedUrl != null) lastUrl[0] = finishedUrl;
                }
                handler.removeCallbacks(fetchRun[0]);
                handler.postDelayed(fetchRun[0], QUIET_PERIOD_MS);
            }

            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                synchronized (lock) {
                    if (done[0]) return;
                    done[0] = true;
                }
                FileLogger.w(Utils.TAG, label + ": WebView load error " + errorCode + " (" + description + ")");
                cleanup.run();
                if (callback != null) callback.onBody(null);
            }

            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed();
            }
        });

        handler.postDelayed(new Runnable() {
            public void run() {
                synchronized (lock) {
                    if (done[0]) return;
                    done[0] = true;
                }
                FileLogger.w(Utils.TAG, label + ": timed out after " + WEBVIEW_TIMEOUT_MS + "ms");
                cleanup.run();
                if (callback != null) callback.onBody(null);
            }
        }, WEBVIEW_TIMEOUT_MS);

        webView.loadUrl(url);

        return new Cancelable() {
            public void cancel() {
                synchronized (lock) {
                    if (done[0]) return;
                    done[0] = true;
                }
                cleanup.run();
            }
        };
    }

    private static final String CHALLENGE_STILL_PENDING = "toNumbers-challenge-still-pending";

    // DOM read, modern or legacy bridge.
    private static void extractRenderedBody(final WebView view, final BodyCallback[] activeExtractionCallback, final BodyCallback callback) {
        final String script =
                "(function(){"
                        + "var html = document.documentElement ? document.documentElement.outerHTML : '';"
                        + "if (html.indexOf('toNumbers') !== -1) { return '" + CHALLENGE_STILL_PENDING + "'; }"
                        + "var b = document.body;"
                        + "return b ? (b.innerText || b.textContent || '') : '';"
                        + "})()";
        extractRenderedBodyLegacy(view, script, activeExtractionCallback, callback);
    }

    private static final String LEGACY_JS_BRIDGE_NAME = "OddMarketDomBridge";

    // Pre-19 DOM read via JS bridge.
    private static void extractRenderedBodyLegacy(final WebView view, final String script, final BodyCallback[] activeExtractionCallback, final BodyCallback callback) {
        activeExtractionCallback[0] = callback;
        try {
            view.loadUrl("javascript:(function(){try{var r=(" + script + ");"
                    + LEGACY_JS_BRIDGE_NAME + ".onHtml(r);}catch(e){"
                    + LEGACY_JS_BRIDGE_NAME + ".onHtml('');}})()");
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Legacy DOM bridge failed", e);
            callback.onBody(null);
        }
    }

    public static Cancelable check(final Activity activity, ViewGroup hostView, final Callback callback) {
        if (activity == null || activity.isFinishing()) {
            if (callback != null && activity != null) {
                callback.onResult(isLoggedIn(activity), cachedNickname(activity));
            }
            return null;
        }

        return fetchViaWebView(activity, hostView, UrlBuilder.meUrl(activity), "Account status check", new BodyCallback() {
            public void onBody(String body) {
                boolean loggedIn;
                String nickname;
                if (body == null) {

                    loggedIn = isLoggedIn(activity);
                    nickname = cachedNickname(activity);
                    recordCheckOutcome(activity, false);
                } else {
                    String trimmed = body.trim();
                    boolean emptyIsLoggedOut = trimmed.length() == 0;
                    boolean looksLikeRealNickname = USERNAME_PATTERN.matcher(trimmed).matches();
                    if (!emptyIsLoggedOut && !looksLikeRealNickname) {
                        loggedIn = isLoggedIn(activity);
                        nickname = cachedNickname(activity);
                        recordCheckOutcome(activity, false);
                    } else {
                        loggedIn = looksLikeRealNickname;
                        nickname = loggedIn ? trimmed : null;
                        saveResult(activity, loggedIn, nickname);
                        recordCheckOutcome(activity, true);
                    }
                }
                if (callback != null) callback.onResult(loggedIn, nickname);
            }
        });
    }

    public static Cancelable deleteAccount(final Activity activity, ViewGroup hostView, final DeleteCallback callback) {
        if (activity == null || activity.isFinishing()) {
            if (callback != null) callback.onResult(false);
            return null;
        }

        return fetchViaWebView(activity, hostView, UrlBuilder.deleteAccountUrl(activity), "Delete-account request", new BodyCallback() {
            public void onBody(String body) {
                boolean success = false;
                if (body != null) {
                    String trimmed = body.trim();

                    if (DELACC_RESPONSE_PATTERN.matcher(trimmed).matches()) {
                        success = "ok".equals(trimmed);
                        if (success) {
                            saveResult(activity, false, null);
                        }
                    }
                }
                if (callback != null) callback.onResult(success);
            }
        });
    }

    public static Cancelable fetchRatings(final Activity activity, ViewGroup hostView, final RatingsCallback callback) {
        if (activity == null || activity.isFinishing()) {
            if (callback != null) callback.onResult(null);
            return null;
        }

        return fetchViaWebView(activity, hostView, UrlBuilder.ratingUrl(), "Fetch app ratings", new BodyCallback() {
            public void onBody(String body) {
                java.util.Map<String, Double> parsed = body == null ? null : parseRatings(body);
                if (parsed != null) {
                    saveRatingsCache(activity, body);
                }
                if (callback != null) callback.onResult(parsed);
            }
        });
    }

    private static java.util.Map<String, Double> parseRatings(String body) {
        String trimmedBody = body == null ? "" : body.trim();
        java.util.Map<String, Double> parsed = new java.util.LinkedHashMap<String, Double>();
        if (trimmedBody.length() == 0) {
            return parsed;
        }
        String[] lines = trimmedBody.split("\n");
        for (String rawLine : lines) {
            String trimmedLine = rawLine.trim();
            if (trimmedLine.length() == 0) continue;
            int sep = trimmedLine.lastIndexOf(' ');
            if (sep <= 0 || sep == trimmedLine.length() - 1) continue;
            String pkg = trimmedLine.substring(0, sep).trim();
            String value = trimmedLine.substring(sep + 1).trim();
            try {
                parsed.put(pkg, Double.parseDouble(value));
            } catch (NumberFormatException ignored) {}
        }
        if (parsed.isEmpty()) {
            return null;
        }
        return parsed;
    }
}
