package com.oddmarket;

import android.app.Activity;

public final class DownloadUi implements DownloadCenter.Listener {

    public interface Callback {
        void onDownloadUiChanged();
    }

    private final Activity activity;
    private final GhostTitle title;
    private final Callback callback;
    private boolean deliveredThisResume = false;

    public DownloadUi(Activity activity, GhostTitle title, Callback callback) {
        this.activity = activity;
        this.title = title;
        this.callback = callback;
    }

    public void start() {
        deliveredThisResume = false;
        DownloadCenter.addListener(this);
        onDownloadChanged(false);
    }

    public void stop() {
        DownloadCenter.removeListener(this);
        if (title != null) title.clearDownloadProgress();
    }

    public void onDownloadChanged(boolean progressOnly) {
        if (title != null) {
            if (DownloadCenter.isActive()) {
                title.setDownloadProgress(DownloadCenter.getProgress(), DownloadCenter.isIndeterminate());
            } else {
                title.clearDownloadProgress();
            }
        }

        if (progressOnly) return;

        if (!deliveredThisResume && DownloadCenter.hasPendingResult() && !activity.isFinishing()) {
            DownloadCenter.Result r = DownloadCenter.takeResult();
            if (r != null) {
                deliveredThisResume = true;
                if (r.error != null) {

                    DownloadNotifier.showDone(activity, r, System.currentTimeMillis());
                    deliveredThisResume = false;
                } else {
                    DownloadNotifier.cancelDone(activity, r);
                    ApkInstaller.install(activity, r, new ApkInstaller.Callback() {
                        public void onInstalledSilently() {
                            deliveredThisResume = false;
                            onDownloadChanged(false);
                        }
                    });
                }
            }
        }

        if (callback != null) callback.onDownloadUiChanged();
    }
}
