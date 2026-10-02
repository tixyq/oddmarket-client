package com.oddmarket;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

public class FilledStarSpan extends ReplacementSpan {

    private static final String SOLID = "\u2605";

    private final float relativeSize;
    private final int fillColor;

    public FilledStarSpan(float relativeSize, int fillColor) {
        this.relativeSize = relativeSize;
        this.fillColor = fillColor;
    }

    private Paint scaled(Paint src) {
        Paint p = new Paint(src);
        p.setTextSize(src.getTextSize() * relativeSize);
        return p;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        Paint p = scaled(paint);
        if (fm != null) {
            Paint.FontMetricsInt big = p.getFontMetricsInt();
            if (big.ascent < fm.ascent) fm.ascent = big.ascent;
            if (big.top < fm.top) fm.top = big.top;
            if (big.descent > fm.descent) fm.descent = big.descent;
            if (big.bottom > fm.bottom) fm.bottom = big.bottom;
        }
        return (int) Math.ceil(p.measureText(SOLID));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                     int top, int y, int bottom, Paint paint) {
        Paint p = scaled(paint);
        p.setColor(fillColor);
        canvas.drawText(SOLID, x, y, p);
    }
}
