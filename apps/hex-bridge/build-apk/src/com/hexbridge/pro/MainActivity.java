package com.hexbridge.pro;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout rootLayout = new FrameLayout(this);
        
        mWebView = new WebView(this);
        mWebView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        
        rootLayout.addView(mWebView);

        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showErrorScreen();
            }
        });

        setContentView(rootLayout);
        loadDashboard();
    }

    private void loadDashboard() {
        mWebView.setVisibility(View.VISIBLE);
        mWebView.loadUrl("http://localhost:5000");
    }

    private void showErrorScreen() {
        mWebView.setVisibility(View.VISIBLE);
        
        String errorHtml = "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "    <meta charset='UTF-8'>" +
                "    <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "    <title>HexBridge Pro Offline</title>" +
                "    <style>" +
                "        body {" +
                "            background-color: #0d0e12;" +
                "            color: #f1f3f9;" +
                "            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;" +
                "            display: flex;" +
                "            flex-direction: column;" +
                "            justify-content: center;" +
                "            align-items: center;" +
                "            height: 100vh;" +
                "            margin: 0;" +
                "            padding: 20px;" +
                "            text-align: center;" +
                "        }" +
                "        .card {" +
                "            background-color: #161821;" +
                "            border: 1px solid #272a37;" +
                "            padding: 30px;" +
                "            border-radius: 16px;" +
                "            max-width: 400px;" +
                "            box-shadow: 0 10px 25px rgba(0,0,0,0.5);" +
                "        }" +
                "        h1 {" +
                "            background: linear-gradient(135deg, #fb7185, #c084fc);" +
                "            -webkit-background-clip: text;" +
                "            -webkit-text-fill-color: transparent;" +
                "            font-size: 2.2rem;" +
                "            margin-top: 0;" +
                "            margin-bottom: 10px;" +
                "        }" +
                "        p {" +
                "            color: #94a3b8;" +
                "            line-height: 1.6;" +
                "            margin-bottom: 25px;" +
                "        }" +
                "        .btn {" +
                "            background: linear-gradient(135deg, #6366f1, #4f46e5);" +
                "            color: white;" +
                "            border: none;" +
                "            padding: 12px 24px;" +
                "            border-radius: 10px;" +
                "            font-size: 1em;" +
                "            font-weight: bold;" +
                "            cursor: pointer;" +
                "            width: 100%;" +
                "            margin-bottom: 10px;" +
                "            text-transform: uppercase;" +
                "            letter-spacing: 0.5px;" +
                "        }" +
                "        .btn-outline {" +
                "            background: transparent;" +
                "            border: 1px solid #334155;" +
                "            color: #94a3b8;" +
                "        }" +
                "    </style>" +
                "</head>" +
                "<body>" +
                "    <div class='card'>" +
                "        <h1>HexBridge Pro</h1>" +
                "        <p>Your local theming backend engine is currently offline. Please launch the server inside Termux first!</p>" +
                "        <button class='btn' onclick='window.location.reload()'>RETRY CONNECTION</button>" +
                "        <button class='btn btn-outline' onclick='launchTermux()'>LAUNCH TERMUX</button>" +
                "    </div>" +
                "    <script>" +
                "        function launchTermux() {" +
                "            window.location.href = 'intent://launch#Intent;scheme=termux;package=com.termux;end';" +
                "        }" +
                "    </script>" +
                "</body>" +
                "</html>";
        
        mWebView.loadDataWithBaseURL(null, errorHtml, "text/html", "utf-8", null);
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
