package com.oddmarket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

final class UpdateScheduler {

    static final String ACTION_CHECK = "com.oddmarket.UPDATE_CHECK";

    static final long INTERVAL_MS = AlarmManager.INTERVAL_HALF_HOUR;

    private static final int REQ = 4713;
    private static final int FLAG_IMMUTABLE_COMPAT = 0x02000000;

    private UpdateScheduler() {}

    private static Intent intent(Context c) {
        Intent i = new Intent(c, UpdateCheckReceiver.class);
        i.setAction(ACTION_CHECK);
        return i;
    }

    static void ensureScheduled(Context c) {
        try {
            Context app = c.getApplicationContext();
            int noCreate = PendingIntent.FLAG_NO_CREATE;
            if (android.os.Build.VERSION.SDK_INT >= 23) noCreate |= FLAG_IMMUTABLE_COMPAT;
            if (PendingIntent.getBroadcast(app, REQ, intent(app), noCreate) != null) return;
            schedule(app);
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "UpdateScheduler: ensureScheduled failed", t);
        }
    }

    static void schedule(Context c) {
        try {
            Context app = c.getApplicationContext();
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) flags |= FLAG_IMMUTABLE_COMPAT;
            PendingIntent pi = PendingIntent.getBroadcast(app, REQ, intent(app), flags);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, pi);
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "UpdateScheduler: schedule failed", t);
        }
    }
}
