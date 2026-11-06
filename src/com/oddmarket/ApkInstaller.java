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

    public static void install(final Activity activity, final DownloadCenter.Result r, final Callback callback) {
        final File file = r.file;
        final boolean wasUpdate = r.wasUpdate;

        if (file == null || !file.exists()) {
            FileLogger.e(Utils.TAG, "Install failed: APK not found");
            showInstallError(activity, r, R.string.toast_install_failed_not_found);
            return;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        Utils.runBackground(new Runnable() {
            public void run() {
                if (Utils.isRootAvailable()) {
                    final boolean success = Utils.installApkAsRoot(file.getAbsolutePath());
                    handler.post(new Runnable() {
                        public void run() {
                            if (success) {
                                int msg = wasUpdate ? R.string.toast_root_update_success : R.string.toast_root_install_success;
                                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                                if (callback != null) callback.onInstalledSilently();
                            } else {
                                openSystemInstaller(activity, r);
                            }
                        }
                    });
                } else {
                    handler.post(new Runnable() {
                        public void run() {
                            openSystemInstaller(activity, r);
                        }
                    });
                }
            }
        });
    }

    private static void showInstallError(Activity activity, DownloadCenter.Result r, int textRes) {
        DownloadNotifier.showError(activity, r.name, activity.getString(textRes), r.page, r.notifId);
    }

    /** Android 8+: PackageManager.canRequestPackageInstalls(); reflection keeps old SDKs compiling. */
    private static boolean canInstallPackages(Activity activity) {
        try {
            java.lang.reflect.Method m = android.content.pm.PackageManager.class.getMethod("canRequestPackageInstalls");
            return (Boolean) m.invoke(activity.getPackageManager());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void openSystemInstaller(Activity activity, DownloadCenter.Result r) {
        File file = r.file;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !canInstallPackages(activity)) {
                // Android 8+: installing APKs needs a per-app "Install unknown apps" permission.
                FileLogger.w(Utils.TAG, "Install blocked: 'install unknown apps' not allowed for this app");
                Toast.makeText(activity, R.string.toast_allow_unknown_sources, Toast.LENGTH_LONG).show();
                try {
                    Intent s = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                            Uri.parse("package:" + activity.getPackageName()));
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(s);
                } catch (Exception e) {
                    FileLogger.e(Utils.TAG, "Cannot open unknown-sources settings", e);
                    showInstallError(activity, r, R.string.toast_autoinstall_blocked);
                }
                return;
            }
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
            FileLogger.e(Utils.TAG, "Cannot start system installer for " + file.getName(), e);
            showInstallError(activity, r, R.string.toast_autoinstall_blocked);
        }
    }
}
