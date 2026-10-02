package com.oddmarket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SearchActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.applyLocale(newBase));
    }

    private static final int KEYBOARD_MIN_DP = 120;

    private final List<AppItem> appList = new ArrayList<AppItem>();
    private final Map<String, Double> ratingsByPkg = new HashMap<String, Double>();

    private FrameLayout root;
    private View backing;
    private ListView listView;
    private EditText searchBox;
    private View clearBtn;
    private TextView hintView;
    private TextView statusView;
    private GhostTitle ghostTitle;
    private ResultsAdapter adapter;

    private boolean isTablet;
    private boolean destroyed = false;
    private boolean loading = false;
    private int requestSeq = 0;

    private boolean searchActive = false;
    private String query = "";
    private String shownQuery = null;
    private int page = 1;
    private boolean hasMore = false;
    private boolean hasPrev = false;

    private boolean keyboardOpen = false;
    private String hintCategory = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);
        GhostTitle.prepareWindow(this);
        paintWindow();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setTitle(R.string.app_name);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        isTablet = dm.widthPixels / dm.density >= 600f;

        Map<String, Double> cached = AccountManager.cachedRatings(this);
        if (cached != null) ratingsByPkg.putAll(cached);

        buildLayout();
        setContentView(root);
        Theme.applyFonts(root);

        ghostTitle = GhostTitle.attach(this).setBackVisible(true).setMenuVisible(false)
                .setMode(GhostTitle.MODE_NORMAL).setBaseColor(MainActivity.bgColor());
        ghostTitle.setTitle("");

        addSearchField();
        watchKeyboard();
        lockSearchBox();

        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        updateHint();
    }

    private void paintWindow() {
        try {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(MainActivity.elColor() | 0xFF000000));
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return Theme.dpToPx(this, v);
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
        listView.setItemsCanFocus(true);

        View zeroBacking = new View(this);
        zeroBacking.setBackgroundColor(MainActivity.bgColor());
        zeroBacking.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, bar));
        listView.addHeaderView(zeroBacking, null, false);
        listView.setVisibility(View.GONE);
        adapter = new ResultsAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setTextColor(Theme.textSecondary());
        statusView.setGravity(Gravity.CENTER);
        statusView.setCompoundDrawablePadding(dp(8));
        statusView.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        slp.topMargin = bar;
        root.addView(statusView, slp);

        hintView = new TextView(this);
        hintView.setTextSize(15);
        hintView.setTextColor(Theme.textSecondary());
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(dp(16), dp(12), dp(16), dp(12));
        hintView.setClickable(true);
        hintView.setVisibility(View.GONE);
        hintView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startFromHint();
            }
        });
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        hlp.topMargin = bar;
        root.addView(hintView, hlp);
    }

    private void addSearchField() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int bar = GhostTitle.heightPx(this);
        int h = Math.max(dp(32), Math.min(bar - dp(10), dp(40))) - dp(5);

        FrameLayout box = new FrameLayout(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, h, Gravity.TOP);

        blp.leftMargin = dp(72);
        blp.rightMargin = dp(12);
        blp.topMargin = (bar - h) / 2;

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint(R.string.search_hint);
        searchBox.setTextColor(Theme.textPrimary());
        searchBox.setHintTextColor(Theme.textHint());
        searchBox.setTextSize(16);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackgroundDrawable(Theme.searchBoxBackground());
        searchBox.setPadding(dp(12), 0, dp(40), 0);
        try {
            searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        } catch (Throwable ignored) {
        }
        box.addView(searchBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));

        LinearLayout clear = new LinearLayout(this);
        clear.setGravity(Gravity.CENTER);
        clear.setClickable(true);
        clear.setFocusable(true);
        clear.setVisibility(View.GONE);
        CrossIconView cross = new CrossIconView(this);
        cross.setColor(Theme.textPrimary());
        cross.setBackgroundDrawable(Theme.circleSelectorBackground());
        cross.setDuplicateParentStateEnabled(true);
        cross.setClickable(false);
        cross.setFocusable(false);
        clear.addView(cross, new LinearLayout.LayoutParams(dp(24), dp(24)));
        box.addView(clear, new FrameLayout.LayoutParams(dp(40),
                ViewGroup.LayoutParams.FILL_PARENT, Gravity.RIGHT));
        clearBtn = clear;
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

                searchBox.setText("");
            }
        });

        searchBox.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) unlockSearchBox();
                return false;
            }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) {
                boolean has = s.length() > 0;
                clearBtn.setVisibility(has ? View.VISIBLE : View.GONE);

                if (!has && searchActive) resetToStart();
            }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    submit();
                    return true;
                }
                return false;
            }
        });

        ViewGroup content = (ViewGroup) findViewById(android.R.id.content);
        content.addView(box, blp);
        Theme.applyFonts(box);
    }

    private void watchKeyboard() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        checkKeyboard();
                    }
                });
    }

    private void checkKeyboard() {
        Rect r = new Rect();
        root.getWindowVisibleDisplayFrame(r);
        int screenH = root.getRootView().getHeight();
        boolean open = screenH - r.bottom > dp(KEYBOARD_MIN_DP);
        if (open != keyboardOpen) {
            keyboardOpen = open;

            if (!open && searchBox != null && searchBox.hasFocus()) {
                lockSearchBox();
            }
            updateHint();
        }
    }

    private void updateHint() {
        boolean show = !keyboardOpen && !searchActive;
        if (!show) {
            hintView.setVisibility(View.GONE);
            return;
        }
        if (hintView.getVisibility() != View.VISIBLE) {

            String name = Categories.randomTranslated(this);
            if (name == null) return;
            hintCategory = name;
            hintView.setText(buildHintText(name));
            hintView.setVisibility(View.VISIBLE);
        }
    }

    private CharSequence buildHintText(String name) {
        String full = getString(R.string.search_start_format, name);
        int idx = full.lastIndexOf(name);
        SpannableStringBuilder sb = new SpannableStringBuilder(full);
        if (idx >= 0) {
            sb.setSpan(new ForegroundColorSpan(Theme.linkColor()), idx, idx + name.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int at = sb.length();
        sb.append('\uFFFC');
        sb.setSpan(new SubscriptSearchIconSpan(Theme.linkColor()), at, at + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static final class SubscriptSearchIconSpan extends ReplacementSpan {
        private final int color;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        SubscriptSearchIconSpan(int color) {
            this.color = color;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        private static float size(Paint paint) {
            return paint.getTextSize() * 0.6f;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return (int) (size(paint) + paint.getTextSize() * 0.2f + 0.5f);
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
            float s = size(paint);
            float left = x + paint.getTextSize() * 0.15f;

            float iconBottom = y + paint.getTextSize() * 0.28f;
            float iconTop = iconBottom - s;
            stroke.setColor(color);
            stroke.setStrokeWidth(Math.max(1f, paint.getTextSize() * 0.08f));
            float r = s * 0.3f;
            float cx = left + s * 0.4f;
            float cy = iconTop + s * 0.4f;
            c.drawCircle(cx, cy, r, stroke);
            float d = r * 0.7071f;
            c.drawLine(cx + d, cy + d, left + s * 0.95f, iconTop + s * 0.95f, stroke);
        }
    }

    private void startFromHint() {
        String text = hintCategory == null ? "" : hintCategory.trim();
        if (text.length() == 0) return;
        searchBox.setText(text);
        searchBox.setSelection(searchBox.getText().length());
        submit();
    }

    private void showKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchBox, 0);
        } catch (Throwable ignored) {
        }
    }

    private void releaseSearchFocus() {
        hideKeyboard();
        lockSearchBox();

        searchBox.postDelayed(new Runnable() {
            public void run() {
                if (!destroyed) hideKeyboard();
            }
        }, 120);
    }

    private void unlockSearchBox() {
        searchBox.setFocusable(true);
        searchBox.setFocusableInTouchMode(true);
    }

    private void lockSearchBox() {
        try {

            searchBox.setFocusable(false);
            searchBox.setFocusableInTouchMode(false);
            searchBox.clearFocus();
            root.requestFocus();
        } catch (Throwable ignored) {
        }
    }

    private boolean touchInside(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = e.getRawX();
        float y = e.getRawY();
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && searchBox != null
                && (keyboardOpen || searchBox.hasFocus())
                && !touchInside(searchBox, ev) && !touchInside(clearBtn, ev)) {
            hideKeyboard();
            lockSearchBox();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPause() {

        hideKeyboard();
        lockSearchBox();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paintWindow();
        if (!keyboardOpen) lockSearchBox();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    private void submit() {
        String q = searchBox.getText().toString().trim();
        if (q.length() == 0) return;
        releaseSearchFocus();
        query = q;
        page = 1;
        searchActive = true;
        updateHint();
        load();
    }

    private void resetToStart() {
        requestSeq++;
        loading = false;
        searchActive = false;
        query = "";
        shownQuery = null;
        appList.clear();
        hasMore = false;
        hasPrev = false;
        adapter.notifyDataSetChanged();
        listView.setVisibility(View.GONE);
        backing.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
        if (ghostTitle != null) ghostTitle.hideProgress();
        updateHint();
    }

    private void showStatus(int textRes, int iconRes) {
        statusView.setText(textRes);
        statusView.setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0);
        statusView.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        backing.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusView.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        backing.setVisibility(View.GONE);
    }

    private String buildUrl(int pg, String q) throws Exception {
        return "http://odd.txy-50b.workers.dev/?page=" + pg
                + "&tab=all"
                + "&tablet=" + (isTablet ? "1" : "0")
                + "&sdk=" + android.os.Build.VERSION.SDK_INT
                + "&q=" + java.net.URLEncoder.encode(q, "UTF-8");
    }

    private void load() {
        final int my = ++requestSeq;
        final int pg = page;
        final String q = query;
        loading = true;
        ghostTitle.showProgressIndeterminate();
        if (shownQuery == null || !shownQuery.equals(q)) {
            showStatus(R.string.status_loading, 0);
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    String json = Utils.downloadString(buildUrl(pg, q));
                    JSONObject response = new JSONObject(json);
                    final boolean more = response.optBoolean("hasMore", false);
                    final boolean prev = response.optBoolean("hasPrev", false);
                    final List<AppItem> items = parseItems(response);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            applyResult(my, q, items, more, prev);
                        }
                    });
                } catch (final Exception e) {
                    FileLogger.w(Utils.TAG, "SearchActivity: load failed", e);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            applyError(my);
                        }
                    });
                }
            }
        }).start();
    }

    private List<AppItem> parseItems(JSONObject response) throws Exception {
        List<AppItem> result = new ArrayList<AppItem>();
        JSONArray items = response.optJSONArray("items");
        if (items == null) return result;
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
            JSONArray screens = obj.optJSONArray("screenshots");
            if (screens != null) {
                for (int j = 0; j < screens.length(); j++) {
                    item.screenshots.add(screens.optString(j));
                }
            }
            result.add(item);
        }
        return result;
    }

    private void applyResult(int my, String q, List<AppItem> items, boolean more, boolean prev) {
        if (destroyed || my != requestSeq) return;
        loading = false;
        ghostTitle.hideProgress();
        shownQuery = q;
        hasMore = more;
        hasPrev = prev;
        appList.clear();
        appList.addAll(items);
        adapter.notifyDataSetChanged();
        if (appList.isEmpty()) {
            showStatus(R.string.status_nothing_found, 0);
        } else {
            hideStatus();
            listView.setSelection(0);
        }
    }

    private void applyError(int my) {
        if (destroyed || my != requestSeq) return;
        loading = false;
        ghostTitle.hideProgress();
        if (appList.isEmpty() || shownQuery == null || !shownQuery.equals(query)) {
            showStatus(R.string.status_couldnt_load, R.drawable.ic_network_error);
        } else {

            page = Math.max(1, page);
            hideStatus();
        }
    }

    private void openDetails(AppItem item) {
        Intent intent = new Intent(this, DetailsActivity.class);
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

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private class ResultsAdapter extends BaseAdapter {
        private static final int TYPE_ITEM = 0;
        private static final int TYPE_FOOTER = 1;

        class Holder {
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

        private int normalCount() {
            return isTablet ? (appList.size() + 1) / 2 : appList.size();
        }

        private boolean hasFooter() {
            return hasMore || hasPrev;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position == normalCount() ? TYPE_FOOTER : TYPE_ITEM;
        }

        public int getCount() {
            return normalCount() + (hasFooter() ? 1 : 0);
        }

        public Object getItem(int position) { return null; }

        public long getItemId(int position) { return position; }

        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(SearchActivity.this);
            if (position == normalCount()) {
                return footerView(inflater, convertView, parent);
            }
            return isTablet ? rowView(inflater, position, convertView, parent)
                    : singleView(inflater, position, convertView, parent);
        }

        private View footerView(LayoutInflater inflater, View convertView, ViewGroup parent) {
            if (!hasFooter()) {
                View empty = new View(SearchActivity.this);
                empty.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, 0));
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
                        if (hasPrev && !loading) {
                            page--;
                            load();
                        }
                    }
                });
                holder.imgNext.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (hasMore && !loading) {
                            page++;
                            load();
                        }
                    }
                });
                footerRoot.setTag(holder);
            }
            if (holder.themeApplied != Theme.CURRENT) {
                Theme.applyFooter(holder.footerRoot);
                holder.themeApplied = Theme.CURRENT;
            }
            holder.imgPrev.setVisibility(hasPrev ? View.VISIBLE : View.INVISIBLE);
            holder.txtPage.setText(getString(R.string.page_format, page));
            holder.imgNext.setVisibility(hasMore ? View.VISIBLE : View.INVISIBLE);
            return holder.footerRoot;
        }

        private View singleView(LayoutInflater inflater, int position, View convertView, ViewGroup parent) {
            LinearLayout container;
            Holder holder;
            if (convertView == null || !(convertView instanceof LinearLayout) || convertView.getTag() == null) {
                container = (LinearLayout) inflater.inflate(R.layout.list_item_single, parent, false);
                View itemView = container.findViewById(R.id.item_single_content);
                holder = new Holder();
                holder.txtName = (TextView) itemView.findViewById(R.id.item_name);
                holder.txtVer = (TextView) itemView.findViewById(R.id.item_version);
                holder.txtRating = (TextView) itemView.findViewById(R.id.item_rating);
                holder.imgIcon = (ImageView) itemView.findViewById(R.id.item_icon);
                holder.clickTarget = itemView;
                holder.divider = container.findViewById(R.id.item_single_divider);
                container.setTag(holder);
            } else {
                container = (LinearLayout) convertView;
                holder = (Holder) container.getTag();
            }
            final AppItem item = appList.get(position);
            if (holder.themeApplied != Theme.CURRENT) {
                Theme.applyListItem(holder.clickTarget);
                Theme.applyDivider(holder.divider);
                holder.themeApplied = Theme.CURRENT;
            }
            bindItem(holder, item);
            return container;
        }

        private View rowView(LayoutInflater inflater, int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            View left;
            View right;
            if (convertView == null || !(convertView instanceof LinearLayout) || !(convertView.getTag() instanceof Object[])) {
                row = (LinearLayout) inflater.inflate(R.layout.list_item_row, parent, false);
                left = row.findViewById(R.id.item_row_left);
                right = row.findViewById(R.id.item_row_right);
                View leftDivider = row.findViewById(R.id.item_row_divider_left);
                View rightDivider = row.findViewById(R.id.item_row_divider_right);

                Holder lh = new Holder();
                lh.txtName = (TextView) left.findViewById(R.id.item_name);
                lh.txtVer = (TextView) left.findViewById(R.id.item_version);
                lh.txtRating = (TextView) left.findViewById(R.id.item_rating);
                lh.imgIcon = (ImageView) left.findViewById(R.id.item_icon);
                lh.divider = leftDivider;
                lh.clickTarget = left;
                left.setTag(lh);

                Holder rh = new Holder();
                rh.txtName = (TextView) right.findViewById(R.id.item_name);
                rh.txtVer = (TextView) right.findViewById(R.id.item_version);
                rh.txtRating = (TextView) right.findViewById(R.id.item_rating);
                rh.imgIcon = (ImageView) right.findViewById(R.id.item_icon);
                rh.divider = rightDivider;
                rh.clickTarget = right;
                right.setTag(rh);

                row.setTag(new Object[]{left, right});
            } else {
                row = (LinearLayout) convertView;
                Object[] tag = (Object[]) row.getTag();
                left = (View) tag[0];
                right = (View) tag[1];
            }
            Holder lh = (Holder) left.getTag();
            Holder rh = (Holder) right.getTag();
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

            int leftIdx = position * 2;
            int rightIdx = leftIdx + 1;
            bindItem(lh, appList.get(leftIdx));
            if (rightIdx < appList.size()) {
                right.setVisibility(View.VISIBLE);
                rh.divider.setVisibility(View.VISIBLE);
                bindItem(rh, appList.get(rightIdx));
            } else {
                right.setVisibility(View.INVISIBLE);
                rh.divider.setVisibility(View.INVISIBLE);
                right.setOnClickListener(null);
            }
            return row;
        }

        private void bindItem(Holder h, final AppItem item) {
            h.txtName.setText(item.name);
            h.txtVer.setText(item.versionLine(SearchActivity.this));
            bindRating(h.txtRating, item.pkg);
            MainActivity.loadIcon(item.icon, h.imgIcon);
            h.clickTarget.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openDetails(item);
                }
            });
        }

        private static final float RATING_STAR_RELATIVE_SIZE = 20f / 14f;
        private static final int RATING_STAR_COLOR = Theme.PROGRESS_COLOR;

        private void bindRating(TextView txtRating, String pkg) {
            if (txtRating == null) return;
            Double rating = ratingsByPkg.get(pkg);
            if (rating == null || rating <= 0) {
                txtRating.setVisibility(View.GONE);
                return;
            }
            String number = String.format(java.util.Locale.US, "%.1f", rating);
            String display = number + " *";
            SpannableString spannable = new SpannableString(display);
            spannable.setSpan(new FilledStarSpan(RATING_STAR_RELATIVE_SIZE, RATING_STAR_COLOR),
                    number.length() + 1, display.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            txtRating.setText(spannable);
            txtRating.setContentDescription(getString(R.string.rating_format, number));
            txtRating.setVisibility(View.VISIBLE);
        }
    }
}
