package com.oddmarket;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Makes small views easier to tap (touch target of at least 48dp) WITHOUT changing the layout:
 * the extra area is invisible and only exists for touch handling (via TouchDelegate on an ancestor).
 */
final class TouchExpander {

    private static final int MIN_DP = 48;
    private static final WeakHashMap<ViewGroup, Group> GROUPS = new WeakHashMap<ViewGroup, Group>();

    private TouchExpander() {}

    /** Call once per view; the ancestor is resolved after the first layout pass. */
    static void expand(final View v) {
        if (v == null) return;
        v.post(new Runnable() {
            public void run() {
                try {
                    attach(v);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static int minPx(View v) {
        return (int) (MIN_DP * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static Rect expandedRect(ViewGroup anc, View v, int min) {
        Rect r = new Rect();
        v.getDrawingRect(r);
        anc.offsetDescendantRectToMyCoords(v, r);
        int w = r.width();
        int h = r.height();
        if (w < min) {
            int d = (min - w + 1) / 2;
            r.left -= d;
            r.right += d;
        }
        if (h < min) {
            int d = (min - h + 1) / 2;
            r.top -= d;
            r.bottom += d;
        }
        return r;
    }

    private static boolean usable(ViewGroup g) {
        if (g instanceof ScrollView || g instanceof HorizontalScrollView || g instanceof AbsListView) return false;
        TouchDelegate td = g.getTouchDelegate();
        return td == null || td instanceof Group;
    }

    private static void attach(View v) {
        int min = minPx(v);
        ViewGroup fallback = null;
        ViewGroup chosen = null;
        ViewParent p = v.getParent();
        while (p instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) p;
            if (g instanceof ScrollView || g instanceof HorizontalScrollView || g instanceof AbsListView) break;
            if (usable(g)) {
                if (fallback == null) fallback = g;
                Rect r = expandedRect(g, v, min);
                if (r.left >= 0 && r.top >= 0 && r.right <= g.getWidth() && r.bottom <= g.getHeight()) {
                    chosen = g;
                    break;
                }
            }
            p = g.getParent();
        }
        if (chosen == null) chosen = fallback;
        if (chosen == null) return;

        Group group = GROUPS.get(chosen);
        if (group == null) {
            group = new Group(chosen);
            GROUPS.put(chosen, group);
            chosen.setTouchDelegate(group);
        }
        group.add(v);
    }

    private static final class Group extends TouchDelegate {
        private final ViewGroup anc;
        private final ArrayList<View> views = new ArrayList<View>();
        private View active;

        Group(ViewGroup anc) {
            super(new Rect(), anc);
            this.anc = anc;
        }

        void add(View v) {
            if (!views.contains(v)) views.add(v);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getAction() & 0xff;
            int x = (int) e.getX();
            int y = (int) e.getY();

            if (action == MotionEvent.ACTION_DOWN) {
                active = null;
                long best = Long.MAX_VALUE;
                for (int i = 0; i < views.size(); i++) {
                    View v = views.get(i);
                    if (!v.isShown() || !v.isEnabled()) continue;
                    Rect r = expandedRect(anc, v, minPx(v));
                    if (!r.contains(x, y)) continue;
                    long dx = x - r.centerX();
                    long dy = y - r.centerY();
                    long d = dx * dx + dy * dy;
                    if (d < best) {
                        best = d;
                        active = v;
                    }
                }
            }
            View target = active;
            if (target == null) return false;

            Rect r = expandedRect(anc, target, minPx(target));
            MotionEvent ev = MotionEvent.obtain(e);
            if (r.contains(x, y)) {
                ev.setLocation(target.getWidth() / 2f, target.getHeight() / 2f);
            } else {
                ev.setLocation(-100f, -100f);
            }
            boolean handled = target.dispatchTouchEvent(ev);
            ev.recycle();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) active = null;
            return handled;
        }
    }
}
