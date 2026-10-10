package com.oddmarket;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

public class LatestAppsWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_TICK = "com.oddmarket.WIDGET_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_TICK.equals(intent.getAction())) {
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(final Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        refresh(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetUpdateWorker.cancelSchedule(context.getApplicationContext());
        super.onDisabled(context);
    }

    private void refresh(final Context context) {
        FileLogger.init(context);

        final android.content.BroadcastReceiver.PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    WidgetUpdateWorker.updateAllWidgets(context.getApplicationContext());
                } finally {
                    pendingResult.finish();
                }
            }
        }).start();
    }
}
