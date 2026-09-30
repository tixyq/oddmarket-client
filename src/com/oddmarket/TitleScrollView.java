package com.oddmarket;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

public class TitleScrollView extends ScrollView {

    public TitleScrollView(Context context) {
        super(context);
        init(context);
    }

    public TitleScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TitleScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        setPadding(getPaddingLeft(), GhostTitle.heightPx(context), getPaddingRight(), getPaddingBottom());
        try {
            java.lang.reflect.Method m = android.view.ViewGroup.class.getMethod("setClipToPadding", boolean.class);
            m.invoke(this, Boolean.FALSE);
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "TitleScrollView: setClipToPadding not available", e);
        }
    }

    public int scrollbarWidthPx() {
        return getVerticalScrollbarWidth();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        int e = getHeight() - getPaddingTop() - getPaddingBottom();
        return e > 0 ? e : super.computeVerticalScrollExtent();
    }

    @Override
    protected int computeVerticalScrollRange() {
        if (getChildCount() == 0) return super.computeVerticalScrollRange();
        View c = getChildAt(0);
        int r = c.getHeight();
        int e = computeVerticalScrollExtent();
        return r > e ? r : e;
    }
}
