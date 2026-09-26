package com.oddmarket;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

public final class Categories {

    private Categories() {}

    private static final Map<String, Integer> KEY_TO_RESOURCE = new HashMap<String, Integer>();
    static {

        KEY_TO_RESOURCE.put("Communication", R.string.category_communication);
        KEY_TO_RESOURCE.put("Entertainment", R.string.category_entertainment);
        KEY_TO_RESOURCE.put("Finance", R.string.category_finance);
        KEY_TO_RESOURCE.put("Lifestyle", R.string.category_lifestyle);
        KEY_TO_RESOURCE.put("Multimedia", R.string.category_multimedia);
        KEY_TO_RESOURCE.put("News & Weather", R.string.category_news_weather);
        KEY_TO_RESOURCE.put("Productivity", R.string.category_productivity);
        KEY_TO_RESOURCE.put("Reference", R.string.category_reference);
        KEY_TO_RESOURCE.put("Shopping", R.string.category_shopping);
        KEY_TO_RESOURCE.put("Social", R.string.category_social);
        KEY_TO_RESOURCE.put("Tools", R.string.category_tools);
        KEY_TO_RESOURCE.put("Travel", R.string.category_travel);

        KEY_TO_RESOURCE.put("Arcade", R.string.category_arcade);
        KEY_TO_RESOURCE.put("Brain & Puzzle", R.string.category_brain_puzzle);
        KEY_TO_RESOURCE.put("Cards & Casino", R.string.category_cards_casino);
        KEY_TO_RESOURCE.put("Casual", R.string.category_casual);
    }

    public static String translate(Context context, String key) {
        if (key == null || key.length() == 0) {
            return null;
        }
        Integer resId = KEY_TO_RESOURCE.get(key);
        if (resId == null) {
            return key;
        }
        return context.getString(resId);
    }
}
