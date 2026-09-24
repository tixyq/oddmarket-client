package com.oddmarket;
// Widget updater.

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

public class WidgetUpdateService extends Service {

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        FileLogger.init(getApplicationContext());
        new Thread(new Runnable() {
            public void run() {
                updateWidgetData();
                stopSelf();
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final int FLAG_IMMUTABLE_COMPAT = 0x02000000;

    private void updateWidgetData() {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(getApplicationContext());
        ComponentName thisWidget = new ComponentName(getApplicationContext(), LatestAppsWidgetProvider.class);
        int[] allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);

        if (allWidgetIds == null || allWidgetIds.length == 0) return;

        RemoteViews views = new RemoteViews(getPackageName(), R.layout.widget_latest_apps);
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
                    setupAppView(views, app1, R.id.widget_app1_container, R.id.widget_app1_icon, R.id.widget_app1_name, 1);
                }
                if (items.length() > 1) {
                    JSONObject app2 = items.getJSONObject(1);
                    setupAppView(views, app2, R.id.widget_app2_container, R.id.widget_app2_icon, R.id.widget_app2_name, 2);
                } else {
                    views.setViewVisibility(R.id.widget_app2_container, android.view.View.INVISIBLE);
                }
            } else {
                showError(views);
            }
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to update latest apps widget", e);
            showError(views);
        }

        for (int widgetId : allWidgetIds) {
            appWidgetManager.updateAppWidget(widgetId, views);
        }
    }

    private void applyWidgetTheme(RemoteViews views) {
        views.setInt(R.id.widget_root, "setBackgroundColor", Theme.windowBackground());
        views.setTextColor(R.id.widget_app1_name, Theme.textPrimary());
        views.setTextColor(R.id.widget_app2_name, Theme.textPrimary());
        views.setTextColor(R.id.widget_error_text, Theme.textSecondary());
    }

    private void showError(RemoteViews views) {
        views.setViewVisibility(R.id.widget_content_container, android.view.View.GONE);
        views.setViewVisibility(R.id.widget_error_container, android.view.View.VISIBLE);

        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE_COMPAT);
        views.setOnClickPendingIntent(R.id.widget_error_container, pi);
    }

    private void setupAppView(RemoteViews views, JSONObject appObj, int containerId, int iconId, int nameId, int requestCode) {
        String pkg = appObj.optString("pkg", "");
        String name = appObj.optString("name", getString(R.string.unknown));
        String iconUrl = appObj.optString("icon", "");

        views.setTextViewText(nameId, name);
        views.setViewVisibility(containerId, android.view.View.VISIBLE);

        Bitmap bmp = downloadBitmap(iconUrl);
        if (bmp != null) {
            views.setImageViewBitmap(iconId, bmp);
        } else {
            views.setImageViewResource(iconId, R.drawable.ic_pic);
        }

        boolean rusFix = MainActivity.isRussianUrlFixActive(getApplicationContext());
        String domain = rusFix ? "odd-m.narod.ws" : "odd-m.w0.am";
        String link = "http://" + domain + "/?" + pkg;

        Intent clickIntent = new Intent(getApplicationContext(), WebActivity.class);
        clickIntent.putExtra("url", link);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), requestCode, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE_COMPAT);
        views.setOnClickPendingIntent(containerId, pi);
    }

    private static final int WIDGET_ICON_MAX_PX = 160;

    private Bitmap downloadBitmap(String urlStr) {
        if (urlStr == null || urlStr.length() == 0) return null;
        try {
            String fixedUrl = MainActivity.fixUrl(getApplicationContext(), urlStr);
            return Utils.downloadAndDecodeBitmap(fixedUrl, WIDGET_ICON_MAX_PX);
        } catch (OutOfMemoryError oom) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
