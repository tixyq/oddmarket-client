package com.oddmarket;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

final class PerfGuard {

    static final float BLUR_MIN_GHZ = 1.4f;
    static final int BLUR_MIN_CORES = 2;
    static final float ANIM_MIN_GHZ = 0.8f;

    private static float cpuGhz = -2f;

    private PerfGuard() {
    }

    static float cpuMaxGhz() {
        if (cpuGhz != -2f) return cpuGhz;
        long maxKhz = 0L;
        int n = Math.max(1, Runtime.getRuntime().availableProcessors());
        for (int i = 0; i < Math.max(n, 8); i++) {
            long khz = readKhz("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (khz <= 0L) khz = readKhz("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq");
            if (khz > maxKhz) maxKhz = khz;
        }
        cpuGhz = maxKhz > 0L ? maxKhz / 1000000f : -1f;
        FileLogger.i(Utils.TAG, "PerfGuard: cpu max " + cpuGhz + " GHz, cores " + n);
        return cpuGhz;
    }

    private static long readKhz(String path) {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(path), 64);
            String line = r.readLine();
            return line == null ? 0L : Long.parseLong(line.trim());
        } catch (Exception e) {
            return 0L;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static boolean blurDefault() {
        float g = cpuMaxGhz();
        if (g <= 0f) return android.os.Build.VERSION.SDK_INT >= 21;
        return g >= BLUR_MIN_GHZ && Runtime.getRuntime().availableProcessors() >= BLUR_MIN_CORES;
    }

    static boolean animDefault() {
        float g = cpuMaxGhz();
        if (g <= 0f) return android.os.Build.VERSION.SDK_INT >= 9;
        return g >= ANIM_MIN_GHZ;
    }

    static final String KEY_BLUR_USER = "blur_user_set";
    static final String KEY_ANIM_USER = "anim_user_set";
    private static final String KEY_BLUR = "blur_enabled";
    private static final String KEY_ANIM = "anim_enabled";

    private static final int FPS_WINDOW = 20;
    private static final float BLUR_MIN_FPS = 25f;
    private static final int BAD_WINDOWS = 2;

    private static long lastFrameMs = 0L;
    private static long warmUntilMs = 0L;
    private static int winCount = 0;
    private static long winSum = 0L;
    private static int badWindows = 0;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static boolean blurUserSet(Context c) {
        return prefs(c).getBoolean(KEY_BLUR_USER, false);
    }

    static boolean animUserSet(Context c) {
        return prefs(c).getBoolean(KEY_ANIM_USER, false);
    }

    static void markBlurUserSet(Context c) {
        Utils.savePrefs(prefs(c).edit().putBoolean(KEY_BLUR_USER, true));
        resetWindow();
    }

    static void markAnimUserSet(Context c) {
        Utils.savePrefs(prefs(c).edit().putBoolean(KEY_ANIM_USER, true));
        resetWindow();
    }

    private static void resetWindow() {
        winCount = 0;
        winSum = 0L;
        badWindows = 0;
    }

    static void onScreenAttached() {
        warmUntilMs = android.os.SystemClock.uptimeMillis() + 1500L;
        lastFrameMs = 0L;
        resetWindow();
    }

    static boolean onMovingFrame(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        long gap = now - lastFrameMs;
        lastFrameMs = now;
        if (now < warmUntilMs || gap <= 0L || gap > 250L) {
            if (gap > 250L) resetWindow();
            return false;
        }
        winCount++;
        winSum += gap;
        if (winCount < FPS_WINDOW) return false;
        float fps = winCount * 1000f / winSum;
        winCount = 0;
        winSum = 0L;

        boolean blurAuto = Utils.isBlurEnabled(c) && !blurUserSet(c);
        if (!blurAuto || fps >= BLUR_MIN_FPS) {
            badWindows = 0;
            return false;
        }
        if (++badWindows < BAD_WINDOWS) return false;
        badWindows = 0;
        FileLogger.i(Utils.TAG, "PerfGuard: " + fps + " fps while moving");
        return disable(c, KEY_BLUR, R.string.perf_blur_off);
    }

    private static boolean disable(Context c, String key, int toastRes) {
        Utils.savePrefs(prefs(c).edit().putBoolean(key, false));
        FileLogger.i(Utils.TAG, "PerfGuard: " + key + " switched off automatically");
        try {
            Toast.makeText(c.getApplicationContext(), toastRes, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "PerfGuard: toast failed", e);
        }
        return true;
    }
}
