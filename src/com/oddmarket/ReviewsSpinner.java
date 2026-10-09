package com.oddmarket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class ReviewsSpinner extends View {

    private static final float SIZE_DP = 28f;
    private static final float STROKE_DP = 3f;
    private static final float SWEEP_DEG = 100f;
    private static final long PERIOD_MS = 900L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int sizePx;
    private final float strokePx;
    private GhostTitle alphaSource;
    private boolean tickPending = false;

    private final Runnable tick = new Runnable() {
        public void run() {
            tickPending = false;
            invalidate();
        }
    };

    public ReviewsSpinner(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        sizePx = Math.round(SIZE_DP * d);
        strokePx = Math.max(STROKE_DP * d, 2f);
        init();
    }

    public ReviewsSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = context.getResources().getDisplayMetrics().density;
        sizePx = Math.round(SIZE_DP * d);
        strokePx = Math.max(STROKE_DP * d, 2f);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(strokePx);
        paint.setColor(Theme.PROGRESS_COLOR);
    }

    public void setAlphaSource(GhostTitle source) {
        alphaSource = source;
    }

    private int lineAlpha() {
        if (alphaSource != null) return alphaSource.lineAlpha();
        return Utils.isBlurEnabled(getContext()) ? 209 : 255;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(sizePx, sizePx);
    }

    @Override
    protected void onDraw(Canvas c) {
        float half = strokePx / 2f;
        oval.set(half, half, sizePx - half, sizePx - half);
        float angle = ((SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS) * 360f;
        paint.setColor(Theme.PROGRESS_COLOR);
        paint.setAlpha(lineAlpha());
        c.drawArc(oval, angle, SWEEP_DEG, false, paint);
        if (!tickPending) {
            tickPending = true;
            Utils.postFrame(this, tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        tickPending = false;
        super.onDetachedFromWindow();
    }
}
