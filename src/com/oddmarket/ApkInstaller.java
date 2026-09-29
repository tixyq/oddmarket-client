package com.oddmarket;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;

public final class ApkInstaller {

    private ApkInstaller() {}

    public interface Callback {
        void onInstalledSilently();
    }

    public static void install(final Activity activity, final File file, final boolean wasUpdate, final Callback callback) {
        FileLogger.i(Utils.TAG, "installApk: " + (file != null ? file.getAbsolutePath() : "null"));

        if (file == null || !file.exists()) {
            Toast.makeText(activity, R.string.toast_install_failed_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                if (Utils.isRootAvailable()) {
                    final boolean success = Utils.installApkAsRoot(file.getAbsolutePath());
                    FileLogger.i(Utils.TAG, "installApk: root install result=" + success);
                    handler.post(new Runnable() {
                        public void run() {
                            if (success) {
                                int msg = wasUpdate ? R.string.toast_root_update_success : R.string.toast_root_install_success;
                                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                                if (callback != null) callback.onInstalledSilently();
                            } else {
                                openSystemInstaller(activity, file);
                            }
                        }
                    });
                } else {
                    handler.post(new Runnable() {
                        public void run() {
                            openSystemInstaller(activity, file);
                        }
                    });
                }
            }
        }).start();
    }

    private static void openSystemInstaller(Activity activity, File file) {
        FileLogger.i(Utils.TAG, "openSystemInstaller: " + file.getAbsolutePath());
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                Uri contentUri = Uri.parse("content://com.oddmarket.provider/" + Uri.encode(file.getName()));
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.toast_autoinstall_blocked, Toast.LENGTH_LONG).show();
        }
    }
}
