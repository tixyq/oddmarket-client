package com.oddmarket;

import android.content.Context;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class AppSize {

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_HOPS = 5;

    private AppSize() {}

    public static long fetch(String url) {
        if (url == null || url.length() == 0) return -1L;
        String plain = Utils.httpsToHttp(url);
        long n = request(plain, true);
        if (n <= 0L) n = request(plain, false);
        if (n <= 0L && !plain.equals(url)) {
            n = request(url, true);
            if (n <= 0L) n = request(url, false);
        }
        return n;
    }

    private static long request(String startUrl, boolean head) {
        String u = startUrl;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Connection", "close");
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (head) {
                    conn.setRequestMethod("HEAD");
                } else {
                    conn.setRequestProperty("Range", "bytes=0-0");
                }
                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null) return -1L;
                    u = Utils.httpsToHttp(new URL(new URL(u), loc).toString());
                    continue;
                }
                if (status == 206) {
                    String range = conn.getHeaderField("Content-Range");
                    if (range == null) return -1L;
                    int slash = range.lastIndexOf('/');
                    if (slash < 0) return -1L;
                    return parseLong(range.substring(slash + 1));
                }
                if (status >= 200 && status < 300) {
                    if (!head) return -1L;
                    return parseLong(conn.getHeaderField("Content-Length"));
                }
                return -1L;
            } catch (Exception e) {
                return -1L;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return -1L;
    }

    private static long parseLong(String s) {
        if (s == null) return -1L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static String[] format(Context context, long bytes) {
        double v = bytes;
        int unit = 0;
        while (unit < 3 && Math.round(v) >= 1024L) {
            v /= 1024d;
            unit++;
        }
        int res;
        if (unit == 0) res = R.string.size_b_unit;
        else if (unit == 1) res = R.string.size_kb_unit;
        else if (unit == 2) res = R.string.size_mb_unit;
        else res = R.string.size_gb_unit;

        String value;
        if (unit >= 2 && Math.round(v * 10d) < 100L) {
            value = String.format(Locale.getDefault(), "%.1f", Double.valueOf(v));
        } else {
            value = String.valueOf(Math.round(v));
        }
        return new String[] { value, context.getString(res) };
    }
}
