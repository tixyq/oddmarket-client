package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class Utils {

    public static final String TAG = "OddMarket";

    public static String deviceCountry = "";

    public static final int HOME_ID = 0x0102002c;
    public static final int SHOW_AS_ACTION_ALWAYS = 2;
    public static final int SHOW_AS_ACTION_NEVER = 0;

    private static final int FROYO = 8;

    private Utils() {}

    public static void disableConnectionReuseIfNecessary() {
        if (android.os.Build.VERSION.SDK_INT < FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    public static void limitDnsCacheTtl() {
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "60");
        } catch (Exception e) {

            FileLogger.w(TAG, "Could not set networkaddress.cache.ttl", e);
        }
    }

    public static String formatMinAndroid(Context context, String minAndroidVersion) {
        String version = minAndroidVersion == null ? "" : minAndroidVersion;
        return context.getString(R.string.min_android_format, version);
    }

    public static String httpsToHttp(String url) {
        if (url != null && url.toLowerCase().startsWith("https://")) {
            return "http://" + url.substring(8);
        }
        return url;
    }

    public static boolean isVersionOlder(String installed, String site) {
        if (installed == null || site == null || installed.length() == 0 || site.length() == 0) {
            return false;
        }

        String[] v1 = installed.split("[\\._-]");
        String[] v2 = site.split("[\\._-]");

        int maxLen = Math.max(v1.length, v2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = 0;
            int num2 = 0;

            if (i < v1.length) {
                try {
                    num1 = Integer.parseInt(v1[i].replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    num1 = 0;
                }
            }
            if (i < v2.length) {
                try {
                    num2 = Integer.parseInt(v2[i].replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    num2 = 0;
                }
            }

            if (num1 < num2) return true;
            if (num1 > num2) return false;
        }
        return false;
    }

    public static void disableOverScrollIfSupported(View view) {
        try {
            Method m = View.class.getMethod("setOverScrollMode", int.class);
            Field overScrollNeverField = View.class.getField("OVER_SCROLL_NEVER");
            m.invoke(view, overScrollNeverField.getInt(null));
        } catch (Exception e) {
            FileLogger.w(TAG, "setOverScrollMode not available", e);
        }
    }

    private static final Method APPLY_METHOD;
    static {
        Method m;
        try {
            m = SharedPreferences.Editor.class.getMethod("apply");
        } catch (NoSuchMethodException e) {
            m = null;
        }
        APPLY_METHOD = m;
    }

    public static void savePrefs(SharedPreferences.Editor editor) {
        if (APPLY_METHOD != null) {
            try {
                APPLY_METHOD.invoke(editor);
                return;
            } catch (Exception e) {
                FileLogger.w(TAG, "SharedPreferences.apply() failed via reflection, falling back to commit()", e);
            }
        }
        editor.commit();
    }

    public static void forceShowOverflowMenu(Activity activity) {
        try {
            android.view.ViewConfiguration config = android.view.ViewConfiguration.get(activity);
            Field menuKeyField = android.view.ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
            if (menuKeyField != null) {
                menuKeyField.setAccessible(true);
                menuKeyField.setBoolean(config, false);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to force-show overflow menu", e);
        }
    }

    public static void setTextColorIfPresent(Activity activity, int id, int color) {
        View v = activity.findViewById(id);
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(color);
        }
    }

    public static boolean handleHomeMenuItem(Activity activity, MenuItem item) {
        if (item.getItemId() == HOME_ID) {
            activity.finish();
            return true;
        }
        return false;
    }

    public static void configureWebViewCompat(WebView webView) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        setDomStorageEnabledCompat(settings);
        setDatabaseEnabledCompat(webView);
        setAppCacheEnabledCompat(webView);
        disableZoom(webView);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                java.lang.reflect.Method m = WebSettings.class.getMethod("setMixedContentMode", int.class);
                java.lang.reflect.Field f = WebSettings.class.getField("MIXED_CONTENT_ALWAYS_ALLOW");
                m.invoke(settings, f.getInt(null));
            } catch (Exception e) {
                FileLogger.w(TAG, "Could not allow WebView mixed content", e);
            }
        }
    }

    private static void setDomStorageEnabledCompat(WebSettings settings) {
        try {
            Method m = WebSettings.class.getMethod("setDomStorageEnabled", boolean.class);
            m.invoke(settings, true);
        } catch (Exception e) {
            FileLogger.w(TAG, "DOM storage not available", e);
        }
    }

    private static void setDatabaseEnabledCompat(WebView webView) {
        try {
            WebSettings settings = webView.getSettings();
            Method setEnabled = WebSettings.class.getMethod("setDatabaseEnabled", boolean.class);
            Method setPath = WebSettings.class.getMethod("setDatabasePath", String.class);
            java.io.File dir = webView.getContext().getDir("webview_dbs", 0);
            setPath.invoke(settings, dir.getAbsolutePath());
            setEnabled.invoke(settings, true);
        } catch (Exception e) {
            FileLogger.w(TAG, "WebView database storage not available", e);
        }
    }

    private static void setAppCacheEnabledCompat(WebView webView) {
        try {
            WebSettings settings = webView.getSettings();
            Method setPath = WebSettings.class.getMethod("setAppCachePath", String.class);
            Method setMax = WebSettings.class.getMethod("setAppCacheMaxSize", long.class);
            Method setEnabled = WebSettings.class.getMethod("setAppCacheEnabled", boolean.class);
            java.io.File dir = new java.io.File(webView.getContext().getCacheDir(), "webview_cache");
            dir.mkdirs();
            setPath.invoke(settings, dir.getAbsolutePath());
            setMax.invoke(settings, 8L * 1024L * 1024L);
            setEnabled.invoke(settings, true);
        } catch (Exception e) {
            FileLogger.w(TAG, "WebView app cache not available", e);
        }
    }

    public static void disableZoom(WebView webView) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                Method m = WebSettings.class.getMethod("setDisplayZoomControls", boolean.class);
                m.invoke(settings, false);
            } catch (Exception e) {
                FileLogger.w(TAG, "Could not disable WebView display zoom controls", e);
            }
        }
    }

    private interface ActionBarUse {
        void run(Object actionBar) throws Exception;
    }

    private static void withActionBar(Activity activity, String failureLogMessage, ActionBarUse use) {
        if (android.os.Build.VERSION.SDK_INT < 11) return;
        try {
            Method getActionBarMethod = Activity.class.getMethod("getActionBar");
            Object actionBar = getActionBarMethod.invoke(activity);
            if (actionBar != null) {
                use.run(actionBar);
            }
        } catch (Exception e) {
            FileLogger.w(TAG, failureLogMessage, e);
        }
    }

    public static void enableActionBarUpButton(Activity activity) {
        withActionBar(activity, "Could not enable action bar Up button", new ActionBarUse() {
            public void run(Object actionBar) throws Exception {
                Method setHomeMethod = actionBar.getClass().getMethod("setDisplayHomeAsUpEnabled", boolean.class);
                setHomeMethod.invoke(actionBar, true);
            }
        });
        hideActionBarIcon(activity);
    }

    public static void hideActionBarIcon(Activity activity) {
        withActionBar(activity, "Could not hide action bar icon", new ActionBarUse() {
            public void run(Object actionBar) throws Exception {
                try {
                    Method setDisplayShowHome = actionBar.getClass()
                            .getMethod("setDisplayShowHomeEnabled", boolean.class);
                    setDisplayShowHome.invoke(actionBar, false);
                } catch (Exception e) {
                    FileLogger.w(TAG, "Could not disable action bar home icon", e);
                }
                try {
                    Method setIcon = actionBar.getClass().getMethod("setIcon", int.class);
                    setIcon.invoke(actionBar, android.R.color.transparent);
                } catch (Exception e) {
                    FileLogger.w(TAG, "Could not clear action bar icon", e);
                }
                try {
                    Method setLogo = actionBar.getClass().getMethod("setLogo", int.class);
                    setLogo.invoke(actionBar, android.R.color.transparent);
                } catch (Exception e) {
                    FileLogger.w(TAG, "Could not clear action bar logo", e);
                }
            }
        });
    }

    public static void invalidateOptionsMenuIfSupported(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                Method m = Activity.class.getMethod("invalidateOptionsMenu");
                m.invoke(activity);
            } catch (Exception e) {
                FileLogger.w(TAG, "Could not invalidate options menu", e);
            }
        }
    }

    public static void setShowAsActionIfSupported(MenuItem item, int flag) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                Method m = MenuItem.class.getMethod("setShowAsAction", int.class);
                m.invoke(item, flag);
            } catch (Exception e) {
                FileLogger.w(TAG, "Could not set showAsAction on menu item", e);
            }
        }
    }

    public static boolean launchUninstallIntent(Context context, String pkg) {
        final Uri packageUri = Uri.parse("package:" + pkg);
        final String[] actions = {
                "android.intent.action.UNINSTALL_PACKAGE",
                Intent.ACTION_DELETE
        };

        for (String action : actions) {
            try {
                Intent intent = new Intent(action, packageUri);
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(intent);
                    return true;
                }
            } catch (Exception e) {
                FileLogger.w(TAG, "Uninstall via " + action + " failed for " + pkg, e);
            }
        }
        return false;
    }

    private static volatile int rootState = -1;

    private static final String KEY_ROOT_STATE = "root_state";

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/su/bin/su"
    };

    private static volatile int suBinaryState = -1;

    public static boolean isSuBinaryPresent() {
        if (suBinaryState == -1) {
            boolean found = false;
            for (String path : SU_PATHS) {
                if (new File(path).exists()) {
                    found = true;
                    break;
                }
            }
            suBinaryState = found ? 1 : 0;
        }
        return suBinaryState == 1;
    }

    public static int getCachedRootState() {
        return rootState;
    }

    public static void checkRootInBackground(final Context context) {
        if (rootState != -1) return;
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (prefs.contains(KEY_ROOT_STATE)) {
                    rootState = prefs.getInt(KEY_ROOT_STATE, 0);
                    return;
                }
                if (!isSuBinaryPresent()) {

                    rootState = 0;
                    return;
                }
                boolean granted = probeRoot();
                rootState = granted ? 1 : 0;
                savePrefs(prefs.edit().putInt(KEY_ROOT_STATE, rootState));
            }
        }, "OddMarket-RootCheck").start();
    }

    public static boolean isRootAvailable() {
        if (rootState == -1) {
            rootState = isSuBinaryPresent() && probeRoot() ? 1 : 0;
        }
        return rootState == 1;
    }

    public static boolean requestRootAccessAndPersist(Context context) {
        boolean granted = probeRoot();
        rootState = granted ? 1 : 0;
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        savePrefs(prefs.edit().putInt(KEY_ROOT_STATE, rootState));
        return granted;
    }

    // Tests su access without prompting.
    private static boolean probeRoot() {
        return runAsRoot("id", false);
    }

    private static boolean runAsRoot(String command) {
        return runAsRoot(command, true);
    }

    private static boolean runAsRoot(String command, boolean logFailure) {
        Process process = null;
        java.io.DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new java.io.DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            return process.waitFor() == 0;
        } catch (Exception e) {
            if (logFailure) FileLogger.w(TAG, "Root command failed: " + command, e);
            return false;
        } finally {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {}
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {}
            }
        }
    }

    private static String shellQuote(String value) {
        if (value == null) value = "";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static boolean shareLogFile(Context context) {
        File logFile = FileLogger.getLogFile();
        if (context == null || logFile == null || !logFile.exists() || logFile.length() == 0) {
            return false;
        }
        try {
            Uri logUri;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                logUri = Uri.parse("content://com.oddmarket.provider/log");
            } else {
                logUri = Uri.fromFile(logFile);
            }

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "OddMarket log");
            sendIntent.putExtra(Intent.EXTRA_STREAM, logUri);
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            String label;
            try {
                label = context.getString(R.string.settings_send_log_label);
            } catch (Exception e) {
                label = "Send log";
            }

            Intent chooser = Intent.createChooser(sendIntent, label);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (Exception e) {
            FileLogger.w(TAG, "Failed to launch log share intent", e);
            return false;
        }
    }

    public static boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.length() == 0 || pkg.length() > 200) return false;
        return pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    public static boolean installApkAsRoot(String apkPath) {
        if (apkPath == null || apkPath.length() == 0) return false;
        return runAsRoot("pm install -r " + shellQuote(apkPath));
    }

    public static boolean uninstallPackageAsRoot(String pkg) {
        if (!isValidPackageName(pkg)) {
            FileLogger.w(TAG, "Refusing root uninstall, package name looks invalid: " + pkg);
            return false;
        }
        return runAsRoot("pm uninstall " + shellQuote(pkg));
    }

    private static final String PREFS_NAME = "prefs";
    private static final String KEY_LANGUAGE = "app_language";
    private static final String LANG_RU = "ru";
    private static final String LANG_EN = "en";

    public static String resolveAppLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_LANGUAGE)) {
            return prefs.getString(KEY_LANGUAGE, LANG_EN);
        }

        String deviceLanguage = Locale.getDefault().getLanguage();
        String initialLanguage = LANG_RU.equalsIgnoreCase(deviceLanguage) ? LANG_RU : LANG_EN;
        savePrefs(prefs.edit().putString(KEY_LANGUAGE, initialLanguage));
        return initialLanguage;
    }

    private static final AtomicInteger nextGeneratedId = new AtomicInteger(1);

    public static int generateViewId() {
        for (;;) {
            int result = nextGeneratedId.get();
            int next = result + 1;
            if (next > 0x00FFFFFF) next = 1;
            if (nextGeneratedId.compareAndSet(result, next)) {
                return result;
            }
        }
    }

    public static String downloadString(String urlStr, int timeoutMillis) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                return sb.toString();
            } finally {
                r.close();
            }
        } finally {

            conn.disconnect();
        }
    }

    public static String downloadString(String urlStr) throws Exception {
        return downloadString(urlStr, 5000);
    }

    // Downsampled bitmap decode to target size.
    public static Bitmap downloadAndDecodeBitmap(String urlStr, int maxDimensionPx) throws Exception {

        byte[] data = ImageDiskCache.fetch(urlStr, 5000).bytes;

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, boundsOpts);

        int sampleSize = 1;
        int largestSide = Math.max(boundsOpts.outWidth, boundsOpts.outHeight);
        while (largestSide / (sampleSize * 2) >= maxDimensionPx) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOpts);
    }

    public static Context resolveAppContext(Context existing, Context incoming) {
        if (existing != null) return existing;
        if (incoming == null) return null;
        return incoming.getApplicationContext();
    }

    public static Context applyLocale(Context base) {
        try {
            String language = resolveAppLanguage(base);
            Locale locale = LANG_RU.equals(language) ? new Locale(LANG_RU) : Locale.ENGLISH;
            Locale.setDefault(locale);

            Resources res = base.getResources();
            Configuration config = new Configuration(res.getConfiguration());

            if (android.os.Build.VERSION.SDK_INT >= 17) {
                try {
                    Method setLocaleMethod = Configuration.class.getMethod("setLocale", Locale.class);
                    setLocaleMethod.invoke(config, locale);

                    Method createConfigContextMethod = Context.class.getMethod("createConfigurationContext", Configuration.class);
                    Context wrapped = (Context) createConfigContextMethod.invoke(base, config);
                    if (wrapped != null) {
                        return wrapped;
                    }
                } catch (Exception e) {
                    FileLogger.w(TAG, "createConfigurationContext reflection failed, falling back to updateConfiguration()", e);
                }
            }

            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
            return base;
        } catch (Exception e) {
            FileLogger.w(TAG, "Failed to apply persisted locale to context", e);
            return base;
        }
    }

    public static final String APK_DOWNLOAD_SUBDIR = "apk_downloads";

    public static File internalApkDownloadDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), APK_DOWNLOAD_SUBDIR);
        dir.mkdirs();
        setWorldReadable(dir);
        setWorldExecutable(dir);
        return dir;
    }

    private static void setWorldPermission(File file, String methodName, String permissionLabel) {
        try {
            java.lang.reflect.Method m = File.class.getMethod(methodName, boolean.class, boolean.class);
            m.invoke(file, true, false);
        } catch (Exception e) {
            try {
                java.lang.reflect.Method m1 = File.class.getMethod(methodName, boolean.class);
                m1.invoke(file, true);
            } catch (Exception e2) {
                FileLogger.w(TAG, "Could not make file world-" + permissionLabel + " (pre-Gingerbread runtime?)", e2);
            }
        }
    }

    public static void setWorldReadable(File file) {
        setWorldPermission(file, "setReadable", "readable");
    }

    public static void setWorldExecutable(File file) {
        setWorldPermission(file, "setExecutable", "executable");
    }

    public static boolean isActuallyWritable(File dir) {
        if (dir == null) return false;
        dir.mkdirs();
        File probe = new File(dir, ".write_test_" + System.currentTimeMillis());
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(probe);
            out.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            probe.delete();
        }
    }

    // Shared Download or internal fallback.
    public static File resolveApkDownloadDir(Context context) {
        File externalDir = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
        externalDir.mkdirs();
        if (isActuallyWritable(externalDir)) {
            return externalDir;
        }
        FileLogger.w(TAG, "External Download folder is not actually writable, falling back to internal storage for APK downloads");
        return internalApkDownloadDir(context);
    }
}
