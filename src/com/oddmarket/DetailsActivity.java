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
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
    private View overlayDim;
    private boolean overlayShowFull = false;
    private int overlayStartIndex = -1;
    private ZoomView zoomView;
    private boolean closing = false;
    private ArrayList<View> overlaySources;
    private ArrayList<View> screenshotThumbs;
    private HorizontalScrollView screenshotsScroll;
    private final int[] locA = new int[2];
    private final int[] locB = new int[2];
    private java.util.concurrent.CountDownLatch thumbsLatch;
    private final Runnable swapToFullRunnable = new Runnable() {
        public void run() {
            overlayShowFull = true;
            refreshOverlayImages();
        }
    };
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

        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        content.addView(overlayLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        applyResponsiveHeaderLayout();
        bindIntentData();
        wireDownloadButton();
        buildScreenshotsRow();
        startFullImageSequence();
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
        }

        ScrollView scrollView = new NoAutoScrollScrollView(this);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        scrollView.setBackgroundColor(Theme.tabRowBackground());
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
        overlayDim = new View(this);
        overlayDim.setBackgroundColor(0xCC000000);
        overlayLayout.addView(overlayDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
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
        // Created up front so it is already laid out when the first zoom starts.
        ensureZoomView();
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
        final ArrayList<View> iconSources = new ArrayList<View>();
        iconSources.add(imgIcon);
        imgIcon.setFocusable(true);
        imgIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverlay(appIconList, iconSources, 0);
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

    // A thumbnail counts as loaded when it shows a real picture: not empty (still downloading) and
    // not the ic_pic placeholder that loadSized puts in when the download failed.
    private boolean isThumbLoaded(View v) {
        if (!(v instanceof ImageView)) return false;
        android.graphics.drawable.Drawable d = ((ImageView) v).getDrawable();
        if (d == null) return false;
        try {
            android.graphics.drawable.Drawable ph = getResources().getDrawable(R.drawable.ic_pic);
            if (ph != null && d.getConstantState() != null
                    && d.getConstantState() == ph.getConstantState()) return false;
        } catch (Exception e) {
            // fall through: treat as loaded
        }
        return true;
    }

    private void buildScreenshotsRow() {
        screenshotsScroll = (HorizontalScrollView) findViewById(R.id.details_screenshots_scroll);
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

        int thumbMaxPx = (int) (heightPx * 1.25f);
        thumbsLatch = new java.util.concurrent.CountDownLatch(appScreenshots.size());
        screenshotThumbs = new ArrayList<View>();
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

            MainActivity.loadSized(url, img, thumbMaxPx, thumbsLatch);

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
                    if (!isThumbLoaded(v)) return;
                    showOverlay(appScreenshots, screenshotThumbs, index);
                }
            });

            screenshotThumbs.add(img);
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

    private void showOverlay(ArrayList<String> images, ArrayList<View> sources, int startIndex) {
        if (overlayLayout != null && images != null && sources != null
                && startIndex >= 0 && startIndex < images.size() && startIndex < sources.size()) {
            resetOverlayState();
            closing = false;
            overlayImages = images;
            overlaySources = sources;
            overlayStartIndex = startIndex;
            overlayShowFull = false;
            handler.removeCallbacks(swapToFullRunnable);
            MainActivity.prefetchFull(getApplicationContext(), images.get(startIndex));
            overlayGallery.setAdapter(new ScreenshotAdapter());
            overlayGallery.setSelection(startIndex);
            overlayLayout.setVisibility(View.VISIBLE);
            if (originalView != null) {
                originalView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            if (startZoom(images.get(startIndex), sources.get(startIndex))) return;
            overlayGallery.requestFocus();
            handler.postDelayed(swapToFullRunnable, 110L);
        }
    }

    // Same timing model as the search dock on the main page (DOCK_ANIM_MS / DOCK_MAX_STEP):
    // linear progress over a fixed time, a per-frame step cap so a slow frame never makes it jump,
    // and smoothstep applied to staggered phases of the progress.
    private static final long ZOOM_MS = 210L;
    private static final float ZOOM_MAX_STEP = 0.12f;
    private static final int DIM_ALPHA = 0xCC;

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    // Half smoothstep, half linear: the motion still eases a little at both ends but is much
    // closer to constant speed than plain smoothstep.
    private static float ease(float t) {
        return 0.5f * t + 0.5f * smooth(t);
    }

    // The side clipping of the zoom picture (the strip's padding) is released between the moment
    // the black backdrop reaches this opacity (absolute alpha 0..1) and the mirror moment, where it
    // is that far from its final opacity. The curve is symmetric, so opening and closing match.
    private static final float CLIP_RELEASE_DIM = 0.05f;
    private static final float DIM_PHASE = 0.6f;

    private int galleryPadW() {
        return (int) (getResources().getDisplayMetrics().widthPixels * 0.065f);
    }

    private int galleryPadH() {
        return (int) (getResources().getDisplayMetrics().heightPixels * 0.065f);
    }

    private static Bitmap bitmapOf(View v) {
        if (!(v instanceof ImageView)) return null;
        android.graphics.drawable.Drawable d = ((ImageView) v).getDrawable();
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
        return null;
    }

    // Where a FIT_CENTER image of bitmap size b is drawn inside view v, in content coordinates.
    private boolean fitRect(View v, Bitmap b, ViewGroup content, RectF out) {
        int bw = b.getWidth();
        int bh = b.getHeight();
        if (bw <= 0 || bh <= 0 || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        float availW = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
        float availH = v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
        if (availW <= 0f || availH <= 0f) return false;
        v.getLocationInWindow(locA);
        content.getLocationInWindow(locB);
        float s = Math.min(availW / bw, availH / bh);
        float w = bw * s;
        float h = bh * s;
        float x = locA[0] - locB[0] + v.getPaddingLeft() + (availW - w) / 2f;
        float y = locA[1] - locB[1] + v.getPaddingTop() + (availH - h) / 2f;
        out.set(x, y, x + w, y + h);
        return true;
    }

    private void ensureZoomView() {
        if (zoomView == null) {
            zoomView = new ZoomView(this);
            zoomView.setClickable(true);
            overlayLayout.addView(zoomView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        }
    }

    // The part of the screen where the screenshot strip actually shows its thumbnails (the strip
    // clips its children to its own side paddings). The zoom picture is clipped to it while it is
    // still near the thumbnail, so it never draws over the page's edge paddings.
    private RectF thumbClip(View thumb, ViewGroup content, int ch) {
        if (screenshotsScroll == null || thumb == null || thumb.getParent() == null
                || thumb.getParent().getParent() != screenshotsScroll) return null;
        screenshotsScroll.getLocationInWindow(locA);
        content.getLocationInWindow(locB);
        float x = locA[0] - locB[0];
        return new RectF(x + screenshotsScroll.getPaddingLeft(), -ch,
                x + screenshotsScroll.getWidth() - screenshotsScroll.getPaddingRight(), 2f * ch);
    }

    private boolean startZoom(String url, View source) {
        if (source == null || !(overlayLayout.getParent() instanceof ViewGroup)) return false;
        ViewGroup content = (ViewGroup) overlayLayout.getParent();
        int cw = content.getWidth();
        int ch = content.getHeight();
        // The thumbnail bitmap is shared with the LRU iconCache and can be evicted from it at any
        // time (lists on other screens push it out), which used to silently skip the animation.
        // The bitmap the source view is actually showing is always the right one to animate.
        Bitmap small = bitmapOf(source);
        if (small == null) small = MainActivity.cachedSmall(this, url);
        if (small == null) small = MainActivity.cachedFull(this, url);
        if (small == null || cw <= 0 || ch <= 0) return false;
        int bw = small.getWidth();
        int bh = small.getHeight();
        if (bw <= 0 || bh <= 0) return false;

        RectF from = new RectF();
        if (!fitRect(source, small, content, from)) return false;

        float availW = cw - 2 * galleryPadW();
        float availH = ch - 2 * galleryPadH();
        float ts = Math.min(availW / bw, availH / bh);
        float tw = bw * ts;
        float th = bh * ts;
        float tx = (cw - tw) / 2f;
        float ty = (ch - th) / 2f;
        RectF to = new RectF(tx, ty, tx + tw, ty + th);

        ensureZoomView();
        // The source thumbnail stays where it is: the animation draws a clone of it.
        overlayDim.setVisibility(View.INVISIBLE);
        overlayGallery.setVisibility(View.INVISIBLE);
        zoomView.setVisibility(View.VISIBLE);
        zoomView.begin(small, from, to, true, thumbClip(source, content, ch), new Runnable() {
            public void run() {
                finishZoom();
            }
        });
        return true;
    }

    private void finishZoom() {
        if (zoomView == null || zoomView.getVisibility() != View.VISIBLE) return;
        zoomView.stop();
        overlayDim.setVisibility(View.VISIBLE);
        overlayGallery.setVisibility(View.VISIBLE);
        zoomView.setVisibility(View.GONE);
        overlayGallery.requestFocus();
        handler.post(swapToFullRunnable);
    }

    private boolean startClose() {
        if (overlayImages == null || overlaySources == null) return false;
        if (!(overlayLayout.getParent() instanceof ViewGroup)) return false;
        final Runnable done = new Runnable() {
            public void run() {
                finishClose();
            }
        };

        if (zoomView != null && zoomView.getVisibility() == View.VISIBLE) {
            // Still opening: turn around from where the animation currently is.
            closing = true;
            zoomView.reverse(done);
            return true;
        }

        ViewGroup content = (ViewGroup) overlayLayout.getParent();
        int pos = overlayGallery.getSelectedItemPosition();
        if (pos < 0 || pos >= overlayImages.size() || pos >= overlaySources.size()) return false;
        View child = overlayGallery.getSelectedView();
        if (child == null) {
            child = overlayGallery.getChildAt(pos - overlayGallery.getFirstVisiblePosition());
        }
        Bitmap cur = bitmapOf(child);
        if (cur == null) return false;

        View thumb = overlaySources.get(pos);
        Bitmap tb = bitmapOf(thumb);
        if (tb == null) tb = cur;

        RectF big = new RectF();
        RectF small = new RectF();
        if (!fitRect(child, cur, content, big)) return false;
        if (!fitRect(thumb, tb, content, small)) return false;

        ensureZoomView();
        closing = true;
        overlayDim.setVisibility(View.INVISIBLE);
        overlayGallery.setVisibility(View.INVISIBLE);
        zoomView.setVisibility(View.VISIBLE);
        zoomView.begin(cur, small, big, false, thumbClip(thumb, content, content.getHeight()), done);
        return true;
    }

    private void resetOverlayState() {
        if (zoomView != null) {
            zoomView.stop();
            zoomView.setVisibility(View.GONE);
        }
        if (overlayDim != null) overlayDim.setVisibility(View.VISIBLE);
        if (overlayGallery != null) overlayGallery.setVisibility(View.VISIBLE);
    }

    private void finishClose() {
        closing = false;
        handler.removeCallbacks(swapToFullRunnable);
        resetOverlayState();
        if (overlayLayout != null) overlayLayout.setVisibility(View.GONE);
        if (originalView != null) {
            originalView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    private final class ZoomView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF thumb = new RectF();
        private final RectF big = new RectF();
        private final RectF cur = new RectF();
        private final RectF drawn = new RectF();
        private final RectF clipThumb = new RectF();
        private final RectF clipNow = new RectF();
        private boolean hasClip;
        private float clipStart;
        private float clipEnd;
        private final Rect dirty = new Rect();
        private Bitmap bmp;
        private float p;
        private float goal;
        private long last;
        private boolean running;
        private boolean haveDrawn;
        private float lastDim = -1f;
        private Runnable onEnd;

        private final Runnable step = new Runnable() {
            public void run() {
                if (!running) return;
                long now = android.os.SystemClock.uptimeMillis();
                float dt = (now - last) / (float) ZOOM_MS;
                if (dt > ZOOM_MAX_STEP) dt = ZOOM_MAX_STEP;
                last = now;
                if (p < goal) p = Math.min(goal, p + dt);
                else if (p > goal) p = Math.max(goal, p - dt);
                invalidateFrame();
                if (p != goal) {
                    Utils.postFrame(ZoomView.this, this);
                } else {
                    running = false;
                    Utils.postFrame(ZoomView.this, endRun);
                }
            }
        };

        private final Runnable endRun = new Runnable() {
            public void run() {
                Runnable r = onEnd;
                onEnd = null;
                if (r != null) r.run();
            }
        };

        ZoomView(Context c) {
            super(c);
            setVisibility(View.GONE);
        }

        // opening: progress 0 (thumbnail) -> 1 (gallery); closing runs the same curve from 1 back to 0.
        void begin(Bitmap b, RectF thumbRect, RectF bigRect, boolean opening, RectF thumbClipRect, Runnable end) {
            stop();
            hasClip = thumbClipRect != null;
            if (hasClip) {
                clipThumb.set(thumbClipRect);
                float share = CLIP_RELEASE_DIM * 255f / DIM_ALPHA;
                clipStart = pForDim(share);
                clipEnd = pForDim(1f - share);
            }
            bmp = b;
            thumb.set(thumbRect);
            big.set(bigRect);
            onEnd = end;
            p = opening ? 0f : 1f;
            goal = opening ? 1f : 0f;
            haveDrawn = false;
            lastDim = -1f;
            last = android.os.SystemClock.uptimeMillis();
            running = true;
            invalidate();
            Utils.postFrame(this, step);
        }

        void reverse(Runnable end) {
            removeCallbacks(step);
            removeCallbacks(endRun);
            onEnd = end;
            goal = 0f;
            last = android.os.SystemClock.uptimeMillis();
            running = true;
            Utils.postFrame(this, step);
        }

        void stop() {
            running = false;
            onEnd = null;
            removeCallbacks(step);
            removeCallbacks(endRun);
        }

        private float dimP() {
            return ease(clamp01(p / DIM_PHASE));
        }

        // Progress at which the backdrop reaches the given share (0..1) of its full dim (bisection).
        private float pForDim(float target) {
            float lo = 0f;
            float hi = DIM_PHASE;
            for (int i = 0; i < 20; i++) {
                float mid = (lo + hi) / 2f;
                if (ease(mid / DIM_PHASE) < target) lo = mid; else hi = mid;
            }
            return hi;
        }

        private void place(RectF out) {
            float px = ease(clamp01(p / 0.6f));
            float py = ease(clamp01((p - 0.2f) / 0.8f));
            float ps = ease(clamp01(p / 0.85f));
            float w = thumb.width() + (big.width() - thumb.width()) * ps;
            float h = thumb.height() + (big.height() - thumb.height()) * ps;
            float cx = thumb.centerX() + (big.centerX() - thumb.centerX()) * px;
            float cy = thumb.centerY() + (big.centerY() - thumb.centerY()) * py;
            out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }

        // While the dim is still changing the whole screen has to be redrawn; once it has settled
        // only the area the picture moved through is invalidated, which is what keeps this cheap
        // on old software-rendered devices.
        private void invalidateFrame() {
            float d = dimP();
            if (!haveDrawn || d != lastDim) {
                invalidate();
                return;
            }
            place(cur);
            dirty.set((int) Math.min(cur.left, drawn.left) - 2, (int) Math.min(cur.top, drawn.top) - 2,
                    (int) Math.max(cur.right, drawn.right) + 3, (int) Math.max(cur.bottom, drawn.bottom) + 3);
            invalidate(dirty);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp == null) return;
            float d = dimP();
            if (d > 0f) c.drawColor(((int) (DIM_ALPHA * d + 0.5f)) << 24);
            place(cur);
            float k = 1f;
            if (hasClip) {
                // The strip's side paddings are ignored more and more, spreading outwards from the
                // strip, but only once the backdrop is visible; when fully released nothing clips.
                float p0 = clipStart;
                k = ease(clamp01((p - p0) / (clipEnd - p0)));
            }
            if (hasClip && k < 1f) {
                clipNow.set(clipThumb.left + (0f - clipThumb.left) * k, clipThumb.top + (0f - clipThumb.top) * k,
                        clipThumb.right + (getWidth() - clipThumb.right) * k,
                        clipThumb.bottom + (getHeight() - clipThumb.bottom) * k);
                c.save();
                c.clipRect(clipNow);
                c.drawBitmap(bmp, null, cur, paint);
                c.restore();
            } else {
                c.drawBitmap(bmp, null, cur, paint);
            }
            drawn.set(cur);
            haveDrawn = true;
            lastDim = d;
        }
    }

    private void startFullImageSequence() {
        final ArrayList<String> shots = appScreenshots == null ? new ArrayList<String>() : new ArrayList<String>(appScreenshots);
        final String icon = (appIconList != null && appIconList.size() > 0) ? appIconList.get(0) : null;
        final java.util.concurrent.CountDownLatch latch = thumbsLatch;
        final Context appCtx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    if (latch != null) latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < shots.size(); i++) {
                    if (isFinishing()) return;
                    MainActivity.loadFullSync(appCtx, shots.get(i));
                }
                if (isFinishing()) return;
                if (icon != null) MainActivity.loadFullSync(appCtx, icon);
            }
        });
        t.start();
    }

    private void refreshOverlayImages() {
        if (overlayGallery == null || overlayImages == null) return;
        int first = overlayGallery.getFirstVisiblePosition();
        int count = overlayGallery.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = overlayGallery.getChildAt(i);
            int pos = first + i;
            if (child instanceof ImageView && pos >= 0 && pos < overlayImages.size()) {
                MainActivity.swapToFull((ImageView) child, overlayImages.get(pos), 0);
            }
        }
    }

    private void closeOverlay() {
        if (overlayLayout == null || overlayLayout.getVisibility() != View.VISIBLE) return;
        if (closing) {
            finishClose();
            return;
        }
        handler.removeCallbacks(swapToFullRunnable);
        if (!startClose()) finishClose();
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

                int paddingW = galleryPadW();
                int paddingH = galleryPadH();
                img.setPadding(paddingW, paddingH, paddingW, paddingH);
            } else {
                img = (ImageView) convertView;
            }

            String url = overlayImages.get(position);
            MainActivity.showFullOrSmall(img, url, overlayShowFull || position == overlayStartIndex);

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
        handler.removeCallbacks(swapToFullRunnable);
        if (zoomView != null) zoomView.stop();
        MainActivity.clearFullCache();
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

    private static final class NoAutoScrollScrollView extends TitleScrollView {
        NoAutoScrollScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }
}
