package com.oddmarket;

import android.content.Context;
import android.content.SharedPreferences;

public class Theme {

    public static final int LIGHT = 0;
    public static final int DARK = 1;

    public static final int PROGRESS_COLOR = 0xFF6E8DC4;

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

    public static boolean isDark() {
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
        if (prevButton != null) prevButton.setBackgroundDrawable(circleSelectorBackground());
        if (nextButton != null) nextButton.setBackgroundDrawable(circleSelectorBackground());
        applyFonts(footerRoot);
    }

    private static android.graphics.drawable.GradientDrawable flatShape(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setColor(color);
        return shape;
    }

    private static final class SoftHighlight extends android.graphics.drawable.Drawable {
        private static final float UNIT = 100f;
        private static final float REACH = 1.12f;
        private static final float[] POS = {0f, 0.3f, 0.6f, 0.85f, 1f};
        private static final float[] ALPHA = {1f, 1f, 0.85f, 0.45f, 0f};
        private static final float[] EDGE_POS = {0f, 0.15f, 0.4f, 0.7f, 1f};
        private static final float[] EDGE_ALPHA = {0f, 0.45f, 0.85f, 1f, 1f};
        private static final float FEATHER_DP = 5f;
        private static final float FEATHER_FRACTION = 0.15f;
        private static final float SQUARE_ALPHA = 0.8f;

        private final boolean round;
        private final int color;
        private final float density;
        private final android.graphics.Paint basePaint;
        private final android.graphics.Paint solidPaint = new android.graphics.Paint();
        private final android.graphics.Paint edgePaint = new android.graphics.Paint();
        private final android.graphics.Paint cornerPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint discPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float shaderFeather = -1f;

        SoftHighlight(int baseColor, int glowColor, boolean round) {
            this.round = round;
            if (!round) glowColor = withAlpha(glowColor, SQUARE_ALPHA);
            this.color = glowColor;
            this.density = (appContext != null)
                    ? appContext.getResources().getDisplayMetrics().density : 1f;
            if ((baseColor >>> 24) != 0) {
                basePaint = new android.graphics.Paint();
                basePaint.setColor(baseColor);
            } else {
                basePaint = null;
            }
            solidPaint.setColor(glowColor);
            if (round) {
                int[] colors = new int[POS.length];
                for (int i = 0; i < colors.length; i++) colors[i] = withAlpha(glowColor, ALPHA[i]);
                discPaint.setShader(new android.graphics.RadialGradient(0f, 0f, UNIT, colors, POS,
                        android.graphics.Shader.TileMode.CLAMP));
            }
        }

        private static int withAlpha(int c, float k) {
            return android.graphics.Color.argb((int) (android.graphics.Color.alpha(c) * k + 0.5f),
                    android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c));
        }

        private float featherFor(android.graphics.Rect b) {
            float f = Math.min(b.width(), b.height()) * FEATHER_FRACTION;
            return Math.min(f, FEATHER_DP * density);
        }

