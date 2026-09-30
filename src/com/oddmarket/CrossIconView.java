package com.oddmarket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class CrossIconView extends View {

    private static final float ARM_DP = 4f;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float arm;

    public CrossIconView(Context context) {
        this(context, null);
    }

    public CrossIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        float iconScale = Math.max(0.85f, Math.min(1f, GhostTitle.heightDp(context) / 48f));
        arm = ARM_DP * density * iconScale;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(Math.max(2f * density, 2f));
        int color;
        try {
            color = Theme.textPrimary();
        } catch (Exception e) {
            color = Color.BLACK;
        }
        strokePaint.setColor(color);
    }

    public void setColor(int color) {
        strokePaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        c.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, strokePaint);
        c.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, strokePaint);
    }
}
