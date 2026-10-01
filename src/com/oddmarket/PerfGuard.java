package com.oddmarket;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

/**
 * Picks the default for blur / animations by CPU class, and switches blur off by itself when the
 * measured FPS while scrolling is too low. Animations are never switched off automatically.
 *
 * Both only ever touch an option the user has never set: the moment the user flips the checkbox in
 * Settings ({@link #markBlurUserSet} / {@link #markAnimUserSet}) that option is theirs for good.
 */
final class PerfGuard {

    // Defaults by CPU class (used only while the user has not chosen the option themselves):
    // blur is heavy -> only fast multi-core phones; animations are cheap -> only very weak CPUs lose them.
    static final float BLUR_MIN_GHZ = 1.4f;
    static final int BLUR_MIN_CORES = 2;
    static final float ANIM_MIN_GHZ = 0.8f;

    private static float cpuGhz = -2f; // -2 = not read yet, -1 = unreadable

    private PerfGuard() {
    }

    /** Highest max frequency over all cores, in GHz; -1 if the system does not tell us. */
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

    /** Default for "blur" when the user has not set it. */
    static boolean blurDefault() {
        float g = cpuMaxGhz();
        if (g <= 0f) return android.os.Build.VERSION.SDK_INT >= 21; // unreadable: trust only new systems
        return g >= BLUR_MIN_GHZ && Runtime.getRuntime().availableProcessors() >= BLUR_MIN_CORES;
    }

    /** Default for "animations" when the user has not set it. */
    static boolean animDefault() {
        float g = cpuMaxGhz();
        if (g <= 0f) return android.os.Build.VERSION.SDK_INT >= 9;
        return g >= ANIM_MIN_GHZ;
    }

    static final String KEY_BLUR_USER = "blur_user_set";
    static final String KEY_ANIM_USER = "anim_user_set";
    private static final String KEY_BLUR = "blur_enabled";
    private static final String KEY_ANIM = "anim_enabled";

    // The only run-time rule: the current FPS while the content is moving.
    // FPS is averaged over FPS_WINDOW frames; two bad windows in a row trigger the switch-off.
    private static final int FPS_WINDOW = 20;
    private static final float BLUR_MIN_FPS = 25f;  // below this blur is switched off (animations are never touched)
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

    /** A screen has just appeared: its first frames (inflation, first layout) are not measured. */
    static void onScreenAttached() {
        warmUntilMs = android.os.SystemClock.uptimeMillis() + 1500L;
        lastFrameMs = 0L;
        resetWindow();
    }

    /**
     * Call once per frame that is drawn while the content is scrolling or moving.
     * @return true if blur has just been switched off (caller refreshes its state).
     */
    static boolean onMovingFrame(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        long gap = now - lastFrameMs;
        lastFrameMs = now;
        if (now < warmUntilMs || gap <= 0L || gap > 250L) {
            if (gap > 250L) resetWindow(); // movement stopped: start a fresh window next time
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
