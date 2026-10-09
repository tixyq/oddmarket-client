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

    private static final Map<String, Integer> KEY_TO_WRAP = new HashMap<String, Integer>();
    static {
        KEY_TO_WRAP.put("Communication", R.string.category_communication_wrap);
        KEY_TO_WRAP.put("Entertainment", R.string.category_entertainment_wrap);
        KEY_TO_WRAP.put("Lifestyle", R.string.category_lifestyle_wrap);
        KEY_TO_WRAP.put("Multimedia", R.string.category_multimedia_wrap);
        KEY_TO_WRAP.put("Productivity", R.string.category_productivity_wrap);
        KEY_TO_WRAP.put("Reference", R.string.category_reference_wrap);
        KEY_TO_WRAP.put("Shopping", R.string.category_shopping_wrap);
        KEY_TO_WRAP.put("Tools", R.string.category_tools_wrap);
        KEY_TO_WRAP.put("Travel", R.string.category_travel_wrap);
        KEY_TO_WRAP.put("Casual", R.string.category_casual_wrap);
        KEY_TO_WRAP.put("Brain & Puzzle", R.string.category_brain_puzzle_wrap);
    }

    public static String wrapForCell(Context context, String key, String translated,
                                     android.graphics.Paint paint, float maxPx) {
        if (translated == null || paint.measureText(translated) <= maxPx) return translated;

        if (translated.indexOf(' ') >= 0) {
            int best = -1;
            float bestWidth = Float.MAX_VALUE;
            for (int i = 0; i < translated.length(); i++) {
                if (translated.charAt(i) != ' ') continue;
                float a = paint.measureText(translated.substring(0, i));
                float b = paint.measureText(translated.substring(i + 1));
                float w = a > b ? a : b;
                if (w < bestWidth) {
                    bestWidth = w;
                    best = i;
                }
            }
            if (best > 0) return translated.substring(0, best) + "\n" + translated.substring(best + 1);
            return translated;
        }

        Integer res = KEY_TO_WRAP.get(key);
        if (res != null) return context.getString(res.intValue());
        return translated;
    }

    public static String randomTranslated(Context context) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<String>(KEY_TO_RESOURCE.keySet());
        if (keys.isEmpty()) return null;
        String key = keys.get(new java.util.Random().nextInt(keys.size()));
        return translate(context, key);
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
