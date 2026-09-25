package com.oddmarket;
// Backend URLs.

import android.content.Context;
import android.net.Uri;

public final class UrlBuilder {

    public static final String BASE_URL = "https://oddmarket.ct.ws/c.php";

    private UrlBuilder() {}

    public static String build(Context context, String screenQuery) {
        StringBuilder url = new StringBuilder(BASE_URL);
        url.append('?').append(screenQuery);

        if ("ru".equals(Utils.resolveAppLanguage(context))) {
            url.append("&lang=ru");
        }
        if (Theme.CURRENT == Theme.LIGHT) {
            url.append("&theme=light");
        }
        return url.toString();
    }

    public static String loginUrl(Context context) {
        return build(context, "log");
    }

    public static String registerUrl(Context context) {
        return build(context, "reg");
    }

    public static String reviewsUrl(Context context, String pkg) {
        return build(context, "reviews&pkg=" + Uri.encode(pkg));
    }

    public static String meUrl(Context context) {
        return build(context, "me");
    }

    public static String deleteAccountUrl(Context context) {
        return build(context, "delacc");
    }

    public static String ratingUrl() {
        return BASE_URL + "?rating";
    }
}
