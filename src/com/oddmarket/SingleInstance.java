package com.oddmarket;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.HashMap;

final class SingleInstance {

    private static final HashMap<Class<?>, WeakReference<Activity>> LIVE =
            new HashMap<Class<?>, WeakReference<Activity>>();

    private SingleInstance() {}

    static void claim(Activity activity) {
        synchronized (LIVE) {
            WeakReference<Activity> ref = LIVE.get(activity.getClass());
            Activity old = ref != null ? ref.get() : null;
            if (old != null && old != activity && !old.isFinishing()) {
                old.finish();
            }
            LIVE.put(activity.getClass(), new WeakReference<Activity>(activity));
        }
    }

    static void release(Activity activity) {
        synchronized (LIVE) {
            WeakReference<Activity> ref = LIVE.get(activity.getClass());
            if (ref != null && ref.get() == activity) LIVE.remove(activity.getClass());
        }
    }
}
