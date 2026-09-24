package com.oddmarket;
// Login state via hidden WebView.

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Pattern;

public final class AccountManager {

    private static final String PREFS_NAME = "prefs";
    private static final String KEY_LOGGED_IN = "account_logged_in";
    private static final String KEY_NICKNAME = "account_nickname";

    private static final int WEBVIEW_TIMEOUT_MS = 15000;

    private static final int QUIET_PERIOD_MS = 350;

    private static final String PROBE_URL_PREFIX = "oddmarket-probe://body?v=";

    private static final int CHUNK_SIZE = 500;

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

    private static Cancelable fetchViaWebView(final Activity activity, final ViewGroup hostView, final String url, final String label, final BodyCallback callback) {
        if (activity == null || activity.isFinishing() || hostView == null) {
            if (callback != null) callback.onBody(null);
            return new Cancelable() {
                public void cancel() {}
            };
        }

        final WebView webView = new WebView(activity);
        webView.getSettings().setJavaScriptEnabled(true);
        hostView.addView(webView, new ViewGroup.LayoutParams(1, 1));

        final Handler handler = new Handler(activity.getMainLooper());

        final boolean[] done = new boolean[]{false};
        final Runnable[] probe = new Runnable[1];

        final StringBuilder collectedBody = new StringBuilder();
        final int[] nextOffset = new int[]{0};

        final Runnable cleanup = new Runnable() {
            public void run() {
                handler.removeCallbacksAndMessages(null);
                webView.stopLoading();
                webView.setWebViewClient(null);
                hostView.removeView(webView);
                webView.destroy();
            }
        };

        webView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String loadingUrl) {
                if (loadingUrl != null && loadingUrl.startsWith(PROBE_URL_PREFIX)) {
                    if (!done[0]) {
                        String chunk;
                        boolean more;
                        try {
                            Uri probeUri = Uri.parse(loadingUrl);
                            chunk = probeUri.getQueryParameter("v");
                            more = "1".equals(probeUri.getQueryParameter("m"));
                        } catch (Exception e) {
                            chunk = null;
                            more = false;
                        }
                        if (chunk != null) collectedBody.append(chunk);
                        if (more) {

                            nextOffset[0] += CHUNK_SIZE;
                            handler.post(probe[0]);
                        } else {
                            done[0] = true;
                            String body = collectedBody.toString();
                            cleanup.run();
                            if (callback != null) callback.onBody(body);
                        }
                    }
                    return true;
                }
                return false;
            }

            public void onPageStarted(WebView view, String startedUrl, Bitmap favicon) {
                handler.removeCallbacks(probe[0]);
            }

            public void onPageFinished(WebView view, String finishedUrl) {
                if (done[0]) return;
                handler.removeCallbacks(probe[0]);
                handler.postDelayed(probe[0], QUIET_PERIOD_MS);
            }

            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (done[0]) return;
                done[0] = true;
                FileLogger.w(Utils.TAG, label + ": WebView load error " + errorCode + " (" + description + ")");
                cleanup.run();
                if (callback != null) callback.onBody(null);
            }
        });

        probe[0] = new Runnable() {
            public void run() {
                if (done[0]) return;

                webView.loadUrl("javascript:(function(){"
                        + "var t=document.body.innerText;"
                        + "var o=" + nextOffset[0] + ";"
                        + "var c=t.substr(o," + CHUNK_SIZE + ");"
                        + "var m=(o+c.length<t.length)?1:0;"
                        + "location.href='" + PROBE_URL_PREFIX + "'+encodeURIComponent(c)+'&m='+m;"
                        + "})();");
            }
        };

        handler.postDelayed(new Runnable() {
            public void run() {
                if (done[0]) return;
                done[0] = true;
                FileLogger.w(Utils.TAG, label + ": timed out after " + WEBVIEW_TIMEOUT_MS + "ms");
                cleanup.run();
                if (callback != null) callback.onBody(null);
            }
        }, WEBVIEW_TIMEOUT_MS);

        webView.loadUrl(url);

        return new Cancelable() {
            public void cancel() {
                if (done[0]) return;
                done[0] = true;
                cleanup.run();
            }
        };
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
