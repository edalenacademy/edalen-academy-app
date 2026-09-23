package com.edalenacademy.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * Keep Android's system bars outside the WebView.
         * This prevents the Edalen Academy page from covering:
         * - phone time
         * - network icons
         * - battery
         * - notification icons
         * - bottom navigation area
         */
        getWindow().setStatusBarColor(Color.rgb(246, 243, 236));
        getWindow().setNavigationBarColor(Color.rgb(246, 243, 236));

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );

        /*
         * IMPORTANT:
         * Do NOT use setDecorFitsSystemWindows(false).
         * Android will automatically keep the WebView
         * below the status bar and above the navigation bar.
         */

        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);

        FrameLayout.LayoutParams params =
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            );

        root.addView(webView, params);

        setContentView(root);

        WebSettings settings = webView.getSettings();

        // Website requires JavaScript.
        settings.setJavaScriptEnabled(true);

        // Required for Supabase and website storage.
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // Normal mobile WebView sizing.
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);

        // Allow the website to communicate with online services.
        settings.setMixedContentMode(
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        webView.setWebViewClient(new WebViewClient());

        /*
         * Load the NEW Edalen Academy HTML bundled inside the APK.
         * This is the index.html we placed in:
         *
         * app/src/main/assets/index.html
         */
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
