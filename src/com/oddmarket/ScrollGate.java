package com.oddmarket;

import android.os.SystemClock;

/**
 * "Don't stream textures while the camera is moving": screens call touch() while their list scrolls,
 * icon loaders call awaitIdle() on a worker thread before they start (or drop) a request.
 */
final class ScrollGate {

    private static final long IDLE_MS = 150L;
    private static final long MAX_WAIT_MS = 1500L;

    private static volatile long lastScroll = 0L;

    private ScrollGate() {}

    /** For ListViews: any scroll movement keeps the gate closed. */
    static final android.widget.AbsListView.OnScrollListener LIST = new android.widget.AbsListView.OnScrollListener() {
        public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            if (scrollState != SCROLL_STATE_IDLE) touch();
        }

        public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
            touch();
        }
    };

    static void touch() {
        lastScroll = SystemClock.uptimeMillis();
    }

    /** Worker threads only. Returns once scrolling has been quiet for a moment (or after the cap). */
    static void awaitIdle() {
        long start = SystemClock.uptimeMillis();
        for (;;) {
            long now = SystemClock.uptimeMillis();
            if (now - lastScroll >= IDLE_MS || now - start >= MAX_WAIT_MS) return;
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
