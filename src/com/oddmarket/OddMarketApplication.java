package com.oddmarket;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

public class OddMarketApplication extends Application {

    private static final String PREFS_NAME = "prefs";
    private static final String KEY_LANGUAGE = "app_language";
    private static final String LANG_EN = "en";

    @Override
    protected void attachBaseContext(Context base) {
        Utils.deviceCountry = Locale.getDefault().getCountry();
        super.attachBaseContext(Utils.applyLocale(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        StrictModeSetup.enableIfDebuggable(this);
        Utils.disableConnectionReuse();
        CookieHelper.init(this);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Theme.init(this, prefs);

        // Everything not needed for the first frame runs off the main thread.
        final Context app = this;
        Utils.runBackground(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                Utils.limitDnsCacheTtl();
                ImageDiskCache.init(app);
                Utils.checkRootInBackground(app);
                UpdateScheduler.ensureScheduled(app);
                registerPackageEvents();
            }
        });
    }

    private void registerPackageEvents() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
            f.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
            f.addDataScheme("package");
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable recheck = new Runnable() {
                public void run() {
                    UpdateChecker.checkNowAsync(OddMarketApplication.this);
                }
            };
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    // Debounce: bulk installs/updates fire dozens of package events in a row.
                    main.removeCallbacks(recheck);
                    main.postDelayed(recheck, 5000L);
                }
            }, f);
        } catch (Throwable t) {
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        try {
            MainActivity.trimMemory(true);
        } catch (Throwable ignored) {
        }
    }

    public void onTrimMemory(int level) {
        try {
            MainActivity.trimMemoryLevel(level);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (android.os.Build.VERSION.SDK_INT < 17) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String language = prefs.getString(KEY_LANGUAGE, LANG_EN);
            Locale locale = "ru".equals(language) ? new Locale("ru") : Locale.ENGLISH;
            Locale.setDefault(locale);

            Resources res = getBaseContext().getResources();
            Configuration config = res.getConfiguration();
            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
        }
    }
}
