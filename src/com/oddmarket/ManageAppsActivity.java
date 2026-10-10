package com.oddmarket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class ManageAppsActivity extends Activity {

    private static final int THREADS = InstalledCatalog.THREADS;

    private static final int MENU_CLEAR = 201;
    private static final int MENU_INVERT = 202;
    private static final int MENU_DELETE = 203;
    private static final int MENU_UPDATE = 204;

    private static final int REQ_WRITE_STORAGE = 212;
    private static final int REQ_POST_NOTIFICATIONS = 213;
    private static final int REQ_UNINSTALL = 214;

    private static final int IDLE_TITLE_MODE = GhostTitle.MODE_NORMAL;

    private FrameLayout root;
    private View backing;
    private ListView listView;
    private TextView statusView;
    private GhostTitle ghostTitle;
    private InstalledAdapter adapter;
    private DownloadUi downloadUi;

    private final List<AppItem> appList = new ArrayList<AppItem>();
    private final HashSet<String> selected = new HashSet<String>();
    private final HashSet<String> updatable = new HashSet<String>();

    private boolean isTablet;
    private boolean loaded;
    private volatile boolean destroyed;
    private volatile ExecutorService pool;

    private final List<String> uninstallQueue = new ArrayList<String>();
    private String uninstallingPkg;
    private boolean pendingUpdateAfterPermission;

    private final Comparator<AppItem> listOrder = new Comparator<AppItem>() {
        public int compare(AppItem a, AppItem b) {
            boolean as = a.pkg.equals(getPackageName());
            boolean bs = b.pkg.equals(getPackageName());
            if (as != bs) return as ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SingleInstance.claim(this);
        FileLogger.init(this);
        FileLogger.i(Utils.TAG, "ManageAppsActivity.onCreate");
        GhostTitle.prepareWindow(this);
        paintWindow();
        setTitle(R.string.menu_manage_apps);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        isTablet = dm.widthPixels / dm.density >= 600f;

        buildLayout();
        setContentView(root);
        Theme.applyFonts(root);

        ghostTitle = GhostTitle.attach(this).setBackVisible(true).setMenuVisible(false)
                .setMode(IDLE_TITLE_MODE).setBaseColor(MainActivity.bgColor());

        loadInstalled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loaded) refreshInstalledState();
        if (downloadUi == null) {
            downloadUi = new DownloadUi(this, ghostTitle, new DownloadUi.Callback() {
                public void onDownloadUiChanged() {
                    if (!isFinishing() && loaded) refreshInstalledState();
                }
            });
        }
        downloadUi.start();
    }

    @Override
    protected void onPause() {
        if (downloadUi != null) downloadUi.stop();
        super.onPause();
    }

    private void paintWindow() {
        try {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(MainActivity.elColor() | 0xFF000000));
        } catch (Throwable ignored) {
        }
    }

    private void buildLayout() {
        int bar = GhostTitle.heightPx(this);

        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        root.setBackgroundColor(MainActivity.elColor());

        backing = new View(this);
        backing.setBackgroundColor(MainActivity.bgColor());
        root.addView(backing, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, bar, Gravity.TOP));

        listView = new ListView(this);
        listView.setBackgroundColor(0x00000000);
        listView.setCacheColorHint(0x00000000);
        listView.setDivider(null);
        listView.setDividerHeight(0);

        listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));

        View zeroBacking = new View(this);
        zeroBacking.setBackgroundColor(MainActivity.bgColor());
        zeroBacking.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, bar));
        listView.addHeaderView(zeroBacking, null, false);
        listView.setVisibility(View.GONE);
        adapter = new InstalledAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        statusView = StatusCenter.create(this);
        root.addView(statusView, StatusCenter.params());

        StatusCenter.track(root, new StatusCenter.Source() {
            public int obstructionPx() {
                return GhostTitle.heightPx(ManageAppsActivity.this);
            }
        }, statusView);
    }

    private void showStatus(int textRes, int iconRes) {
        StatusCenter.show(statusView, root, GhostTitle.heightPx(this), textRes, iconRes);
        listView.setVisibility(View.GONE);
        backing.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusView.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        backing.setVisibility(View.GONE);
    }

    private void loadInstalled() {
        ghostTitle.showProgressIndeterminate();
        showStatus(R.string.status_loading, 0);

        new Thread(new Runnable() {
            public void run() {
                runLoad();
            }
        }, "manage-apps").start();
    }

    private void runLoad() {
        final List<String> installed = readInstalledPackages();
        final JSONObject cache = readCache();
        final long now = System.currentTimeMillis();

        final List<AppItem> fromCache = new ArrayList<AppItem>();
        final List<String> toFetch = new ArrayList<String>();
        boolean hasMissing = false;
        for (int i = 0; i < installed.size(); i++) {
            String pkg = installed.get(i);
            JSONObject e = cache.optJSONObject(pkg);
            if (e == null) {
                toFetch.add(pkg);
                hasMissing = true;
                continue;
            }
            if (InstalledCatalog.isHit(e)) fromCache.add(itemFromCache(pkg, e));
            if (InstalledCatalog.isStale(e, now)) toFetch.add(pkg);
        }
        pruneCache(cache, installed);

        final boolean blocking = hasMissing;
        final List<AppItem> collected = Collections.synchronizedList(new ArrayList<AppItem>());

        if (!blocking && !fromCache.isEmpty()) {
            runOnUiThread(new Runnable() {
                public void run() {
                    mergeFound(fromCache);
                }
            });
        }

        boolean failed = false;
        if (!toFetch.isEmpty() && !destroyed) {
            failed = fetchAll(toFetch, cache, blocking, collected) && fromCache.isEmpty();
            writeCache(cache);
        }

        final boolean failedFinal = failed;
        runOnUiThread(new Runnable() {
            public void run() {
                if (blocking) {

                    List<AppItem> all = new ArrayList<AppItem>(fromCache);
                    synchronized (collected) {
                        all.addAll(collected);
                    }
                    if (!all.isEmpty()) mergeFound(all);
                }
                finishLoading(failedFinal);
            }
        });
    }

    private boolean fetchAll(List<String> pkgs, final JSONObject cache,
                             final boolean blocking, final List<AppItem> collected) {
        final ExecutorService ex = Executors.newFixedThreadPool(Math.min(THREADS, pkgs.size()));
        pool = ex;
        final CountDownLatch done = new CountDownLatch(pkgs.size());
        final AtomicInteger okCount = new AtomicInteger();

        for (int i = 0; i < pkgs.size(); i++) {
            final String pkg = pkgs.get(i);
            ex.execute(new Runnable() {
                public void run() {
                    try {
                        if (destroyed) return;
                        JSONObject found = fetchOne(pkg);
                        okCount.incrementAndGet();
                        JSONObject entry = InstalledCatalog.makeEntry(found);
                        putCache(cache, pkg, entry);
                        if (found == null) return;

                        final List<AppItem> one = new ArrayList<AppItem>(1);
                        one.add(itemFromCache(pkg, entry));
                        if (blocking) {
                            collected.addAll(one);
                        } else {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    mergeFound(one);
                                }
                            });
                        }
                    } catch (Exception e) {

                        FileLogger.w(Utils.TAG, "ManageAppsActivity: lookup failed for " + pkg, e);
                    } finally {
                        done.countDown();
                    }
                }
            });
        }

        try {
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        ex.shutdownNow();
        return okCount.get() == 0 && !destroyed;
    }

    private static JSONObject fetchOne(String pkg) throws Exception {
        return InstalledCatalog.fetchOne(pkg);
    }

    private List<String> readInstalledPackages() {
        return InstalledCatalog.installedPackages(this);
    }

    private String installedVersion(String pkg) {
        return InstalledCatalog.installedVersion(this, pkg);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private JSONObject readCache() {
        return InstalledCatalog.readCache(this);
    }

    private void putCache(JSONObject cache, String pkg, JSONObject entry) throws Exception {
        InstalledCatalog.putCache(cache, pkg, entry);
    }

    private void pruneCache(JSONObject cache, List<String> installed) {
        InstalledCatalog.pruneCache(cache, installed);
    }

    private void writeCache(JSONObject cache) {
        InstalledCatalog.writeCache(this, cache);
    }

    private AppItem itemFromCache(String pkg, JSONObject e) {
        AppItem item = new AppItem();
        item.pkg = pkg;
        String name = e.optString("n", "");
        item.name = name.length() > 0 ? name : getString(R.string.unknown);
        item.icon = e.optString("i", "");
        item.version = e.optString("v", "");
        return item;
    }

    private void mergeFound(List<AppItem> found) {
        if (destroyed) return;
        boolean first = appList.isEmpty();
        for (int i = 0; i < found.size(); i++) {
            AppItem n = found.get(i);
            int at = -1;
            for (int j = 0; j < appList.size(); j++) {
                if (appList.get(j).pkg.equals(n.pkg)) { at = j; break; }
            }
            if (at >= 0) appList.set(at, n); else appList.add(n);
        }
        Collections.sort(appList, listOrder);
        recomputeUpdates();
        adapter.notifyDataSetChanged();
        if (first && !appList.isEmpty()) hideStatus();
        updateSelectionUi();
    }

    private void finishLoading(boolean failed) {
        if (destroyed) return;
        loaded = true;
        ghostTitle.hideProgress();
        if (!appList.isEmpty()) return;
        if (failed) {
            showStatus(R.string.status_couldnt_load, R.drawable.ic_network_error);
        } else {
            showStatus(R.string.status_nothing_found, 0);
        }
    }

    private void recomputeUpdates() {
        updatable.clear();
        for (int i = 0; i < appList.size(); i++) {
            AppItem it = appList.get(i);
            String iv = installedVersion(it.pkg);
            if (iv != null && Utils.isVersionOlder(iv, it.version)) updatable.add(it.pkg);
        }
        reportUpdates();
    }

    private void reportUpdates() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < appList.size(); i++) {
            AppItem it = appList.get(i);
            if (updatable.contains(it.pkg)) names.add(it.name);
        }
        UpdateChecker.report(this, names);
    }

    private void refreshInstalledState() {
        if (destroyed) return;
        for (int i = appList.size() - 1; i >= 0; i--) {
            String pkg = appList.get(i).pkg;
            if (!isInstalled(pkg)) {
                appList.remove(i);
                selected.remove(pkg);
            }
        }
        recomputeUpdates();
        adapter.notifyDataSetChanged();
        updateSelectionUi();
        if (appList.isEmpty() && loaded) showStatus(R.string.status_nothing_found, 0);
    }

    private void toggle(String pkg) {
        if (!selected.remove(pkg)) selected.add(pkg);
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void invertSelection() {
        for (int i = 0; i < appList.size(); i++) {
            String pkg = appList.get(i).pkg;
            if (!selected.remove(pkg)) selected.add(pkg);
        }
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (ghostTitle == null) return;
        int n = selected.size();
        if (n > 0) {
            ghostTitle.setTitle(getString(R.string.manage_selected, Integer.valueOf(n)));
            ghostTitle.setMenuVisible(true);
            ghostTitle.setMode(GhostTitle.MODE_NORMAL);
        } else {
            ghostTitle.setTitle(getString(R.string.menu_manage_apps));
            ghostTitle.setMenuVisible(false);
            ghostTitle.setMode(IDLE_TITLE_MODE);
        }
    }

    private boolean allSelectedUpdatable() {
        if (selected.isEmpty()) return false;
        for (String pkg : selected) {
            if (!updatable.contains(pkg)) return false;
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_CLEAR, 0, R.string.manage_menu_clear);
        menu.add(0, MENU_INVERT, 1, R.string.manage_menu_invert);
        menu.add(0, MENU_DELETE, 2, R.string.btn_delete);
        menu.add(0, MENU_UPDATE, 3, R.string.btn_update);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean any = !selected.isEmpty();
        MenuItem clear = menu.findItem(MENU_CLEAR);
        MenuItem invert = menu.findItem(MENU_INVERT);
        MenuItem delete = menu.findItem(MENU_DELETE);
        MenuItem update = menu.findItem(MENU_UPDATE);
        if (clear != null) clear.setVisible(any);
        if (invert != null) invert.setVisible(any && selected.size() < appList.size());

        if (delete != null) delete.setVisible(any && !selected.contains(getPackageName()));

        if (update != null) {
            update.setVisible(any && !MainActivity.isLegacyDownloadActive(this) && allSelectedUpdatable());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CLEAR:
                clearSelection();
                return true;
            case MENU_INVERT:
                invertSelection();
                return true;
            case MENU_DELETE:
                confirmDelete();
                return true;
            case MENU_UPDATE:
                startUpdates();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void confirmDelete() {
        final List<String> targets = new ArrayList<String>();
        for (int i = 0; i < appList.size(); i++) {
            String pkg = appList.get(i).pkg;
            if (selected.contains(pkg) && !pkg.equals(getPackageName())) targets.add(pkg);
        }
        if (targets.isEmpty()) return;

        int n = targets.size();
        PopupMenuCompat.newBuilder(this)
                .setMessage(getResources().getQuantityString(R.plurals.manage_delete_confirm, n, Integer.valueOf(n)))
                .setPositiveButton(R.string.manage_yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        startDelete(targets);
                    }
                })
                .setNegativeButton(R.string.manage_no, null)
                .show();
    }

    private void startDelete(final List<String> targets) {
        ghostTitle.showProgressIndeterminate();
        new Thread(new Runnable() {
            public void run() {
                final boolean root = Utils.isRootAvailable();
                if (root) {
                    int failed = 0;
                    for (int i = 0; i < targets.size(); i++) {
                        if (!Utils.uninstallPackageAsRoot(targets.get(i))) failed++;
                    }
                    final int failedFinal = failed;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) return;
                            ghostTitle.hideProgress();
                            Toast.makeText(ManageAppsActivity.this,
                                    failedFinal == 0 ? R.string.toast_root_uninstall_success
                                            : R.string.toast_cannot_uninstall_app,
                                    Toast.LENGTH_SHORT).show();
                            refreshInstalledState();
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (destroyed) return;
                            ghostTitle.hideProgress();
                            uninstallQueue.clear();
                            uninstallQueue.addAll(targets);
                            uninstallNext();
                        }
                    });
                }
            }
        }, "manage-delete").start();
    }

    private void uninstallNext() {
        while (!uninstallQueue.isEmpty()) {
            String pkg = uninstallQueue.get(0);
            if (!isInstalled(pkg)) {
                uninstallQueue.remove(0);
                continue;
            }
            uninstallingPkg = pkg;
            if (launchUninstall(pkg)) return;
            uninstallQueue.clear();
            uninstallingPkg = null;
            Toast.makeText(this, R.string.toast_cannot_uninstall_app, Toast.LENGTH_SHORT).show();
            refreshInstalledState();
            return;
        }
        uninstallingPkg = null;
        refreshInstalledState();
    }

    private boolean launchUninstall(String pkg) {
        Uri uri = Uri.parse("package:" + pkg);
        String[] actions = {"android.intent.action.UNINSTALL_PACKAGE", Intent.ACTION_DELETE};
        for (int i = 0; i < actions.length; i++) {
            try {
                Intent intent = new Intent(actions[i], uri);
                intent.putExtra("android.intent.extra.RETURN_RESULT", true);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivityForResult(intent, REQ_UNINSTALL);
                    return true;
                }
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "Uninstall via " + actions[i] + " failed for " + pkg, e);
            }
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_UNINSTALL) return;
        String pkg = uninstallingPkg;
        if (pkg == null) return;

        boolean removed = resultCode == RESULT_OK || !isInstalled(pkg);
        if (removed) {
            uninstallQueue.remove(pkg);
            uninstallNext();
        } else {
            uninstallQueue.clear();
            uninstallingPkg = null;
            Toast.makeText(this, R.string.toast_operation_declined, Toast.LENGTH_SHORT).show();
            refreshInstalledState();
        }
    }

    private void startUpdates() {
        final List<AppItem> targets = new ArrayList<AppItem>();
        for (int i = 0; i < appList.size(); i++) {
            AppItem it = appList.get(i);
            if (selected.contains(it.pkg) && updatable.contains(it.pkg)) targets.add(it);
        }
        if (targets.isEmpty() || MainActivity.isLegacyDownloadActive(this)) return;

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Toast.makeText(this, R.string.toast_sdcard_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                Method check = Context.class.getMethod("checkSelfPermission", String.class);
                int r = (Integer) check.invoke(this, "android.permission.WRITE_EXTERNAL_STORAGE");
                if (r != PackageManager.PERMISSION_GRANTED) {
                    pendingUpdateAfterPermission = true;
                    Method req = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    req.invoke(this, new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_WRITE_STORAGE);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        requestNotificationPermission();

        ghostTitle.showProgressIndeterminate();
        new Thread(new Runnable() {
            public void run() {
                resolveAndQueue(targets);
            }
        }, "manage-update").start();
    }

    private static final class Resolved {
        final String pkg;
        final String name;
        final String version;
        final String url;
        Resolved(String pkg, String name, String version, String url) {
            this.pkg = pkg;
            this.name = name;
            this.version = version;
            this.url = url;
        }
    }

    private void resolveAndQueue(List<AppItem> targets) {
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(THREADS, targets.size()));
        pool = ex;
        List<Future<JSONObject>> futures = new ArrayList<Future<JSONObject>>();
        for (int i = 0; i < targets.size(); i++) {
            final String pkg = targets.get(i).pkg;
            futures.add(ex.submit(new Callable<JSONObject>() {
                public JSONObject call() throws Exception {
                    return fetchOne(pkg);
                }
            }));
        }

        final List<Resolved> ready = new ArrayList<Resolved>();
        final List<Resolved> versionOnly = new ArrayList<Resolved>();
        for (int i = 0; i < targets.size(); i++) {
            AppItem it = targets.get(i);
            try {
                JSONObject o = futures.get(i).get();
                if (o == null) continue;
                String version = o.optString("version", "");
                String url = o.optString("download_url", "");
                String name = o.optString("name", it.name);
                versionOnly.add(new Resolved(it.pkg, name, version, ""));
                String iv = installedVersion(it.pkg);
                if (url.length() > 0 && iv != null && Utils.isVersionOlder(iv, version)) {
                    ready.add(new Resolved(it.pkg, name, version, url));
                }
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "ManageAppsActivity: update lookup failed for " + it.pkg, e);
            }
        }
        ex.shutdownNow();

        runOnUiThread(new Runnable() {
            public void run() {
                if (destroyed) return;
                ghostTitle.hideProgress();

                for (int i = 0; i < versionOnly.size(); i++) {
                    Resolved r = versionOnly.get(i);
                    for (int j = 0; j < appList.size(); j++) {
                        if (appList.get(j).pkg.equals(r.pkg)) appList.get(j).version = r.version;
                    }
                }
                recomputeUpdates();
                adapter.notifyDataSetChanged();

                int started = 0;
                for (int i = 0; i < ready.size(); i++) {
                    Resolved r = ready.get(i);
                    Bundle page = new Bundle();
                    page.putString("pkg", r.pkg);
                    page.putBoolean(DetailsActivity.EXTRA_FETCH, true);
                    if (DownloadCenter.begin(ManageAppsActivity.this, r.url, r.name, r.pkg, true, page)) {
                        started++;
                    }
                }
                if (started == 0) {
                    Toast.makeText(ManageAppsActivity.this, R.string.toast_download_start_failed,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            Method check = Context.class.getMethod("checkSelfPermission", String.class);
            int r = (Integer) check.invoke(this, "android.permission.POST_NOTIFICATIONS");
            if (r != PackageManager.PERMISSION_GRANTED) {
                Method req = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                req.invoke(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFICATIONS);
            }
        } catch (Exception ignored) {
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_WRITE_STORAGE) {
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean wanted = pendingUpdateAfterPermission;
            pendingUpdateAfterPermission = false;
            if (granted) {
                if (wanted) startUpdates();
            } else {
                Toast.makeText(this, R.string.toast_permission_denied_download, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        SingleInstance.release(this);
        super.onDestroy();
    }

    private class InstalledAdapter extends BaseAdapter {

        class Holder {
            TextView txtName;
            TextView txtUpdate;
            CheckBox chk;
            ImageView imgIcon;
            View row;
            View divider;
            String pkg;
            int themeApplied = -1;
        }

        public int getCount() {
            return appList.size();
        }

        public Object getItem(int position) {
            return appList.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public boolean isEnabled(int position) {
            return false;
        }

        public boolean areAllItemsEnabled() {
            return false;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null || convertView.getTag() == null) {
                convertView = LayoutInflater.from(ManageAppsActivity.this)
                        .inflate(R.layout.manage_list_item, parent, false);
                holder = new Holder();
                holder.row = convertView.findViewById(R.id.manage_item_row);
                holder.txtName = (TextView) convertView.findViewById(R.id.item_name);
                holder.txtUpdate = (TextView) convertView.findViewById(R.id.item_version);
                holder.chk = (CheckBox) convertView.findViewById(R.id.manage_item_check);
                holder.imgIcon = (ImageView) convertView.findViewById(R.id.item_icon);
                holder.divider = convertView.findViewById(R.id.manage_item_divider);
                final Holder h = holder;

                holder.row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (h.pkg == null) return;
                        if (selected.isEmpty()) {
                            DetailsActivity.openByPackage(ManageAppsActivity.this, h.pkg);
                        } else {
                            toggle(h.pkg);
                        }
                    }
                });
                holder.chk.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (h.pkg != null) toggle(h.pkg);
                    }
                });
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            if (holder.themeApplied != Theme.CURRENT) {
                Theme.applyListItem(holder.row);
                Theme.applyDivider(holder.divider);
                holder.themeApplied = Theme.CURRENT;
            }
            AppItem item = appList.get(position);
            holder.pkg = item.pkg;
            holder.txtName.setText(item.name);
            if (updatable.contains(item.pkg)) {
                holder.txtUpdate.setText(getString(R.string.manage_update_available, item.version));
                holder.txtUpdate.setVisibility(View.VISIBLE);
            } else {
                holder.txtUpdate.setVisibility(View.GONE);
            }
            holder.chk.setChecked(selected.contains(item.pkg));

            holder.chk.setClickable(selected.isEmpty());
            MainActivity.loadIcon(item.icon, holder.imgIcon);
            return convertView;
        }
    }
}
