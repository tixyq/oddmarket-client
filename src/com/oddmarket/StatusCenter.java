package com.oddmarket;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

final class StatusCenter {
    interface Source {
        int obstructionPx();
    }

    private static final int HALF_BOX_DP = 48;

    private StatusCenter() {
    }

    static FrameLayout.LayoutParams params() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    }

    static void place(View v, int hostHeightPx, int obstructionPx) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        float density = v.getResources().getDisplayMetrics().density;
        int height = hostHeightPx > 0 ? hostHeightPx : v.getResources().getDisplayMetrics().heightPixels;
        int obstruction = Math.max(0, obstructionPx);
        boolean strict = height / 2 - (int) (HALF_BOX_DP * density + 0.5f) >= obstruction;
        int top = strict ? 0 : obstruction / 2;
        if (lp.topMargin != top || lp.gravity != Gravity.CENTER) {
            lp.gravity = Gravity.CENTER;
            lp.topMargin = top;
            v.setLayoutParams(lp);
        }
    }

    static void track(final View host, final Source source, final View... views) {
        host.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                int obstruction = source.obstructionPx();
                int height = host.getHeight();
                for (int i = 0; i < views.length; i++) {
                    View v = views[i];
                    if (v != null && v.getVisibility() == View.VISIBLE) place(v, height, obstruction);
                }
            }
        });
    }
}
