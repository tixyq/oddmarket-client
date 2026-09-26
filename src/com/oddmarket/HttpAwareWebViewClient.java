package com.oddmarket;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

abstract class HttpAwareWebViewClient extends WebViewClient {

    @Override
    // Main-frame HTTP errors only.
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        boolean mainFrame = true;
        try {
            mainFrame = request.isForMainFrame();
        } catch (Exception ignored) {}
        if (mainFrame) {
            onMainFrameHttpError();
        }
    }

    protected abstract void onMainFrameHttpError();
}
