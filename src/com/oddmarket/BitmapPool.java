package com.oddmarket;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Small pool of icon bitmaps for BitmapFactory.Options.inBitmap (API 11+): decoding a new icon into
 * the memory of an evicted one avoids an allocation + GC per icon.
 *
 * A bitmap is only ever recycled for new content when no ImageView still shows it - hidden screens
 * keep their views, so every attach/detach of a pooled icon is tracked here.
 */
final class BitmapPool {

    private static final int MAX_ITEMS = 6;
    private static final int MAX_ITEM_BYTES = 128 * 1024;
    private static final long MIN_AGE_MS = 400L;

    private static final class Entry {
        final Bitmap bmp;
        final long since;

        Entry(Bitmap bmp) {
            this.bmp = bmp;
            this.since = SystemClock.uptimeMillis();
        }
    }

    private static final ArrayList<Entry> POOL = new ArrayList<Entry>();
    /** Bitmaps decoded as reusable icons. */
    private static final Map<Bitmap, Boolean> POOLABLE =
            Collections.synchronizedMap(new WeakHashMap<Bitmap, Boolean>());
    /** What each live ImageView currently shows (icons only). */
    private static final Map<ImageView, Bitmap> SHOWN =
            Collections.synchronizedMap(new WeakHashMap<ImageView, Bitmap>());

    private static Field fMutable;
    private static Field fInBitmap;
    private static boolean reflected;

    private BitmapPool() {}

    static boolean supported() {
        return android.os.Build.VERSION.SDK_INT >= 11;
    }

    private static synchronized void reflect() {
        if (reflected) return;
        reflected = true;
        try {
            fMutable = BitmapFactory.Options.class.getField("inMutable");
            fInBitmap = BitmapFactory.Options.class.getField("inBitmap");
        } catch (Throwable t) {
            fMutable = null;
            fInBitmap = null;
        }
    }

    /**
     * Prepares decode options for a pooled icon: mutable (so it can be reused later) and, when the pool
     * has a fitting bitmap, inBitmap. Returns the bitmap handed to the decoder, or null.
     */
    static Bitmap prepare(BitmapFactory.Options o, int outW, int outH, int sample) {
        if (!supported()) return null;
        reflect();
        if (fMutable == null || fInBitmap == null) return null;
        try {
            fMutable.setBoolean(o, true);
            int w = (outW + sample - 1) / sample;
            int h = (outH + sample - 1) / sample;
            Bitmap candidate = obtain(w, h, sample);
            if (candidate != null) {
                fInBitmap.set(o, candidate);
                return candidate;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The decoder refused the pooled bitmap: decode again without it. */
    static void dropReuse(BitmapFactory.Options o) {
        try {
            if (fInBitmap != null) fInBitmap.set(o, null);
        } catch (Throwable ignored) {
        }
    }

    static void markPoolable(Bitmap b) {
        if (b != null && supported()) POOLABLE.put(b, Boolean.TRUE);
    }

    private static boolean isInUse(Bitmap b) {
        synchronized (SHOWN) {
            for (Bitmap shown : SHOWN.values()) {
                if (shown == b) return true;
            }
        }
        return false;
    }

    private static Bitmap obtain(int w, int h, int sample) {
        long now = SystemClock.uptimeMillis();
        synchronized (POOL) {
            for (Iterator<Entry> it = POOL.iterator(); it.hasNext();) {
                Entry e = it.next();
                Bitmap b = e.bmp;
                if (b.isRecycled() || !b.isMutable() || isInUse(b)) {
                    it.remove();
                    continue;
                }
                if (now - e.since < MIN_AGE_MS) continue;
                boolean fits;
                if (android.os.Build.VERSION.SDK_INT >= 19) {
                    fits = b.getWidth() * b.getHeight() * 4 >= w * h * 4;
                } else {
                    // Before KitKat: exactly the same size, no down-sampling, same config.
                    fits = sample == 1 && b.getWidth() == w && b.getHeight() == h;
                }
                if (fits && b.getConfig() == Bitmap.Config.ARGB_8888) {
                    it.remove();
                    return b;
                }
            }
        }
        return null;
    }

    /** A bitmap left the icon cache: keep it for reuse if nothing shows it. */
    static void offer(Bitmap b) {
        if (b == null || !supported() || b.isRecycled() || !b.isMutable()) return;
        if (!POOLABLE.containsKey(b)) return;
        if (b.getRowBytes() * b.getHeight() > MAX_ITEM_BYTES) return;
        synchronized (POOL) {
            for (int i = 0; i < POOL.size(); i++) {
                if (POOL.get(i).bmp == b) return;
            }
            if (POOL.size() >= MAX_ITEMS) POOL.remove(0);
            POOL.add(new Entry(b));
        }
    }

    static void attach(ImageView v, Bitmap b) {
        if (v == null) return;
        if (b != null && POOLABLE.containsKey(b)) SHOWN.put(v, b);
        else SHOWN.remove(v);
    }

    static void detach(ImageView v) {
        if (v != null) SHOWN.remove(v);
    }

    static void clear() {
        synchronized (POOL) {
            POOL.clear();
        }
    }
}