        private void buildEdgeShaders(float f) {
            int[] edge = new int[EDGE_POS.length];
            for (int i = 0; i < edge.length; i++) edge[i] = withAlpha(color, EDGE_ALPHA[i]);
            edgePaint.setShader(new android.graphics.LinearGradient(0f, 0f, 0f, f, edge, EDGE_POS,
                    android.graphics.Shader.TileMode.CLAMP));
            int[] corner = new int[POS.length];
            for (int i = 0; i < corner.length; i++) corner[i] = withAlpha(color, ALPHA[i]);
            cornerPaint.setShader(new android.graphics.RadialGradient(0f, 0f, f, corner, POS,
                    android.graphics.Shader.TileMode.CLAMP));
            shaderFeather = f;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            if (basePaint != null) c.drawRect(b, basePaint);
            if (round) {
                float r = Math.min(b.width(), b.height()) * 0.5f * REACH;
                c.save();
                c.clipRect(b);
                c.translate(b.exactCenterX(), b.exactCenterY());
                c.scale(r / UNIT, r / UNIT);
                c.drawCircle(0f, 0f, UNIT, discPaint);
                c.restore();
                return;
            }
            float f = featherFor(b);
            if (f < 1f) {
                c.drawRect(b, solidPaint);
                return;
            }
            if (f != shaderFeather) buildEdgeShaders(f);
            float l = b.left, t = b.top, r = b.right, bt = b.bottom;
            float w = r - l - 2f * f, h = bt - t - 2f * f;

            c.drawRect(l + f, t + f, r - f, bt - f, solidPaint);

            c.save(); c.translate(l + f, t);
            c.drawRect(0f, 0f, w, f, edgePaint); c.restore();

            c.save(); c.translate(r - f, bt); c.rotate(180f);
            c.drawRect(0f, 0f, w, f, edgePaint); c.restore();

            c.save(); c.translate(l, bt - f); c.rotate(-90f);
            c.drawRect(0f, 0f, h, f, edgePaint); c.restore();

            c.save(); c.translate(r, t + f); c.rotate(90f);
            c.drawRect(0f, 0f, h, f, edgePaint); c.restore();

            c.save(); c.translate(l + f, t + f);
            c.drawRect(-f, -f, 0f, 0f, cornerPaint); c.restore();

            c.save(); c.translate(r - f, t + f);
            c.drawRect(0f, -f, f, 0f, cornerPaint); c.restore();

            c.save(); c.translate(l + f, bt - f);
            c.drawRect(-f, 0f, 0f, f, cornerPaint); c.restore();

            c.save(); c.translate(r - f, bt - f);
            c.drawRect(0f, 0f, f, f, cornerPaint); c.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            solidPaint.setAlpha(alpha);
            edgePaint.setAlpha(alpha);
            cornerPaint.setAlpha(alpha);
            discPaint.setAlpha(alpha);
            if (basePaint != null) basePaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            solidPaint.setColorFilter(cf);
            edgePaint.setColorFilter(cf);
            cornerPaint.setColorFilter(cf);
            discPaint.setColorFilter(cf);
            if (basePaint != null) basePaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public static android.graphics.drawable.StateListDrawable buttonBackground() {
        return buttonBackground(buttonNormal());
    }

    public static android.graphics.drawable.StateListDrawable buttonBackground(int normal) {
        android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed}, new SoftHighlight(normal, buttonPressed(), false));
        selector.addState(new int[]{android.R.attr.state_focused}, new SoftHighlight(normal, buttonFocused(), false));
        selector.addState(new int[]{}, flatShape(normal));
        return selector;
    }

    public static android.graphics.drawable.StateListDrawable rowSelectorBackground() {
        int normal = listItemNormal();
        android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed}, new SoftHighlight(normal, listItemPressed(), false));
        selector.addState(new int[]{android.R.attr.state_focused}, new SoftHighlight(normal, listItemFocused(), false));
        selector.addState(new int[]{android.R.attr.state_selected}, new SoftHighlight(normal, listItemFocused(), false));
        selector.addState(new int[]{}, flatShape(normal));
        return selector;
    }

    public static android.graphics.drawable.StateListDrawable circleSelectorBackground() {
        int normal = listItemNormal();
        android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed}, new SoftHighlight(normal, listItemPressed(), true));
        selector.addState(new int[]{android.R.attr.state_focused}, new SoftHighlight(normal, listItemFocused(), true));
        selector.addState(new int[]{android.R.attr.state_selected}, new SoftHighlight(normal, listItemFocused(), true));
        selector.addState(new int[]{}, flatShape(normal));
        return selector;
    }

    public static void applySoftRows(android.view.View root) {
        if (root == null) return;
        Object tag = root.getTag();
        if ("soft_row".equals(tag)) {
            root.setBackgroundDrawable(rowSelectorBackground());
        } else if ("soft_circle".equals(tag)) {
            root.setBackgroundDrawable(circleSelectorBackground());
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applySoftRows(group.getChildAt(i));
            }
        }
    }

    public static SearchBoxBackground searchBoxBackground() {
        return new SearchBoxBackground();
    }

    public static final class SearchBoxBackground extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int pageColor;
        private final int pageFocused;
        private boolean focused = false;

        SearchBoxBackground() {
            if (isDark()) {
                pageColor = 0x0AFFFFFF;
                pageFocused = editTextFocused();
            } else {
                pageColor = 0x1A000000;
                pageFocused = editTextFocused();
            }
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            paint.setColor(focused ? pageFocused : pageColor);
            float r = 3f * android.content.res.Resources.getSystem().getDisplayMetrics().density;
            c.drawRoundRect(new android.graphics.RectF(getBounds()), r, r, paint);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean f = false;
            for (int i = 0; i < state.length; i++) {
                if (state[i] == android.R.attr.state_focused) f = true;
            }
            if (f == focused) return false;
            focused = f;
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
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

    public static final int STATUS_BAR_COLOR = 0xFF000000;

    public static void applyWindow(android.app.Activity activity) {
        if (activity == null || appContext == null) return;
        int bg = windowBackground();
        try {
            android.view.Window w = activity.getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));

            if (android.os.Build.VERSION.SDK_INT >= 21) {

                w.clearFlags(0x04000000 | 0x08000000);
                w.addFlags(0x80000000);
                invokeInt(w, "setStatusBarColor", STATUS_BAR_COLOR);
                invokeInt(w, "setNavigationBarColor", bg);
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                applySystemBarIcons(w);
            }
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                applyTaskHeader(activity, bg);
            }
        } catch (Throwable t) {
            FileLogger.w(Utils.TAG, "Theme.applyWindow failed", t);
        }
    }

    private static void applySystemBarIcons(android.view.Window w) throws Exception {
        final int LIGHT_STATUS_BAR = 0x00002000;
        final int LIGHT_NAVIGATION_BAR = 0x00000010;

        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        java.lang.reflect.Field field = android.view.WindowManager.LayoutParams.class.getField("systemUiVisibility");
        int flags = field.getInt(lp);

        flags &= ~LIGHT_STATUS_BAR;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (isDark()) flags &= ~LIGHT_NAVIGATION_BAR;
            else flags |= LIGHT_NAVIGATION_BAR;
        }
        field.setInt(lp, flags);
        w.setAttributes(lp);
    }

    private static void applyTaskHeader(android.app.Activity activity, int color) throws Exception {

        Class<?> tdCls = Class.forName("android.app.ActivityManager$TaskDescription");
        Object td = tdCls.getConstructor(String.class, android.graphics.Bitmap.class, int.class)
                .newInstance(null, null, color | 0xFF000000);
        android.app.Activity.class.getMethod("setTaskDescription", tdCls).invoke(activity, td);
    }

    private static void invokeInt(Object target, String method, int value) throws Exception {
        target.getClass().getMethod(method, int.class).invoke(target, value);
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
