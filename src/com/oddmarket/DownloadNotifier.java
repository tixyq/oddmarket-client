package com.oddmarket;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class DownloadNotifier {

    static final int ID_PROGRESS = 4711;
    static final int ID_RANGE = 100;
    static final int ID_QUEUED_BASE = 5000;
    static final int ID_DONE_BASE = 6000;
    private static final String CHANNEL_ID = "oddmarket_downloads";
    private static final int FLAG_IMMUTABLE_COMPAT = 0x02000000;

    private DownloadNotifier() {}

    private static NotificationManager notificationManager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static PendingIntent openPage(Context c, Bundle page, int requestCode) {
        Intent intent;
        if (page != null) {
            intent = new Intent(c, DetailsActivity.class);
            intent.putExtras(page);
        } else {
            intent = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (intent == null) intent = new Intent();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= FLAG_IMMUTABLE_COMPAT;
        return PendingIntent.getActivity(c, requestCode, intent, flags);
    }

    private static boolean channelCreated = false;

    private static void ensureChannel(Context c) {
        if (channelCreated || android.os.Build.VERSION.SDK_INT < 26) return;
        channelCreated = true;
        try {
            Class<?> chCls = Class.forName("android.app.NotificationChannel");
            Constructor<?> ctor = chCls.getConstructor(String.class, CharSequence.class, int.class);
            Object channel = ctor.newInstance(CHANNEL_ID, c.getString(R.string.notif_channel_downloads), 2 );
            Method create = NotificationManager.class.getMethod("createNotificationChannel", chCls);
            create.invoke(notificationManager(c), channel);
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "createNotificationChannel failed", t);
        }
    }

    private static final int NO_PROGRESS = -2;

    private static final int ICON_PROGRESS = android.R.drawable.stat_sys_download;
    private static final int ICON_DONE = android.R.drawable.stat_sys_download_done;
    private static final int ICON_ERROR = android.R.drawable.stat_notify_error;

    static Notification buildProgress(Context c, DownloadCenter.Item item) {
        return buildProgress(c, item, DownloadCenter.getProgress(), DownloadCenter.isIndeterminate());
    }

    static Notification buildProgress(Context c, DownloadCenter.Item item, int progress, boolean indeterminate) {
        int textRes = DownloadCenter.isStarted() ? R.string.notif_downloading : R.string.notif_initializing;
        return buildWithProgress(c, item.name, c.getString(textRes),
                ICON_PROGRESS, true, item.when, null,
                openPage(c, item.page, ID_PROGRESS), indeterminate ? -1 : progress);
    }

    static void updateProgress(Context c, DownloadCenter.Item item, int progress, boolean indeterminate) {
        postNotification(c, ID_PROGRESS, buildProgress(c, item, progress, indeterminate));
    }

    static void showProgress(Context c, DownloadCenter.Item item) {
        postNotification(c, ID_PROGRESS, buildProgress(c, item));
    }

    static void showQueued(Context c, DownloadCenter.Item item) {
        Notification n = build(c, item.name, c.getString(R.string.notif_waiting),
                ICON_PROGRESS, false, item.when, null,
                openPage(c, item.page, item.notifId));
        postNotification(c, item.notifId, n);
    }

    static void showDone(Context c, DownloadCenter.Result r, long when) {
        String title;
        String text;
        int icon;
        android.graphics.Bitmap largeIcon = null;
        if (r.error == null) {
            title = r.name;
            text = c.getString(R.string.notif_download_done);

            icon = ICON_DONE;
        } else {
            title = r.name;
            text = c.getString(R.string.notif_download_error_format, r.error);
            icon = ICON_ERROR;
        }
        Notification n = build(c, title, text, icon, false, when, largeIcon, openPage(c, r.page, r.notifId));
        if (n == null) return;
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        postNotification(c, r.notifId, n);
    }

    static void showError(Context c, String title, String text, Bundle page, int notifId) {
        Notification n = build(c, title, text, ICON_ERROR, false,
                System.currentTimeMillis(), null, openPage(c, page, notifId));
        if (n == null) return;
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        postNotification(c, notifId, n);
    }

    static void cancelDone(Context c, DownloadCenter.Result r) {
        cancelNotification(c, r.notifId);
    }

    static void cancelQueued(Context c, DownloadCenter.Item item) {
        cancelNotification(c, item.notifId);
    }

    private static boolean staleCleaned = false;

    static void cancelStaleQueued(Context c) {
        if (staleCleaned) return;
        staleCleaned = true;
        for (int i = 0; i < ID_RANGE; i++) cancelNotification(c, ID_QUEUED_BASE + i);
    }

    static Notification buildIdle(Context c) {
        return build(c, c.getString(R.string.app_name), null, ICON_PROGRESS,
                true, System.currentTimeMillis(), null, openPage(c, null, ID_PROGRESS));
    }

    static void cancelProgress(Context c) {
        cancelNotification(c, ID_PROGRESS);
    }

    private static void postNotification(Context c, int id, Notification n) {
        if (n == null) return;
        try {
            notificationManager(c).notify(id, n);
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "notify failed", t);
        }
    }

    private static void cancelNotification(Context c, int id) {
        try {
            notificationManager(c).cancel(id);
        } catch (Throwable ignored) {}
    }

    private static Notification build(Context c, String title, String text, int icon,
                                      boolean ongoing, long when, android.graphics.Bitmap largeIcon,
                                      PendingIntent open) {
        return buildWithProgress(c, title, text, icon, ongoing, when, largeIcon, open, NO_PROGRESS);
    }

    private static Notification buildWithProgress(Context c, String title, String text, int icon,
                                      boolean ongoing, long when, android.graphics.Bitmap largeIcon,
                                      PendingIntent open, int progress) {
        try {

            if (progress != NO_PROGRESS && android.os.Build.VERSION.SDK_INT < 14) {
                return buildLegacyProgress(c, title, text, icon, when, open, progress);
            }
            if (android.os.Build.VERSION.SDK_INT >= 11) {
                ensureChannel(c);
                Class<?> bCls = Class.forName("android.app.Notification$Builder");
                Object b;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    b = bCls.getConstructor(Context.class, String.class).newInstance(c, CHANNEL_ID);
                } else {
                    b = bCls.getConstructor(Context.class).newInstance(c);
                }
                bCls.getMethod("setSmallIcon", int.class).invoke(b, icon);
                if (largeIcon != null) {
                    bCls.getMethod("setLargeIcon", android.graphics.Bitmap.class).invoke(b, largeIcon);
                }
                bCls.getMethod("setContentTitle", CharSequence.class).invoke(b, title);
                if (text != null) bCls.getMethod("setContentText", CharSequence.class).invoke(b, text);
                bCls.getMethod("setContentIntent", PendingIntent.class).invoke(b, open);
                bCls.getMethod("setOngoing", boolean.class).invoke(b, ongoing);
                bCls.getMethod("setWhen", long.class).invoke(b, when);
                if (progress != NO_PROGRESS) {
                    boolean indet = progress < 0;
                    bCls.getMethod("setProgress", int.class, int.class, boolean.class)
                            .invoke(b, 100, indet ? 0 : progress, indet);
                }
                String buildName = android.os.Build.VERSION.SDK_INT >= 16 ? "build" : "getNotification";
                return (Notification) bCls.getMethod(buildName).invoke(b);
            }

            Constructor<Notification> ctor = Notification.class.getConstructor(int.class, CharSequence.class, long.class);
            Notification n = ctor.newInstance(icon, title, when);
            Method set = Notification.class.getMethod("setLatestEventInfo",
                    Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
            set.invoke(n, c, title, text == null ? "" : text, open);
            if (ongoing) n.flags |= Notification.FLAG_ONGOING_EVENT;
            return n;
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "build notification failed", t);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Notification buildLegacyProgress(Context c, String title, String text, int icon, long when,
                                                    PendingIntent open, int progress) throws Exception {
        Constructor<Notification> ctor = Notification.class.getConstructor(int.class, CharSequence.class, long.class);
        Notification n = ctor.newInstance(icon, title, when);
        boolean indet = progress < 0;
        android.widget.RemoteViews rv = new android.widget.RemoteViews(c.getPackageName(), R.layout.notif_progress);
        rv.setImageViewResource(R.id.notif_icon, icon);
        rv.setTextViewText(R.id.notif_title, title);
        rv.setTextViewText(R.id.notif_text, text);
        rv.setProgressBar(R.id.notif_bar, 100, indet ? 0 : progress, indet);
        n.contentView = rv;
        n.contentIntent = open;
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        return n;
    }
}
