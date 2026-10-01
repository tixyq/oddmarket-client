package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class GhostTitle extends View {

    public static final int MIN_HEIGHT_DP = 47;
    public static final int MAX_HEIGHT_DP = 50;

    private static final float HEIGHT_FRACTION = 0.09f;

    public static final int TOUCH_SLOP_DP = 8;

    public static final int MODE_AUTO = 0;

    public static final int MODE_NORMAL = 1;

    public static final int MODE_HIDDEN = 2;

    private static final int ANIM_MS = 200;

    private static final float PROGRESS_DP = 3f;
    private static final long SWEEP_MS = 1100L;

    private static final int SCRIM_ALPHA = 0x18;
    private static final float SCRIM_HEIGHT_DP = 9f;

    private static final int GLASS_SEE_THROUGH = 46;
    private static final int BLUR_FIRST_DIV = 16;
    private static final int BLUR_STEPS = 2;

    private static final int BLUR_REAL_FPS = 10;
    private static final long BLUR_REAL_MS = 1000L / BLUR_REAL_FPS;
    private static final long BLUR_TICK_MS = 66L;
    private static final long BLUR_SAFETY_MS = 250L;
    // The refresh interval stretches to ~3x the real capture cost (never below BLUR_REAL_MS, never
    // above this), so a slow device spends at most about a third of its time on the backdrop.
    private static final long BLUR_MAX_MS = 400L;

    // Title transparency is quantised to this many steps.
    private static final int TITLE_FADE_STEPS = 10;

    public static final int ID_BACK = -1;
    public static final int ID_MENU = -2;

    private static final int KIND_BACK = 0;
    private static final int KIND_MENU = 1;

    private static final int MASK = 0xff;

    private static final float UNIT = 100f;

    private static final float[] SOFT_POS = {0f, 0.3f, 0.6f, 0.85f, 1f};
    private static final float[] SOFT_ALPHA = {1f, 1f, 0.85f, 0.45f, 0f};

    private static final float TITLE_SP = 17.5f;

    // Title text is pinned to the zero-scroll backing: in MODE_AUTO it moves 1:1 with the content
    // (no per-frame easing maths, so it cannot lag or jump) and only its transparency is animated.
    // The fade is finished after this fraction of the bar height has been scrolled.
    private static final float TITLE_FADE_FRACTION = 0.6f;

    private static final class Btn {
        int id;
        int kind;
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
    private final List<Btn> visibleBtns = new ArrayList<Btn>();

    private boolean showBack = false;
    private boolean showMenu = false;

    private CharSequence title = "";
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
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect blurSrc = new Rect();
    private final Rect blurDst = new Rect();
    private Bitmap[] blurBmp;
    private Canvas[] blurCanvas;
    private int blurForWidth = -1;
    private boolean blurBroken = false;
    private long blurIntervalMs = BLUR_REAL_MS;
    private boolean dockAnimating = false;
    private static final long DOCK_SETTLE_MS = 140L;
    private final Runnable dockSettle = new Runnable() {
        public void run() {
            if (!dockAnimating) return;
            dockAnimating = false;
            blurDirty = true;
            invalidate();
        }
    };
    private boolean blurValid = false;
    private boolean blurPending = false;
    private boolean blurMoving = false;
    private boolean blurDirty = true;
    private boolean blurEnabled = true;
    private boolean animEnabled = true;
    private long blurCaptureMs = 0L;
    private Bitmap[] blurFinal;
    private Canvas[] blurFinalCanvas;
    private int[][] blurPx;
    private int blurCur = 0;
    private boolean selfPass = false;
    private boolean progressTickPending = false;
    private final Runnable blurRefresh = new Runnable() {
        public void run() {
            blurPending = false;
            selfPass = true;
            invalidate();
        }
    };
    private final Runnable progressTick = new Runnable() {
        public void run() {
            progressTickPending = false;
            selfPass = true;
            invalidate();
        }
    };
    private final ViewTreeObserver.OnPreDrawListener preDrawListener =
            new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    sampleScrollFrame();
                    if (selfPass) {
                        selfPass = false;
                        return true;
                    }
                    if (blurBroken || !blurEnabled || getVisibility() != View.VISIBLE || getWidth() <= 0) return true;
                    blurDirty = true;
                    // The backdrop is frozen while the search box animates; it refreshes right after.
                    if (dockAnimating) return true;
                    long age = SystemClock.uptimeMillis() - blurCaptureMs;
                    if (age >= blurIntervalMs) {
                        invalidate();
                    } else if (!blurPending) {
                        blurPending = true;
                        postDelayed(blurRefresh, blurIntervalMs - age);
                    }
                    return true;
                }
            };
    // FPS probe: frames drawn while the content is being scrolled feed PerfGuard.
    private static final long SCROLL_RECENT_MS = 120L;
    private long lastScrollMs = 0L;

    private void sampleScrollFrame() {
        if (SystemClock.uptimeMillis() - lastScrollMs > SCROLL_RECENT_MS) return;
        if (PerfGuard.onMovingFrame(getContext())) {
            post(new Runnable() {
                public void run() {
                    refreshBlurEnabled();
                    refreshAnimEnabled();
                }
            });
        }
    }

    private float searchDockP = 0f;
    private final Path path = new Path();
    private final Paint progressFillPaint = new Paint();
    private final Paint progressTrackPaint = new Paint();
    private final Paint idleLinePaint = new Paint();

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
    private int titleScrollPx = 0;

    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            new ViewTreeObserver.OnScrollChangedListener() {
                public void onScrollChanged() {
                    handleScroll();
                }
            };

    private GhostTitle(Activity activity) {
        super(activity);
        this.activity = activity;
        this.blurEnabled = Utils.isBlurEnabled(activity);
        this.animEnabled = Utils.isAnimEnabled(activity);
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
        idleLinePaint.setColor(Theme.PROGRESS_COLOR);
        progressTrackPaint.setColor((Theme.PROGRESS_COLOR & 0x00FFFFFF) | 0x33000000);

        refreshTheme();
    }

    public static int heightDp(Context c) {
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
        installMenuKeyHook(activity);
        return ghost;
    }

    private static void installMenuKeyHook(final Activity activity) {
        try {
            final Window w = activity.getWindow();
            final Window.Callback orig = w.getCallback();
            if (orig == null || Proxy.isProxyClass(orig.getClass())) return;
            w.setCallback((Window.Callback) Proxy.newProxyInstance(
                    GhostTitle.class.getClassLoader(), new Class[]{Window.Callback.class},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                            if ("dispatchKeyEvent".equals(m.getName()) && a != null && a.length == 1
                                    && a[0] instanceof KeyEvent) {
                                KeyEvent e = (KeyEvent) a[0];
                                if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                                    if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                                        openMenu(activity);
                                    }
                                    return Boolean.TRUE;
                                }
                            }
                            try {
                                return m.invoke(orig, a);
                            } catch (InvocationTargetException ex) {
                                throw ex.getCause() != null ? ex.getCause() : ex;
                            }
                        }
                    }));
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "GhostTitle.installMenuKeyHook failed", e);
        }
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

    public int getLeftReservePx() {
        return showBack ? reserveEdgePx() + reserveButtonPx() : 0;
    }

    public int getRightReservePx() {
        return reserveEdgePx() + (showMenu ? reserveButtonPx() : 0);
    }

    private int reserveEdgePx() {
        return (int) (4 * density);
    }

    private int reserveButtonPx() {
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        return (int) ((w / density <= 320f ? 44 : 48) * density);
    }

    public GhostTitle setMenuVisible(boolean visible) {
        showMenu = visible;
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

    public GhostTitle hideProgress() {
        if (!progressVisible) return this;
        progressVisible = false;
        invalidate();
        return this;
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

    public void refreshTheme() {
        iconColor = Theme.textPrimary();
        textColor = iconColor;
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
        float h = SCRIM_HEIGHT_DP * density;
        scrimPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                Color.argb(SCRIM_ALPHA, 0, 0, 0),
                Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        PerfGuard.onScreenAttached();
        blurDirty = true;
        refreshBlurEnabled();
        refreshAnimEnabled();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            refreshBlurEnabled();
            refreshAnimEnabled();
        }
    }

    public void refreshAnimEnabled() {
        boolean on = Utils.isAnimEnabled(getContext());
        if (on == animEnabled) return;
        animEnabled = on;
        if (!on) {
            hideP = hideTarget;
            lastAnimTime = 0L;
        }
        invalidate();
    }

    public void refreshBlurEnabled() {
        boolean on = Utils.isBlurEnabled(getContext());
        if (on == blurEnabled) return;
        blurEnabled = on;
        blurDirty = true;
        if (!on) {
            removeCallbacks(blurRefresh);
            blurPending = false;
            blurMoving = false;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blurRefresh);
        blurPending = false;
        ViewTreeObserver o = getViewTreeObserver();
        if (o.isAlive()) {
            o.removeOnScrollChangedListener(scrollListener);
            o.removeOnPreDrawListener(preDrawListener);
        }
        removeCallbacks(progressTick);
        progressTickPending = false;
        super.onDetachedFromWindow();
    }

    private void applyTarget(float t) {
        hideTarget = t;
        if (getWidth() == 0 || !animEnabled) {
            hideP = t;
        }
        invalidate();
    }

    private void handleScroll() {
        if (scrollTarget == null) return;
        int y = scrollTarget.getScrollY();
        if (y != lastScrollY) lastScrollMs = SystemClock.uptimeMillis();
        if (mode != MODE_AUTO) {
            lastScrollY = y;
            invalidate();
            return;
        }
        if (y == lastScrollY) return;
        lastScrollY = y;
        syncToScroll(y);
    }

    private void syncToScroll(int y) {
        float p = (y <= 0) ? 0f : Math.min(1f, y / (float) barPx);
        int oldPx = titleScrollPx;
        titleScrollPx = (y <= 0) ? 0 : Math.min(y, barPx);
        hideP = p;
        hideTarget = p;
        lastAnimTime = 0L;
        // Once the title has left the bar, scrolling changes nothing in this view (the blurred
        // backdrop refreshes itself through the pre-draw listener), so no redraw is needed.
        if (titleScrollPx != oldPx || mode != MODE_AUTO) invalidate();
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

        if (showBack) backBtn.bounds.set(edge, 0, edge + btnW, h);

        if (showMenu) menuBtn.bounds.set(w - edge - btnW, 0, w - edge, h);

        visibleBtns.clear();
        if (showBack) visibleBtns.add(backBtn);
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

        drawBlurBackdrop(c, w, h);
        surfacePaint.setAlpha((blurEnabled && !blurBroken) ? 255 - GLASS_SEE_THROUGH : 255);
        c.drawRect(0f, 0f, w, h, surfacePaint);
        c.drawRect(0f, 0f, w, SCRIM_HEIGHT_DP * density, scrimPaint);

        List<Btn> buttons = visibleButtons();

        float btnCy = h / 2f;
        float btnShadowR = 24f * density * shadowScale;

        float cy;
        int titleAlpha;
        if (mode == MODE_AUTO) {
            // 1:1 with the scrolled content; transparency only (none at all when animations are off)
            cy = h / 2f - titleScrollPx;
            float fade = 1f;
            if (animEnabled) fade = 1f - Math.min(1f, titleScrollPx / (h * TITLE_FADE_FRACTION));
            fade *= (1f - Math.min(1f, searchDockP * 2f));
            fade = Math.round(fade * TITLE_FADE_STEPS) / (float) TITLE_FADE_STEPS;
            titleAlpha = (int) (255 * fade + 0.5f);
        } else {
            cy = h / 2f - hid * h * 0.6f;
            titleAlpha = (int) (255 * (1f - hid) * (1f - Math.min(1f, searchDockP * 2f)));
        }

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
            if (baseline + fontMetrics.descent > 0f) {
                c.drawText(text, 0, text.length(), titleX, baseline, textPaint);
            }
        }

        drawProgress(c, w);
    }

    public GhostTitle setSearchDockProgress(float p) {
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        if (p == searchDockP) return this;
        searchDockP = p;
        boolean anim = p > 0f && p < 1f;
        boolean animChanged = anim != dockAnimating;
        dockAnimating = anim;
        // The transition is scroll-driven, so "finished" can't be detected: the backdrop stays frozen
        // only while p keeps changing and is released shortly after it stops.
        removeCallbacks(dockSettle);
        if (anim) postDelayed(dockSettle, DOCK_SETTLE_MS);
        if (animChanged && !anim) blurDirty = true;
        boolean titleShown = title != null && title.length() > 0
                && (mode != MODE_AUTO || titleScrollPx < barPx);
        if (titleShown || animChanged) invalidate();
        return this;
    }

    private boolean ensureBlurBuffers(int w) {
        if (blurBmp != null && blurForWidth == w) return true;
        blurBmp = null;
        blurCanvas = null;
        blurFinal = null;
        blurFinalCanvas = null;
        blurPx = null;
        blurValid = false;
        blurMoving = false;
        if (w <= 0 || barPx <= 0) return false;
        int bw = Math.max(2, (w + BLUR_FIRST_DIV - 1) / BLUR_FIRST_DIV);
        int bh = Math.max(2, (barPx + BLUR_FIRST_DIV - 1) / BLUR_FIRST_DIV);
        Bitmap[] b = new Bitmap[BLUR_STEPS + 1];
        Canvas[] cv = new Canvas[BLUR_STEPS + 1];
        for (int i = 0; i <= BLUR_STEPS; i++) {
            b[i] = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
            cv[i] = new Canvas(b[i]);
            bw = Math.max(2, bw / 2);
            bh = Math.max(2, bh / 2);
        }
        blurBmp = b;
        blurCanvas = cv;
        int fw = b[BLUR_STEPS].getWidth();
        int fh = b[BLUR_STEPS].getHeight();
        blurFinal = new Bitmap[2];
        blurFinalCanvas = new Canvas[2];
        blurPx = new int[2][fw * fh];
        for (int i = 0; i < 2; i++) {
            blurFinal[i] = Bitmap.createBitmap(fw, fh, Bitmap.Config.RGB_565);
            blurFinalCanvas[i] = new Canvas(blurFinal[i]);
        }
        blurForWidth = w;
        return true;
    }

    private void drawBlurBackdrop(Canvas c, int w, int h) {
        if (blurBroken || !blurEnabled) return;
        try {
            if (!ensureBlurBuffers(w)) return;
            ViewGroup parent = (ViewGroup) getParent();
            if (parent == null) return;

            long now = SystemClock.uptimeMillis();
            long age = now - blurCaptureMs;
            boolean captured = false;
            if (!blurValid || (!dockAnimating && (blurDirty || age >= BLUR_SAFETY_MS) && age >= blurIntervalMs)) {
                blurDirty = false;
                long t0 = SystemClock.uptimeMillis();
                captureBlur(parent, w, h);
                long cost = SystemClock.uptimeMillis() - t0;
                blurCaptureMs = now;
                captured = true;
                long want = cost * 3L;
                blurIntervalMs = want < BLUR_REAL_MS ? BLUR_REAL_MS : (want > BLUR_MAX_MS ? BLUR_MAX_MS : want);
            }

            float a = 1f;
            if (blurMoving) a = Math.min(1f, (now - blurCaptureMs) / (float) blurIntervalMs);
            Bitmap cur = blurFinal[blurCur];
            Bitmap prev = blurFinal[1 - blurCur];
            blurSrc.set(0, 0, cur.getWidth(), cur.getHeight());
            blurDst.set(0, 0, w, h);
            if (a < 1f) {
                blurPaint.setAlpha(255);
                c.drawBitmap(prev, blurSrc, blurDst, blurPaint);
            }
            blurPaint.setAlpha(a < 1f ? (int) (a * 255f + 0.5f) : 255);
            c.drawBitmap(cur, blurSrc, blurDst, blurPaint);

            if (blurMoving && a >= 1f && !captured) blurMoving = false;
            long delay = -1L;
            if (blurMoving) delay = BLUR_TICK_MS;
            if (blurDirty && !captured && !dockAnimating) {
                long rest = Math.max(1L, blurIntervalMs - age);
                delay = (delay < 0L) ? rest : Math.min(delay, rest);
            }
            if (delay > 0L && !blurPending) {
                blurPending = true;
                postDelayed(blurRefresh, delay);
            }
        } catch (Throwable t) {
            blurBroken = true;
            FileLogger.w(Utils.TAG, "GhostTitle: backdrop blur disabled", t);
        }
    }

    private void captureBlur(ViewGroup parent, int w, int h) {
        Bitmap b0 = blurBmp[0];
        Canvas c0 = blurCanvas[0];
        c0.drawColor(Theme.windowBackground() | 0xFF000000);
        c0.save();
        c0.scale(b0.getWidth() / (float) w, b0.getHeight() / (float) h);
        int self = parent.indexOfChild(this);
        int myTop = getTop();
        for (int i = 0; i < self; i++) {
            View v = parent.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;

            if (v.getBottom() <= myTop || v.getTop() >= myTop + h) continue;
            c0.save();
            c0.translate(v.getLeft() - v.getScrollX() - getLeft(), v.getTop() - v.getScrollY() - myTop);
            v.draw(c0);
            c0.restore();
        }
        c0.restore();

        blurPaint.setAlpha(255);
        for (int i = 1; i <= BLUR_STEPS; i++) {
            Bitmap src = blurBmp[i - 1];
            Bitmap dst = blurBmp[i];
            blurSrc.set(0, 0, src.getWidth(), src.getHeight());
            blurDst.set(0, 0, dst.getWidth(), dst.getHeight());
            blurCanvas[i].drawBitmap(src, blurSrc, blurDst, blurPaint);
        }

        Bitmap last = blurBmp[BLUR_STEPS];
        int fw = last.getWidth();
        int fh = last.getHeight();
        if (!blurValid) {
            blurFinalCanvas[0].drawBitmap(last, 0f, 0f, null);
            blurFinalCanvas[1].drawBitmap(last, 0f, 0f, null);
            blurCur = 0;
            blurValid = true;
            blurMoving = false;
            return;
        }
        int prevIdx = blurCur;
        blurCur = 1 - blurCur;
        blurFinalCanvas[blurCur].drawBitmap(last, 0f, 0f, null);
        blurFinal[prevIdx].getPixels(blurPx[prevIdx], 0, fw, 0, 0, fw, fh);
        blurFinal[blurCur].getPixels(blurPx[blurCur], 0, fw, 0, 0, fw, fh);
        boolean same = true;
        int[] pa = blurPx[prevIdx];
        int[] pb = blurPx[blurCur];
        for (int i = 0; i < pa.length; i++) {
            if (pa[i] != pb[i]) { same = false; break; }
        }
        blurMoving = !same;
    }

    private void drawProgress(Canvas c, int w) {
        if (w <= 0) return;
        boolean dl = dlVisible;
        float top = barBottom();
        float bottom = top + Math.max(PROGRESS_DP * density, 2f);

        if (!dl && !progressVisible) {
            idleLinePaint.setAlpha(surfacePaint.getAlpha());
            c.drawRect(0f, top, w, bottom, idleLinePaint);
            return;
        }

        progressFillPaint.setAlpha(surfacePaint.getAlpha());
        c.drawRect(0f, top, w, bottom, progressTrackPaint);

        boolean indeterminate = dl ? dlIndeterminate : progressIndeterminate;
        int value = dl ? dlValue : progressValue;
        int max = dl ? 100 : progressMax;
        long start = dl ? dlStart : progressStart;

        if (indeterminate) {
            float seg = w * 0.35f;
            float t = ((SystemClock.uptimeMillis() - start) % SWEEP_MS) / (float) SWEEP_MS;
            float left = -seg + t * (w + seg);
            c.save();
            c.clipRect(0f, top, w, bottom);
            c.drawRect(left, top, left + seg, bottom, progressFillPaint);
            c.restore();
            if (!progressTickPending) {
                progressTickPending = true;
                Utils.postFrame(this, progressTick);
            }
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
        if (b.id == ID_BACK) {
            activity.finish();
        } else if (b.id == ID_MENU) {
            openMenu(activity);
        }
    }

    static void openMenu(Activity activity) {
        if (PopupMenuCompat.show(activity)) {
            return;
        }
        activity.openOptionsMenu();
    }
}
