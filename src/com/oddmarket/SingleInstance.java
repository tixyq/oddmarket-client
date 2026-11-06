package com.oddmarket;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Registry of live activities.
 *
 * claim()  - at most one instance per class: a newer instance closes the older one (no duplicates).
 * track()  - just registers the activity, no uniqueness.
 *
 * Every entry remembers the settings version it was built with, so a theme / language change can
 * refresh the screens that are already open (see refreshOthers / refreshIfStale).
 */
final class SingleInstance {

    private static final class Entry {
        final WeakReference<Activity> ref;
        int version;

        Entry(Activity a, int version) {
            this.ref = new WeakReference<Activity>(a);
            this.version = version;
        }
    }

    private static final HashMap<Class<?>, WeakReference<Activity>> LIVE =
            new HashMap<Class<?>, WeakReference<Activity>>();
    private static final ArrayList<Entry> ALL = new ArrayList<Entry>();

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
        track(activity);
    }

    static void track(Activity activity) {
        synchronized (LIVE) {
            Entry e = find(activity);
            if (e != null) {
                e.version = Utils.settingsVersion;
                return;
            }
            ALL.add(new Entry(activity, Utils.settingsVersion));
        }
    }

    static void release(Activity activity) {
        synchronized (LIVE) {
            WeakReference<Activity> ref = LIVE.get(activity.getClass());
            if (ref != null && ref.get() == activity) LIVE.remove(activity.getClass());
            for (Iterator<Entry> it = ALL.iterator(); it.hasNext();) {
                Activity a = it.next().ref.get();
                if (a == null || a == activity) it.remove();
            }
        }
    }

    /** The live instance of the class, or null (also when it is already finishing). */
    static Activity current(Class<?> cls) {
        synchronized (LIVE) {
            WeakReference<Activity> ref = LIVE.get(cls);
            Activity a = ref != null ? ref.get() : null;
            return (a != null && !a.isFinishing()) ? a : null;
        }
    }

    private static Entry find(Activity activity) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).ref.get() == activity) return ALL.get(i);
        }
        return null;
    }

    /** The activity has just been rebuilt in place for the current settings. */
    static void markCurrent(Activity activity) {
        synchronized (LIVE) {
            Entry e = find(activity);
            if (e != null) e.version = Utils.settingsVersion;
        }
    }

    /**
     * Theme / language changed: rebuild every other open screen right now, while it is hidden
     * behind the one the user is looking at, so nothing flashes when they go back.
     * Old devices (no Activity.recreate) fall back to refreshIfStale() on resume.
     */
    static void refreshOthers(Activity except) {
        if (android.os.Build.VERSION.SDK_INT < 11) return;
        ArrayList<Activity> stale = new ArrayList<Activity>();
        synchronized (LIVE) {
            for (int i = 0; i < ALL.size(); i++) {
                Entry e = ALL.get(i);
                Activity a = e.ref.get();
                if (a == null || a == except || a.isFinishing()) continue;
                if (e.version != Utils.settingsVersion) {
                    e.version = Utils.settingsVersion;
                    stale.add(a);
                }
            }
        }
        for (int i = 0; i < stale.size(); i++) Utils.recreateCompat(stale.get(i));
    }

    /** Safety net for onResume: the screen was built before the last settings change. */
    static boolean refreshIfStale(Activity activity) {
        boolean stale;
        synchronized (LIVE) {
            Entry e = find(activity);
            stale = e != null && e.version != Utils.settingsVersion;
            if (stale) e.version = Utils.settingsVersion;
        }
        if (stale) Utils.recreateCompat(activity);
        return stale;
    }
}
