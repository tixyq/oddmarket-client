package com.oddmarket;
// Pure-HTTP anti-bot solver, no WebView.
public final class AntiBot {

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; U; Android 4.0.3; en-us; HTC Sensation Build/IML74K) "
            + "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30";

    private AntiBot() {}

    public static boolean isChallenge(String body) {
        return body != null && body.contains("toNumbers") && body.contains("location.href");
    }

    public static String solveTestCookie(String html) {
        if (html == null) return null;
        java.util.ArrayList<String> blocks = new java.util.ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "toNumbers\\(\"([0-9a-fA-F]{32,})\"\\)").matcher(html);
        while (m.find() && blocks.size() < 3) {
            blocks.add(m.group(1));
        }
        if (blocks.size() < 3) return null;
        try {
            byte[] a = hex(blocks.get(0));
            byte[] b = hex(blocks.get(1));
            byte[] c = hex(blocks.get(2));
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(a, "AES"),
                    new javax.crypto.spec.IvParameterSpec(b));
            return toHex(cipher.doFinal(c));
        } catch (Exception e) {
            return null;
        }
    }

    public static String fetchDirect(android.content.Context ctx, String url, String label) {
        String first = AccountManager.httpGet(ctx, BROWSER_UA, url, label + "/direct");
        if (first == null) return null;
        if (!isChallenge(first)) return first;
        String cookie = solveTestCookie(first);
        if (cookie == null) {
            FileLogger.w(Utils.TAG, label + ": direct challenge unsolvable");
            return null;
        }
        try {
            android.webkit.CookieManager.getInstance().setCookie(url, "__test=" + cookie);
            CookieHelper.flush();
        } catch (Exception ignored) {}
        String url2 = url + (url.contains("?") ? "&i=1" : "?i=1");
        String second = AccountManager.httpGet(ctx, BROWSER_UA, url2, label + "/direct");
        if (second == null || isChallenge(second)) return null;
        return second;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 15, 16));
            sb.append(Character.forDigit(b[i] & 15, 16));
        }
        return sb.toString();
    }
}
