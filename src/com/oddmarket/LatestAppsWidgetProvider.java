package com.oddmarket;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class LatestAppsWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(final Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
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
