package com.oddmarket;

import android.content.Context;
import android.content.SharedPreferences;

public class Theme {

    public static final int LIGHT = 0;
    public static final int DARK = 1;

    private static final String PREFS_NAME = "prefs";
    private static final String KEY_THEME = "app_theme";

    public static int CURRENT = LIGHT;

    private static Context appContext;

    public static void init(Context context, SharedPreferences prefs) {
        appContext = context.getApplicationContext();

        if (!prefs.contains(KEY_THEME)) {
            int defaultTheme = resolveDefaultTheme(context);
            Utils.savePrefs(prefs.edit().putInt(KEY_THEME, defaultTheme));
        }

        CURRENT = prefs.getInt(KEY_THEME, LIGHT);
    }

    private static int resolveDefaultTheme(Context context) {
        if (android.os.Build.VERSION.SDK_INT < 21) {
            return DARK;
        }
        Boolean systemDark = resolveSystemNightMode(context);
        if (systemDark != null) {
            return systemDark ? DARK : LIGHT;
        }
        return LIGHT;
    }

    private static Boolean resolveSystemNightMode(Context context) {
        try {
            android.content.res.Configuration config = context.getResources().getConfiguration();
            java.lang.reflect.Field uiModeField = android.content.res.Configuration.class.getField("uiMode");
            int uiMode = uiModeField.getInt(config);

            final int UI_MODE_NIGHT_MASK = 0x30;
            final int UI_MODE_NIGHT_YES = 0x20;
            int nightBits = uiMode & UI_MODE_NIGHT_MASK;
            if (nightBits == 0) return null;
            return nightBits == UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isDark() {
        return CURRENT == DARK;
    }

    private static int color(int resId) {
        return appContext.getResources().getColor(resId);
    }

    private static int color(int lightResId, int darkResId) {
        return color(isDark() ? darkResId : lightResId);
    }

    public static int windowBackground() {
        return color(R.color.window_background_light, R.color.window_background_dark);
    }

    public static int tabRowBackground() {
        return color(R.color.tab_row_background_light, R.color.tab_row_background_dark);
    }

    public static int buttonNormal() {
        return color(R.color.button_normal_light, R.color.button_normal_dark);
    }

    public static int buttonPressed() {
        return color(R.color.button_pressed_light, R.color.button_pressed_dark);
    }

    public static int buttonFocused() {
        return color(R.color.button_focused_light, R.color.button_focused_dark);
    }

    public static int editTextNormal() {
        return color(R.color.edittext_normal_light, R.color.edittext_normal_dark);
    }

    public static int editTextFocused() {
        return color(R.color.edittext_focused_light, R.color.edittext_focused_dark);
    }

    public static int listItemPressed() {
        return color(R.color.list_item_pressed_light, R.color.list_item_pressed_dark);
    }

    public static int listItemFocused() {
        return color(R.color.list_item_focused_light, R.color.list_item_focused_dark);
    }

    private static int listItemNormal() {
        return color(R.color.list_item_normal);
    }

    public static int textPrimary() {
        return color(R.color.text_primary_light, R.color.text_primary_dark);
    }

    public static int textSecondary() {
        return color(R.color.text_secondary_light, R.color.text_secondary_dark);
    }

    public static int textHint() {
        return color(R.color.text_hint_light, R.color.text_hint_dark);
    }

    public static int divider() {
        return color(R.color.divider_light, R.color.divider_dark);
    }

    public static int linkColor() {
        return color(R.color.link_color_light, R.color.link_color_dark);
    }

    public static void applyListItem(android.view.View itemRoot) {
        android.widget.TextView name = (android.widget.TextView) itemRoot.findViewById(R.id.item_name);
        android.widget.TextView version = (android.widget.TextView) itemRoot.findViewById(R.id.item_version);
        android.widget.TextView rating = (android.widget.TextView) itemRoot.findViewById(R.id.item_rating);
        if (name != null) name.setTextColor(textPrimary());
        if (version != null) version.setTextColor(textSecondary());
        if (rating != null) rating.setTextColor(textPrimary());

        itemRoot.setBackgroundDrawable(rowSelectorBackground());
        applyFonts(itemRoot);
    }

    public static void applyDivider(android.view.View dividerView) {
        if (dividerView != null) dividerView.setBackgroundColor(divider());
    }

    public static void applyFooter(android.view.View footerRoot) {
        android.widget.TextView pageText = (android.widget.TextView) footerRoot.findViewById(R.id.footer_page_text);
        if (pageText != null) pageText.setTextColor(textSecondary());

        android.view.View prevButton = footerRoot.findViewById(R.id.footer_prev);
        android.view.View nextButton = footerRoot.findViewById(R.id.footer_next);
        if (prevButton != null) prevButton.setBackgroundDrawable(rowSelectorBackground());
        if (nextButton != null) nextButton.setBackgroundDrawable(rowSelectorBackground());
        applyFonts(footerRoot);
    }

    private static android.graphics.drawable.GradientDrawable flatShape(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setColor(color);
        return shape;
    }

    public static android.graphics.drawable.StateListDrawable buttonBackground() {
        android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed}, flatShape(buttonPressed()));
        selector.addState(new int[]{android.R.attr.state_focused}, flatShape(buttonFocused()));
        selector.addState(new int[]{}, flatShape(buttonNormal()));
        return selector;
    }

