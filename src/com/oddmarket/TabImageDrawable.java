package com.oddmarket;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TabImageDrawable extends Drawable {

    private static final Map<String, Bitmap> CACHE = new HashMap<String, Bitmap>();
    private static final Set<String> MISSING = new HashSet<String>();

    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    private TabImageDrawable(Bitmap bitmap) {
        this.bitmap = bitmap;
        src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    public static TabImageDrawable load(Context context, String name) {
        Bitmap b = bitmapFor(context, name);
        return b == null ? null : new TabImageDrawable(b);
    }

    private static synchronized Bitmap bitmapFor(Context context, String name) {
        if (MISSING.contains(name)) return null;
        Bitmap b = CACHE.get(name);
        if (b != null) return b;
        try {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id != 0) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                b = BitmapFactory.decodeResource(context.getResources(), id, o);
            }
        } catch (Throwable t) {
            b = null;
        }
        if (b == null) {
            MISSING.add(name);
            return null;
        }
        CACHE.put(name, b);
        return b;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect r = getBounds();
        int h = r.height();
        if (h <= 0 || bitmap.getHeight() <= 0) return;
        int w = Math.round(bitmap.getWidth() * (h / (float) bitmap.getHeight()));
        dst.set(r.left, r.top, r.left + w, r.bottom);
        int save = canvas.save();
        canvas.clipRect(r);
        canvas.drawBitmap(bitmap, src, dst, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
