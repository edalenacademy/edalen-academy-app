package com.edalenacademy.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.security.KeyStore;
import java.util.ArrayList;

public class MainActivity extends FragmentActivity {

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int MEDIA_PERMISSION_REQUEST = 2001;

    private static final String APP_URL =
            "https://edalenacademy.edalenacademy.workers.dev";

    /*
     * Names of the SharedPreferences file and Keystore alias the old,
     * now-removed biometric login feature used to use. Kept only long
     * enough to wipe any leftover data from devices that had biometric
     * login enabled before it was removed -- see cleanupLegacyBiometricData().
     */
    private static final String LEGACY_BIOMETRIC_PREFS_NAME =
            "edalen_native_biometric";

    private static final String LEGACY_BIOMETRIC_KEY_ALIAS =
            "EdalenAcademyBiometricSessionKeyV4";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private SwipeRefreshLayout swipeRefreshLayout;
    private Button refreshButton;
    private PermissionRequest pendingPermissionRequest;
    private boolean isRefreshing = false;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cleanupLegacyBiometricData();

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

        FrameLayout.LayoutParams webParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        swipeRefreshLayout.addView(webView, webParams);
        root.addView(swipeRefreshLayout, webParams);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0;
            int bottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
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
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(Color.WHITE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                Uri uri = request.getUrl();
                if (uri == null) return false;

                String host = uri.getHost();

                /* WhatsApp links must leave WebView. */
                if (host != null && (
                        "wa.link".equalsIgnoreCase(host)
                                || "wa.me".equalsIgnoreCase(host)
                                || "api.whatsapp.com".equalsIgnoreCase(host)
                )) {
                    openExternal(uri);
                    return true;
                }

                /* Jitsi meetings. */
                if ("meet.jit.si".equalsIgnoreCase(host)) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.setPackage("org.jitsi.meet");
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        intent.setPackage(null);
                        try {
                            startActivity(intent);
                        } catch (Exception ignored) {
                        }
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

                /*
                 * BUG FIX: the retry FAB was created and fully wired up with
                 * a click listener, but was left permanently hidden
                 * (View.GONE) with nothing anywhere that ever made it
                 * visible again -- a dead button the user could never
                 * actually see or use. It's meant to give the user a manual
                 * way to retry when a page load fails (pull-to-refresh is
                 * intentionally disabled on this screen), so hide it on a
                 * successful load and show it on a failed one (see
                 * onReceivedError below).
                 */
                if (refreshButton != null) {
                    refreshButton.setVisibility(View.GONE);
                }

                disableBiometricWebUi();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    int errorCode,
                    String description,
                    String failingUrl
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                isRefreshing = false;
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
                if (refreshButton != null) {
                    refreshButton.setVisibility(View.VISIBLE);
                }
            }
        });

        swipeRefreshLayout.setEnabled(false);
        swipeRefreshLayout.setOnRefreshListener(this::refreshPageSafely);
        swipeRefreshLayout.setOnChildScrollUpCallback(
                (parent, child) -> webView != null && webView.getScrollY() > 0
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

                    boolean cameraAllowed = !needsCamera ||
                            checkSelfPermission(Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED;

                    boolean microphoneAllowed = !needsAudio ||
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
                    FileChooserParams params
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
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }

                return true;
            }
        });

        refreshButton = new Button(this);
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
        refreshButton.setOnClickListener(view -> refreshPageSafely());

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

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No app is available to open this link.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void refreshPageSafely() {
        if (webView == null || isRefreshing) return;

        isRefreshing = true;
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }
        webView.reload();
    }

    /*
     * Biometric login has been removed from the app. This does not touch
     * index.html/the web bundle (only this native wrapper is editable
     * here), so rather than leaving the page to fall back to whatever
     * WebAuthn-based implementation it may already have, this explicitly
     * defines every hook the page used to call into the native biometric
     * bridge as an inert no-op: no biometric card is shown in Settings, the
     * "Enable fingerprint login" flow does nothing, and the biometric lock
     * screen can never be triggered.
     */
    private void disableBiometricWebUi() {
        if (webView == null) return;

        String script =
                "(function(){"
                        + "window.biometricSupported=function(){return false;};"
                        + "window.biometricSettingsCardHtml=function(){return '';};"
                        + "window.enableBiometricLogin=function(){"
                        + "if(typeof toast==='function')toast('Biometric login is not available in this app.');"
                        + "};"
                        + "window.disableBiometricLogin=function(){};"
                        + "window.attemptBiometricUnlock=function(){};"
                        + "window.attemptBiometricLoginAtAuth=function(){};"
                        + "window.biometricLoginInfo=function(){return null;};"
                        + "window.nativeBiometricSuccess=function(){};"
                        + "window.nativeBiometricError=function(){};"
                        + "if(window.AppState)AppState.biometricLockPending=false;"
                        + "if(typeof render==='function')setTimeout(function(){try{render();}catch(e){}},50);"
                        + "})();";

        webView.evaluateJavascript(script, null);
    }

    /*
     * One-time cleanup for devices that had the old biometric login feature
     * enabled before it was removed: wipes the SharedPreferences file it
     * used to store an encrypted session, and deletes its Android Keystore
     * key. Safe to call on every launch -- both are no-ops once the data
     * is gone.
     */
    private void cleanupLegacyBiometricData() {
        try {
            getSharedPreferences(LEGACY_BIOMETRIC_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit();
        } catch (Exception ignored) {
        }

        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(LEGACY_BIOMETRIC_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_BIOMETRIC_KEY_ALIAS);
            }
        } catch (Exception ignored) {
        }
    }

    private void requestMediaPermissions(boolean needCamera, boolean needAudio) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        ArrayList<String> permissions = new ArrayList<>();

        if (needCamera && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        if (needAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
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

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;

            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != MEDIA_PERMISSION_REQUEST
                || pendingPermissionRequest == null) {
            return;
        }

        boolean cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean microphoneAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
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

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
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

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
