package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
    public static final int SHOW_AS_ACTION_NEVER = 0;

    private static final int FROYO = 8;

    private Utils() {}

    public static void disableConnectionReuse() {
        if (android.os.Build.VERSION.SDK_INT < FROYO) {
            System.setProperty("http.keepAlive", "false");
        }
    }

    private static final java.util.concurrent.ThreadPoolExecutor BG =
            new java.util.concurrent.ThreadPoolExecutor(3, 3, 20L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "OddMarket-bg");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    static {
        if (android.os.Build.VERSION.SDK_INT >= 9) {
            BG.allowCoreThreadTimeOut(true);
        }
    }

    /** Shared bounded pool for short background work (replaces ad-hoc threads: less stack memory, no thread bursts). */
    public static void runBackground(final Runnable task) {
        try {
            BG.execute(new Runnable() {
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** "%.1f" in Locale.US without String.format (which is slow and allocation-heavy on old Dalvik). */
    public static String formatOneDecimal(double value) {
        long tenths = Math.round(value * 10d);
        long abs = tenths < 0 ? -tenths : tenths;
        StringBuilder sb = new StringBuilder(8);
        if (tenths < 0) sb.append('-');
        sb.append(abs / 10).append('.').append(abs % 10);
        return sb.toString();
    }

    public static void limitDnsCacheTtl() {
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "60");
        } catch (Exception e) {

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

        String[] installedParts = installed.split("[\\._-]");
        String[] siteParts = site.split("[\\._-]");

        int maxLen = Math.max(installedParts.length, siteParts.length);
        for (int i = 0; i < maxLen; i++) {
            int installedPart = 0;
            int sitePart = 0;

            if (i < installedParts.length) {
                try {
                    installedPart = Integer.parseInt(installedParts[i].replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    installedPart = 0;
                }
            }
            if (i < siteParts.length) {
                try {
                    sitePart = Integer.parseInt(siteParts[i].replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    sitePart = 0;
                }
            }

            if (installedPart < sitePart) return true;
            if (installedPart > sitePart) return false;
        }
        return false;
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

    public static void postFrame(View v, Runnable r) {
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            v.postOnAnimation(r);
        } else {
            v.postDelayed(r, 16L);
        }
    }

    public static boolean isBlurEnabled(Context context) {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("blur_enabled", PerfGuard.blurDefault());
    }

    public static boolean isAnimEnabled(Context context) {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getBoolean("anim_enabled", PerfGuard.animDefault());
    }

    public static void savePrefs(SharedPreferences.Editor editor) {
        if (APPLY_METHOD != null) {
            try {
                APPLY_METHOD.invoke(editor);
                return;
            } catch (Exception e) {
            }
        }
        editor.commit();
    }

    public static void setTextColorById(Activity activity, int id, int color) {
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

    public static void configureWebView(WebView webView) {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        enableDomStorage(settings);
        enableDatabase(webView);
        enableAppCache(webView);
        disableZoom(webView);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                java.lang.reflect.Method m = WebSettings.class.getMethod("setMixedContentMode", int.class);
                java.lang.reflect.Field f = WebSettings.class.getField("MIXED_CONTENT_ALWAYS_ALLOW");
                m.invoke(settings, f.getInt(null));
            } catch (Exception e) {
            }
        }
    }

    private static void enableDomStorage(WebSettings settings) {
        try {
            Method m = WebSettings.class.getMethod("setDomStorageEnabled", boolean.class);
            m.invoke(settings, true);
        } catch (Exception e) {
        }
    }

    private static void enableDatabase(WebView webView) {
        try {
            WebSettings settings = webView.getSettings();
            Method setEnabled = WebSettings.class.getMethod("setDatabaseEnabled", boolean.class);
            Method setPath = WebSettings.class.getMethod("setDatabasePath", String.class);
            java.io.File dir = webView.getContext().getDir("webview_dbs", 0);
            setPath.invoke(settings, dir.getAbsolutePath());
            setEnabled.invoke(settings, true);
        } catch (Exception e) {
        }
    }

    private static void enableAppCache(WebView webView) {
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
            }
        }
    }

    public static void refreshOptionsMenu(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                Method m = Activity.class.getMethod("invalidateOptionsMenu");
                m.invoke(activity);
            } catch (Exception e) {
            }
        }
    }

    public static void showInActionBar(MenuItem item, int flag) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            try {
                Method m = MenuItem.class.getMethod("setShowAsAction", int.class);
                m.invoke(item, flag);
            } catch (Exception e) {
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
                StringBuilder sb = new StringBuilder(4096);
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
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

    public static Bitmap downloadAndDecodeBitmap(String urlStr, int maxDimensionPx) throws Exception {
        return downloadAndDecodeBitmap(urlStr, maxDimensionPx, false);
    }

    /**
     * @param opaque true for images without transparency (screenshots, banners): decoded as RGB_565,
     *               i.e. 2 bytes/pixel instead of 4. Icons keep ARGB_8888 to preserve alpha.
     */
    public static Bitmap downloadAndDecodeBitmap(String urlStr, int maxDimensionPx, boolean opaque) throws Exception {

        File file = ImageDiskCache.fetchFile(urlStr, 5000);
        if (file != null) return decodeSampledFile(file, maxDimensionPx, opaque);

        byte[] data = ImageDiskCache.fetch(urlStr, 5000).bytes;

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, boundsOpts);
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null;

        BitmapFactory.Options decodeOpts = bitmapOptions(
                sampleSizeFor(boundsOpts.outWidth, boundsOpts.outHeight, maxDimensionPx), opaque);
        return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOpts);
    }

    private static Bitmap decodeSampledFile(File file, int maxDimensionPx, boolean opaque) throws Exception {
        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        java.io.FileInputStream probe = new java.io.FileInputStream(file);
        try {
            BitmapFactory.decodeFileDescriptor(probe.getFD(), null, boundsOpts);
        } finally {
            probe.close();
        }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {

            file.delete();
            return null;
        }

        BitmapFactory.Options decodeOpts = bitmapOptions(
                sampleSizeFor(boundsOpts.outWidth, boundsOpts.outHeight, maxDimensionPx), opaque);
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            return BitmapFactory.decodeFileDescriptor(in.getFD(), null, decodeOpts);
        } finally {
            in.close();
        }
    }

    private static int sampleSizeFor(int width, int height, int maxDimensionPx) {
        int sampleSize = 1;
        int largestSide = Math.max(width, height);
        int limit = Math.max(1, maxDimensionPx);
        while (largestSide / (sampleSize * 2) >= limit) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static BitmapFactory.Options bitmapOptions(int sampleSize, boolean opaque) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize;
        if (opaque) {
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inDither = true;
        }
        return o;
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
                }
            }

            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
            return base;
        } catch (Exception e) {
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
            }
        }
    }

    public static void setWorldReadable(File file) {
        setWorldPermission(file, "setReadable", "readable");
    }

    public static void setWorldExecutable(File file) {
        setWorldPermission(file, "setExecutable", "executable");
    }

    public static boolean canWriteTo(File dir) {
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

    public static File resolveApkDownloadDir(Context context) {
        File externalDir = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
        externalDir.mkdirs();
        if (canWriteTo(externalDir)) {
            return externalDir;
        }
        return internalApkDownloadDir(context);
    }
}
