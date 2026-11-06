package com.oddmarket;

import android.os.SystemClock;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Image worker pool with a "newest request first" queue, like texture streaming: what scrolled into
 * view last is loaded first, requests for rows that already scrolled away wait at the back (and are
 * dropped by the loader when nobody wants them any more).
 *
 * Requests submitted in one burst (a page being bound top to bottom) form one generation and keep
 * their own order, so the top of a freshly loaded page is still loaded first.
 */
final class ImageQueue {

    private static final long BURST_GAP_MS = 60L;

    private static final AtomicLong SEQ = new AtomicLong();
    private static long generation = 0L;
    private static long lastSubmitMs = 0L;

    private ImageQueue() {}

    static ThreadPoolExecutor create(int threads) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<Runnable>());
    }

    static void submit(ThreadPoolExecutor pool, Runnable task) {
        long gen;
        synchronized (ImageQueue.class) {
            long now = SystemClock.uptimeMillis();
            if (now - lastSubmitMs > BURST_GAP_MS) generation++;
            lastSubmitMs = now;
            gen = generation;
        }
        pool.execute(new Task(task, gen, SEQ.incrementAndGet()));
    }

    private static final class Task implements Runnable, Comparable<Task> {
        final Runnable inner;
        final long gen;
        final long seq;

        Task(Runnable inner, long gen, long seq) {
            this.inner = inner;
            this.gen = gen;
            this.seq = seq;
        }

        public void run() {
            inner.run();
        }

        public int compareTo(Task o) {
            if (gen != o.gen) return gen > o.gen ? -1 : 1;
            return seq < o.seq ? -1 : (seq == o.seq ? 0 : 1);
        }
    }
}
