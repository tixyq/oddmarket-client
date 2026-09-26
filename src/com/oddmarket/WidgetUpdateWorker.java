package com.oddmarket;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

public class WidgetUpdateWorker {

    private static final int FLAG_IMMUTABLE_COMPAT = 0x02000000;
    private static final int WIDGET_ICON_MAX_PX = 160;

    public static void updateAllWidgets(Context appContext) {
        FileLogger.init(appContext);
        try {
            updateWidgetData(appContext);
        } catch (Exception e) {

            FileLogger.w(Utils.TAG, "WidgetUpdateWorker: unexpected failure", e);
        }
    }

    // Loads top apps into all widget instances.
    private static void updateWidgetData(Context appContext) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(appContext);
        ComponentName thisWidget = new ComponentName(appContext, LatestAppsWidgetProvider.class);
        int[] allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);

        if (allWidgetIds == null || allWidgetIds.length == 0) return;

        RemoteViews views = new RemoteViews(appContext.getPackageName(), R.layout.widget_latest_apps);
        applyWidgetTheme(views);

        try {
            String jsonStr = Utils.downloadString("http://odd.txy-50b.workers.dev/?page=1&tab=all", 8000);
            JSONObject response = new JSONObject(jsonStr);
            JSONArray items = response.optJSONArray("items");

            if (items != null && items.length() > 0) {
                views.setViewVisibility(R.id.widget_content_container, android.view.View.VISIBLE);
                views.setViewVisibility(R.id.widget_error_container, android.view.View.GONE);

                if (items.length() > 0) {
                    JSONObject app1 = items.getJSONObject(0);
                    setupAppView(appContext, views, app1, R.id.widget_app1_container, R.id.widget_app1_icon, R.id.widget_app1_name, 1);
                }
                if (items.length() > 1) {
                    JSONObject app2 = items.getJSONObject(1);
                    setupAppView(appContext, views, app2, R.id.widget_app2_container, R.id.widget_app2_icon, R.id.widget_app2_name, 2);
                } else {
                    views.setViewVisibility(R.id.widget_app2_container, android.view.View.INVISIBLE);
                }
            } else {
                showError(appContext, views);
            }
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to update latest apps widget", e);
            showError(appContext, views);
        }

        for (int widgetId : allWidgetIds) {
            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }

    private static void applyWidgetTheme(RemoteViews views) {
        views.setInt(R.id.widget_root, "setBackgroundColor", Theme.windowBackground());
        views.setTextColor(R.id.widget_app1_name, Theme.textPrimary());
        views.setTextColor(R.id.widget_app2_name, Theme.textPrimary());
        views.setTextColor(R.id.widget_error_text, Theme.textSecondary());
    }

    private static void showError(Context appContext, RemoteViews views) {
        views.setViewVisibility(R.id.widget_content_container, android.view.View.GONE);
        views.setViewVisibility(R.id.widget_error_container, android.view.View.VISIBLE);

        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE_COMPAT);
        views.setOnClickPendingIntent(R.id.widget_error_container, pi);
    }

    private static void setupAppView(Context appContext, RemoteViews views, JSONObject appObj, int containerId, int iconId, int nameId, int requestCode) {
        String pkg = appObj.optString("pkg", "");
        String name = appObj.optString("name", appContext.getString(R.string.unknown));
        String iconUrl = appObj.optString("icon", "");

        views.setTextViewText(nameId, name);
        views.setViewVisibility(containerId, android.view.View.VISIBLE);

        Bitmap bmp = downloadBitmap(appContext, iconUrl);
        if (bmp != null) {
            views.setImageViewBitmap(iconId, bmp);
        } else {
            views.setImageViewResource(iconId, R.drawable.ic_pic);
        }

        boolean rusFix = MainActivity.isRussianUrlFixActive(appContext);
        String domain = rusFix ? "odd-m.narod.ws" : "odd-m.w0.am";
        String link = "http://" + domain + "/?" + pkg;

        Intent clickIntent = new Intent(appContext, WebActivity.class);
        clickIntent.putExtra("url", link);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(appContext, requestCode, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE_COMPAT);
        views.setOnClickPendingIntent(containerId, pi);
    }

    private static Bitmap downloadBitmap(Context appContext, String urlStr) {
        if (urlStr == null || urlStr.length() == 0) return null;
        try {
            String fixedUrl = MainActivity.fixUrl(appContext, urlStr);
            return Utils.downloadAndDecodeBitmap(fixedUrl, WIDGET_ICON_MAX_PX);
        } catch (OutOfMemoryError oom) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
