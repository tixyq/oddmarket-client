package com.oddmarket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UpdateCheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String action = intent != null ? intent.getAction() : null;

        final boolean rearm = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.MY_PACKAGE_REPLACED".equals(action);
        if (rearm) UpdateScheduler.schedule(app);

        // goAsync() exists since API 11; older systems just run the work on the shared background pool.
        final PendingResult pending = android.os.Build.VERSION.SDK_INT >= 11 ? goAsync() : null;
        Utils.runBackground(new Runnable() {
            public void run() {
                try {
                    UpdateChecker.check(app);
                } finally {
                    if (pending != null) pending.finish();
                }
            }
        });
    }
}
