package com.oddmarket;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FileLogger {

    private static final String LOG_FILE_NAME = "log.txt";

    private static final long MAX_LOG_FILE_BYTES = 512 * 1024;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;

    private static volatile boolean sessionStarted = false;

    private static volatile boolean externalMirrorDisabled = false;

    private FileLogger() {}

    public static void init(Context context) {
        appContext = Utils.resolveAppContext(appContext, context);
    }

    public static void startNewSession(Context context) {
        if (sessionStarted) return;
        synchronized (LOCK) {
            if (sessionStarted) return;
            sessionStarted = true;
        }
        init(context);
        Context ctx = appContext;
        if (ctx != null) {
            new File(ctx.getFilesDir(), LOG_FILE_NAME).delete();
            new File(ctx.getFilesDir(), LOG_FILE_NAME + ".old").delete();
        }
        deleteExternalMirrorQuietly();
        i(Utils.TAG, "=== new session === model=" + android.os.Build.MODEL
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " app=" + (ctx != null ? ctx.getPackageName() : "?"));
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        write("INFO", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        write("WARN", tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
        write("WARN", tag, msg, t);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        write("ERROR", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        write("ERROR", tag, msg, t);
    }

    private static String formatLine(String level, String tag, String msg, Throwable t) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(timestamp()).append("] ");
        line.append(level).append('/').append(tag).append(": ").append(msg);

        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line.append('\n').append(sw.toString());
        }
        line.append('\n');
        return line.toString();
    }

    private static void write(String level, String tag, String msg, Throwable t) {
        Context ctx = appContext;
        if (ctx == null) return;

        String line = formatLine(level, tag, msg, t);

        synchronized (LOCK) {
            File logFile = new File(ctx.getFilesDir(), LOG_FILE_NAME);
            if (logFile.length() > MAX_LOG_FILE_BYTES) {
                File oldLogFile = new File(ctx.getFilesDir(), LOG_FILE_NAME + ".old");
                oldLogFile.delete();
                logFile.renameTo(oldLogFile);
            }
            appendQuietly(logFile, line);
            appendToExternalMirrorQuietly(line);
        }
    }

    private static void appendQuietly(File file, String line) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, true);
            fos.write(line.getBytes("UTF-8"));
            fos.flush();
        } catch (IOException e) {
            Log.e("OddMarket", "FileLogger failed to write log file", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void appendToExternalMirrorQuietly(String line) {
        if (externalMirrorDisabled) return;
        FileOutputStream fos = null;
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                externalMirrorDisabled = true;
                return;
            }
            File externalLog = new File(Environment.getExternalStorageDirectory(), LOG_FILE_NAME);
            fos = new FileOutputStream(externalLog, true);
            fos.write(line.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {

            externalMirrorDisabled = true;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void deleteExternalMirrorQuietly() {
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                return;
            }
            new File(Environment.getExternalStorageDirectory(), LOG_FILE_NAME).delete();
        } catch (Throwable ignored) {
        }
    }

    private static String timestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.US);
        return fmt.format(new Date());
    }

    public static File getLogFile() {
        Context ctx = appContext;
        if (ctx == null) return null;
        File f = new File(ctx.getFilesDir(), LOG_FILE_NAME);
        return f.exists() ? f : null;
    }
}
