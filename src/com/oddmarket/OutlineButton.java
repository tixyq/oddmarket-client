package com.oddmarket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.Button;

/**
 * Button whose label has a thick outline in a given colour (the button's own colour), so the text stays
 * readable and cleanly cut out where it overlaps the picture drawn on the button.
 */
public class OutlineButton extends Button {

    /** Total stroke width; half of it shows outside the glyphs. */
    private static final float OUTLINE_DP = 4f;

    private int outlineNormal;
    private int outlinePressed;
    private int outlineFocused;
    private boolean outlineEnabled;
    private boolean drawing;

    public OutlineButton(Context context) {
        super(context);
    }

    public OutlineButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public OutlineButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /** Outline colour per button state, so it always matches the background (normal / touched / focused). */
    public void setOutlineColors(int normal, int pressed, int focused) {
        outlineNormal = normal;
        outlinePressed = pressed;
        outlineFocused = focused;
        outlineEnabled = true;
        invalidate();
    }

    private int currentOutline() {
        if (isPressed()) return outlinePressed;
        if (isFocused()) return outlineFocused;
        return outlineNormal;
    }

    /** setTextColor() below invalidates; inside onDraw that would loop forever. */
    @Override
    public void invalidate() {
        if (drawing) return;
        super.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!outlineEnabled) {
            super.onDraw(canvas);
            return;
        }
        TextPaint paint = getPaint();
        int fill = getCurrentTextColor();
        Paint.Style style = paint.getStyle();
        float width = paint.getStrokeWidth();
        Paint.Join join = paint.getStrokeJoin();
        drawing = true;
        try {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(OUTLINE_DP * getResources().getDisplayMetrics().density);
            setTextColor(currentOutline());
            super.onDraw(canvas);

            paint.setStyle(style);
            paint.setStrokeJoin(join);
            paint.setStrokeWidth(width);
            setTextColor(fill);
            super.onDraw(canvas);
        } finally {
            paint.setStyle(style);
            paint.setStrokeJoin(join);
            paint.setStrokeWidth(width);
            drawing = false;
        }
    }
}
