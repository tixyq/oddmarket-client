package com.oddmarket;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

public class DownloadService extends Service {

    private static final long WAKE_LOCK_TIMEOUT_MS = 60L * 60 * 1000;
    private static PowerManager.WakeLock wakeLock = null;

    private Task task;

    static synchronized void acquireWakeLock(Context c) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OddMarket:Download");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to acquire wake lock for download", e);
        }
    }

    static synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to release wake lock", e);
        }
    }

    private static volatile Task runningTask = null;

    static void cancelRunning() {
        Task t = runningTask;
        if (t != null) t.cancel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleStart(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        if (android.os.Build.VERSION.SDK_INT < 5) handleStart(startId);
    }

    private void handleStart(int startId) {
        DownloadCenter.Item item = DownloadCenter.serviceItem;
        if (item == null) {

            goForeground(DownloadNotifier.buildIdle(this));
            leaveForeground();
            stopSelf(startId);
            return;
        }

        goForeground(DownloadNotifier.buildProgress(this, item));
        acquireWakeLock(this);

        if (task != null && task.gen == item.gen && task.isAlive()) {
            return;
        }
        task = new Task(this, item.url, item.name, item.pkg, item.gen);
        runningTask = task;
        task.start();
    }

    @SuppressWarnings("deprecation")
    private void goForeground(Notification n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                if (n == null) n = new Notification();
                Method m = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                m.invoke(this, DownloadNotifier.ID_PROGRESS, n, 1 );
            } else if (android.os.Build.VERSION.SDK_INT >= 5) {
                if (n == null) n = new Notification();
                startForeground(DownloadNotifier.ID_PROGRESS, n);
            } else {

                try {
                    Method sf = Service.class.getMethod("setForeground", boolean.class);
                    sf.invoke(this, true);
                } catch (Throwable ignored) {}
                if (n != null) {
                    ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                            .notify(DownloadNotifier.ID_PROGRESS, n);
                }
            }
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "startForeground failed", t);
        }
    }

    @SuppressWarnings("deprecation")
    void leaveForeground() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 5) {
                stopForeground(true);
            } else {
                try {
                    Method sf = Service.class.getMethod("setForeground", boolean.class);
                    sf.invoke(this, false);
                } catch (Throwable ignored) {}
                ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                        .cancel(DownloadNotifier.ID_PROGRESS);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        FileLogger.i(Utils.TAG, "DownloadService.onDestroy");

        Task t = task;
        if (t != null && t.gen == DownloadCenter.generation && !t.finished) {
            t.cancel();
            DownloadCenter.postCancelled(t.gen);
        }
        if (DownloadCenter.serviceItem == null) releaseWakeLock();
        super.onDestroy();
    }

    private static final class CancelledException extends Exception {
        CancelledException() { super("Cancelled"); }
    }

    private static class Task extends Thread {

        private static final int CONNECT_TIMEOUT_MS = 30000;
        private static final int READ_TIMEOUT_MS = 180000;

        private static final int BUFFER_SIZE = 32768;

        private static final int MAX_ATTEMPTS = 4;
        private static final long RETRY_BACKOFF_MS = 1500;

        private static final java.util.Set<String> httpsUnsupportedHosts =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        private static final class HttpsHandshakeException extends Exception {
            HttpsHandshakeException(Throwable cause) { super(cause); }
        }

        final DownloadService service;
        final Context appContext;
        final String apkUrl;
        final String appName;
        final String expectedPkg;

        volatile int progressVal = 0;
        volatile boolean isIndeterminate = false;
        volatile boolean cancelled = false;
        volatile File outputFile = null;
        volatile File finalFile = null;
        volatile HttpURLConnection currentConn = null;
        volatile boolean finished = false;
        final int gen;

        private volatile int totalExpectedLength = -1;

        Task(DownloadService service, String url, String name, String expectedPkg, int gen) {
            this.gen = gen;
            this.service = service;
            this.appContext = service.getApplicationContext();
            this.apkUrl = url;
            this.appName = name;
            this.expectedPkg = expectedPkg;
        }

        void cancel() {
            if (finished) return;
            cancelled = true;
            interrupt();
            final HttpURLConnection c = currentConn;
            if (c != null) {
                new Thread(new Runnable() {
                    public void run() {
                        try { c.disconnect(); } catch (Throwable ignored) {}
                    }
                }).start();
            }
            deletePartial();
        }

        private void deletePartial() {
            File f = outputFile;
            if (f != null) f.delete();
        }

        private void publishProgress(int value, boolean indeterminate) {
            DownloadCenter.postProgress(gen, value, indeterminate);
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            File result = null;
            String error = null;
            boolean wasCancelled = false;
            try {
                publishProgress(0, true);

                String originalUrl = MainActivity.fixApkUrl(appContext, apkUrl);
                final String host = extractHost(originalUrl);

                File dir = Utils.resolveApkDownloadDir(appContext);
                dir.mkdirs();

                String safeName = appName == null ? "" : appName.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
                if (safeName.length() == 0) safeName = "app";
                finalFile = new File(dir, safeName + ".apk");
                cleanStalePartials(dir);

                outputFile = new File(dir, safeName + ".dl" + gen + ".apk");
                outputFile.delete();
                if (cancelled) throw new CancelledException();

                String httpsUrl = originalUrl.toLowerCase().startsWith("https://")
                        ? originalUrl
                        : "https://" + stripScheme(originalUrl);

                boolean useHttps = host != null && !httpsUnsupportedHosts.contains(host);
                long resumeFrom = 0;
                Exception lastError = null;

                boolean reusedLocal = reuseLocalFile(finalFile, httpsUrl, Utils.httpsToHttp(originalUrl), useHttps);
                if (cancelled) throw new CancelledException();

                if (!reusedLocal) {
                    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                        if (cancelled) throw new CancelledException();
                        String attemptUrl = useHttps ? httpsUrl : Utils.httpsToHttp(originalUrl);
                        try {
                            attemptOnce(attemptUrl, resumeFrom, useHttps);
                            requireApk(outputFile);
                            requirePackage(outputFile, expectedPkg);
                            lastError = null;
                            break;
                        } catch (CancelledException e) {
                            throw e;
                        } catch (HttpsHandshakeException e) {
                            if (cancelled) throw new CancelledException();

                            if (host != null) httpsUnsupportedHosts.add(host);
                            useHttps = false;
                            resumeFrom = 0;
                            outputFile.delete();
                            attempt--;
                            continue;
                        } catch (Exception e) {
                            if (cancelled) throw new CancelledException();
                            lastError = e;
                            if (useHttps) {

                                if (host != null) httpsUnsupportedHosts.add(host);
                                useHttps = false;
                                resumeFrom = 0;
                                outputFile.delete();
                                attempt--;
                                continue;
                            }
                            resumeFrom = outputFile.exists() ? outputFile.length() : 0;
                            if (attempt < MAX_ATTEMPTS) {
                                try { Thread.sleep(RETRY_BACKOFF_MS); } catch (InterruptedException ignored) {}
                            }
                        }
                    }

                    if (lastError != null) {
                        throw lastError;
                    }

                    if (cancelled) throw new CancelledException();
                    finalFile.delete();
                    if (!outputFile.renameTo(finalFile)) {
                        throw new Exception("Could not save the downloaded file.");
                    }
                    outputFile = finalFile;
                }

                Utils.setWorldReadable(finalFile);

                DownloadRegistry.registerDownloadedFile(appContext, finalFile.getName());
                result = finalFile;
                finished = true;
            } catch (CancelledException e) {
                wasCancelled = true;
                deletePartial();
            } catch (final Exception e) {
                if (cancelled) {
                    wasCancelled = true;
                    deletePartial();
                } else {
                    deletePartial();
                    error = e.getMessage();
                    if (error == null) {
                        if (e instanceof java.net.SocketTimeoutException) {
                            error = "Connection timed out.";
                        } else {
                            error = e.getClass().getSimpleName();
                        }
                    }
                }
            }

            if (wasCancelled) {
                DownloadCenter.postCancelled(gen);
            } else {
                finished = true;
                DownloadCenter.postFinished(gen, appContext, result, error);
            }
        }

        private static boolean isRedirect(int status) {
            return status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308;
        }

        private HttpURLConnection openFollowingRedirects(String startUrl, String range, boolean allowHttps,
                                                         int connectTimeoutMs, int readTimeoutMs,
                                                         boolean wrapHttpsErrors) throws Exception {
            String u = startUrl;
            for (int hop = 0; ; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                currentConn = conn;
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setRequestProperty("Connection", "close");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setInstanceFollowRedirects(false);
                if (range != null) conn.setRequestProperty("Range", range);

                int status;
                try {
                    status = conn.getResponseCode();
                } catch (Exception e) {
                    if (wrapHttpsErrors) throw new HttpsHandshakeException(e);
                    throw e;
                }

                if (!isRedirect(status) || hop >= 5) return conn;

                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new Exception("HTTP error code: " + status);
                u = allowHttps ? loc : Utils.httpsToHttp(loc);
            }
        }

        private void attemptOnce(String startUrl, long resumeFrom, boolean allowHttps) throws Exception {
            HttpURLConnection conn = null;
            InputStream input = null;
            FileOutputStream output = null;

            try {
                conn = openFollowingRedirects(startUrl, resumeFrom > 0 ? "bytes=" + resumeFrom + "-" : null,
                        allowHttps, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, allowHttps);
                int status = conn.getResponseCode();

                boolean resumedHere = false;
                if (resumeFrom > 0 && status == 206) {
                    resumedHere = true;
                } else if (resumeFrom > 0 && status >= 200 && status < 300) {

                    resumeFrom = 0;
                    outputFile.delete();
                }

                if (status < 200 || status > 299) {
                    throw new Exception("HTTP error code: " + status);
                }

                int reportedLength = conn.getContentLength();
                if (resumedHere) {
                    if (totalExpectedLength <= 0 && reportedLength > 0) {
                        totalExpectedLength = (int) resumeFrom + reportedLength;
                    }
                } else {
                    totalExpectedLength = reportedLength;
                }
                isIndeterminate = (totalExpectedLength <= 0);

                publishProgress(progressVal, isIndeterminate);

                input = conn.getInputStream();
                output = new FileOutputStream(outputFile, resumedHere);

                byte[] data = new byte[BUFFER_SIZE];
                long total = resumedHere ? resumeFrom : 0;
                int count;
                int lastProgress = -1;

                while ((count = input.read(data)) != -1) {
                    if (cancelled) throw new CancelledException();
                    total += count;
                    output.write(data, 0, count);

                    if (!isIndeterminate) {
                        progressVal = (int) ((total * 100L) / totalExpectedLength);
                        if (progressVal > lastProgress) {
                            lastProgress = progressVal;
                            publishProgress(progressVal, false);
                        }
                    }
                }

                output.flush();
                output.close();
                output = null;

                if (!isIndeterminate && total != totalExpectedLength) {
                    throw new Exception("Incomplete download (" + total + " of " + totalExpectedLength + " bytes).");
                }
            } finally {
                try { if (output != null) output.close(); } catch (Exception ignored) {}
                try { if (input != null) input.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        private static final int PROBE_TIMEOUT_MS = 15000;
        private static final long MAX_PROBE_BYTES = 8L * 1024 * 1024;

        private boolean reuseLocalFile(File local, String httpsUrl, String httpUrl, boolean preferHttps) {
            try {
                if (local == null || !local.isFile() || !local.canRead()) return false;
                long len = local.length();
                if (len < 22) return false;
                if (!looksLikeValidLocalApk(local)) return false;

                long cdOffset = findCentralDirectoryOffset(local, len);
                if (cdOffset < 0 || len - cdOffset > MAX_PROBE_BYTES) return false;

                boolean same;
                if (preferHttps) {
                    same = compareRemoteTail(httpsUrl, true, local, len, cdOffset);
                    if (!same && !cancelled) same = compareRemoteTail(httpUrl, false, local, len, cdOffset);
                } else {
                    same = compareRemoteTail(httpUrl, false, local, len, cdOffset);
                }
                if (same) FileLogger.i(Utils.TAG, "Download skipped, identical local file: " + local.getAbsolutePath());
                return same;
            } catch (Throwable t) {
                FileLogger.w(Utils.TAG, "reuseLocalFile failed, downloading normally", t);
                return false;
            }
        }

        private boolean looksLikeValidLocalApk(File file) {
            java.io.FileInputStream in = null;
            try {
                in = new java.io.FileInputStream(file);
                byte[] h = new byte[4];
                if (in.read(h) != 4 || h[0] != 0x50 || h[1] != 0x4B || h[2] != 0x03 || h[3] != 0x04) return false;
            } catch (java.io.IOException e) {
                return false;
            } finally {
                if (in != null) { try { in.close(); } catch (Exception ignored) {} }
            }
            if (expectedPkg == null || expectedPkg.length() == 0) return true;
            PackageInfo info = appContext.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
            return info != null && expectedPkg.equals(info.packageName);
        }

        private static long findCentralDirectoryOffset(File file, long len) throws java.io.IOException {
            int tailLen = (int) Math.min(len, 65557L);
            byte[] tail = new byte[tailLen];
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            try {
                raf.seek(len - tailLen);
                raf.readFully(tail);
            } finally {
                raf.close();
            }
            for (int i = tailLen - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                    long cdSize = readUInt32LE(tail, i + 12);
                    long cdOffset = readUInt32LE(tail, i + 16);
                    if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return -1;
                    long eocdPos = (len - tailLen) + i;
                    if (cdOffset + cdSize > eocdPos) return -1;
                    return cdOffset;
                }
            }
            return -1;
        }

        private static long readUInt32LE(byte[] b, int o) {
            return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
        }

        private boolean compareRemoteTail(String url, boolean allowHttps, File local, long len, long cdOffset) {
            HttpURLConnection conn = null;
            InputStream remote = null;
            java.io.RandomAccessFile raf = null;
            try {
                conn = openFollowingRedirects(url, "bytes=" + cdOffset + "-" + (len - 1), allowHttps,
                        PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS, false);
                if (conn.getResponseCode() != 206) return false;

                String range = conn.getHeaderField("Content-Range");
                if (range == null) return false;
                int slash = range.lastIndexOf('/');
                if (slash < 0 || Long.parseLong(range.substring(slash + 1).trim()) != len) return false;

                long remaining = len - cdOffset;
                remote = new BufferedInputStream(conn.getInputStream(), 16384);
                raf = new java.io.RandomAccessFile(local, "r");
                raf.seek(cdOffset);

                byte[] a = new byte[16384];
                byte[] b = new byte[16384];
                while (remaining > 0) {
                    if (cancelled) return false;
                    int want = (int) Math.min(a.length, remaining);
                    int got = 0;
                    while (got < want) {
                        int r = remote.read(a, got, want - got);
                        if (r < 0) return false;
                        got += r;
                    }
                    raf.readFully(b, 0, want);
                    for (int i = 0; i < want; i++) {
                        if (a[i] != b[i]) return false;
                    }
                    remaining -= want;
                }
                return true;
            } catch (Throwable t) {
                return false;
            } finally {
                try { if (raf != null) raf.close(); } catch (Exception ignored) {}
                try { if (remote != null) remote.close(); } catch (Exception ignored) {}
                try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        private static final java.util.regex.Pattern STALE_PARTIAL =
                java.util.regex.Pattern.compile(".*\\.dl[0-9]+\\.apk");

        private void cleanStalePartials(File dir) {
            try {
                File[] files = dir.listFiles();
                if (files == null) return;
                for (int i = 0; i < files.length; i++) {
                    if (STALE_PARTIAL.matcher(files[i].getName()).matches()) files[i].delete();
                }
            } catch (Throwable ignored) {}
        }

        private static String stripScheme(String url) {
            int idx = url.indexOf("://");
            return idx >= 0 ? url.substring(idx + 3) : url;
        }

        private static String extractHost(String url) {
            try {
                return new URL(url).getHost();
            } catch (Exception e) {
                return null;
            }
        }

        private static void requireApk(File file) throws Exception {
            java.io.FileInputStream in = null;
            try {
                in = new java.io.FileInputStream(file);
                byte[] header = new byte[4];
                int read = in.read(header);
                if (read != 4 || header[0] != 0x50 || header[1] != 0x4B
                        || header[2] != 0x03 || header[3] != 0x04) {
                    file.delete();
                    throw new Exception("Downloaded file is not a valid package.");
                }
            } catch (java.io.IOException e) {
                file.delete();
                throw new Exception("Could not verify downloaded file.");
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }
        }

        private void requirePackage(File file, String expectedPkg) throws Exception {
            if (expectedPkg == null || expectedPkg.length() == 0 || appContext == null) {
                return;
            }
            PackageManager pm = appContext.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info == null || info.packageName == null || !expectedPkg.equals(info.packageName)) {
                file.delete();
                throw new Exception("Downloaded package does not match the expected app.");
            }
        }
    }
}
