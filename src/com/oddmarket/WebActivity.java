package com.oddmarket;
// In-app browser.

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;

public class WebActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Utils.applyLocale(newBase));
    }

    private WebView webView;
    private ProgressBar progressBar;
    private String currentUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLogger.init(this);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT));
        rootLayout.setBackgroundColor(Theme.windowBackground());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        int heightPx = (int) (3 * getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, heightPx);
        progressBar.setLayoutParams(progressParams);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, 0, 1.0f);
        webView.setLayoutParams(webParams);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setSupportZoom(false);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.setBackgroundColor(Theme.windowBackground());

        try {
            java.lang.reflect.Method m = android.view.View.class.getMethod("setScrollbarFadingEnabled", boolean.class);
            m.invoke(webView, false);
        } catch (Exception e) {
            Log.d(Utils.TAG, "setScrollbarFadingEnabled not available", e);
        }

        webView.setVerticalScrollBarEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                setTitle(title);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    if (progressBar.getVisibility() == View.GONE) {
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }
        });

        rootLayout.addView(progressBar);
        rootLayout.addView(webView);

        setContentView(rootLayout);

        currentUrl = getIntent().getStringExtra("url");
        if (currentUrl == null || currentUrl.length() == 0) {
            currentUrl = "http://orchis.w0.am";
        }

        currentUrl = MainActivity.applyRussianUrlFix(this, currentUrl);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isBareDebugInterfaceUrl(url)) {
                    finishAfterDebugRedirect();
                    return true;
                }

                if (url != null) {
                    if (url.startsWith("web://")) {
                        try {
                            String httpLink = "http://" + url.substring(6);
                            httpLink = MainActivity.applyRussianUrlFix(view.getContext(), httpLink);

                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(httpLink));
                            view.getContext().startActivity(intent);
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    }

                    String fixedUrl = MainActivity.applyRussianUrlFix(view.getContext(), url);
                    if (!fixedUrl.equals(url)) {
                        view.loadUrl(fixedUrl);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);

                if (isBareDebugInterfaceUrl(url)) {
                    view.stopLoading();
                    finishAfterDebugRedirect();
                    return;
                }

                currentUrl = url;
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });

        webView.loadUrl(currentUrl);

        Utils.enableActionBarUpButton(this);
    }

    private static final String[] KNOWN_ACTIONS = {"log", "reg", "reviews", "me"};

    private static boolean hasQueryParam(Uri uri, String name) {
        String query = uri.getQuery();
        if (query == null || query.length() == 0) return false;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = (eq >= 0) ? pair.substring(0, eq) : pair;
            if (name.equals(key)) return true;
        }
        return false;
    }

    private static boolean isBareDebugInterfaceUrl(String url) {
        if (url == null) return false;
        try {
            Uri parsed = Uri.parse(url);
            Uri base = Uri.parse(UrlBuilder.BASE_URL);
            if (!base.getHost().equalsIgnoreCase(parsed.getHost())) return false;
            if (!base.getPath().equals(parsed.getPath())) return false;

            for (String action : KNOWN_ACTIONS) {
                if (hasQueryParam(parsed, action)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void finishAfterDebugRedirect() {
        FileLogger.w(Utils.TAG, "Blocked navigation to c.php debug interface, closing screen");
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }

        CookieHelper.flush();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (Utils.handleHomeMenuItem(this, item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
