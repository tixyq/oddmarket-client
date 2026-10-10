package com.oddmarket;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class InstalledCatalog {

    static final int THREADS = 4;
    private static final long SYNC_DEADLINE_SEC = 40;

    private static final String PREFS = "oddmarket_manage";
    private static final String KEY_CACHE = "cache_v2";

    static final long HIT_TTL = 60L * 60 * 1000;
    static final long NEG_TTL = 24L * 60 * 60 * 1000;

    private static final int FLAG_UPDATED_SYSTEM_APP = 0x80;

    private InstalledCatalog() {}

    static List<String> installedPackages(Context ctx) {
        List<String> result = new ArrayList<String>();
        try {
            List<PackageInfo> packages = ctx.getPackageManager().getInstalledPackages(0);
            for (int i = 0; i < packages.size(); i++) {
                PackageInfo p = packages.get(i);
                if (p.packageName == null) continue;
                ApplicationInfo ai = p.applicationInfo;
                if (ai != null) {
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & FLAG_UPDATED_SYSTEM_APP) == 0;
                    if (system) continue;
                }
                result.add(p.packageName);
            }
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "InstalledCatalog: installed packages failed", t);
        }
        return result;
    }

    static String installedVersion(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String url(String pkg) {
        return "http://odd.txy-50b.workers.dev/?pkg=" + Uri.encode(pkg);
    }

    static JSONObject fetchOne(String pkg) throws Exception {
        JSONObject response = new JSONObject(Utils.downloadString(url(pkg)));
        JSONArray items = response.optJSONArray("items");
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject obj = items.getJSONObject(i);
            if (pkg.equals(obj.optString("pkg", ""))) return obj;
        }
        return null;
    }

    static JSONObject readCache(Context ctx) {
        try {
            String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CACHE, "");
            if (s.length() > 0) return new JSONObject(s);
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "InstalledCatalog: cache read failed", t);
        }
        return new JSONObject();
    }

    static void putCache(JSONObject cache, String pkg, JSONObject entry) throws Exception {
        synchronized (cache) {
            cache.put(pkg, entry);
        }
    }

    static void pruneCache(JSONObject cache, List<String> installed) {
        synchronized (cache) {
            List<String> drop = new ArrayList<String>();
            Iterator<?> keys = cache.keys();
            while (keys.hasNext()) {
                String k = (String) keys.next();
                if (!installed.contains(k)) drop.add(k);
            }
            for (int i = 0; i < drop.size(); i++) cache.remove(drop.get(i));
        }
    }

    static void writeCache(Context ctx, JSONObject cache) {
        try {
            String s;
            synchronized (cache) {
                s = cache.toString();
            }
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.putString(KEY_CACHE, s);
            Utils.savePrefs(ed);
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "InstalledCatalog: cache write failed", t);
        }
    }

    static JSONObject makeEntry(JSONObject found) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("t", System.currentTimeMillis());
        if (found == null) {
            entry.put("ok", false);
        } else {
            entry.put("ok", true);
            entry.put("n", found.optString("name", ""));
            entry.put("i", found.optString("icon", ""));
            entry.put("v", found.optString("version", ""));
        }
        return entry;
    }

    static boolean isHit(JSONObject entry) {
        return entry != null && entry.optBoolean("ok", false);
    }

    static boolean isStale(JSONObject entry, long now) {
        long age = now - entry.optLong("t", 0);
        return age < 0 || age > (isHit(entry) ? HIT_TTL : NEG_TTL);
    }

    static final class Entry {
        final String pkg;
        final String name;
        final String version;
        Entry(String pkg, String name, String version) {
            this.pkg = pkg;
            this.name = name;
            this.version = version;
        }
    }

    static final class Snapshot {
        final List<Entry> hits = new ArrayList<Entry>();
        int failed;
    }

    static Snapshot sync(Context ctx) {
        final JSONObject cache = readCache(ctx);
        List<String> installed = installedPackages(ctx);
        long now = System.currentTimeMillis();

        final List<String> toFetch = new ArrayList<String>();
        for (int i = 0; i < installed.size(); i++) {
            JSONObject e = cache.optJSONObject(installed.get(i));
            if (e == null || isStale(e, now)) toFetch.add(installed.get(i));
        }
        pruneCache(cache, installed);

        final AtomicInteger failed = new AtomicInteger();
        if (!toFetch.isEmpty()) {
            ExecutorService ex = Executors.newFixedThreadPool(Math.min(THREADS, toFetch.size()));
            final CountDownLatch done = new CountDownLatch(toFetch.size());
            for (int i = 0; i < toFetch.size(); i++) {
                final String pkg = toFetch.get(i);
                ex.execute(new Runnable() {
                    public void run() {
                        try {
                            putCache(cache, pkg, makeEntry(fetchOne(pkg)));
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            FileLogger.w(Utils.TAG, "InstalledCatalog: lookup failed for " + pkg, e);
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            try {

                if (!done.await(SYNC_DEADLINE_SEC, java.util.concurrent.TimeUnit.SECONDS)) {
                    failed.incrementAndGet();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                failed.incrementAndGet();
            }
            ex.shutdownNow();
            writeCache(ctx, cache);
        }

        Snapshot snap = new Snapshot();
        snap.failed = failed.get();
        for (int i = 0; i < installed.size(); i++) {
            String pkg = installed.get(i);
            JSONObject e = cache.optJSONObject(pkg);
            if (!isHit(e)) continue;
            String name = e.optString("n", "");
            snap.hits.add(new Entry(pkg, name.length() > 0 ? name : pkg, e.optString("v", "")));
        }
        return snap;
    }
}
