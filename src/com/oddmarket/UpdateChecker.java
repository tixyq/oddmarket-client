package com.oddmarket;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateChecker {

    private static final long COOLDOWN_MS = 5L * 60 * 1000;

    private static final String PREFS = "oddmarket_updates";
    private static final String KEY_COUNT = "count";
    private static final int FLAG_IMMUTABLE_COMPAT = 0x02000000;
    private static final int REQ_OPEN = 4712;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile long lastOkElapsed = 0L;
    private static volatile boolean rerun = false;

    private UpdateChecker() {}

    static void checkAsync(Context c) {
        start(c, false);
    }

    static void checkNowAsync(Context c) {
        start(c, true);
    }

    private static void start(Context c, final boolean force) {
        final Context app = c.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() {
                check(app, force);
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    static void check(Context c) {
        check(c, false);
    }

    static void check(Context c, boolean force) {
        Context app = c.getApplicationContext();
        for (;;) {
            if (!running.compareAndSet(false, true)) {
                if (force) rerun = true;
                return;
            }
            try {
                do {
                    rerun = false;
                    runOnce(app, force);
                    force = true;
                } while (rerun);
            } finally {
                running.set(false);
            }
            if (!rerun) return;
        }
    }

    private static void runOnce(Context app, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastOkElapsed != 0L && now - lastOkElapsed < COOLDOWN_MS) return;
        try {
            FileLogger.init(app);
            InstalledCatalog.Snapshot snap = InstalledCatalog.sync(app);
            if (snap.failed > 0) {

                return;
            }
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < snap.hits.size(); i++) {
                InstalledCatalog.Entry e = snap.hits.get(i);
                String installed = InstalledCatalog.installedVersion(app, e.pkg);
                if (installed != null && Utils.isVersionOlder(installed, e.version)) names.add(e.name);
            }
            report(app, names);
            lastOkElapsed = now;
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "UpdateChecker: check failed", t);
        }
    }

    static void report(Context c, List<String> updatableNames) {
        Context app = c.getApplicationContext();
        int count = updatableNames.size();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int last = prefs.getInt(KEY_COUNT, 0);
        if (count == last) return;

        if (count == 0) {
            DownloadNotifier.cancelUpdates(app);
            Utils.savePrefs(prefs.edit().putInt(KEY_COUNT, 0));
            return;
        }
        if (!canNotify(app)) return;

        String title = app.getResources().getQuantityString(R.plurals.notif_updates_available, count, Integer.valueOf(count));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < updatableNames.size(); i++) {
            if (i > 0) text.append(", ");
            text.append(updatableNames.get(i));
        }

        Intent open = new Intent(app, ManageAppsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= FLAG_IMMUTABLE_COMPAT;
        PendingIntent pi = PendingIntent.getActivity(app, REQ_OPEN, open, flags);

        DownloadNotifier.showUpdates(app, title, text.toString(), pi);
        Utils.savePrefs(prefs.edit().putInt(KEY_COUNT, count));
    }

    private static boolean canNotify(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        try {
            Method check = Context.class.getMethod("checkSelfPermission", String.class);
            int r = (Integer) check.invoke(c, "android.permission.POST_NOTIFICATIONS");
            return r == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }
}
