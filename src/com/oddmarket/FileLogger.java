package com.oddmarket;
// Logcat plus file log.

import android.content.Context;
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

    private FileLogger() {}

    public static void init(Context context) {
        appContext = Utils.resolveAppContext(appContext, context);
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

    private static void write(String level, String tag, String msg, Throwable t) {
        Context ctx = appContext;
        if (ctx == null) {

            return;
        }

        StringBuilder line = new StringBuilder();
        line.append('[').append(timestamp()).append("] ");
        line.append(level).append('/').append(tag).append(": ").append(msg);

        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line.append('\n').append(sw.toString());
        }
        line.append('\n');

        synchronized (LOCK) {
            File logFile = new File(ctx.getFilesDir(), LOG_FILE_NAME);
            if (logFile.length() > MAX_LOG_FILE_BYTES) {
                File oldLogFile = new File(ctx.getFilesDir(), LOG_FILE_NAME + ".old");
                oldLogFile.delete();
                logFile.renameTo(oldLogFile);
            }

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(logFile, true);
                fos.write(line.toString().getBytes("UTF-8"));
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
