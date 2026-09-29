package com.oddmarket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.util.Log;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.applyLocale(newBase));
    }
    private ScrollView scrollView;
    private ListView listView;
    private EditText searchBox;
    private Button tabApps, tabGames;
    private TextView statusTextView;
    private TextView accountBadgeView;

    private static final int ID_BANNER = Utils.generateViewId();

    private View bannerContainer;
    private ImageView bannerViewFront;
    private ImageView bannerViewBack;

    private List<AppItem> appList = new ArrayList<AppItem>();
    private AppAdapter adapter;

    private final Map<String, Double> ratingsByPkg = new LinkedHashMap<String, Double>();

    private String currentTab = "all";
    private String currentSearchQuery = "";

    private boolean isTablet = false;
    private String deepLinkPackage = null;
    private boolean lastRussianDomainState = false;

    private int currentPage = 1;
    private int lastLoadedPage = 1;

    private String lastLoadedTab = null;
    private String lastLoadedQuery = null;
    private boolean hasMorePages = false;
    private boolean hasPrevPages = false;
    private boolean isLoading = false;
    private int requestSeq = 0;

    private AlertDialog updateDialog;

    private boolean isDestroyed = false;

    private static final long ACCOUNT_CHECK_THROTTLE_MS = 30000;
    private static final long RATINGS_THROTTLE_MS = 600000;
    private long lastAccountCheckMs = 0;
    private long lastRatingsOkMs = 0;

    private AccountManager.Cancelable pendingAccountCheck;
    private AccountManager.Cancelable pendingRatingsFetch;
    private AccountManager.Cancelable pendingDeleteAccount;

    private boolean forceAccountCheckOnNextResume = false;
    private boolean accountRefreshDeferred = false;

    private android.os.Handler searchHandler = new android.os.Handler();
    private Runnable searchRunnable = new Runnable() {
        public void run() {
            if (isDestroyed) return;
            currentPage = 1;
            loadData(true, true);
        }
    };

    private List<JSONObject> bannersList = new ArrayList<JSONObject>();
    private int currentBannerIdx = 0;
    private boolean isFrontVisible = true;
    private android.os.Handler bannerHandler = new android.os.Handler();
    private Runnable bannerRunnable = new Runnable() {
        public void run() {
            if (isDestroyed) return;
            switchBanner();
        }
    };

    private static final class IconCache {
        private static final int MAX_CACHE_BYTES = 6 * 1024 * 1024;

        private final LinkedHashMap<String, Bitmap> map =
                new LinkedHashMap<String, Bitmap>(16, 0.75f, true);
        private int currentBytes = 0;

        private static int sizeOf(Bitmap b) {
            if (b == null) return 0;
            return b.getRowBytes() * b.getHeight();
        }

        synchronized boolean containsKey(String key) {
            return map.containsKey(key);
        }

        synchronized Bitmap get(String key) {
            return map.get(key);
        }

        synchronized void put(String key, Bitmap value) {
            Bitmap old = map.put(key, value);
            currentBytes += sizeOf(value) - sizeOf(old);
            trim();
        }

        private void trim() {
            Iterator<Map.Entry<String, Bitmap>> it = map.entrySet().iterator();
            while (currentBytes > MAX_CACHE_BYTES && it.hasNext()) {
                Map.Entry<String, Bitmap> eldest = it.next();
                currentBytes -= sizeOf(eldest.getValue());
                it.remove();
            }
        }
    }

    public static final IconCache iconCache = new IconCache();

    private class AspectImageView extends ImageView {
        public AspectImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d != null && d.getIntrinsicWidth() > 0) {
                int width = View.MeasureSpec.getSize(widthMeasureSpec);
                int height = width * d.getIntrinsicHeight() / d.getIntrinsicWidth();
                setMeasuredDimension(width, height);
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    private FrameLayout bannerImageWrapper;
    private View searchClearContainer;
    private FrameLayout rootLayout;
    private GhostTitle ghostTitle;
    private DownloadUi downloadUi;
    private View ghostSpacer;

    private ViewGroup searchDock;
    private View searchPlaceholder;
    private int dockRightInsetPx;
    private boolean searchDockShown = false;
    private final int[] dockLocA = new int[2];
    private final int[] dockLocB = new int[2];

    private static final long SEARCH_DEBOUNCE_MS = 350L;
    private static final long PAGE_CACHE_TTL_MS = 90L * 1000L;

    private static final class CachedPage {
        List<AppItem> items;
        boolean more;
        boolean prev;
        long time;
    }

    private final LinkedHashMap<String, CachedPage> pageCache =
            new LinkedHashMap<String, CachedPage>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
                    return size() > 24;
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FileLogger.init(this);
        FileLogger.i(Utils.TAG, "MainActivity.onCreate start, intent=" + getIntent());

        GhostTitle.prepareWindow(this);

        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setTitle(R.string.app_name);

        initPreferences();
        detectTablet();
        buildRootLayout();
        bindViews();
        setupSearchDock();
        buildBannerContainer();
        wireBannerClick();
        wireListAdapter();
        wireSearchBox();
        wireTabButtons();
        wireFocusOrder();

        handleDeepLink(getIntent());
        checkAndOpenDeepLink();

        Map<String, Double> cachedRatings = AccountManager.cachedRatings(this);
        if (cachedRatings != null) {
            ratingsByPkg.putAll(cachedRatings);
        }

        loadData(true);
        loadBanners();

        listView.post(new Runnable() {
            public void run() {
                listView.requestFocus();
            }
        });
        FileLogger.i(Utils.TAG, "MainActivity.onCreate done");
    }

    @Override
    protected void onTitleChanged(CharSequence title, int color) {
        super.onTitleChanged(title, color);
        if (ghostTitle != null) ghostTitle.setTitle(title);
    }

    private void initPreferences() {
        android.content.SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        if (!prefs.contains("rus_url_fix")) {
            boolean isRu = "RU".equalsIgnoreCase(Utils.deviceCountry);
            Utils.savePrefs(prefs.edit().putBoolean("rus_url_fix", isRu));
        }
        if (!prefs.contains("legacy_download")) {
            Utils.savePrefs(prefs.edit().putBoolean("legacy_download", false));
        }
        lastRussianDomainState = isRussianDomainActive(this);
    }

    private void detectTablet() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        float dpWidth = metrics.widthPixels / metrics.density;
        isTablet = dpWidth >= 600;
    }

    private void buildRootLayout() {
        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        rootLayout.setBackgroundColor(Theme.windowBackground());

        LinearLayout originalView = (LinearLayout) LayoutInflater.from(this).inflate(R.layout.main, null);
        originalView.setBackgroundColor(Theme.windowBackground());
        ghostSpacer = GhostTitle.insertSpacer(originalView, Theme.tabRowBackground());
        View headerView = originalView.findViewById(R.id.main_header);
        if (headerView != null) headerView.setBackgroundColor(Theme.tabRowBackground());

        accountBadgeView = new TextView(this);
        accountBadgeView.setTextSize(13);
        accountBadgeView.setTextColor(Theme.textSecondary());
        accountBadgeView.setVisibility(View.GONE);
        if (!isTablet && headerView instanceof LinearLayout) {
            accountBadgeView.setPadding(
                    Theme.dpToPx(this, 12), Theme.dpToPx(this, 6),
                    Theme.dpToPx(this, 12), Theme.dpToPx(this, 2));
            accountBadgeView.setGravity(android.view.Gravity.RIGHT);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ((LinearLayout) headerView).addView(accountBadgeView, 0, badgeParams);
        }
        updateAccountBadge(AccountManager.cachedNickname(this));

        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0x00000000);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        scrollView.setFillViewport(true);

        try {
            java.lang.reflect.Method m = android.view.View.class.getMethod("setScrollbarFadingEnabled", boolean.class);
            m.invoke(scrollView, false);
        } catch (Exception e) {
            Log.d(Utils.TAG, "setScrollbarFadingEnabled not available", e);
        }

        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(originalView);

        statusTextView = new TextView(this);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1.0f);
        statusTextView.setLayoutParams(tvParams);
        statusTextView.setGravity(android.view.Gravity.CENTER);
        statusTextView.setMinimumHeight(Theme.dpToPx(this, 96));
        statusTextView.setPadding(Theme.dpToPx(this, 16), 0, Theme.dpToPx(this, 16), 0);
        statusTextView.setTextSize(18);
        statusTextView.setTextColor(Theme.textSecondary());
        statusTextView.setCompoundDrawablePadding(15);
        statusTextView.setVisibility(View.GONE);
        originalView.addView(statusTextView);

        rootLayout.addView(scrollView, 0);

        setContentView(rootLayout);
        Theme.applyFonts(rootLayout);

        ghostTitle = GhostTitle.attach(this).setBackVisible(false).setMenuVisible(true)
                .setMode(GhostTitle.MODE_AUTO).setBaseColor(Theme.tabRowBackground()).trackScroll(scrollView);
    }

    private void bindViews() {
        listView = (ListView) findViewById(R.id.app_list);
        listView.setBackgroundColor(0x00000000);
        listView.setCacheColorHint(0x00000000);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setFocusable(true);
        listView.setFocusableInTouchMode(true);
        listView.setItemsCanFocus(true);

        searchBox = (EditText) findViewById(R.id.search_box);
        searchBox.setTextColor(Theme.textPrimary());
        searchBox.setHintTextColor(Theme.textHint());
        searchBox.setBackgroundDrawable(Theme.editTextBackground());
        searchBox.setPadding(
                Theme.dpToPx(this, 8), Theme.dpToPx(this, 6),
                Theme.dpToPx(this, 40), Theme.dpToPx(this, 6));

        searchClearContainer = findViewById(R.id.search_clear_container);
        TextView searchClearText = (TextView) findViewById(R.id.search_clear);
        if (searchClearText != null) searchClearText.setTextColor(Theme.textPrimary());
        Theme.applySoftRows(searchClearContainer);

        tabApps = (Button) findViewById(R.id.tab_apps);
        tabGames = (Button) findViewById(R.id.tab_games);
        tabApps.setTextColor(Theme.textPrimary());
        tabGames.setTextColor(Theme.textPrimary());
        tabApps.setBackgroundDrawable(Theme.buttonBackground());
        tabGames.setBackgroundDrawable(Theme.buttonBackground());
        tabApps.setPadding(Theme.dpToPx(this, 12), Theme.dpToPx(this, 8), Theme.dpToPx(this, 12), Theme.dpToPx(this, 8));
        tabGames.setPadding(Theme.dpToPx(this, 12), Theme.dpToPx(this, 8), Theme.dpToPx(this, 12), Theme.dpToPx(this, 8));

        View spacer = findViewById(R.id.tabs_list_spacer);
        if (spacer != null) spacer.setBackgroundColor(Theme.tabRowBackground());

        bannerImageWrapper = (FrameLayout) findViewById(R.id.banner_container);
        bannerImageWrapper.setVisibility(View.VISIBLE);
        bannerImageWrapper.setFocusable(true);
        bannerImageWrapper.setBackgroundDrawable(Theme.rowSelectorBackground());
    }

    private void buildBannerContainer() {
        bannerImageWrapper.removeAllViews();

        bannerViewBack = new AspectImageView(this);
        bannerViewBack.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bannerViewBack.setScaleType(ImageView.ScaleType.FIT_XY);
        bannerImageWrapper.addView(bannerViewBack);

        bannerViewFront = new AspectImageView(this);
        bannerViewFront.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bannerViewFront.setScaleType(ImageView.ScaleType.FIT_XY);
        bannerImageWrapper.addView(bannerViewFront);

        ViewGroup mainLayout = (ViewGroup) bannerImageWrapper.getParent();
        int bannerIndex = mainLayout.indexOfChild(bannerImageWrapper);
        mainLayout.removeView(bannerImageWrapper);

        bannerImageWrapper.setPadding(0, 0, 0, 0);

        LayoutInflater inflater = LayoutInflater.from(this);
        if (isTablet) {
            View row = inflater.inflate(R.layout.banner_row_tablet, mainLayout, false);
            FrameLayout slot = (FrameLayout) row.findViewById(R.id.banner_slot);
            View itemsContainer = row.findViewById(R.id.banner_items_container);
            if (itemsContainer != null) itemsContainer.setBackgroundColor(Theme.tabRowBackground());
            bannerImageWrapper.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            slot.addView(bannerImageWrapper);

            FrameLayout dummySlot = (FrameLayout) row.findViewById(R.id.banner_dummy);
            if (dummySlot != null && accountBadgeView != null) {
                if (accountBadgeView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) accountBadgeView.getParent()).removeView(accountBadgeView);
                }
                accountBadgeView.setPadding(
                        Theme.dpToPx(this, 8), Theme.dpToPx(this, 6),
                        Theme.dpToPx(this, 8), Theme.dpToPx(this, 6));
                accountBadgeView.setGravity(android.view.Gravity.RIGHT);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                badgeParams.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
                dummySlot.addView(accountBadgeView, badgeParams);

                Theme.applyFont(accountBadgeView);
            }

            row.setVisibility(View.GONE);
            mainLayout.addView(row, bannerIndex);

            bannerContainer = row;
        } else {
            View row = inflater.inflate(R.layout.banner_row_phone, mainLayout, false);
            bannerImageWrapper.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((ViewGroup) row).addView(bannerImageWrapper);

            row.setVisibility(View.GONE);
            mainLayout.addView(row, bannerIndex);

            bannerContainer = row;
        }
    }

    private void wireBannerClick() {
        bannerImageWrapper.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentBannerIdx < bannersList.size()) {
                    JSONObject cur = bannersList.get(currentBannerIdx);
                    String link = cur.optString("link");
                    if (link != null && link.length() > 0) {
                        link = MainActivity.applyRussianDomain(MainActivity.this, link);
                        try {
                            if (link.startsWith("web://")) {
                                String httpLink = "http://" + link.substring(6);
                                Intent intent = new Intent(MainActivity.this, WebActivity.class);
                                intent.putExtra("url", httpLink);
                                startActivity(intent);
                            } else {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                                startActivity(intent);
                            }
                        } catch (Exception e) {
                            FileLogger.w(Utils.TAG, "Failed to open banner link: " + link, e);
                        }
                    }
                }
            }
        });
    }

    private void wireListAdapter() {
        adapter = new AppAdapter(this, appList);
        listView.setAdapter(adapter);
    }

    private void setupSearchDock() {
        View box = findViewById(R.id.search_box);
        if (box == null || !(box.getParent() instanceof ViewGroup)) return;
        final ViewGroup wrapper = (ViewGroup) box.getParent();
        if (!(wrapper.getParent() instanceof ViewGroup)) return;
        ViewGroup header = (ViewGroup) wrapper.getParent();
        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        if (content == null) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        wrapper.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = wrapper.getMeasuredHeight();

        int index = header.indexOfChild(wrapper);
        header.removeView(wrapper);
        searchPlaceholder = new View(this);
        header.addView(searchPlaceholder, index,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, h));

        wrapper.setBackgroundColor(Theme.tabRowBackground());
        wrapper.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP);
        content.addView(wrapper, lp);
        searchDock = wrapper;

        float d = dm.density;
        dockRightInsetPx = (int) (((dm.widthPixels / d <= 320f ? 44 : 48) + 4) * d + 0.5f);

        android.view.ViewTreeObserver vto = content.getViewTreeObserver();
        vto.addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            public void onScrollChanged() {
                syncSearchDock();
            }
        });
        vto.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                syncSearchDock();
            }
        });
    }

    private void syncSearchDock() {
        if (searchDock == null || searchPlaceholder == null) return;
        if (searchPlaceholder.getWidth() == 0) return;
        ViewGroup content = (ViewGroup) searchDock.getParent();
        if (content == null) return;

        searchPlaceholder.getLocationInWindow(dockLocA);
        content.getLocationInWindow(dockLocB);
        int raw = dockLocA[1] - dockLocB[1];

        int bar = GhostTitle.heightPx(this);
        int h = searchDock.getHeight();
        if (h <= 0) h = searchPlaceholder.getHeight();
        int slot = Math.max(0, (bar - h) / 2);

        int top = Math.max(slot, raw);
        int zone = bar - slot;
        float p;
        if (zone <= 0) {
            p = raw <= slot ? 1f : 0f;
        } else {
            p = 1f - Math.max(0f, Math.min(1f, (raw - slot) / (float) zone));
        }
        int right = (int) (dockRightInsetPx * p + 0.5f);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) searchDock.getLayoutParams();
        if (lp.topMargin != top || lp.rightMargin != right) {
            lp.topMargin = top;
            lp.rightMargin = right;
            searchDock.setLayoutParams(lp);
        }
        int realH = searchDock.getHeight();
        if (realH > 0 && searchPlaceholder.getHeight() != realH) {
            ViewGroup.LayoutParams plp = searchPlaceholder.getLayoutParams();
            plp.height = realH;
            searchPlaceholder.setLayoutParams(plp);
        }
        if (!searchDockShown) {
            searchDockShown = true;
            searchDock.setVisibility(View.VISIBLE);
        }
    }

    private String pageCacheKey(int page, String tab, String query) {
        return page + "|" + tab + "|" + (query == null ? "" : query);
    }

    private CachedPage freshCache(int page, String tab, String query) {
        CachedPage c = pageCache.get(pageCacheKey(page, tab, query));
        if (c != null && android.os.SystemClock.uptimeMillis() - c.time < PAGE_CACHE_TTL_MS) return c;
        return null;
    }

    private void wireSearchBox() {
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();

                searchClearContainer.setVisibility(query.length() > 0 ? View.VISIBLE : View.GONE);

                if (query.equals(currentSearchQuery)) return;
                currentSearchQuery = query;

                if (scrollView != null && scrollView.getScrollY() > 0) {
                    scrollView.smoothScrollTo(0, 0);
                }

                searchHandler.removeCallbacks(searchRunnable);
                boolean instant = query.length() == 0 || freshCache(1, currentTab, query) != null;
                searchHandler.postDelayed(searchRunnable, instant ? 0L : SEARCH_DEBOUNCE_MS);
            }
        });

        try {
            searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        } catch (Throwable ignored) {
        }
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                boolean enter = event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || enter) {
                    searchHandler.removeCallbacks(searchRunnable);
                    searchRunnable.run();
                    return true;
                }
                return false;
            }
        });

        searchClearContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchBox.setText("");
                searchBox.requestFocus();
            }
        });
    }

    private void wireTabButtons() {
        tabApps.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentTab = currentTab.equals("a") ? "all" : "a";
                updateTabUI();
                currentPage = 1;
                loadData(true);
            }
        });

        tabGames.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentTab = currentTab.equals("b") ? "all" : "b";
                updateTabUI();
                currentPage = 1;
                loadData(true);
            }
        });
    }

    private void wireFocusOrder() {
        bannerImageWrapper.setId(ID_BANNER);
        bannerImageWrapper.setNextFocusDownId(R.id.search_box);

        searchBox.setNextFocusUpId(ID_BANNER);
        searchBox.setNextFocusDownId(R.id.tab_apps);

        searchClearContainer.setNextFocusUpId(ID_BANNER);
        searchClearContainer.setNextFocusDownId(R.id.tab_apps);
        searchClearContainer.setNextFocusLeftId(R.id.search_box);

        tabApps.setNextFocusUpId(R.id.search_box);
        tabApps.setNextFocusRightId(R.id.tab_games);
        tabApps.setNextFocusDownId(R.id.app_list);

        tabGames.setNextFocusUpId(R.id.search_box);
        tabGames.setNextFocusLeftId(R.id.tab_apps);
        tabGames.setNextFocusDownId(R.id.app_list);

        listView.setNextFocusUpId(R.id.tab_apps);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (downloadUi == null) downloadUi = new DownloadUi(this, ghostTitle, null);
        downloadUi.start();
        FileLogger.i(Utils.TAG, "MainActivity.onResume");
        checkForClientUpdate();

        boolean currentRussianDomainState = isRussianDomainActive(this);
        if (lastRussianDomainState != currentRussianDomainState) {
            lastRussianDomainState = currentRussianDomainState;
            currentPage = 1;
            loadData(true);
            loadBanners();
        }

        refreshAccountStatus(forceAccountCheckOnNextResume);
        forceAccountCheckOnNextResume = false;
    }

    private void reloadAll() {
        currentPage = 1;
        loadData(true);
        loadBanners();
        accountRefreshDeferred = true;

        bannerHandler.postDelayed(new Runnable() {
            public void run() { runDeferredAccountRefresh(); }
        }, 8000);
    }

    private void runDeferredAccountRefresh() {
        if (!accountRefreshDeferred) return;
        accountRefreshDeferred = false;
        bannerHandler.postDelayed(new Runnable() {
            public void run() {
                if (!isDestroyed) refreshAccountStatus(true);
            }
        }, 300);
    }

    private void refreshAccountStatus(final boolean force) {
        if (!force && System.currentTimeMillis() - lastAccountCheckMs < ACCOUNT_CHECK_THROTTLE_MS) {
            return;
        }
        if (pendingAccountCheck != null) {
            pendingAccountCheck.cancel();
            pendingAccountCheck = null;
        }
        pendingAccountCheck = AccountManager.check(this, rootLayout, new AccountManager.Callback() {
            public void onResult(boolean loggedIn, String nickname) {
                pendingAccountCheck = null;
                lastAccountCheckMs = System.currentTimeMillis();
                if (isDestroyed) return;
                Utils.refreshOptionsMenu(MainActivity.this);
                updateAccountBadge(nickname);

                bannerHandler.postDelayed(new Runnable() {
                    public void run() { fetchRatings(); }
                }, 600);
            }
        });
    }

    private void updateAccountBadge(String nickname) {
        if (accountBadgeView == null) return;

        if (nickname == null || nickname.length() == 0 || !AccountManager.isAccountSystemReachable(this)) {
            accountBadgeView.setVisibility(View.GONE);
            accountBadgeView.setText("");
            return;
        }

        String formatted = getString(R.string.signed_in_as, nickname);
        android.text.SpannableString spannable = new android.text.SpannableString(formatted);
        int start = formatted.indexOf(nickname);
        if (start >= 0) {
            spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, start + nickname.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        accountBadgeView.setText(spannable);
        accountBadgeView.setVisibility(View.VISIBLE);
    }

    private void performLogout() {
        CookieHelper.clearSessionCookie(UrlBuilder.BASE_URL);
        AccountManager.forceLoggedOut(this);
        Utils.refreshOptionsMenu(this);
        updateAccountBadge(null);

        reloadAll();
    }

    private void openWebScreen(String url) {
        openWebScreen(url, false);
    }

    private void openWebScreen(String url, boolean ghostHidden) {

        forceAccountCheckOnNextResume = true;
        Intent intent = new Intent(this, WebActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("ghost_hidden", ghostHidden);
        startActivity(intent);
    }

    @Override
    protected void onPause() {
        FileLogger.i(Utils.TAG, "MainActivity.onPause");
        searchHandler.removeCallbacks(searchRunnable);
        bannerHandler.removeCallbacks(bannerRunnable);
        if (downloadUi != null) downloadUi.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        FileLogger.i(Utils.TAG, "MainActivity.onDestroy");
        isDestroyed = true;
        searchHandler.removeCallbacks(searchRunnable);
        bannerHandler.removeCallbacks(bannerRunnable);
        if (pendingAccountCheck != null) {
            pendingAccountCheck.cancel();
            pendingAccountCheck = null;
        }
        if (pendingRatingsFetch != null) {
            pendingRatingsFetch.cancel();
            pendingRatingsFetch = null;
        }
        if (pendingDeleteAccount != null) {
            pendingDeleteAccount.cancel();
            pendingDeleteAccount = null;
        }
        if (updateDialog != null && updateDialog.isShowing()) {
            updateDialog.dismiss();
        }
        super.onDestroy();
    }

    private void updateTabUI() {
        if (currentTab.equals("all")) {
            tabApps.setText(R.string.tab_apps);
            tabGames.setText(R.string.tab_games);
        } else if (currentTab.equals("a")) {
            tabApps.setText(R.string.tab_all);
            tabGames.setText(R.string.tab_games);
        } else if (currentTab.equals("b")) {
            tabApps.setText(R.string.tab_apps);
            tabGames.setText(R.string.tab_all);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
        checkAndOpenDeepLink();
    }

    private void handleDeepLink(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                String query = data.getQuery();
                if (query != null && query.trim().length() > 0) {
                    deepLinkPackage = query.trim();
                }
            }
        }
    }

    private void checkAndOpenDeepLink() {
        if (deepLinkPackage == null || deepLinkPackage.length() == 0) return;
        fetchSpecificApp(deepLinkPackage);
        deepLinkPackage = null;
    }

    private void showStatus(int textResId, int iconResId) {
        if (statusTextView == null) return;
        statusTextView.setText(textResId);
        statusTextView.setCompoundDrawablesWithIntrinsicBounds(iconResId, 0, 0, 0);
        statusTextView.setVisibility(View.VISIBLE);
        hideListViewPreservingScroll();
    }

    private void setLoadingStrip(final boolean visible) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (ghostTitle != null) {
                    if (visible) ghostTitle.showProgressIndeterminate();
                    else ghostTitle.hideProgress();
                }
            }
        });
    }

    private void hideStatus() {
        if (statusTextView == null) return;
        statusTextView.setVisibility(View.GONE);
        showListViewPreservingScroll();
    }

    private void hideListViewPreservingScroll() {
        if (listView == null) return;
        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;
        listView.setVisibility(View.GONE);
        restoreScrollY(savedScrollY);
    }

    private void showListViewPreservingScroll() {
        if (listView == null) return;
        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;
        listView.setVisibility(View.VISIBLE);
        restoreScrollY(savedScrollY);
    }

    private void restoreScrollY(final int scrollY) {
        if (scrollView == null) return;
        scrollView.post(new Runnable() {
            public void run() {
                scrollView.scrollTo(0, scrollY);
            }
        });
    }

    private void fetchSpecificApp(final String pkgName) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String urlStr = "http://odd.txy-50b.workers.dev/?pkg=" + pkgName;
                    String jsonStr = Utils.downloadString(urlStr);
                    JSONObject response = new JSONObject(jsonStr);
                    org.json.JSONArray items = response.optJSONArray("items");

                    if (items != null && items.length() > 0) {
                        JSONObject obj = items.getJSONObject(0);
                        final AppItem item = new AppItem();
                        item.pkg = obj.optString("pkg", "");
                        item.name = obj.optString("name", getString(R.string.unknown));
                        item.version = obj.optString("version", "");
                        item.minAndroid = obj.optString("min_android", "");
                        item.icon = obj.optString("icon", "");
                        item.category = obj.optString("category", "");
                        item.description = obj.optString("description", "");
                        item.downloadUrl = obj.optString("download_url", "");

                        item.screenshots = new ArrayList<String>();
                        org.json.JSONArray screens = obj.optJSONArray("screenshots");
                        if (screens != null) {
                            for (int j = 0; j < screens.length(); j++) {
                                item.screenshots.add(screens.optString(j));
                            }
                        }

                        runOnUiThread(new Runnable() {
                            public void run() {
                                hideStatus();
                                openDetailsActivity(item);
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (statusTextView != null) {
                                    showStatus(R.string.status_app_not_found, 0);
                                    statusTextView.postDelayed(new Runnable() {
                                        public void run() { hideStatus(); }
                                    }, 2000);
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (statusTextView != null) {
                                showStatus(R.string.status_couldnt_load, R.drawable.ic_network_error);
                                statusTextView.postDelayed(new Runnable() {
                                    public void run() { hideStatus(); }
                                }, 2000);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private static final long CLIENT_UPDATE_CHECK_COOLDOWN_MS = 6L * 60 * 60 * 1000;

    private void checkForClientUpdate() {
        android.content.SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        long lastCheck = prefs.getLong("last_client_update_check", 0);
        if (System.currentTimeMillis() - lastCheck < CLIENT_UPDATE_CHECK_COOLDOWN_MS) {
            return;
        }
        Utils.savePrefs(prefs.edit().putLong("last_client_update_check", System.currentTimeMillis()));
        new Thread(new Runnable() {
            public void run() {
                try {
                    String urlStr = "http://odd.txy-50b.workers.dev/?pkg=com.oddmarket";
                    String jsonStr = Utils.downloadString(urlStr);
                    JSONObject response = new JSONObject(jsonStr);
                    org.json.JSONArray items = response.optJSONArray("items");
                    if (items != null && items.length() > 0) {
                        JSONObject obj = items.getJSONObject(0);
                        final String siteVer = obj.optString("version", "");
                        android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                        final String installedVer = pInfo.versionName;
                        if (Utils.isVersionOlder(installedVer, siteVer)) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (isDestroyed || isFinishing()) return;
                                    if (updateDialog != null && updateDialog.isShowing()) return;
                                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                                    builder.setMessage(R.string.update_available_message);
                                    builder.setPositiveButton(R.string.btn_update, new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int which) {
                                            fetchSpecificApp("com.oddmarket");
                                        }
                                    });
                                    builder.setCancelable(true);
                                    updateDialog = builder.create();
                                    updateDialog.show();
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    FileLogger.w(Utils.TAG, "Failed to check for client update", e);
                }
            }
        }).start();
    }

    private void openDetailsActivity(AppItem item) {
        Intent intent = new Intent(MainActivity.this, DetailsActivity.class);
        intent.putExtra("pkg", item.pkg);
        intent.putExtra("name", item.name);
        intent.putExtra("version", item.version);
        intent.putExtra("min_android", item.minAndroid);
        intent.putExtra("icon", item.icon);
        intent.putExtra("category", item.category);
        intent.putExtra("description", item.description);
        intent.putExtra("download_url", item.downloadUrl);
        intent.putStringArrayListExtra("screenshots", item.screenshots);
        startActivity(intent);
    }

    private void updateListViewHeight() {
        if (listView == null || adapter == null) return;
        int count = adapter.getCount();
        if (count == 0) {
            ViewGroup.LayoutParams params = listView.getLayoutParams();
            if (params != null && params.height != 0) {
                params.height = 0;
                listView.setLayoutParams(params);
            }
            return;
        }

        int width = listView.getWidth();
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }

        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int totalHeight = 0;

        View itemConvertView = null;
        View footerConvertView = null;

        adapter.measureOnly = true;
        try {
            for (int i = 0; i < count; i++) {
                int viewType = adapter.getItemViewType(i);
                if (viewType == AppAdapter.VIEW_TYPE_ITEM) {
                    itemConvertView = adapter.getView(i, itemConvertView, listView);
                    itemConvertView.measure(widthSpec, heightSpec);
                    totalHeight += itemConvertView.getMeasuredHeight();
                } else {
                    footerConvertView = adapter.getView(i, footerConvertView, listView);
                    footerConvertView.measure(widthSpec, heightSpec);
                    totalHeight += footerConvertView.getMeasuredHeight();
                }
            }

            ViewGroup.LayoutParams params = listView.getLayoutParams();
            if (params != null) {
                params.height = totalHeight;
                listView.setLayoutParams(params);
            }
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to measure list view height", e);
        } finally {
            adapter.measureOnly = false;
        }
    }

    public static String fixUrl(Context context, String u) {
        u = applyRussianDomain(context, u);
        return Utils.httpsToHttp(u);
    }

    public static String fixApkUrl(Context context, String u) {
        return applyRussianDomain(context, u);
    }

    public static boolean isRussianDomainActive(Context context) {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("rus_url_fix", false);
    }

    public static boolean isLegacyDownloadActive(Context context) {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("legacy_download", false);
    }

    public static String applyRussianDomain(Context context, String url) {
        if (url == null || !isRussianDomainActive(context)) {
            return url;
        }
        String fixed = url;
        if (fixed.contains("w0.am")) {
            fixed = fixed.replace("w0.am", "narod.ws");
        }
        if (fixed.contains("w10.site")) {
            fixed = fixed.replace("w10.site", "narod.ws");
        }
        if (fixed.contains("oldcities.org")) {
            fixed = fixed.replace("oldcities.org", "narod.ws");
        }
        return fixed;
    }

    public static final ExecutorService imageExecutor = Executors.newFixedThreadPool(6);

    private static Bitmap downloadBitmapWithRetry(String url, int maxDimensionPx) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Bitmap bmp = Utils.downloadAndDecodeBitmap(url, maxDimensionPx);
                if (bmp != null) return bmp;
            } catch (OutOfMemoryError oom) {
                Log.d(Utils.TAG, "Out of memory decoding image (attempt " + attempt + "): " + url);
            } catch (Exception e) {
                Log.d(Utils.TAG, "Failed to load image (attempt " + attempt + "): " + url, e);
            }
            if (attempt == 1) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    public static void loadIcon(String rawUrl, final ImageView img) {
        if (rawUrl == null || rawUrl.length() == 0) {
            img.setImageResource(R.drawable.ic_pic);
            return;
        }

        final String url = fixUrl(img.getContext(), rawUrl);

        img.setTag(url);

        if (iconCache.containsKey(url)) {
            Bitmap cachedBmp = iconCache.get(url);
            if (cachedBmp != null) {
                img.setImageBitmap(cachedBmp);
                return;
            }
        }

        img.setImageResource(R.drawable.ic_pic);

        final int maxDimensionPx = iconTargetPx(img);

        imageExecutor.execute(new Runnable() {
            public void run() {
                if (!url.equals(img.getTag())) return;

                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

                final Bitmap bmp = downloadBitmapWithRetry(url, maxDimensionPx);
                if (bmp != null) {
                    iconCache.put(url, bmp);
                    img.post(new Runnable() {
                        public void run() {
                            if (url.equals(img.getTag())) img.setImageBitmap(bmp);
                        }
                    });
                }
            }
        });
    }

    private static int iconTargetPx(ImageView img) {
        ViewGroup.LayoutParams lp = img.getLayoutParams();
        if (lp != null && lp.width > 0 && lp.height > 0) {
            return Math.max(lp.width, lp.height);
        }
        DisplayMetrics dm = img.getContext().getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    public static void loadBannerImage(final String rawUrl, final ImageView img) {
        loadBannerImage(rawUrl, img, false);
    }

    public static void loadBannerImage(final String rawUrl, final ImageView img, final boolean showPlaceholderOnFailure) {
        if (rawUrl == null || rawUrl.length() == 0) return;

        final String url = fixUrl(img.getContext(), rawUrl);

        img.setTag(url);

        if (iconCache.containsKey(url)) {
            Bitmap cachedBmp = iconCache.get(url);
            if (cachedBmp != null) {
                img.setImageBitmap(cachedBmp);
                return;
            }
        }

        final DisplayMetrics dm = img.getContext().getResources().getDisplayMetrics();
        final int maxDimensionPx = Math.max(dm.widthPixels, dm.heightPixels);

        imageExecutor.execute(new Runnable() {
            public void run() {
                if (!url.equals(img.getTag())) return;
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

                final Bitmap bmp = downloadBitmapWithRetry(url, maxDimensionPx);
                if (bmp != null) {
                    iconCache.put(url, bmp);
                    img.post(new Runnable() {
                        public void run() {
                            if (url.equals(img.getTag())) img.setImageBitmap(bmp);
                        }
                    });
                } else if (showPlaceholderOnFailure) {
                    img.post(new Runnable() {
                        public void run() {
                            if (url.equals(img.getTag())) img.setImageResource(R.drawable.ic_pic);
                        }
                    });
                }
            }
        });
    }

    private void loadBanners() {
        FileLogger.i(Utils.TAG, "loadBanners: start");
        new Thread(new Runnable() {
            public void run() {
                try {
                    String bannerListUrl = applyRussianDomain(MainActivity.this, "http://odd-m.w0.am/banner.json");
                    String jsonStr = Utils.downloadString(bannerListUrl);
                    JSONObject jsonObj = new JSONObject(jsonStr);
                    final org.json.JSONArray array = jsonObj.optJSONArray("banners");
                    FileLogger.i(Utils.TAG, "loadBanners: got " + (array != null ? array.length() : 0) + " banner(s)");
                    if (array != null && array.length() > 0) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (isDestroyed) return;
                                bannersList.clear();
                                for (int i = 0; i < array.length(); i++) {
                                    JSONObject b = array.optJSONObject(i);
                                    if (b != null) bannersList.add(b);
                                }

                                if (bannersList.size() > 0) {
                                    currentBannerIdx = 0;
                                    isFrontVisible = true;
                                    bannerViewFront.setVisibility(View.VISIBLE);
                                    bannerViewBack.setVisibility(View.INVISIBLE);

                                    JSONObject first = bannersList.get(0);
                                    String imgUrl = applyRussianDomain(MainActivity.this, "http://odd-m.w0.am/" + first.optString("image"));
                                    loadBannerImage(imgUrl, bannerViewFront);
                                    bannerContainer.setVisibility(View.VISIBLE);

                                    bannerHandler.removeCallbacks(bannerRunnable);
                                    if (bannersList.size() > 1) {
                                        int delay = first.optInt("time", 5) * 1000;
                                        bannerHandler.postDelayed(bannerRunnable, delay);
                                    }
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    FileLogger.w(Utils.TAG, "Failed to load banners", e);
                }
            }
        }).start();
    }

    private void switchBanner() {
        if (bannersList == null || bannersList.size() <= 1) return;

        int nextIdx = (currentBannerIdx + 1) % bannersList.size();
        JSONObject nextBanner = bannersList.get(nextIdx);
        String imgUrl = "http://odd-m.w0.am/" + nextBanner.optString("image");

        final ImageView activeView = isFrontVisible ? bannerViewFront : bannerViewBack;
        final ImageView incomingView = isFrontVisible ? bannerViewBack : bannerViewFront;

        loadBannerImage(imgUrl, incomingView);

        final AlphaAnimation animOut = new AlphaAnimation(1.0f, 0.0f);
        animOut.setDuration(400);
        animOut.setFillAfter(true);

        final AlphaAnimation animIn = new AlphaAnimation(0.0f, 1.0f);
        animIn.setDuration(400);
        animIn.setFillAfter(true);

        animOut.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation animation) {
                incomingView.setVisibility(View.VISIBLE);
                incomingView.startAnimation(animIn);
            }
            public void onAnimationEnd(Animation animation) {
                activeView.setVisibility(View.INVISIBLE);
            }
            public void onAnimationRepeat(Animation animation) {}
        });

        activeView.startAnimation(animOut);
        currentBannerIdx = nextIdx;
        isFrontVisible = !isFrontVisible;

        int delay = nextBanner.optInt("time", 5) * 1000;
        bannerHandler.postDelayed(bannerRunnable, delay);
    }

    private void loadData(final boolean scrollToTop) {
        loadData(scrollToTop, false);
    }

    private void loadData(final boolean scrollToTop, final boolean useCache) {
        final int myRequest = ++requestSeq;
        final int pageSnapshot = currentPage;
        final String tabSnapshot = currentTab;
        final String querySnapshot = currentSearchQuery;

        if (useCache) {
            CachedPage hit = freshCache(pageSnapshot, tabSnapshot, querySnapshot);
            if (hit != null) {
                applyLoadedData(myRequest, pageSnapshot, tabSnapshot, querySnapshot,
                        new ArrayList<AppItem>(hit.items), hit.more, hit.prev, scrollToTop);
                return;
            }
        }

        isLoading = true;
        setLoadingStrip(true);

        final boolean sameAsShown = !appList.isEmpty()
                && pageSnapshot == lastLoadedPage
                && tabSnapshot.equals(lastLoadedTab)
                && querySnapshot.equals(lastLoadedQuery);

        if (!sameAsShown) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (myRequest != requestSeq) return;

                    showStatus(R.string.status_loading, 0);
                }
            });
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    String urlStr = buildListUrl(pageSnapshot, tabSnapshot, querySnapshot);
                    FileLogger.i(Utils.TAG, "loadData: fetching " + urlStr);
                    String jsonStr = Utils.downloadString(urlStr);
                    JSONObject response = new JSONObject(jsonStr);

                    final boolean moreFlag = response.optBoolean("hasMore", false);
                    final boolean prevFlag = response.optBoolean("hasPrev", false);
                    final List<AppItem> resultList = parseAppItems(response);
                    FileLogger.i(Utils.TAG, "loadData: got " + resultList.size() + " item(s), page=" + pageSnapshot + " tab=" + tabSnapshot);

                    final CachedPage cp = new CachedPage();
                    cp.items = new ArrayList<AppItem>(resultList);
                    cp.more = moreFlag;
                    cp.prev = prevFlag;
                    cp.time = android.os.SystemClock.uptimeMillis();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            pageCache.put(pageCacheKey(pageSnapshot, tabSnapshot, querySnapshot), cp);
                            applyLoadedData(myRequest, pageSnapshot, tabSnapshot, querySnapshot, resultList, moreFlag, prevFlag, scrollToTop);
                        }
                    });
                } catch (final Exception e) {
                    FileLogger.w(Utils.TAG, "loadData: failed for page=" + pageSnapshot + " tab=" + tabSnapshot, e);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            applyLoadError(myRequest);
                        }
                    });
                }
            }
        }).start();
    }

    private String buildListUrl(int page, String tab, String query) throws Exception {
        String queryEnc = "";
        if (query != null && query.length() > 0) {
            queryEnc = java.net.URLEncoder.encode(query, "UTF-8");
        }

        String urlStr = "http://odd.txy-50b.workers.dev/?page=" + page
                      + "&tab=" + tab
                      + "&tablet=" + (isTablet ? "1" : "0")
                      + "&sdk=" + android.os.Build.VERSION.SDK_INT;

        if (queryEnc.length() > 0) {
            urlStr += "&q=" + queryEnc;
        }
        return urlStr;
    }

    private List<AppItem> parseAppItems(JSONObject response) throws Exception {
        List<AppItem> resultList = new ArrayList<AppItem>();
        org.json.JSONArray items = response.optJSONArray("items");

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject obj = items.getJSONObject(i);
                AppItem item = new AppItem();
                item.pkg = obj.optString("pkg", "");
                item.category = obj.optString("category", "");
                item.name = obj.optString("name", getString(R.string.unknown));
                item.version = obj.optString("version", "");
                item.minAndroid = obj.optString("min_android", "");
                item.icon = obj.optString("icon", "");
                item.description = obj.optString("description", "");
                item.downloadUrl = obj.optString("download_url", "");

                item.screenshots = new ArrayList<String>();
                org.json.JSONArray screens = obj.optJSONArray("screenshots");
                if (screens != null) {
                    for (int j = 0; j < screens.length(); j++) {
                        item.screenshots.add(screens.optString(j));
                    }
                }
                resultList.add(item);
            }
        }
        return resultList;
    }

    private void applyLoadedData(int myRequest, int pageSnapshot, String tabSnapshot, String querySnapshot, List<AppItem> resultList, boolean moreFlag, boolean prevFlag, final boolean scrollToTop) {
        if (myRequest != requestSeq) return;
        isLoading = false;
        setLoadingStrip(false);
        lastLoadedPage = pageSnapshot;
        lastLoadedTab = tabSnapshot;
        lastLoadedQuery = querySnapshot;
        hasMorePages = moreFlag;
        hasPrevPages = prevFlag;
        runDeferredAccountRefresh();

        final boolean wasFocused = searchBox != null && searchBox.hasFocus();
        final int selStart = searchBox != null ? searchBox.getSelectionStart() : 0;
        final int selEnd = searchBox != null ? searchBox.getSelectionEnd() : 0;

        final boolean listHadFocus = listView.hasFocus();
        listView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        appList.clear();
        appList.addAll(resultList);
        adapter.notifyDataSetChanged();

        updateListViewHeight();

        if (appList.isEmpty()) {
            showStatus(R.string.status_nothing_found, 0);
        } else {
            hideStatus();
        }

        if (scrollView != null) {
            scrollView.post(new Runnable() {
                public void run() {
                    scrollView.post(new Runnable() {
                        public void run() {
                            listView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                            if (listHadFocus) {
                                listView.requestFocus();
                            }

                            if (scrollToTop) {
                                scrollView.scrollTo(0, 0);
                            } else if (!scrollToTop) {
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                            }

                            if (wasFocused && searchBox != null) {
                                searchBox.requestFocus();
                                try {
                                    searchBox.setSelection(selStart, selEnd);
                                } catch (Exception e) {
                                    Log.d(Utils.TAG, "Failed to restore search box selection", e);
                                }
                            }
                        }
                    });
                }
            });
        } else {
            listView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    private void applyLoadError(int myRequest) {
        if (myRequest != requestSeq) return;
        isLoading = false;
        setLoadingStrip(false);
        runDeferredAccountRefresh();

        currentPage = lastLoadedPage;

        if (appList.size() == 0) {
            showStatus(R.string.status_couldnt_load, R.drawable.ic_network_error);
        } else {

            hideStatus();
            Toast.makeText(this, R.string.status_couldnt_load, Toast.LENGTH_SHORT).show();
            updateListViewHeight();
        }
    }

    private void fetchRatings() {
        if (isDestroyed) return;
        if (System.currentTimeMillis() - lastRatingsOkMs < RATINGS_THROTTLE_MS) {
            return;
        }
        if (pendingRatingsFetch != null) {
            pendingRatingsFetch.cancel();
            pendingRatingsFetch = null;
        }
        pendingRatingsFetch = AccountManager.fetchRatings(this, rootLayout, new AccountManager.RatingsCallback() {
            public void onResult(Map<String, Double> ratings) {
                pendingRatingsFetch = null;
                if (isDestroyed) return;
                if (ratings == null) return;
                lastRatingsOkMs = System.currentTimeMillis();
                ratingsByPkg.clear();
                ratingsByPkg.putAll(ratings);
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
    }

    private class AppAdapter extends BaseAdapter {
        private static final int VIEW_TYPE_ITEM = 0;
        private static final int VIEW_TYPE_FOOTER = 1;

        private Context ctx;
        private List<AppItem> list;
        private LayoutInflater inflater;

        boolean measureOnly = false;

        class ViewHolder {
            TextView txtName;
            TextView txtVer;
            TextView txtRating;
            ImageView imgIcon;
            View clickTarget;
            View divider;
            int themeApplied = -1;
        }

        class FooterHolder {
            View footerRoot;
            int themeApplied = -1;
            ImageView imgPrev;
            TextView txtPage;
            ImageView imgNext;
        }

        public AppAdapter(Context context, List<AppItem> objects) {
            this.ctx = context;
            this.list = objects;
            this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            int normalCount = isTablet ? (list.size() + 1) / 2 : list.size();
            if (position == normalCount) return VIEW_TYPE_FOOTER;
            return VIEW_TYPE_ITEM;
        }

        public int getCount() {
            int normalCount = isTablet ? (list.size() + 1) / 2 : list.size();
            boolean isSinglePage = !hasMorePages && !hasPrevPages;
            if (isSinglePage) {
                return normalCount;
            }
            return normalCount + 1;
        }

        public Object getItem(int position) { return null; }
        public long getItemId(int position) { return position; }

        public View getView(int position, View convertView, ViewGroup parent) {
            int normalCount = isTablet ? (list.size() + 1) / 2 : list.size();

            if (position == normalCount) {
                if (!hasMorePages && !hasPrevPages) {
                    View empty = new View(ctx);
                    empty.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0));
                    empty.setVisibility(View.GONE);
                    return empty;
                }

                FooterHolder holder;
                if (convertView != null && convertView.getTag() instanceof FooterHolder) {
                    holder = (FooterHolder) convertView.getTag();
                } else {
                    int layoutRes = isTablet ? R.layout.list_footer_tablet : R.layout.list_footer;
                    View footerRoot = inflater.inflate(layoutRes, parent, false);

                    holder = new FooterHolder();
                    holder.footerRoot = footerRoot;
                    holder.imgPrev = (ImageView) footerRoot.findViewById(R.id.footer_prev);
                    holder.txtPage = (TextView) footerRoot.findViewById(R.id.footer_page_text);
                    holder.imgNext = (ImageView) footerRoot.findViewById(R.id.footer_next);

                    holder.imgPrev.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (hasPrevPages && !isLoading) {
                                currentPage--;
                                loadData(false);
                            }
                        }
                    });
                    holder.imgNext.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (hasMorePages && !isLoading) {
                                currentPage++;
                                loadData(false);
                            }
                        }
                    });

                    footerRoot.setTag(holder);
                }

                if (holder.themeApplied != Theme.CURRENT) {
                    Theme.applyFooter(holder.footerRoot);
                    holder.themeApplied = Theme.CURRENT;
                }

                holder.imgPrev.setVisibility(hasPrevPages ? View.VISIBLE : View.INVISIBLE);
                holder.txtPage.setText(getString(R.string.page_format, currentPage));
                holder.imgNext.setVisibility(hasMorePages ? View.VISIBLE : View.INVISIBLE);

                return holder.footerRoot;
            }

            if (isTablet) {
                LinearLayout row;
                View left;
                View right;
                View leftDivider;
                View rightDivider;
                if (convertView == null || !(convertView instanceof LinearLayout) || !(convertView.getTag() instanceof Object[])) {
                    row = (LinearLayout) inflater.inflate(R.layout.list_item_row, parent, false);

                    left = row.findViewById(R.id.item_row_left);
                    right = row.findViewById(R.id.item_row_right);
                    leftDivider = row.findViewById(R.id.item_row_divider_left);
                    rightDivider = row.findViewById(R.id.item_row_divider_right);

                    ViewHolder lh = new ViewHolder();
                    lh.txtName = (TextView) left.findViewById(R.id.item_name);
                    lh.txtVer = (TextView) left.findViewById(R.id.item_version);
                    lh.txtRating = (TextView) left.findViewById(R.id.item_rating);
                    lh.imgIcon = (ImageView) left.findViewById(R.id.item_icon);
                    lh.divider = leftDivider;
                    left.setTag(lh);

                    ViewHolder rh = new ViewHolder();
                    rh.txtName = (TextView) right.findViewById(R.id.item_name);
                    rh.txtVer = (TextView) right.findViewById(R.id.item_version);
                    rh.txtRating = (TextView) right.findViewById(R.id.item_rating);
                    rh.imgIcon = (ImageView) right.findViewById(R.id.item_icon);
                    rh.divider = rightDivider;
                    right.setTag(rh);

                    Object[] rowTag = new Object[] { left, right, leftDivider, rightDivider };
                    row.setTag(rowTag);
                } else {
                    row = (LinearLayout) convertView;
                    Object[] rowTag = (Object[]) row.getTag();
                    left = (View) rowTag[0];
                    right = (View) rowTag[1];
                    leftDivider = (View) rowTag[2];
                    rightDivider = (View) rowTag[3];
                }

                ViewHolder lh = (ViewHolder) left.getTag();
                ViewHolder rh = (ViewHolder) right.getTag();

                if (lh.themeApplied != Theme.CURRENT) {
                    Theme.applyListItem(left);
                    Theme.applyDivider(lh.divider);
                    lh.themeApplied = Theme.CURRENT;
                }
                if (rh.themeApplied != Theme.CURRENT) {
                    Theme.applyListItem(right);
                    Theme.applyDivider(rh.divider);
                    rh.themeApplied = Theme.CURRENT;
                }

                final int leftIdx = position * 2;
                final int rightIdx = leftIdx + 1;

                final AppItem leftItem = list.get(leftIdx);
                lh.txtName.setText(leftItem.name);
                lh.txtVer.setText(leftItem.versionLine(ctx));
                bindRating(lh.txtRating, leftItem.pkg);
                if (!measureOnly) {
                    MainActivity.loadIcon(leftItem.icon, lh.imgIcon);
                    left.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { openDetailsActivity(leftItem); }
                    });
                }

                if (rightIdx < list.size()) {
                    right.setVisibility(View.VISIBLE);
                    rightDivider.setVisibility(View.VISIBLE);
                    final AppItem rightItem = list.get(rightIdx);
                    rh.txtName.setText(rightItem.name);
                    rh.txtVer.setText(rightItem.versionLine(ctx));
                    bindRating(rh.txtRating, rightItem.pkg);
                    if (!measureOnly) {
                        MainActivity.loadIcon(rightItem.icon, rh.imgIcon);
                        right.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) { openDetailsActivity(rightItem); }
                        });
                    }
                } else {
                    right.setVisibility(View.INVISIBLE);
                    rightDivider.setVisibility(View.INVISIBLE);
                    right.setOnClickListener(null);
                }

                return row;
            } else {

                LinearLayout container;
                ViewHolder holder;
                if (convertView == null || !(convertView instanceof LinearLayout) || convertView.getTag() == null) {
                    container = (LinearLayout) inflater.inflate(R.layout.list_item_single, parent, false);

                    View itemView = container.findViewById(R.id.item_single_content);

                    holder = new ViewHolder();
                    holder.txtName = (TextView) itemView.findViewById(R.id.item_name);
                    holder.txtVer = (TextView) itemView.findViewById(R.id.item_version);
                    holder.txtRating = (TextView) itemView.findViewById(R.id.item_rating);
                    holder.imgIcon = (ImageView) itemView.findViewById(R.id.item_icon);
                    holder.clickTarget = itemView;
                    holder.divider = container.findViewById(R.id.item_single_divider);
                    container.setTag(holder);
                    convertView = container;
                } else {
                    container = (LinearLayout) convertView;
                    holder = (ViewHolder) container.getTag();
                }

                final AppItem item = list.get(position);
                if (holder.themeApplied != Theme.CURRENT) {
                    Theme.applyListItem(holder.clickTarget);
                    Theme.applyDivider(holder.divider);
                    holder.themeApplied = Theme.CURRENT;
                }
                holder.txtName.setText(item.name);
                holder.txtVer.setText(item.versionLine(ctx));
                bindRating(holder.txtRating, item.pkg);
                if (!measureOnly) {
                    MainActivity.loadIcon(item.icon, holder.imgIcon);
                    holder.clickTarget.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            openDetailsActivity(item);
                        }
                    });
                }

                return convertView;
            }
        }

        private static final String RATING_STAR = "\u2606";
        private static final float RATING_STAR_RELATIVE_SIZE = 16f / 14f;
        private static final int RATING_STAR_COLOR = Theme.PROGRESS_COLOR;

        private void bindRating(TextView txtRating, String pkg) {
            if (txtRating == null) return;
            Double rating = ratingsByPkg.get(pkg);
            if (rating == null || rating <= 0) {
                txtRating.setVisibility(View.GONE);
                return;
            }

            String ratingNumber = String.format(java.util.Locale.US, "%.1f", rating);
            String display = ratingNumber + " " + RATING_STAR;
            SpannableString spannable = new SpannableString(display);
            int starStart = ratingNumber.length() + 1;
            spannable.setSpan(new RelativeSizeSpan(RATING_STAR_RELATIVE_SIZE), starStart, display.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new ForegroundColorSpan(RATING_STAR_COLOR), starStart, display.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            txtRating.setText(spannable);
            txtRating.setContentDescription(getString(R.string.rating_format, ratingNumber));
            txtRating.setVisibility(View.VISIBLE);
        }
    }

    private static final int MENU_ID_REFRESH = 102;
    private static final int MENU_ID_CHECK_UPDATES = 101;
    private static final int MENU_ID_PREFERENCES = 106;
    private static final int MENU_ID_LOGIN = 110;
    private static final int MENU_ID_REGISTER = 111;
    private static final int MENU_ID_LOGOUT = 112;
    private static final int MENU_ID_DELETE_ACCOUNT = 113;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ID_REFRESH, 0, R.string.menu_refresh);
        menu.add(0, MENU_ID_CHECK_UPDATES, 1, R.string.menu_check_updates);
        menu.add(0, MENU_ID_PREFERENCES, 2, R.string.menu_preferences);
        menu.add(0, MENU_ID_LOGIN, 3, R.string.menu_login);
        menu.add(0, MENU_ID_REGISTER, 4, R.string.menu_register);
        menu.add(0, MENU_ID_LOGOUT, 5, R.string.menu_logout);
        menu.add(0, MENU_ID_DELETE_ACCOUNT, 6, R.string.menu_delete_account);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean loggedIn = AccountManager.isLoggedIn(this);

        boolean reachable = AccountManager.isAccountSystemReachable(this);

        MenuItem loginItem = menu.findItem(MENU_ID_LOGIN);
        MenuItem registerItem = menu.findItem(MENU_ID_REGISTER);
        MenuItem logoutItem = menu.findItem(MENU_ID_LOGOUT);
        MenuItem deleteAccountItem = menu.findItem(MENU_ID_DELETE_ACCOUNT);

        if (loginItem != null) loginItem.setVisible(reachable && !loggedIn);
        if (registerItem != null) registerItem.setVisible(reachable && !loggedIn);
        if (logoutItem != null) logoutItem.setVisible(reachable && loggedIn);
        if (deleteAccountItem != null) deleteAccountItem.setVisible(reachable && loggedIn);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ID_REFRESH) {
            reloadAll();
            return true;
        } else if (item.getItemId() == MENU_ID_CHECK_UPDATES) {
            fetchSpecificApp("com.oddmarket");
            return true;
        } else if (item.getItemId() == MENU_ID_PREFERENCES) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == MENU_ID_LOGIN) {
            openWebScreen(UrlBuilder.loginUrl(this), true);
            return true;
        } else if (item.getItemId() == MENU_ID_REGISTER) {
            openWebScreen(UrlBuilder.registerUrl(this), true);
            return true;
        } else if (item.getItemId() == MENU_ID_LOGOUT) {
            performLogout();
            return true;
        } else if (item.getItemId() == MENU_ID_DELETE_ACCOUNT) {
            confirmDeleteAccount();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmDeleteAccount() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.delete_account_confirm_title);
        builder.setMessage(R.string.delete_account_confirm_message);
        builder.setPositiveButton(R.string.btn_delete, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                performDeleteAccount();
            }
        });
        builder.setNegativeButton(R.string.btn_cancel, null);
        builder.show();
    }

    private void performDeleteAccount() {
        if (pendingDeleteAccount != null) {
            pendingDeleteAccount.cancel();
            pendingDeleteAccount = null;
        }
        pendingDeleteAccount = AccountManager.deleteAccount(this, rootLayout, new AccountManager.DeleteCallback() {
            public void onResult(boolean success) {
                pendingDeleteAccount = null;
                if (isDestroyed || isFinishing()) return;
                if (success) {
                    Toast.makeText(MainActivity.this, R.string.toast_account_deleted, Toast.LENGTH_SHORT).show();
                    CookieHelper.clearSessionCookie(UrlBuilder.BASE_URL);
                    AccountManager.forceLoggedOut(MainActivity.this);
                    Utils.refreshOptionsMenu(MainActivity.this);
                    updateAccountBadge(null);
                    currentPage = 1;
                    loadData(true);
                    loadBanners();
                } else {
                    Toast.makeText(MainActivity.this, R.string.toast_account_delete_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
