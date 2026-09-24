package com.oddmarket;
// App details, download and install.

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.text.Html;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Gallery;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class DetailsActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.applyLocale(newBase));
    }

    Handler handler = new Handler();
    private String currentPkg = "";
    private WebView reviewsWebView;
    private Runnable reviewsTimeoutRunnable;

    private AccountManager.Cancelable pendingReviewsAccountCheck;

    private static final int REVIEWS_LOAD_TIMEOUT_MS = 8000;
    private String siteVersion = "";
    private String downloadUrl = "";
    private String appName = "";

    private Button btnDownload;

    private int btnState = STATE_INSTALL;
    private static final int STATE_INSTALL = 1;
    private static final int STATE_UPDATE = 2;
    private static final int STATE_OPEN = 3;

    private static final int MENU_ID_SHARE = 1;
    private static final int MENU_ID_UNINSTALL = 2;

    private static final String SELF_PACKAGE = "com.oddmarket";

    private DownloadTask activeDownloadTask;
    private ProgressDialog progressDialog;

    private static final int REQUEST_WRITE_STORAGE = 112;
    private String pendingApkUrl = null;
    private String pendingAppName = null;

    private FrameLayout rootLayout;
    private FrameLayout overlayLayout;
    private SlowGallery overlayGallery;
    private ViewGroup originalView;

    private ArrayList<String> appScreenshots;
    private ArrayList<String> appIconList;

    private ArrayList<String> overlayImages;

    private class SlowGallery extends Gallery {
        public SlowGallery(Context context) {
            super(context);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            int keyCode = velocityX < 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            return true;
        }
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        if (activeDownloadTask != null) {
            activeDownloadTask.detach();
        }
        return activeDownloadTask;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        setTitle(R.string.title_details);
        Utils.forceShowOverflowMenu(this);

        buildRootAndOverlay();
        setContentView(rootLayout);
        Theme.applyFonts(rootLayout);

        applyResponsiveHeaderLayout();
        bindIntentData();
        wireDownloadButton();
        buildScreenshotsRow();
        buildReviewsWebView();
        restoreDownloadTask();
        enableActionBarUpButton();

        btnDownload.requestFocus();
    }

    private void buildRootAndOverlay() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        View originalContentView = LayoutInflater.from(this).inflate(R.layout.details, null);
        originalView = (ViewGroup) originalContentView;
        originalContentView.setBackgroundColor(Theme.windowBackground());

        View headerContainer = originalContentView.findViewById(R.id.details_header_container);
        if (headerContainer != null) headerContainer.setBackgroundColor(Theme.tabRowBackground());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        scrollView.setBackgroundColor(Theme.windowBackground());
        scrollView.setFillViewport(true);

        try {
            java.lang.reflect.Method m = android.view.View.class.getMethod("setScrollbarFadingEnabled", boolean.class);
            m.invoke(scrollView, false);
        } catch (Exception e) {
            Log.d(Utils.TAG, "setScrollbarFadingEnabled not available", e);
        }

        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(originalContentView);
        rootLayout.addView(scrollView);

        overlayLayout = new FrameLayout(this);
        overlayLayout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        overlayLayout.setBackgroundColor(0xCC000000);
        overlayLayout.setVisibility(View.GONE);

        overlayGallery = new SlowGallery(this);
        overlayGallery.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        overlayGallery.setSpacing(20);
        overlayGallery.setUnselectedAlpha(1.0f);

        overlayGallery.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                closeOverlay();
            }
        });

        overlayLayout.addView(overlayGallery);
        rootLayout.addView(overlayLayout);
    }

    private void applyResponsiveHeaderLayout() {
        btnDownload = (Button) findViewById(R.id.details_btn_download);
        LinearLayout headerContainer = (LinearLayout) findViewById(R.id.details_header_container);
        LinearLayout metaBlock = (LinearLayout) findViewById(R.id.details_meta_block);
        View actionRow = findViewById(R.id.details_action_row);

        float scale = getResources().getDisplayMetrics().density;
        int screenWidthPx = getResources().getDisplayMetrics().widthPixels;
        int screenHeightPx = getResources().getDisplayMetrics().heightPixels;
        float screenWidthDp = screenWidthPx / scale;

        if (screenWidthPx > screenHeightPx || screenWidthDp > 500) {
            headerContainer.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            metaBlock.setLayoutParams(metaParams);

            int rowWidthPx = (int) (150 * scale + 0.5f);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(rowWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins((int)(15 * scale + 0.5f), 0, 0, 0);
            actionRow.setLayoutParams(rowParams);
        } else {
            headerContainer.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0.0f);
            metaBlock.setLayoutParams(metaParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, (int)(12 * scale + 0.5f), 0, 0);
            actionRow.setLayoutParams(rowParams);
        }
    }

    private void bindIntentData() {
        ImageView imgIcon = (ImageView) findViewById(R.id.details_icon);
        TextView txtName = (TextView) findViewById(R.id.details_name);
        TextView txtVer = (TextView) findViewById(R.id.details_version);
        TextView txtCategory = (TextView) findViewById(R.id.details_category);
        TextView txtDesc = (TextView) findViewById(R.id.details_desc);

        txtName.setTextColor(Theme.textPrimary());
        txtVer.setTextColor(Theme.textSecondary());
        txtCategory.setTextColor(Theme.textSecondary());
        txtDesc.setTextColor(Theme.textPrimary());

        Utils.setTextColorIfPresent(this, R.id.details_caption_screenshots, Theme.textSecondary());
        Utils.setTextColorIfPresent(this, R.id.details_caption_description, Theme.textSecondary());

        View screenshotsScroll = findViewById(R.id.details_screenshots_scroll);
        if (screenshotsScroll != null) screenshotsScroll.setBackgroundColor(Theme.tabRowBackground());

        View descBlock = findViewById(R.id.details_desc_block);
        if (descBlock != null) descBlock.setBackgroundColor(Theme.tabRowBackground());

        btnDownload.setTextColor(Theme.textPrimary());
        btnDownload.setBackgroundDrawable(Theme.buttonBackground());
        btnDownload.setPadding(
                Theme.dpToPx(this, 12), Theme.dpToPx(this, 8),
                Theme.dpToPx(this, 12), Theme.dpToPx(this, 8));
        removeButtonShadow(btnDownload);

        Intent intent = getIntent();
        currentPkg = intent.getStringExtra("pkg");
        if (currentPkg == null) currentPkg = "";

        appName = intent.getStringExtra("name");
        txtName.setText(appName);

        siteVersion = intent.getStringExtra("version");
        String minAndroid = intent.getStringExtra("min_android");

        txtVer.setText(siteVersion + " (" + Utils.formatMinAndroid(this, minAndroid) + ")");

        String category = intent.getStringExtra("category");

        String translatedCategory = Categories.translate(this, category);
        if (translatedCategory == null) {
            txtCategory.setVisibility(View.GONE);
        } else {
            txtCategory.setText(translatedCategory);
            txtCategory.setVisibility(View.VISIBLE);
        }

        String description = intent.getStringExtra("description");
        if (description != null) {
            CharSequence htmlText = Html.fromHtml(description);
            txtDesc.setText(htmlText);

            boolean hasLink = false;
            if (htmlText instanceof android.text.Spanned) {
                android.text.style.URLSpan[] spans = ((android.text.Spanned) htmlText).getSpans(
                        0, htmlText.length(), android.text.style.URLSpan.class);
                hasLink = spans != null && spans.length > 0;
            }

            if (hasLink) {
                txtDesc.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                txtDesc.setFocusable(true);
            } else {
                txtDesc.setFocusable(false);
            }
        }

        String iconUrl = intent.getStringExtra("icon");
        MainActivity.loadIcon(iconUrl, imgIcon);

        appIconList = new ArrayList<String>();
        if (iconUrl != null && iconUrl.length() > 0) {
            appIconList.add(iconUrl);
        }
        imgIcon.setFocusable(true);
        imgIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverlay(appIconList, 0);
            }
        });

        downloadUrl = intent.getStringExtra("download_url");
        appScreenshots = intent.getStringArrayListExtra("screenshots");
    }

    private void removeButtonShadow(View button) {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            try {
                Class<?> stateListAnimatorClass = Class.forName("android.animation.StateListAnimator");
                Method setStateListAnimatorMethod = View.class.getMethod("setStateListAnimator", stateListAnimatorClass);
                setStateListAnimatorMethod.invoke(button, new Object[]{ null });
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "Could not clear stateListAnimator on the action button", e);
            }
            try {
                Method setElevationMethod = View.class.getMethod("setElevation", float.class);
                setElevationMethod.invoke(button, 0f);
            } catch (Exception e) {
                FileLogger.w(Utils.TAG, "Could not clear elevation on the action button", e);
            }
        }
    }

    private void wireDownloadButton() {
        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnState == STATE_OPEN) {
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(currentPkg);
                    if (launchIntent != null) {
                        startActivity(launchIntent);
                    } else {
                        Toast.makeText(DetailsActivity.this, R.string.toast_cannot_open_app, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (downloadUrl != null && downloadUrl.length() > 0) {
                        boolean isLegacy = MainActivity.isLegacyDownloadActive(DetailsActivity.this);
                        if (isLegacy) {
                            String finalUrl = MainActivity.fixApkUrl(DetailsActivity.this, downloadUrl);

                            if (finalUrl.toLowerCase().startsWith("https://")) {
                                finalUrl = "http://" + finalUrl.substring(8);
                            } else if (!finalUrl.toLowerCase().startsWith("http://")) {
                                finalUrl = "http://" + finalUrl;
                            }
                            finalUrl = finalUrl.replace(" ", "%20");

                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl));
                            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);

                            try {
                                startActivity(browserIntent);
                            } catch (Exception e) {
                                Toast.makeText(DetailsActivity.this, R.string.toast_browser_not_found, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            downloadAndInstall(downloadUrl, appName);
                        }
                    }
                }
            }
        });
    }

    private void buildScreenshotsRow() {
        HorizontalScrollView screenshotsScroll = (HorizontalScrollView) findViewById(R.id.details_screenshots_scroll);
        LinearLayout screenshotsContainer = (LinearLayout) findViewById(R.id.details_screenshots_container);
        View screenshotsCaption = findViewById(R.id.details_caption_screenshots);

        if (appScreenshots == null || appScreenshots.size() == 0) {
            return;
        }

        screenshotsScroll.setVisibility(View.VISIBLE);
        if (screenshotsCaption != null) screenshotsCaption.setVisibility(View.VISIBLE);

        float scale = getResources().getDisplayMetrics().density;
        int heightPx = (int) (200 * scale + 0.5f);
        int marginPx = (int) (10 * scale + 0.5f);

        ImageView prevThumb = null;
        int firstThumbId = View.NO_ID;
        for (int i = 0; i < appScreenshots.size(); i++) {
            final String url = appScreenshots.get(i);
            final int index = i;
            ImageView img = new ImageView(this);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, heightPx);
            lp.setMargins(0, 0, marginPx, 0);
            img.setLayoutParams(lp);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);

            MainActivity.loadBannerImage(url, img, true);

            img.setId(Utils.generateViewId());
            if (i == 0) firstThumbId = img.getId();
            img.setFocusable(true);
            img.setNextFocusUpId(R.id.details_btn_download);
            if (prevThumb != null) {
                prevThumb.setNextFocusRightId(img.getId());
                img.setNextFocusLeftId(prevThumb.getId());
            }
            prevThumb = img;

            img.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showOverlay(appScreenshots, index);
                }
            });

            screenshotsContainer.addView(img);
        }

        if (firstThumbId != View.NO_ID) {
            btnDownload.setNextFocusDownId(firstThumbId);
        }
    }

    private void buildReviewsWebView() {
        if (!Utils.isValidPackageName(currentPkg)) {
            return;
        }

        final FrameLayout container = (FrameLayout) findViewById(R.id.details_reviews_container);
        if (container == null) return;

        if (!AccountManager.isAccountSystemReachable(this)) {

            if (pendingReviewsAccountCheck != null) {
                pendingReviewsAccountCheck.cancel();
                pendingReviewsAccountCheck = null;
            }

            pendingReviewsAccountCheck = AccountManager.check(this, rootLayout, new AccountManager.Callback() {
                public void onResult(boolean loggedIn, String nickname) {
                    pendingReviewsAccountCheck = null;
                    if (isFinishing()) return;
                    if (AccountManager.isAccountSystemReachable(DetailsActivity.this)) {
                        buildReviewsWebView();
                    } else {
                        container.setVisibility(View.GONE);
                    }
                }
            });
            return;
        }

        reviewsWebView = new WebView(this);
        reviewsWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        reviewsWebView.getSettings().setJavaScriptEnabled(true);
        reviewsWebView.setBackgroundColor(Theme.windowBackground());
        reviewsWebView.setVerticalScrollBarEnabled(false);
        reviewsWebView.setHorizontalScrollBarEnabled(false);
        Utils.disableOverScrollIfSupported(reviewsWebView);

        final int maxHeightPx = getResources().getDisplayMetrics().heightPixels * 2;

        reviewsWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                cancelReviewsTimeout();
                scheduleReviewsHeightChecks(view, maxHeightPx);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {

                cancelReviewsTimeout();
                container.setVisibility(View.GONE);
            }
        });

        container.addView(reviewsWebView);
        container.setVisibility(View.VISIBLE);
        reviewsWebView.loadUrl(UrlBuilder.reviewsUrl(this, currentPkg));

        reviewsTimeoutRunnable = new Runnable() {
            public void run() {
                if (isFinishing() || reviewsWebView == null) return;
                reviewsWebView.stopLoading();
                container.setVisibility(View.GONE);
            }
        };
        handler.postDelayed(reviewsTimeoutRunnable, REVIEWS_LOAD_TIMEOUT_MS);
    }

    private void cancelReviewsTimeout() {
        if (reviewsTimeoutRunnable != null) {
            handler.removeCallbacks(reviewsTimeoutRunnable);
            reviewsTimeoutRunnable = null;
        }
    }

    private void scheduleReviewsHeightChecks(final WebView view, final int maxHeightPx) {
        int[] delaysMs = {300, 800, 1600, 3000};
        for (final int delay : delaysMs) {
            handler.postDelayed(new Runnable() {
                public void run() {
                    if (isFinishing() || reviewsWebView == null) return;
                    applyMeasuredReviewsHeight(view.getContentHeight(), maxHeightPx);
                }
            }, delay);
        }
    }

    private void applyMeasuredReviewsHeight(int cssHeight, int maxHeightPx) {
        if (reviewsWebView == null || isFinishing()) return;
        if (cssHeight <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        int measuredPx = (int) (cssHeight * density + 0.5f);
        int boundedPx = Math.min(measuredPx, maxHeightPx);

        ViewGroup.LayoutParams params = reviewsWebView.getLayoutParams();
        if (params.height != boundedPx) {
            params.height = boundedPx;
            reviewsWebView.setLayoutParams(params);
        }
    }

    private void restoreDownloadTask() {
        activeDownloadTask = (DownloadTask) getLastNonConfigurationInstance();
        if (activeDownloadTask != null) {
            activeDownloadTask.attach(this);
        }
    }

    private void enableActionBarUpButton() {
        Utils.enableActionBarUpButton(this);
    }

    private void showOverlay(ArrayList<String> images, int startIndex) {
        if (overlayLayout != null && images != null && startIndex >= 0 && startIndex < images.size()) {
            overlayImages = images;
            overlayGallery.setAdapter(new ScreenshotAdapter());
            overlayGallery.setSelection(startIndex);
            overlayLayout.setVisibility(View.VISIBLE);
            if (originalView != null) {
                originalView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            overlayGallery.requestFocus();
        }
    }

    private void closeOverlay() {
        if (overlayLayout != null && overlayLayout.getVisibility() == View.VISIBLE) {
            overlayLayout.setVisibility(View.GONE);
            if (originalView != null) {
                originalView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (overlayLayout != null && overlayLayout.getVisibility() == View.VISIBLE) {
                closeOverlay();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private class ScreenshotAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return overlayImages != null ? overlayImages.size() : 0;
        }

        @Override
        public Object getItem(int position) {
            return overlayImages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView img;
            if (convertView == null) {
                img = new ImageView(DetailsActivity.this);
                img.setLayoutParams(new Gallery.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);

                int screenW = getResources().getDisplayMetrics().widthPixels;
                int screenH = getResources().getDisplayMetrics().heightPixels;
                int paddingW = (int) (screenW * 0.065f);
                int paddingH = (int) (screenH * 0.065f);
                img.setPadding(paddingW, paddingH, paddingW, paddingH);
            } else {
                img = (ImageView) convertView;
            }

            img.setImageResource(android.R.color.transparent);
            String url = overlayImages.get(position);
            MainActivity.loadBannerImage(url, img, true);

            return img;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtonState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissProgress();
        if (activeDownloadTask != null) {
            activeDownloadTask.detach();
        }
        if (pendingReviewsAccountCheck != null) {
            pendingReviewsAccountCheck.cancel();
            pendingReviewsAccountCheck = null;
        }
        if (reviewsWebView != null) {
            cancelReviewsTimeout();
            reviewsWebView.stopLoading();
            reviewsWebView.setWebViewClient(null);
            reviewsWebView.destroy();
            reviewsWebView = null;
        }
    }

    private void updateButtonState() {
        boolean isLegacy = MainActivity.isLegacyDownloadActive(this);

        if (currentPkg == null || currentPkg.length() == 0) {
            btnState = STATE_INSTALL;
            btnDownload.setText(isLegacy ? R.string.btn_state_download : R.string.btn_state_install);
            return;
        }

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(currentPkg, 0);
            String installedVersion = pInfo.versionName;

            if (Utils.isVersionOlder(installedVersion, siteVersion)) {
                btnState = STATE_UPDATE;
                btnDownload.setText(isLegacy ? R.string.btn_state_download : R.string.btn_state_update);
            } else {
                btnState = STATE_OPEN;
                btnDownload.setText(R.string.btn_state_open);
            }
        } catch (PackageManager.NameNotFoundException e) {
            btnState = STATE_INSTALL;
            btnDownload.setText(isLegacy ? R.string.btn_state_download : R.string.btn_state_install);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem itemShare = menu.add(0, MENU_ID_SHARE, 0, R.string.menu_share);
        itemShare.setIcon(android.R.drawable.ic_menu_share);
        Utils.setShowAsActionIfSupported(itemShare, Utils.SHOW_AS_ACTION_NEVER);

        MenuItem itemUninstall = menu.add(0, MENU_ID_UNINSTALL, 1, R.string.menu_uninstall);
        Utils.setShowAsActionIfSupported(itemUninstall, Utils.SHOW_AS_ACTION_NEVER);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem itemUninstall = menu.findItem(MENU_ID_UNINSTALL);
        if (itemUninstall != null) {
            itemUninstall.setVisible(!isSelfPackage() && isCurrentPackageInstalled());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private boolean isSelfPackage() {
        return SELF_PACKAGE.equals(currentPkg);
    }

    private boolean isCurrentPackageInstalled() {
        if (currentPkg == null || currentPkg.length() == 0) {
            return false;
        }
        try {
            getPackageManager().getPackageInfo(currentPkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static final String SHARE_DOMAIN = "oddmarket.ct.ws";

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (Utils.handleHomeMenuItem(this, item)) {
            return true;
        }

        if (item.getItemId() == MENU_ID_SHARE) {
            String url = "https://" + SHARE_DOMAIN + "/?" + currentPkg;
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, url);
            sendIntent.setType("text/plain");
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share_chooser_title)));
            return true;
        }

        if (item.getItemId() == MENU_ID_UNINSTALL) {
            performUninstall();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void performUninstall() {
        if (isSelfPackage() || !isCurrentPackageInstalled()) {
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                final boolean rootAvailable = Utils.isRootAvailable();
                if (rootAvailable) {
                    final boolean success = Utils.uninstallPackageAsRoot(currentPkg);
                    handler.post(new Runnable() {
                        public void run() {
                            if (success) {
                                Toast.makeText(DetailsActivity.this, R.string.toast_root_uninstall_success, Toast.LENGTH_SHORT).show();
                                updateButtonState();
                            } else {
                                Toast.makeText(DetailsActivity.this, R.string.toast_cannot_uninstall_app, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } else {
                    handler.post(new Runnable() {
                        public void run() {
                            if (!Utils.launchUninstallIntent(DetailsActivity.this, currentPkg)) {
                                Toast.makeText(DetailsActivity.this, R.string.toast_cannot_uninstall_app, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void downloadAndInstall(final String apkUrl, final String appName) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Toast.makeText(this, R.string.toast_sdcard_required, Toast.LENGTH_SHORT).show();
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                Method checkMethod = Context.class.getMethod("checkSelfPermission", String.class);
                int result = (Integer) checkMethod.invoke(this, "android.permission.WRITE_EXTERNAL_STORAGE");

                if (result != PackageManager.PERMISSION_GRANTED) {
                    pendingApkUrl = apkUrl;
                    pendingAppName = appName;

                    Method requestMethod = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    requestMethod.invoke(this, new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQUEST_WRITE_STORAGE);
                    return;
                }
            } catch (Exception e) {
            }
        }

        startDownloadTask(apkUrl, appName);
    }

    private void startDownloadTask(String apkUrl, String appName) {
        if (activeDownloadTask != null && !activeDownloadTask.isFinished) {
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        activeDownloadTask = new DownloadTask(apkUrl, appName, currentPkg, getApplicationContext());
        activeDownloadTask.attach(this);
        activeDownloadTask.start();
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingApkUrl != null && pendingAppName != null) {
                    startDownloadTask(pendingApkUrl, pendingAppName);
                    pendingApkUrl = null;
                    pendingAppName = null;
                }
            } else {
                Toast.makeText(this, R.string.toast_permission_denied_download, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void showProgress(String appName) {
        if (progressDialog == null) {
            progressDialog = new ProgressDialog(this);
            progressDialog.setMessage(getString(R.string.downloading_format, appName));
            progressDialog.setIndeterminate(false);
            progressDialog.setMax(100);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setCancelable(false);
        }
        if (!progressDialog.isShowing()) {
            progressDialog.show();
        }
    }

    public void updateProgress(int progress, boolean indeterminate) {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.setIndeterminate(indeterminate);
            if (!indeterminate) {
                progressDialog.setProgress(progress);
            }
        }
    }

    public void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    void installApk(final File file) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.toast_install_failed_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean wasUpdate = (btnState == STATE_UPDATE);

        new Thread(new Runnable() {
            public void run() {
                if (Utils.isRootAvailable()) {
                    final boolean success = Utils.installApkAsRoot(file.getAbsolutePath());
                    handler.post(new Runnable() {
                        public void run() {
                            if (success) {
                                int msg = wasUpdate ? R.string.toast_root_update_success : R.string.toast_root_install_success;
                                Toast.makeText(DetailsActivity.this, msg, Toast.LENGTH_SHORT).show();
                                updateButtonState();
                            } else {
                                installApkViaSystemInstaller(file);
                            }
                        }
                    });
                } else {
                    handler.post(new Runnable() {
                        public void run() {
                            installApkViaSystemInstaller(file);
                        }
                    });
                }
            }
        }).start();
    }

    private void installApkViaSystemInstaller(File file) {
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
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_autoinstall_blocked, Toast.LENGTH_LONG).show();
        }
    }

    private static class DownloadTask extends Thread {

        private static final int CONNECT_TIMEOUT_MS = 30000;
        private static final int READ_TIMEOUT_MS = 180000;

        private static final int MAX_ATTEMPTS = 4;
        private static final long RETRY_BACKOFF_MS = 1500;

        private static final java.util.Set<String> httpsUnsupportedHosts =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        private static final class HttpsHandshakeException extends Exception {
            HttpsHandshakeException(Throwable cause) { super(cause); }
        }

        volatile DetailsActivity activity;
        Context appContext;
        String apkUrl;
        String appName;

        String expectedPkg;
        volatile int progressVal = 0;
        volatile boolean isIndeterminate = false;
        volatile boolean isFinished = false;
        volatile String error = null;
        volatile File outputFile = null;
        volatile boolean resultDelivered = false;
        PowerManager.WakeLock wakeLock = null;

        private volatile int totalExpectedLength = -1;

        public DownloadTask(String url, String name, String expectedPkg, Context context) {
            this.apkUrl = url;
            this.appName = name;
            this.expectedPkg = expectedPkg;
            this.appContext = context;
        }

        public void attach(DetailsActivity act) {
            this.activity = act;
            if (isFinished) {
                act.dismissProgress();
                deliverResultOnce();
            } else {
                act.showProgress(appName);
                act.updateProgress(progressVal, isIndeterminate);
            }
        }

        private synchronized void deliverResultOnce() {
            if (resultDelivered) return;
            if (activity == null) return;
            resultDelivered = true;

            if (error != null) {
                Toast.makeText(activity, activity.getString(R.string.toast_download_error_format, error), Toast.LENGTH_LONG).show();
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.installApk(outputFile);
            }
        }

        public void detach() {
            if (activity != null) {
                activity.dismissProgress();
                activity = null;
            }
        }

        private void postIfAttached(Runnable runnable) {
            DetailsActivity currentActivity = activity;
            if (currentActivity != null) {
                currentActivity.handler.post(runnable);
            }
        }

        @Override
        public void run() {
            try {
                try {
                    PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OddMarket:DownloadTask");
                    wakeLock.acquire();
                } catch (Exception e) {
                    FileLogger.w(Utils.TAG, "Failed to acquire wake lock for download", e);
                }

                String originalUrl = apkUrl;
                if (activity != null) {
                    originalUrl = MainActivity.fixApkUrl(activity, apkUrl);
                }
                final String host = extractHost(originalUrl);

                File dir = Utils.resolveApkDownloadDir(appContext);
                dir.mkdirs();

                String safeName = appName == null ? "" : appName.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
                if (safeName.length() == 0) safeName = "app";
                outputFile = new File(dir, safeName + ".apk");

                outputFile.delete();

                String httpsUrl = originalUrl.toLowerCase().startsWith("https://")
                        ? originalUrl
                        : "https://" + stripScheme(originalUrl);

                boolean useHttps = host != null && !httpsUnsupportedHosts.contains(host);
                long resumeFrom = 0;
                Exception lastError = null;

                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    String attemptUrl = useHttps ? httpsUrl : forceHttp(originalUrl);
                    try {
                        attemptOnce(attemptUrl, resumeFrom, useHttps);
                        verifyIsApkOrThrow(outputFile);
                        verifyExpectedPackageOrThrow(outputFile, expectedPkg);
                        lastError = null;
                        break;
                    } catch (HttpsHandshakeException e) {

                        if (host != null) httpsUnsupportedHosts.add(host);
                        useHttps = false;
                        resumeFrom = 0;
                        outputFile.delete();
                        attempt--;
                        continue;
                    } catch (Exception e) {
                        lastError = e;
                        if (useHttps) {

                            if (host != null) httpsUnsupportedHosts.add(host);
                            useHttps = false;
                            resumeFrom = 0;
                            outputFile.delete();
                            attempt--;
                            continue;
                        }
                        resumeFrom = outputFile.exists() ? outputFile.length() : 0;
                        if (attempt < MAX_ATTEMPTS) {
                            try { Thread.sleep(RETRY_BACKOFF_MS); } catch (InterruptedException ignored) {}
                        }
                    }
                }

                if (lastError != null) {
                    throw lastError;
                }

                Utils.setWorldReadable(outputFile);

                DownloadRegistry.registerDownloadedFile(appContext, outputFile.getName());

                isFinished = true;

                postIfAttached(new Runnable() {
                    public void run() {
                        if (activity != null) {
                            activity.dismissProgress();
                        }
                        deliverResultOnce();
                    }
                });
            } catch (final Exception e) {
                isFinished = true;
                error = e.getMessage();

                if (error == null) {
                    if (e instanceof java.net.SocketTimeoutException) {
                        error = "Connection timed out.";
                    } else {
                        error = e.getClass().getSimpleName();
                    }
                }

                postIfAttached(new Runnable() {
                    public void run() {
                        if (activity != null) {
                            activity.dismissProgress();
                        }
                        deliverResultOnce();
                    }
                });
            } finally {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                } catch (Exception ignored) {}
            }
        }

        private void attemptOnce(String startUrl, long resumeFrom, boolean allowHttps) throws Exception {
            HttpURLConnection conn = null;
            InputStream input = null;
            FileOutputStream output = null;

            try {
                URL url = new URL(startUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Connection", "close");
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setInstanceFollowRedirects(false);
                if (resumeFrom > 0) {
                    conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
                }

                int status;
                try {
                    conn.connect();
                    status = conn.getResponseCode();
                } catch (Exception e) {
                    if (allowHttps) throw new HttpsHandshakeException(e);
                    throw e;
                }

                int redirectHops = 0;
                while ((status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307 || status == 308)
                        && redirectHops < 5) {

                    String redirectUrl = conn.getHeaderField("Location");
                    conn.disconnect();

                    if (redirectUrl == null) {
                        break;
                    }
                    if (!allowHttps && redirectUrl.toLowerCase().startsWith("https://")) {
                        redirectUrl = "http://" + redirectUrl.substring(8);
                    }
                    url = new URL(redirectUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestProperty("Connection", "close");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setInstanceFollowRedirects(false);
                    if (resumeFrom > 0) {
                        conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
                    }
                    try {
                        conn.connect();
                        status = conn.getResponseCode();
                    } catch (Exception e) {
                        if (allowHttps) throw new HttpsHandshakeException(e);
                        throw e;
                    }
                    redirectHops++;
                }

                boolean resumedHere = false;
                if (resumeFrom > 0 && status == 206) {
                    resumedHere = true;
                } else if (resumeFrom > 0 && status >= 200 && status < 300) {

                    resumeFrom = 0;
                    outputFile.delete();
                }

                if (status < 200 || status > 299) {
                    throw new Exception("HTTP error code: " + status);
                }

                int reportedLength = conn.getContentLength();
                if (resumedHere) {

                    if (totalExpectedLength <= 0 && reportedLength > 0) {
                        totalExpectedLength = (int) resumeFrom + reportedLength;
                    }
                } else {
                    totalExpectedLength = reportedLength;
                }
                isIndeterminate = (totalExpectedLength <= 0);

                if (activity != null) {
                    activity.handler.post(new Runnable() {
                        public void run() {
                            if (activity != null) activity.updateProgress(progressVal, isIndeterminate);
                        }
                    });
                }

                input = new BufferedInputStream(conn.getInputStream(), 16384);
                output = new FileOutputStream(outputFile, resumedHere);

                byte data[] = new byte[16384];
                long total = resumedHere ? resumeFrom : 0;
                int count;
                int lastProgress = -1;

                while ((count = input.read(data)) != -1) {
                    total += count;

                    if (!isIndeterminate) {
                        progressVal = (int) ((total * 100L) / totalExpectedLength);

                        if (progressVal > lastProgress) {
                            lastProgress = progressVal;
                            if (activity != null) {
                                activity.handler.post(new Runnable() {
                                    public void run() {
                                        if (activity != null) activity.updateProgress(progressVal, false);
                                    }
                                });
                            }
                        }
                    } else {

                        if (total % 512000 < 16384 && activity != null) {
                            activity.handler.post(new Runnable() {
                                public void run() {
                                    if (activity != null) activity.updateProgress(0, true);
                                }
                            });
                        }
                    }
                    output.write(data, 0, count);
                }

                output.flush();
                output.close();
                output = null;

                if (!isIndeterminate && total != totalExpectedLength) {
                    throw new Exception("Incomplete download (" + total + " of " + totalExpectedLength + " bytes).");
                }
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        }

        private static String forceHttp(String url) {
            if (url != null && url.toLowerCase().startsWith("https://")) {
                return "http://" + url.substring(8);
            }
            return url;
        }

        private static String stripScheme(String url) {
            int idx = url.indexOf("://");
            return idx >= 0 ? url.substring(idx + 3) : url;
        }

        private static String extractHost(String url) {
            try {
                return new URL(url).getHost();
            } catch (Exception e) {
                return null;
            }
        }

        private static void verifyIsApkOrThrow(File file) throws Exception {
            java.io.FileInputStream in = null;
            try {
                in = new java.io.FileInputStream(file);
                byte[] header = new byte[4];
                int read = in.read(header);
                if (read != 4 || header[0] != 0x50 || header[1] != 0x4B
                        || header[2] != 0x03 || header[3] != 0x04) {
                    file.delete();
                    throw new Exception("Downloaded file is not a valid package.");
                }
            } catch (java.io.IOException e) {
                file.delete();
                throw new Exception("Could not verify downloaded file.");
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }
        }

        private void verifyExpectedPackageOrThrow(File file, String expectedPkg) throws Exception {
            if (expectedPkg == null || expectedPkg.length() == 0 || appContext == null) {
                return;
            }
            PackageManager pm = appContext.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info == null || info.packageName == null || !expectedPkg.equals(info.packageName)) {
                file.delete();
                throw new Exception("Downloaded package does not match the expected app.");
            }
        }
    }
}
