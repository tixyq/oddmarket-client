package com.oddmarket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UpdateCheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String action = intent != null ? intent.getAction() : null;
        FileLogger.init(app);

        final boolean rearm = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.MY_PACKAGE_REPLACED".equals(action);
        if (rearm) UpdateScheduler.schedule(app);

        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    UpdateChecker.check(app);
                } finally {
                    pending.finish();
                }
            }
        }, "update-check-bg").start();
    }
}
