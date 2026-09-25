package com.oddmarket;
// Widget entry point.

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

public class LatestAppsWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        FileLogger.init(context);
        Intent intent = new Intent(context, WidgetUpdateService.class);
        context.startService(intent);
    }
}
