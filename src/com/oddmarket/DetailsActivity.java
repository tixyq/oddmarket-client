package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.Html;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
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
import java.lang.reflect.Method;
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

    private static final int REVIEWS_LOAD_TIMEOUT_MS = 12000;
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

    private DownloadUi downloadUi;

    private static final int REQUEST_WRITE_STORAGE = 112;
    private static final int REQUEST_POST_NOTIFICATIONS = 113;
    private String pendingApkUrl = null;
    private String pendingAppName = null;

    private FrameLayout rootLayout;
    private FrameLayout overlayLayout;
    private GhostTitle ghostTitle;
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        FileLogger.i(Utils.TAG, "DetailsActivity.onCreate, intent=" + getIntent());
        GhostTitle.prepareWindow(this);
        setTitle(R.string.title_details);

        buildLayout();
        setContentView(rootLayout);
        Theme.applyFonts(rootLayout);

        ghostTitle = GhostTitle.attach(this).setBackVisible(true).setMenuVisible(true)
                .setMode(GhostTitle.MODE_AUTO).setBaseColor(Theme.tabRowBackground()).trackScroll(rootLayout.getChildAt(0));

        applyResponsiveHeaderLayout();
        bindIntentData();
        wireDownloadButton();
        buildScreenshotsRow();
        buildReviewsWebView();

        btnDownload.requestFocus();
    }

    private void buildLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        View originalContentView = LayoutInflater.from(this).inflate(R.layout.details, null);
        originalView = (ViewGroup) originalContentView;
        originalContentView.setBackgroundColor(Theme.windowBackground());

        View headerContainer = originalContentView.findViewById(R.id.details_header_container);
        if (headerContainer != null) {
            headerContainer.setBackgroundColor(Theme.tabRowBackground());
            if (headerContainer.getParent() instanceof LinearLayout) {
                GhostTitle.insertSpacer((LinearLayout) headerContainer.getParent(), Theme.tabRowBackground());
            }
        }

        ScrollView scrollView = new NoAutoScrollScrollView(this);
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

        Utils.setTextColorById(this, R.id.details_caption_screenshots, Theme.textSecondary());
        Utils.setTextColorById(this, R.id.details_caption_description, Theme.textSecondary());

        View screenshotsScroll = findViewById(R.id.details_screenshots_scroll);
        if (screenshotsScroll != null) screenshotsScroll.setBackgroundColor(Theme.tabRowBackground());

        View descBlock = findViewById(R.id.details_desc_block);
        if (descBlock != null) descBlock.setBackgroundColor(Theme.tabRowBackground());

        btnDownload.setTextColor(Theme.textPrimary());
        btnDownload.setBackgroundDrawable(Theme.buttonBackground());
        btnDownload.setPadding(
                Theme.dpToPx(this, 12), Theme.dpToPx(this, 8),
                Theme.dpToPx(this, 12), Theme.dpToPx(this, 8));
        flattenButton(btnDownload);

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

    private void flattenButton(View button) {
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
                if (isDownloadingThisApp()) {
                    DownloadCenter.cancel(DetailsActivity.this, currentPkg, appName);
                } else if (btnState == STATE_OPEN) {
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

                            finalUrl = Utils.httpsToHttp(finalUrl);
                            if (!finalUrl.toLowerCase().startsWith("http://")) {
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
        reviewsDead = false;
        container.setVisibility(android.view.View.GONE);

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
        Utils.configureWebView(reviewsWebView);
        reviewsWebView.setBackgroundColor(Theme.windowBackground());
        CookieHelper.enableCookiesForWebView(reviewsWebView);

        final WebViewClient client;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            client = new HttpAwareWebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    CookieHelper.flush();
                    cancelReviewsTimeout();
                    if (!reviewsDead) {
                        container.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    hideReviewsBlock(container);
                }

                @Override
                protected void onMainFrameHttpError() {
                    hideReviewsBlock(container);
                }

                @Override
                public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                    handler.proceed();
                }
            };
        } else {
            client = new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    CookieHelper.flush();
                    cancelReviewsTimeout();
                    if (!reviewsDead) {
                        container.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    hideReviewsBlock(container);
                }

                public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                    handler.proceed();
                }
            };
        }
        reviewsWebView.setWebViewClient(client);

        reviewsWebView.setWebChromeClient(new WebChromeClient());

        container.addView(reviewsWebView);
        reviewsWebView.loadUrl(UrlBuilder.reviewsUrl(this, currentPkg));

        reviewsTimeoutRunnable = new Runnable() {
            public void run() {
                if (isFinishing() || reviewsWebView == null) return;
                reviewsWebView.stopLoading();
                hideReviewsBlock(container);
            }
        };
        handler.postDelayed(reviewsTimeoutRunnable, REVIEWS_LOAD_TIMEOUT_MS);
    }

    private boolean reviewsDead = false;

    private void hideReviewsBlock(android.view.View container) {
        reviewsDead = true;
        cancelReviewsTimeout();
        container.setVisibility(View.GONE);
    }

    private void cancelReviewsTimeout() {
        if (reviewsTimeoutRunnable != null) {
            handler.removeCallbacks(reviewsTimeoutRunnable);
            reviewsTimeoutRunnable = null;
        }
    }

    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        if (ghostTitle != null) ghostTitle.setTitle(title);
    }

    private void showOverlay(ArrayList<String> images, int startIndex) {
        if (overlayLayout != null && images != null && startIndex >= 0 && startIndex < images.size()) {
            overlayImages = images;
            overlayGallery.setAdapter(new ScreenshotAdapter());
            overlayGallery.setSelection(startIndex);
            overlayLayout.setVisibility(View.VISIBLE);
            if (ghostTitle != null) ghostTitle.setVisibility(View.GONE);
            if (originalView != null) {
                originalView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            overlayGallery.requestFocus();
        }
    }

    private void closeOverlay() {
        if (overlayLayout != null && overlayLayout.getVisibility() == View.VISIBLE) {
            overlayLayout.setVisibility(View.GONE);
            if (ghostTitle != null) ghostTitle.setVisibility(View.VISIBLE);
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
        if (downloadUi == null) {
            downloadUi = new DownloadUi(this, ghostTitle, new DownloadUi.Callback() {
                public void onDownloadUiChanged() {
                    if (!isFinishing()) updateButtonState();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        FileLogger.i(Utils.TAG, "DetailsActivity.onDestroy");
        if (pendingReviewsAccountCheck != null) {
            pendingReviewsAccountCheck.cancel();
            pendingReviewsAccountCheck = null;
        }
        if (reviewsWebView != null) {
            cancelReviewsTimeout();
            reviewsWebView.stopLoading();
            reviewsWebView.setWebViewClient(null);
            reviewsWebView.setWebChromeClient(null);
            reviewsWebView.destroy();
            reviewsWebView = null;
            CookieHelper.flush();
        }
    }

    private void updateButtonState() {
        boolean isLegacy = MainActivity.isLegacyDownloadActive(this);

        if (isDownloadingThisApp()) {
            btnDownload.setText(R.string.btn_cancel);
            return;
        }

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

    private boolean isDownloadingThisApp() {
        return DownloadCenter.isTracked(currentPkg, appName);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem itemShare = menu.add(0, MENU_ID_SHARE, 0, R.string.menu_share);
        itemShare.setIcon(android.R.drawable.ic_menu_share);
        Utils.showInActionBar(itemShare, Utils.SHOW_AS_ACTION_NEVER);

        MenuItem itemUninstall = menu.add(0, MENU_ID_UNINSTALL, 1, R.string.menu_uninstall);
        Utils.showInActionBar(itemUninstall, Utils.SHOW_AS_ACTION_NEVER);

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
        FileLogger.i(Utils.TAG, "downloadAndInstall: " + appName + " <- " + apkUrl);
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

        if (isDownloadingThisApp()) return;

        requestNotificationPermission();

        boolean started = DownloadCenter.begin(this, apkUrl, appName, currentPkg,
                btnState == STATE_UPDATE, getIntent().getExtras());
        if (!started) {
            Toast.makeText(this, R.string.toast_download_start_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            Method check = Context.class.getMethod("checkSelfPermission", String.class);
            int r = (Integer) check.invoke(this, "android.permission.POST_NOTIFICATIONS");
            if (r != PackageManager.PERMISSION_GRANTED) {
                Method req = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                req.invoke(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_POST_NOTIFICATIONS);
            }
        } catch (Exception ignored) {
        }
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

    private static final class NoAutoScrollScrollView extends ScrollView {
        NoAutoScrollScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }
}
