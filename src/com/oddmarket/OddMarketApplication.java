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

        FileLogger.startNewSession(base);
        installCrashLogger(base);
    }

    private static void installCrashLogger(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    FileLogger.init(context);
                    FileLogger.e(Utils.TAG, "FATAL crash, thread=" + thread.getName()
                            + " model=" + android.os.Build.MODEL
                            + " sdk=" + android.os.Build.VERSION.SDK_INT, ex);
                } catch (Throwable loggingFailure) {

                }

                try {
                    Utils.shareLogFile(context);
                } catch (Throwable shareFailure) {

                }

                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }

                if (previous != null) {
                    previous.uncaughtException(thread, ex);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    Runtime.getRuntime().exit(10);
                }
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        FileLogger.i(Utils.TAG, "OddMarketApplication.onCreate");
        Utils.disableConnectionReuseIfNecessary();
        Utils.limitDnsCacheTtl();
        Utils.checkRootInBackground(this);
        CookieHelper.init(this);
        ImageDiskCache.init(this);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Theme.init(this, prefs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FileLogger.i(Utils.TAG, "onConfigurationChanged");
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
