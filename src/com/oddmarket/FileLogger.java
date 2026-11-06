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

    private static final String EXTERNAL_LOG_PATH = "/storage/emulated/0/log.txt";

    private static final long MAX_LOG_FILE_BYTES = 512 * 1024;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;

    private static volatile boolean sessionStarted = false;

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
        deleteMirror();
        i(Utils.TAG, "=== new session === model=" + android.os.Build.MODEL
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " app=" + (ctx != null ? ctx.getPackageName() : "?"));
    }

    /** INFO is ignored: only WARN and ERROR are logged. */
    public static void i(String tag, String msg) {
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
            appendLine(logFile, line);
            appendToMirror(line);
        }
    }

    private static void appendLine(File file, String line) {
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

    /**
     * Android 11+ (API 30) does not allow writing into the root of shared storage
     * without "All files access" (MANAGE_EXTERNAL_STORAGE), which the user grants in Settings.
     */
    public static boolean hasExternalAccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                java.lang.reflect.Method m = Environment.class.getMethod("isExternalStorageManager");
                return (Boolean) m.invoke(null);
            }
            if (android.os.Build.VERSION.SDK_INT >= 23 && appContext != null) {
                java.lang.reflect.Method m = Context.class.getMethod("checkSelfPermission", String.class);
                return ((Integer) m.invoke(appContext, "android.permission.WRITE_EXTERNAL_STORAGE"))
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void appendToMirror(String line) {
        FileOutputStream fos = null;
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return;
            if (!hasExternalAccess()) return;
            File externalLog = new File(EXTERNAL_LOG_PATH);
            if (externalLog.length() > MAX_LOG_FILE_BYTES) {
                externalLog.delete();
            }
            fos = new FileOutputStream(externalLog, true);
            fos.write(line.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            // external log is best-effort; the internal log is always written
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void deleteMirror() {
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                return;
            }
            new File(EXTERNAL_LOG_PATH).delete();
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
