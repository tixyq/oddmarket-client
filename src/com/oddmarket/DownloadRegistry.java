package com.oddmarket;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public final class DownloadRegistry {

    private static final String PREFS_NAME = "oddmarket_downloads";
    private static final String KEY_FILES = "files";
    private static final String SEPARATOR = "\n";

    private static final int MAX_ENTRIES = 200;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;

    private DownloadRegistry() {}

    public static void init(Context context) {
        appContext = Utils.resolveAppContext(appContext, context);
    }

    public static void registerDownloadedFile(Context context, String fileName) {
        if (fileName == null || fileName.length() == 0) return;
        init(context);
        if (appContext == null) return;

        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            List<String> names = readNames(prefs);
            names.remove(fileName);
            names.add(fileName);
            while (names.size() > MAX_ENTRIES) {
                names.remove(0);
            }
            Utils.savePrefs(prefs.edit().putString(KEY_FILES, joinNames(names)));
        }
    }

    public static boolean isRegisteredFileName(Context context, String fileName) {
        if (fileName == null || fileName.length() == 0) return false;
        init(context);
        if (appContext == null) return false;

        synchronized (LOCK) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return readNames(prefs).contains(fileName);
        }
    }

    private static List<String> readNames(SharedPreferences prefs) {
        List<String> names = new ArrayList<String>();
        String stored = prefs.getString(KEY_FILES, "");
        if (stored.length() == 0) return names;
        String[] parts = stored.split(SEPARATOR);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0) names.add(parts[i]);
        }
        return names;
    }

    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