    public static android.graphics.drawable.StateListDrawable rowSelectorBackground() {
        android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed}, flatShape(listItemPressed()));
        selector.addState(new int[]{android.R.attr.state_focused}, flatShape(listItemFocused()));
        selector.addState(new int[]{android.R.attr.state_selected}, flatShape(listItemFocused()));
        selector.addState(new int[]{}, flatShape(listItemNormal()));
        return selector;
    }

    public static android.graphics.drawable.StateListDrawable editTextBackground() {
        android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_focused}, flatShape(editTextFocused()));
        selector.addState(new int[]{}, flatShape(editTextNormal()));
        return selector;
    }

    public static int dpToPx(android.content.Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private static final String FONT_REGULAR = "fonts/DroidSans.ttf";
    private static final String FONT_BOLD = "fonts/DroidSans-Bold.ttf";

    private static android.graphics.Typeface regularTypeface;
    private static android.graphics.Typeface boldTypeface;

    public static android.graphics.Typeface regularFont() {
        if (regularTypeface == null) {
            regularTypeface = loadFont(FONT_REGULAR, android.graphics.Typeface.DEFAULT);
        }
        return regularTypeface;
    }

    public static android.graphics.Typeface boldFont() {
        if (boldTypeface == null) {
            boldTypeface = loadFont(FONT_BOLD, android.graphics.Typeface.DEFAULT_BOLD);
        }
        return boldTypeface;
    }

    private static android.graphics.Typeface loadFont(String assetPath, android.graphics.Typeface fallback) {
        if (appContext == null) return fallback;
        try {
            return android.graphics.Typeface.createFromAsset(appContext.getAssets(), assetPath);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void applyFont(android.widget.TextView tv) {
        if (tv == null) return;
        android.graphics.Typeface current = tv.getTypeface();
        boolean bold = current != null && current.isBold();
        boolean italic = current != null && current.isItalic();
        android.graphics.Typeface base = bold ? boldFont() : regularFont();
        if (italic) {
            tv.setTypeface(android.graphics.Typeface.create(base, android.graphics.Typeface.ITALIC));
        } else {
            tv.setTypeface(base);
        }
    }

    public static void applyFonts(android.view.View root) {
        if (root == null) return;
        if (root instanceof android.widget.TextView) {
            applyFont((android.widget.TextView) root);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyFonts(group.getChildAt(i));
            }
        }
    }
}
