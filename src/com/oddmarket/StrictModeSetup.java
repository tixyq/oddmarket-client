package com.oddmarket;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.StrictMode;

/**
 * Measurement aid: reports disk/network access on the UI thread and leaked objects (logcat tag "StrictMode").
 * Active only in debuggable builds on API 9+; does nothing in release builds.
 * Kept in its own class so devices below API 9 never load StrictMode.
 */
final class StrictModeSetup {

    private StrictModeSetup() {
    }

    static void enableIfDebuggable(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 9) return;
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build());
        StrictMode.VmPolicy.Builder vm = new StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects();
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            // detectLeakedClosableObjects() does not exist before API 11 (NoSuchMethodError on 2.3.x).
            vm.detectLeakedClosableObjects();
        }
        StrictMode.setVmPolicy(vm.penaltyLog().build());
    }
}
