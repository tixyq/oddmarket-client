package com.oddmarket;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ImageDiskCache {

    private ImageDiskCache() {}

    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final long TRIM_TARGET = MAX_BYTES - MAX_BYTES / 4;
    private static final long FRESH_MS = 6L * 60 * 60 * 1000;
    private static final long STALE_TMP_MS = 10L * 60 * 1000;

    private static long approxTotal = -1L;

    private static volatile File dir;

    public static synchronized void init(Context context) {
        if (dir != null) return;
        File d = new File(context.getApplicationContext().getCacheDir(), "images");
        if (!d.exists()) {
            d.mkdirs();
        }
        dir = d;
    }

    public static final class Fetched {
        public final byte[] bytes;
        public final boolean fromNetwork;

        Fetched(byte[] bytes, boolean fromNetwork) {
            this.bytes = bytes;
            this.fromNetwork = fromNetwork;
        }
    }

    public static Fetched fetch(String url, int timeoutMillis) throws Exception {
        File cacheDir = dir;
        if (cacheDir == null) {

            return new Fetched(plainDownload(url, timeoutMillis), true);
        }

        String key = keyFor(url);
        File img = new File(cacheDir, key + ".img");
        File meta = new File(cacheDir, key + ".meta");

        long cachedLen = img.length();
        boolean hasCached = cachedLen > 0;

        if (hasCached && meta.exists()
                && System.currentTimeMillis() - meta.lastModified() < FRESH_MS) {
            try {
                byte[] fresh = readFile(img);
                img.setLastModified(System.currentTimeMillis());
                return new Fetched(fresh, false);
            } catch (Exception unreadable) {
                img.delete();
                meta.delete();
                hasCached = false;
                cachedLen = 0L;
            }
        }

        String etag = null;
        String lastModified = null;
        if (hasCached && meta.exists()) {
            String[] kv = readMeta(meta);
            etag = kv[0];
            lastModified = kv[1];
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            if (hasCached && etag != null) {
                conn.setRequestProperty("If-None-Match", etag);
            }
            if (hasCached && lastModified != null) {
                conn.setRequestProperty("If-Modified-Since", lastModified);
            }

            int code = conn.getResponseCode();

            if (hasCached && code == HttpURLConnection.HTTP_NOT_MODIFIED) {

                touch(img, meta, true);
                return new Fetched(readFile(img), false);
            }

            byte[] body = readStream(conn.getInputStream(), conn.getContentLength());
            try {
                writeFile(cacheDir, img, body);
                writeMeta(cacheDir, meta, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"));
                account(cacheDir, body.length - cachedLen);
            } catch (Exception writeFailure) {
                img.delete();
                meta.delete();
            }
            return new Fetched(body, true);

        } catch (Exception networkFailure) {
            if (hasCached) {

                touch(img, meta, false);
                return new Fetched(readFile(img), false);
            }
            throw networkFailure;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] plainDownload(String url, int timeoutMillis) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMillis);
        conn.setReadTimeout(timeoutMillis);
        try {
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String keyFor(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xFF;
                String hex = Integer.toHexString(b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {

            return Integer.toHexString(url.hashCode());
        }
    }

    private static byte[] readStream(InputStream in) throws Exception {
        return readStream(in, -1);
    }

    private static byte[] readStream(InputStream in, int expected) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(expected > 0 && expected < 4 * 1024 * 1024 ? expected : 8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static byte[] readFile(File f) throws Exception {
        return readStream(new FileInputStream(f));
    }

    private static void writeFile(File cacheDir, File dest, byte[] data) throws Exception {
        File tmp = new File(cacheDir, dest.getName() + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".tmp");
        boolean done = false;
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            done = tmp.renameTo(dest);
        } finally {
            if (!done) tmp.delete();
        }
    }

    private static void writeMeta(File cacheDir, File meta, String etag, String lastModified) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(etag == null ? "" : etag).append("\n");
            sb.append(lastModified == null ? "" : lastModified).append("\n");
            writeFile(cacheDir, meta, sb.toString().getBytes("UTF-8"));
        } catch (Exception e) {

        }
    }

    private static String[] readMeta(File meta) {
        try {
            byte[] raw = readFile(meta);
            String s = new String(raw, "UTF-8");
            String[] lines = s.split("\n", -1);
            String etag = (lines.length > 0 && lines[0].length() > 0) ? lines[0] : null;
            String lastModified = (lines.length > 1 && lines[1].length() > 0) ? lines[1] : null;
            return new String[]{etag, lastModified};
        } catch (Exception e) {
            return new String[]{null, null};
        }
    }

    private static void touch(File img, File meta, boolean validated) {
        long now = System.currentTimeMillis();
        img.setLastModified(now);
        if (validated && meta.exists()) meta.setLastModified(now);
    }

    private static synchronized void account(File cacheDir, long delta) {
        if (approxTotal < 0L) {
            trim(cacheDir);
            return;
        }
        approxTotal += delta;
        if (approxTotal > MAX_BYTES) trim(cacheDir);
    }

    private static synchronized void trim(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;

        long now = System.currentTimeMillis();
        long total = 0;
        List<File> imgs = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String fileName = f.getName();
            if (fileName.endsWith(".img")) {
                imgs.add(f);
                total += f.length();
            } else if (fileName.endsWith(".tmp") && now - f.lastModified() > STALE_TMP_MS) {
                f.delete();
            }
        }
        approxTotal = total;
        if (total <= MAX_BYTES) return;

        Collections.sort(imgs, new Comparator<File>() {
            public int compare(File a, File b) {
                long diff = a.lastModified() - b.lastModified();
                return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
            }
        });

        for (int i = 0; i < imgs.size() && total > TRIM_TARGET; i++) {
            File f = imgs.get(i);
            total -= f.length();
            String name = f.getName();
            String key = name.substring(0, name.length() - ".img".length());
            f.delete();
            new File(cacheDir, key + ".meta").delete();
        }
        approxTotal = total;
    }
}
