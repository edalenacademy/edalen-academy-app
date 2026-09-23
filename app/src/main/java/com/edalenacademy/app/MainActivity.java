package com.edalenacademy.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();

        // Keep the website below the phone's status bar.
        window.setStatusBarColor(Color.rgb(246, 243, 236));
        window.setNavigationBarColor(Color.rgb(246, 243, 236));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);

            WindowInsetsController controller =
                window.getInsetsController();

            if (controller != null) {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        }

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);

        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        // Prevent the website from covering the phone's clock,
        // network icons, battery and bottom navigation area.
        root.setOnApplyWindowInsetsListener((v, insets) -> {

            int top = 0;
            int bottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.systemBars());

                top = bars.top;
                bottom = bars.bottom;

            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }

            webView.setPadding(0, top, 0, bottom);

            return insets;
        });

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);

        // Do not use the old website page saved in the APK.
        webView.clearCache(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient());

        // Always load the LIVE Edalen Academy website.
        webView.loadUrl(
            "https://edalenacademy.github.io/edalenacademy/?app=1#/login"
        );
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
