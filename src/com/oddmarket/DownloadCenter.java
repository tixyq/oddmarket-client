package com.oddmarket;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class DownloadCenter {

    public interface Listener {

        void onDownloadChanged(boolean progressOnly);
    }

    static final class Item {
        final int seq;
        final String url;
        final String name;
        final String pkg;
        final boolean wasUpdate;
        final Bundle page;
        final int notifId;
        volatile int gen;
        long when;

        Item(int seq, String url, String name, String pkg, boolean wasUpdate, Bundle page) {
            this.seq = seq;
            this.url = url;
            this.name = name == null ? "" : name;
            this.pkg = pkg == null ? "" : pkg;
            this.wasUpdate = wasUpdate;
            this.page = page;
            this.notifId = DownloadNotifier.ID_QUEUED_BASE + (seq % DownloadNotifier.ID_RANGE);
        }

        boolean is(String p, String n) {
            if (pkg.length() > 0 && p != null && p.length() > 0) return pkg.equals(p);
            return name.equals(n == null ? "" : n);
        }
    }

    public static final class Result {
        public final File file;
        public final String error;
        public final boolean wasUpdate;
        public final String name;
        final Bundle page;
        final int notifId;
        Result(File file, String error, boolean wasUpdate, String name, Bundle page, int seq) {
            this.file = file;
            this.error = error;
            this.wasUpdate = wasUpdate;
            this.name = name;
            this.page = page;
            this.notifId = DownloadNotifier.ID_DONE_BASE + (seq % DownloadNotifier.ID_RANGE);
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();

    static volatile int generation = 0;

    static volatile Item serviceItem = null;

    private static Item current = null;
    private static final List<Item> queue = new ArrayList<Item>();
    private static final List<Result> pending = new ArrayList<Result>();
    private static int progress = 0;
    private static boolean indeterminate = true;
    private static int seqCounter = 0;
    private static long lastWhen = 0L;
    private static Context appCtx = null;

    private DownloadCenter() {}

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static boolean isActive() { return current != null; }
    public static int getProgress() { return progress; }
    public static boolean isIndeterminate() { return indeterminate; }
    public static boolean hasPendingResult() { return !pending.isEmpty(); }

    public static boolean isTracked(String pkg, String name) {
        if (current != null && current.is(pkg, name)) return true;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).is(pkg, name)) return true;
        }
        return false;
    }

    public static Result takeResult() {
        return pending.isEmpty() ? null : pending.remove(0);
    }

    private static void fire() {
        fire(false);
    }

    private static void fire(boolean progressOnly) {
        if (LISTENERS.isEmpty()) return;
        List<Listener> copy = new ArrayList<Listener>(LISTENERS);
        for (int i = 0; i < copy.size(); i++) {
            try { copy.get(i).onDownloadChanged(progressOnly); } catch (Throwable t) {
                FileLogger.w(Utils.TAG, "DownloadCenter listener failed", t);
            }
        }
    }

    private static long nextWhen() {
        long now = System.currentTimeMillis();
        lastWhen = Math.max(now, lastWhen + 1000L);
        return lastWhen;
    }

    public static boolean begin(Context context, String url, String name, String pkg,
                                boolean wasUpdate, Bundle pageExtras) {
        if (isTracked(pkg, name)) return false;
        Context app = context.getApplicationContext();
        appCtx = app;

        Item item = new Item(++seqCounter, url, name, pkg, wasUpdate, pageExtras);
        if (current == null) {
            DownloadNotifier.cancelStaleQueued(app);
            if (!startItem(app, item)) return false;
        } else {
            queue.add(item);
            repostQueue(app);
        }
        fire();
        return true;
    }

    public static void cancel(Context context, String pkg, String name) {
        Context app = context.getApplicationContext();

        if (current != null && current.is(pkg, name)) {
            generation++;
            DownloadService.cancelRunning();
            current = null;
            serviceItem = null;
            advance(app);
            fire();
            return;
        }
        for (int i = 0; i < queue.size(); i++) {
            Item it = queue.get(i);
            if (it.is(pkg, name)) {
                queue.remove(i);
                DownloadNotifier.cancelQueued(app, it);
                repostQueue(app);
                fire();
                return;
            }
        }
    }

    private static boolean startItem(Context app, Item item) {
        current = item;
        progress = 0;
        indeterminate = true;
        item.gen = ++generation;
        item.when = nextWhen();
        serviceItem = item;

        Intent intent = new Intent(app, DownloadService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(app, intent);
            } else {
                app.startService(intent);
            }
        } catch (Throwable t) {
            FileLogger.e(Utils.TAG, "Cannot start DownloadService", t);
            current = null;
            serviceItem = null;
            return false;
        }
        return true;
    }

    private static void advance(Context app) {
        while (!queue.isEmpty()) {
            Item next = queue.remove(0);
            DownloadNotifier.cancelQueued(app, next);
            if (startItem(app, next)) {
                repostQueue(app);
                return;
            }
        }

        DownloadService.releaseWakeLock();
        DownloadNotifier.cancelProgress(app);
        try {
            app.stopService(new Intent(app, DownloadService.class));
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "stopService failed", t);
        }
    }

    private static void repostQueue(Context app) {
        long base = current != null ? current.when : nextWhen();
        for (int i = 0; i < queue.size(); i++) {
            Item it = queue.get(i);
            it.when = base - (i + 1) * 10L;
            DownloadNotifier.showQueued(app, it);
        }
    }

    static void postProgress(final int gen, final int value, final boolean isIndeterminate) {
        MAIN.post(new Runnable() {
            public void run() {
                if (gen != generation || current == null) return;
                if (progress == value && indeterminate == isIndeterminate) return;
                progress = value;
                indeterminate = isIndeterminate;
                fire(true);
            }
        });
    }

    static void postFinished(final int gen, final Context context, final File file, final String error) {
        final Context app = context.getApplicationContext();
        MAIN.post(new Runnable() {
            public void run() {
                if (gen != generation || current == null) {

                    if (file != null) file.delete();
                    return;
                }
                Item done = current;
                current = null;
                serviceItem = null;

                Result r = new Result(file, error, done.wasUpdate, done.name, done.page, done.seq);
                pending.add(r);
                fire();
                if (pending.contains(r)) {
                    DownloadNotifier.showDone(app, r, nextWhen());
                }
                advance(app);
                fire();
            }
        });
    }

    static void postCancelled(final int gen) {
        MAIN.post(new Runnable() {
            public void run() {
                if (gen != generation || current == null) return;
                current = null;
                serviceItem = null;
                if (appCtx != null) advance(appCtx);
                fire();
            }
        });
    }
}
