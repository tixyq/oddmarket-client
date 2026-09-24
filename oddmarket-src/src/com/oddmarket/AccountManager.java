package com.oddmarket;
// Login state via hidden WebView.

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Handler;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Pattern;

public final class AccountManager {

    private static final String PREFS_NAME = "prefs";
    private static final String KEY_LOGGED_IN = "account_logged_in";
    private static final String KEY_NICKNAME = "account_nickname";

    private static final int WEBVIEW_TIMEOUT_MS = 25000;

    private static final int QUIET_PERIOD_MS = 350;

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

        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};
        final int[] gen = new int[]{0};
        final String[] lastUrl = new String[]{url};
        final Runnable[] fetchRun = new Runnable[1];

        final Runnable cleanup = new Runnable() {
            public void run() {
                handler.removeCallbacksAndMessages(null);
                webView.stopLoading();
                webView.setWebViewClient(null);
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
                final String myUrl;
                synchronized (lock) {
                    myGen = gen[0];
                    myUrl = lastUrl[0];
                }
                new Thread(new Runnable() {
                    public void run() {
                        String direct = AntiBot.fetchDirect(activity.getApplicationContext(), myUrl, label);
                        if (direct != null && !AntiBot.isChallenge(direct)) {
                            final String clean = direct;
                            handler.post(new Runnable() {
                                public void run() {
                                    synchronized (lock) {
                                        if (done[0] || myGen != gen[0]) return;
                                        done[0] = true;
                                    }
                                    cleanup.run();
                                    if (callback != null) callback.onBody(clean);
                                }
                            });
                            return;
                        }
                        final String body = fetchBodyWithCookies(webView, myUrl, label);
                        handler.post(new Runnable() {
                            public void run() {
                                synchronized (lock) {
                                    if (done[0] || myGen != gen[0]) return;
                                    if (body == null || body.contains("toNumbers")) {
                                        handler.removeCallbacks(fetchRun[0]);
                                        handler.postDelayed(fetchRun[0], QUIET_PERIOD_MS);
                                        return;
                                    }
                                    done[0] = true;
                                }
                                cleanup.run();
                                if (callback != null) callback.onBody(body);
                            }
                        });
                    }
                }).start();
            }
        };

        webView.setWebViewClient(new WebViewClient() {
            public void onPageFinished(WebView view, String finishedUrl) {
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

            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
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

    private static String fetchBodyWithCookies(WebView view, String url, String label) {
        String ua = null;
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            try {
                ua = view.getSettings().getUserAgentString();
            } catch (Exception ignored) {}
        }
        return httpGet(view.getContext(), ua, url, label);
    }

    static String httpGet(android.content.Context ctx, String ua, String url, String label) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            boolean haveCookies = false;
            try {
                String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookies != null && cookies.length() > 0) {
                    conn.setRequestProperty("Cookie", cookies);
                    haveCookies = true;
                }
            } catch (Exception ignored) {}
            if (!haveCookies) {
                FileLogger.w(Utils.TAG, label + ": fetch has no cookies yet");
            }
            boolean pinned = false;
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                try {
                    javax.net.ssl.SSLSocketFactory f = PinnedTrust.forOddmarket(ctx);
                    if (f != null) {
                        ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(f);
                        pinned = true;
                    }
                } catch (Exception ignored) {}
            }
            if (!pinned && url.toLowerCase().startsWith("https://")) {
                FileLogger.w(Utils.TAG, label + ": fetch without pinned TLS");
            }
            conn.connect();
            int status = conn.getResponseCode();
            if (status < 200 || status > 299) {
                FileLogger.w(Utils.TAG, label + ": fetch HTTP " + status);
                return null;
            }
            String encoding = "UTF-8";
            try {
                String ct = conn.getContentType();
                if (ct != null) {
                    String[] parts = ct.split(";");
                    for (int i = 1; i < parts.length; i++) {
                        String p = parts[i].trim();
                        if (p.length() > 8 && p.substring(0, 8).equalsIgnoreCase("charset=")) {
                            String cs = p.substring(8).trim();
                            if (cs.length() > 0) encoding = cs;
                        }
                    }
                }
            } catch (Exception ignored) {}
            in = new java.io.BufferedInputStream(conn.getInputStream(), 16384);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buf.write(chunk, 0, read);
            }
            try {
                java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
                for (java.util.Map.Entry<String, java.util.List<String>> h : headers.entrySet()) {
                    if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie") && h.getValue() != null) {
                        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                        for (String c : h.getValue()) {
                            try {
                                cm.setCookie(url, c);
                            } catch (Exception ignored) {}
                        }
                        CookieHelper.flush();
                    }
                }
            } catch (Exception ignored) {}
            FileLogger.w(Utils.TAG, label + ": fetch ok, " + buf.size() + " bytes");
            try {
                return buf.toString(encoding);
            } catch (Exception e) {
                return buf.toString();
            }
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, label + ": fetch failed: " + e.toString());
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
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
