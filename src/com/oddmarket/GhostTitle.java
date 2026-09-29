package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

public class GhostTitle extends View {

    public static final int MIN_HEIGHT_DP = 36;
    public static final int MAX_HEIGHT_DP = 50;

    private static final float HEIGHT_FRACTION = 0.09f;

    public static final int TOUCH_SLOP_DP = 8;

    private static int forcedHeightDp = 0;

    public static final int MODE_AUTO = 0;

    public static final int MODE_NORMAL = 1;

    public static final int MODE_HIDDEN = 2;

    private static final int ANIM_MS = 200;

    private static final float PROGRESS_DP = 3f;
    private static final long SWEEP_MS = 1100L;
    private static final long FRAME_MS = 30L;

    private static final int SCRIM_ALPHA = 0x18;

    public static final int ID_BACK = -1;
    public static final int ID_MENU = -2;

    public interface Listener {
        boolean onAction(int id);
    }

    private static final int KIND_BACK = 0;
    private static final int KIND_MENU = 1;
    private static final int KIND_ICON = 2;

    private static final int MASK = 0xff;

    private static final float UNIT = 100f;

    private static final float[] SOFT_POS = {0f, 0.3f, 0.6f, 0.85f, 1f};
    private static final float[] SOFT_ALPHA = {1f, 1f, 0.85f, 0.45f, 0f};

    private static final float TITLE_SP = 17.5f;

    private static final class Btn {
        int id;
        int kind;
        Drawable icon;
        CharSequence desc;
        final Rect bounds = new Rect();
    }

    private final Activity activity;
    private final float density;
    private final int barPx;
    private final int slopPx;
    private final float iconScale;
    private final float shadowScale;

    private final Btn backBtn = new Btn();
    private final Btn menuBtn = new Btn();
    private final List<Btn> actions = new ArrayList<Btn>();
    private final List<Btn> visibleBtns = new ArrayList<Btn>();

    private boolean showBack = false;
    private boolean showMenu = false;
    private boolean scrimEnabled = true;
    private float scrimHeightDp = 9f;

    private CharSequence title = "";
    private Listener listener;

    private Integer iconOverride;
    private Integer textOverride;

    private int iconColor;
    private int textColor;
    private int pressColor;
    private Integer baseOverride;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Path path = new Path();
    private final Paint progressFillPaint = new Paint();
    private final Paint progressTrackPaint = new Paint();

    private boolean progressVisible = false;
    private boolean progressIndeterminate = false;
    private int progressMax = 100;
    private int progressValue = 0;
    private long progressStart = 0L;

    private boolean dlVisible = false;
    private boolean dlIndeterminate = false;
    private int dlValue = 0;
    private long dlStart = 0L;

    private boolean layoutDirty = true;
    private int leftReserve;
    private int rightReserve;

    private CharSequence ellipsizedText;
    private CharSequence ellipsizedSource;
    private float ellipsizedAvail = -1f;

    private Btn pressed;

    private int mode = MODE_AUTO;
    private float hideP = 0f;
    private float hideTarget = 0f;
    private long lastAnimTime = 0L;
    private View scrollTarget;
    private int lastScrollY = 0;

    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            new ViewTreeObserver.OnScrollChangedListener() {
                public void onScrollChanged() {
                    handleScroll();
                }
            };

    private GhostTitle(Activity activity) {
        super(activity);
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        int barDp = heightDp(activity);
        this.barPx = heightPx(activity);
        this.slopPx = slopPx(activity);
        this.iconScale = Math.max(0.85f, Math.min(1f, barDp / 48f));
        this.shadowScale = barDp / (float) MAX_HEIGHT_DP;

        setFocusable(false);
        setClickable(true);

        backBtn.id = ID_BACK;
        backBtn.kind = KIND_BACK;
        menuBtn.id = ID_MENU;
        menuBtn.kind = KIND_MENU;

        textPaint.setTextSize(Math.max(TITLE_SP * activity.getResources().getDisplayMetrics().scaledDensity, 14f));
        textPaint.setTypeface(Theme.regularFont());

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(Math.max(2f * density, 2f));

        pressPaint.setStyle(Paint.Style.FILL);
        surfacePaint.setStyle(Paint.Style.FILL);

        progressFillPaint.setColor(Theme.PROGRESS_COLOR);
        progressTrackPaint.setColor((Theme.PROGRESS_COLOR & 0x00FFFFFF) | 0x33000000);

        refreshTheme();
    }

