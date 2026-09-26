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
        String line = versionAndroidLine(context);
        String translatedCategory = Categories.translate(context, category);
        if (translatedCategory != null) {
            line += " - " + translatedCategory;
        }
        return line;
    }
}
