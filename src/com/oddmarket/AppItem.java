package com.oddmarket;

import android.content.Context;

import java.util.ArrayList;

public class AppItem {
    public String pkg;
    public String name;
    public String version;
    public String minAndroid;
    public String icon;
    public String description;
    public String downloadUrl;

    public String category;

    public ArrayList<String> screenshots = new ArrayList<String>();

    public String versionAndroidLine(Context context) {
        return version + " (" + Utils.formatMinAndroid(context, minAndroid) + ")";
    }

    public String versionLine(Context context) {
        boolean hasMin = minAndroid != null && minAndroid.length() > 0;
        String translatedCategory = Categories.translate(context, category);
        boolean hasCategory = translatedCategory != null && translatedCategory.length() > 0;
        if (hasCategory && hasMin) {
            return translatedCategory + " (" + Utils.formatMinAndroid(context, minAndroid) + ")";
        }
        if (hasCategory) return translatedCategory;
        if (hasMin) return Utils.formatMinAndroid(context, minAndroid);
        return "";
    }
}
