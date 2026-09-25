package com.edalenacademy.app;
import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.CookieManager;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final String APP_URL =
            "file:///android_asset/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private SwipeRefreshLayout swipeRefreshLayout;
    private boolean isRefreshing = false;
    private PermissionRequest pendingPermissionRequest;
private static final int MEDIA_PERMISSION_REQUEST = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(246, 243, 236));
        window.setNavigationBarColor(Color.rgb(246, 243, 236));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }

            window.getDecorView().setSystemUiVisibility(flags);
        }

        FrameLayout root = new FrameLayout(this);

        swipeRefreshLayout = new SwipeRefreshLayout(this);
        webView = new WebView(this);

        FrameLayout.LayoutParams webViewParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        swipeRefreshLayout.addView(webView, webViewParams);

        FrameLayout.LayoutParams swipeParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        root.addView(swipeRefreshLayout, swipeParams);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0;
            int bottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                        insets.getInsets(
                                android.view.WindowInsets.Type.systemBars()
                        );

                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }

            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        CookieManager.getInstance().setAcceptCookie(true);
        settings.setSupportMultipleWindows(false);
settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(Color.WHITE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    Uri uri = request.getUrl();

    if (uri != null
            && "meet.jit.si".equalsIgnoreCase(uri.getHost())) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
intent.setPackage("org.jitsi.meet");

try {
    startActivity(intent);
} catch (Exception e) {
    intent.setPackage(null);
    startActivity(intent);
}
        return true;
    }

    return false;
}

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                super.onPageStarted(view, url, favicon);

                isRefreshing = true;

                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(true);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                isRefreshing = false;

                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    int errorCode,
                    String description,
                    String failingUrl
            ) {
                super.onReceivedError(
                        view,
                        errorCode,
                        description,
                        failingUrl
                );

                isRefreshing = false;

                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }
        });

        /*
         * Refresh remains enabled.
         * The guard prevents repeated refresh requests while the page
         * is already loading, which can cause a blank or unstable screen.
         */
        swipeRefreshLayout.setEnabled(false);

        swipeRefreshLayout.setOnRefreshListener(() -> {
            refreshPageSafely();
        });

        swipeRefreshLayout.setOnChildScrollUpCallback(
                (parent, child) -> webView.getScrollY() > 0
        );

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
public void onPermissionRequest(PermissionRequest request) {
    runOnUiThread(() -> {
        String[] resources = request.getResources();

        boolean needsCamera = false;
        boolean needsAudio = false;

        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                needsCamera = true;
            }

            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                needsAudio = true;
            }
        }

        boolean cameraAllowed =
                !needsCamera ||
                checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;

        boolean microphoneAllowed =
                !needsAudio ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;

        if (cameraAllowed && microphoneAllowed) {
            request.grant(resources);
        } else {
            pendingPermissionRequest = request;
            requestMediaPermissions(needsCamera, needsAudio);
        }
    });
}
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }

                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

                try {
                    startActivityForResult(
                            intent,
                            FILE_CHOOSER_REQUEST
                    );
                } catch (Exception exception) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }

                return true;
            }
        });

        Button refreshButton = new Button(this);

        refreshButton.setText("\u21BB");
        refreshButton.setTextSize(23);
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setGravity(Gravity.CENTER);
        refreshButton.setPadding(0, 0, 0, 0);
        refreshButton.setMinWidth(0);
        refreshButton.setMinHeight(0);
        refreshButton.setAllCaps(false);
        refreshButton.setContentDescription("Refresh page");

        GradientDrawable refreshBackground = new GradientDrawable();
        refreshBackground.setShape(GradientDrawable.OVAL);
        refreshBackground.setColor(Color.rgb(25, 45, 75));
        refreshButton.setBackground(refreshBackground);

        refreshButton.setOnClickListener(view -> {
            refreshPageSafely();
        });

        FrameLayout.LayoutParams refreshParams =
                new FrameLayout.LayoutParams(
                        dpToPx(46),
                        dpToPx(46),
                        Gravity.END | Gravity.BOTTOM
                );

        refreshParams.setMargins(
                dpToPx(12),
                dpToPx(12),
                dpToPx(16),
                dpToPx(112)
        );

        root.addView(refreshButton, refreshParams);
        
        refreshButton.setVisibility(View.GONE);
        webView.loadUrl(APP_URL);
    }

    private void refreshPageSafely() {
        if (webView == null || isRefreshing) {
            return;
        }

        isRefreshing = true;

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }

        webView.post(() -> {
            if (webView != null) {
                webView.reload();
            }
        });
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) {
                return;
            }

            Uri[] results = null;

            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private int dpToPx(int dp) {
        float density =
                getResources().getDisplayMetrics().density;

        return Math.round(dp * density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
    private void requestMediaPermissions(boolean needCamera, boolean needAudio) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();

        if (needCamera &&
                checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        if (needAudio &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissions.isEmpty()) {
            requestPermissions(
                    permissions.toArray(new String[0]),
                    MEDIA_PERMISSION_REQUEST
            );
        }
    }
}
    @Override
public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults) {

    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == MEDIA_PERMISSION_REQUEST
            && pendingPermissionRequest != null) {

        boolean cameraAllowed =
                checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED;

        boolean microphoneAllowed =
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;

        String[] resources = pendingPermissionRequest.getResources();
        boolean allowed = true;

        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && !cameraAllowed) {
                allowed = false;
            }

            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && !microphoneAllowed) {
                allowed = false;
            }
        }

        if (allowed) {
            pendingPermissionRequest.grant(resources);
        } else {
            pendingPermissionRequest.deny();
        }

        pendingPermissionRequest = null;
    }
}
}