    public static void setForcedHeightDp(int dp) {
        forcedHeightDp = dp;
    }

    public static int heightDp(Context c) {
        if (forcedHeightDp > 0) return Math.max(forcedHeightDp, MIN_HEIGHT_DP);
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        int dp = Math.round(m.heightPixels / m.density * HEIGHT_FRACTION);
        if (dp < MIN_HEIGHT_DP) dp = MIN_HEIGHT_DP;
        if (dp > MAX_HEIGHT_DP) dp = MAX_HEIGHT_DP;
        return dp;
    }

    public static int heightPx(Context c) {
        return (int) (heightDp(c) * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int slopPx(Context c) {
        return (int) (TOUCH_SLOP_DP * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void prepareWindow(Activity activity) {
        try {
            activity.requestWindowFeature(Window.FEATURE_NO_TITLE);
            Theme.applyWindow(activity);
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "GhostTitle.prepareWindow failed (called after setContentView?)", e);
        }
    }

    public static GhostTitle attach(Activity activity) {
        GhostTitle ghost = new GhostTitle(activity);
        ghost.setTitle(activity.getTitle());
        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, heightPx(activity) + slopPx(activity), Gravity.TOP);
        content.addView(ghost, lp);
        return ghost;
    }

    public static View insertSpacer(LinearLayout parent, int color) {
        return insertSpacer(parent, color, heightPx(parent.getContext()));
    }

    public static View insertSpacer(LinearLayout parent, int color, int heightPx) {
        View spacer = new View(parent.getContext());
        spacer.setBackgroundColor(color);
        parent.addView(spacer, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, heightPx));
        return spacer;
    }

    public static int compactHeightPx(Context c) {
        return heightPx(c);
    }

    public GhostTitle setTitle(CharSequence t) {
        this.title = (t == null) ? "" : t;
        invalidate();
        return this;
    }

    public GhostTitle setBackVisible(boolean visible) {
        showBack = visible;
        layoutDirty = true;
        invalidate();
        return this;
    }

    public GhostTitle setMenuVisible(boolean visible) {
        showMenu = visible;
        layoutDirty = true;
        invalidate();
        return this;
    }

    public GhostTitle addAction(int id, Drawable icon, CharSequence description) {
        Btn b = new Btn();
        b.id = id;
        b.kind = KIND_ICON;
        b.icon = icon.mutate();
        b.desc = description;
        actions.add(b);
        layoutDirty = true;
        invalidate();
        return this;
    }

    public GhostTitle clearActions() {
        actions.clear();
        layoutDirty = true;
        invalidate();
        return this;
    }

    public GhostTitle setMode(int m) {
        mode = m;
        if (m == MODE_NORMAL) {
            applyTarget(0f);
        } else if (m == MODE_HIDDEN) {
            applyTarget(1f);
        } else if (scrollTarget != null) {
            lastScrollY = scrollTarget.getScrollY();
            syncToScroll(lastScrollY);
        }
        return this;
    }

    public GhostTitle trackScroll(View v) {
        scrollTarget = v;
        lastScrollY = (v != null) ? v.getScrollY() : 0;
        if (mode == MODE_AUTO) syncToScroll(lastScrollY);
        return this;
    }

    public GhostTitle setBaseColor(int color) {
        baseOverride = Integer.valueOf(color);
        buildShaders();
        invalidate();
        return this;
    }

    public GhostTitle showProgress(int max) {
        progressMax = Math.max(1, max);
        progressValue = 0;
        progressIndeterminate = false;
        progressVisible = true;
        progressStart = SystemClock.uptimeMillis();
        invalidate();
        return this;
    }

    public GhostTitle showProgressIndeterminate() {
        progressIndeterminate = true;
        progressVisible = true;
        progressStart = SystemClock.uptimeMillis();
        invalidate();
        return this;
    }

    public GhostTitle setProgress(int value) {
        progressValue = Math.max(0, Math.min(progressMax, value));
        progressIndeterminate = false;
        progressVisible = true;
        invalidate();
        return this;
    }

    public GhostTitle setProgressIndeterminate(boolean indeterminate) {
        if (progressIndeterminate == indeterminate) return this;
        progressIndeterminate = indeterminate;
        invalidate();
        return this;
    }

    public GhostTitle hideProgress() {
        if (!progressVisible) return this;
        progressVisible = false;
        invalidate();
        return this;
    }

    public boolean isProgressVisible() {
        return progressVisible;
    }

    public GhostTitle setDownloadProgress(int percent, boolean indeterminate) {
        int value = Math.max(0, Math.min(100, percent));
        if (dlVisible && dlIndeterminate == indeterminate && dlValue == value) return this;
        if (!dlVisible || dlIndeterminate != indeterminate) dlStart = SystemClock.uptimeMillis();
        dlVisible = true;
        dlIndeterminate = indeterminate;
        dlValue = value;
        invalidate();
        return this;
    }

    public GhostTitle clearDownloadProgress() {
        if (!dlVisible) return this;
        dlVisible = false;
        invalidate();
        return this;
    }

    public GhostTitle setListener(Listener l) {
        listener = l;
        return this;
    }

    public GhostTitle setScrim(boolean enabled, float heightDp) {
        scrimEnabled = enabled;
        scrimHeightDp = heightDp;
        buildScrim();
        invalidate();
        return this;
    }

    public GhostTitle setIconColor(Integer color) {
        iconOverride = color;
        refreshTheme();
        return this;
    }

    public GhostTitle setTextColor(Integer color) {
        textOverride = color;
        refreshTheme();
        return this;
    }

    public void refreshTheme() {
        iconColor = (iconOverride != null) ? iconOverride.intValue() : Theme.textPrimary();
        textColor = (textOverride != null) ? textOverride.intValue() : Theme.textPrimary();
        pressColor = Theme.listItemPressed();

        buildShaders();
        invalidate();
    }

    private static Shader softShader(int r, int g, int b, int alpha) {
        int[] colors = new int[SOFT_POS.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = Color.argb((int) (alpha * SOFT_ALPHA[i] + 0.5f), r, g, b);
        }
        return new RadialGradient(0f, 0f, UNIT, colors, SOFT_POS, Shader.TileMode.CLAMP);
    }

    private void buildShaders() {
        int base = (baseOverride != null) ? baseOverride.intValue() : Theme.windowBackground();

        surfacePaint.setShader(null);
        surfacePaint.setColor(base | 0xFF000000);

        pressPaint.setShader(softShader(Color.red(pressColor), Color.green(pressColor),
                Color.blue(pressColor), 255));
        buildScrim();
    }

    private void buildScrim() {
        float h = scrimHeightDp * density;
        if (h <= 0f) {
            scrimPaint.setShader(null);
            return;
        }
        scrimPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                Color.argb(SCRIM_ALPHA, 0, 0, 0),
                Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewTreeObserver o = getViewTreeObserver();
        if (o.isAlive()) o.removeOnScrollChangedListener(scrollListener);
        super.onDetachedFromWindow();
    }

    private void applyTarget(float t) {
        hideTarget = t;
        if (getWidth() == 0) {
            hideP = t;
        }
        invalidate();
    }

    private void handleScroll() {
        if (scrollTarget == null) return;
        int y = scrollTarget.getScrollY();
        if (mode != MODE_AUTO) {
            lastScrollY = y;
            return;
        }
        if (y == lastScrollY) return;
        lastScrollY = y;
        syncToScroll(y);
    }

    private void syncToScroll(int y) {
        float p = (y <= 0) ? 0f : Math.min(1f, y / (float) barPx);
        hideP = p;
        hideTarget = p;
        lastAnimTime = 0L;
        invalidate();
    }

    private void stepAnimation() {
        if (hideP == hideTarget) {
            lastAnimTime = 0L;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (lastAnimTime == 0L) lastAnimTime = now;
        float step = (now - lastAnimTime) / (float) ANIM_MS;
        lastAnimTime = now;
        if (hideP < hideTarget) {
            hideP = Math.min(hideTarget, hideP + step);
        } else {
            hideP = Math.max(hideTarget, hideP - step);
        }
        invalidate();
    }

    private float hidden() {
        if (mode == MODE_AUTO) return hideP;
        return hideP * hideP * (3f - 2f * hideP);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutDirty = true;
    }

    private void ensureLayout() {
        if (!layoutDirty) return;
        layoutDirty = false;

        int w = getWidth();
        int h = barPx + slopPx;

        int btnW = (int) ((w / density <= 320f ? 44 : 48) * density);
        int edge = (int) (4 * density);

        leftReserve = 0;
        if (showBack) {
            backBtn.bounds.set(edge, 0, edge + btnW, h);
            leftReserve = edge + btnW;
        }

        int right = w - edge;
        if (showMenu) {
            menuBtn.bounds.set(right - btnW, 0, right, h);
            right -= btnW;
        }
        for (int i = actions.size() - 1; i >= 0; i--) {
            actions.get(i).bounds.set(right - btnW, 0, right, h);
            right -= btnW;
        }
        rightReserve = w - right;

        visibleBtns.clear();
        if (showBack) visibleBtns.add(backBtn);
        visibleBtns.addAll(actions);
        if (showMenu) visibleBtns.add(menuBtn);
    }

    private List<Btn> visibleButtons() {
        ensureLayout();
        return visibleBtns;
    }

    private CharSequence ellipsizedTitle(float avail) {
        if (ellipsizedText != null && ellipsizedSource == title && ellipsizedAvail == avail) {
            return ellipsizedText;
        }
        ellipsizedSource = title;
        ellipsizedAvail = avail;
        ellipsizedText = TextUtils.ellipsize(title, textPaint, avail, TextUtils.TruncateAt.END);
        return ellipsizedText;
    }

    @Override
    protected void onDraw(Canvas c) {
        stepAnimation();
        float hid = hidden();
        int w = getWidth();
        int h = barPx;

        c.drawRect(0f, 0f, w, h, surfacePaint);

        if (scrimEnabled && scrimHeightDp > 0f) {
            c.drawRect(0f, 0f, w, scrimHeightDp * density, scrimPaint);
        }

        List<Btn> buttons = visibleButtons();

        float btnCy = h / 2f;
        float btnShadowR = 24f * density * shadowScale;

        float cy = h / 2f - hid * h * 0.6f;
        int titleAlpha = (int) (255 * (1f - hid));

        CharSequence text = null;
        float titleX = 0f;
        if (title != null && title.length() > 0 && titleAlpha > 0) {

            float pad = 16f * density * shadowScale;
            float gap = 2f * density;
            float textX = showBack
                    ? backBtn.bounds.centerX() + btnShadowR + gap + pad
                    : 16f * density;
            float limit = w - 12f * density;
            for (int i = 0; i < buttons.size(); i++) {
                Btn b = buttons.get(i);
                if (b != backBtn) limit = Math.min(limit, b.bounds.centerX() - btnShadowR);
            }
            float avail = limit - gap - pad - textX;
            if (avail < 0f) avail = 0f;
            textPaint.setColor(textColor);
            textPaint.setAlpha((textColor >>> 24) * titleAlpha / 255);
            text = ellipsizedTitle(avail);

            titleX = textX;
        }

        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            float bx = b.bounds.centerX();
            float by = btnCy;
            if (b == pressed) {
                drawSoft(c, pressPaint, bx, by, btnShadowR, btnShadowR);
            }
            drawIcon(c, b, bx, by);
        }

        if (text != null) {
            textPaint.getFontMetrics(fontMetrics);
            float baseline = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            c.drawText(text, 0, text.length(), titleX, baseline, textPaint);
        }

        drawProgress(c, w);
    }

    private void drawProgress(Canvas c, int w) {
        boolean dl = dlVisible;
        if ((!dl && !progressVisible) || w <= 0) return;
        boolean indeterminate = dl ? dlIndeterminate : progressIndeterminate;
        int value = dl ? dlValue : progressValue;
        int max = dl ? 100 : progressMax;
        long start = dl ? dlStart : progressStart;

        float bottom = barBottom();
        float top = bottom - Math.max(PROGRESS_DP * density, 2f);
        c.drawRect(0f, top, w, bottom, progressTrackPaint);

        if (indeterminate) {
            float seg = w * 0.35f;
            float t = ((SystemClock.uptimeMillis() - start) % SWEEP_MS) / (float) SWEEP_MS;
            float left = -seg + t * (w + seg);
            c.save();
            c.clipRect(0f, top, w, bottom);
            c.drawRect(left, top, left + seg, bottom, progressFillPaint);
            c.restore();
            postInvalidateDelayed(FRAME_MS);
        } else if (value > 0) {
            c.drawRect(0f, top, w * (value / (float) max), bottom, progressFillPaint);
        }
    }

    private void drawSoft(Canvas c, Paint paint, float cx, float cy, float rx, float ry) {
        c.save();
        c.translate(cx, cy);
        c.scale(rx / UNIT, ry / UNIT);
        c.drawCircle(0f, 0f, UNIT, paint);
        c.restore();
    }

    private void drawIcon(Canvas c, Btn b, float cx, float cy) {
        float d = density * iconScale;
        strokePaint.setColor(iconColor);
        if (b.kind == KIND_BACK) {
            path.reset();
            path.moveTo(cx + 9f * d, cy);
            path.lineTo(cx - 9f * d, cy);
            path.moveTo(cx - 1f * d, cy - 8f * d);
            path.lineTo(cx - 9f * d, cy);
            path.lineTo(cx - 1f * d, cy + 8f * d);
            c.drawPath(path, strokePaint);
        } else if (b.kind == KIND_MENU) {
            float half = 9f * d;
            float gap = 6f * d;
            c.drawLine(cx - half, cy - gap, cx + half, cy - gap, strokePaint);
            c.drawLine(cx - half, cy, cx + half, cy, strokePaint);
            c.drawLine(cx - half, cy + gap, cx + half, cy + gap, strokePaint);
        } else if (b.icon != null) {
            int r = (int) (12 * d);
            b.icon.setBounds((int) cx - r, (int) cy - r, (int) cx + r, (int) cy + r);
            b.icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            b.icon.draw(c);
        }
    }

    private float barBottom() {
        return barPx;
    }

    private boolean contains(Btn b, float x, float y, int slop) {
        Rect r = b.bounds;
        int bottom = (int) barBottom() + slopPx;
        return x >= r.left - slop && x < r.right + slop && y >= r.top - slop && y < bottom + slop;
    }

    private Btn hit(float x, float y, int slop) {
        List<Btn> buttons = visibleButtons();
        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            if (contains(b, x, y, slop)) {
                return b;
            }
        }
        return null;
    }

    private void setPressedBtn(Btn b) {
        if (pressed != b) {
            pressed = b;
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getAction() & MASK;
        float x = e.getX();
        float y = e.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {

                setPressedBtn(hit(x, y, 0));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed == null) return true;
                boolean inside = contains(pressed, x, y, (int) (16 * density));
                if (!inside) {
                    setPressedBtn(null);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                Btn b = pressed;
                setPressedBtn(null);
                if (b != null) {
                    fire(b);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                setPressedBtn(null);
                return true;
            default:
                return true;
        }
    }

    private void fire(Btn b) {
        playSoundEffect(SoundEffectConstants.CLICK);
        if (listener != null && listener.onAction(b.id)) {
            return;
        }
        if (b.id == ID_BACK) {
            activity.finish();
        } else if (b.id == ID_MENU) {
            activity.openOptionsMenu();
        }
    }
}
