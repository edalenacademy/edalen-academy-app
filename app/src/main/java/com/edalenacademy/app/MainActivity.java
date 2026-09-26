package com.edalenacademy.app;

import android.content.Intent;
import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends FragmentActivity {

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int MEDIA_PERMISSION_REQUEST = 2001;

    private static final String APP_URL =
            "file:///android_asset/index.html";

    private static final String KEYSTORE_NAME =
            "AndroidKeyStore";

    private static final String BIOMETRIC_KEY_ALIAS =
            "EdalenAcademyBiometricSessionKey";

    private static final String PREFS_NAME =
            "edalen_native_biometric";

    private static final String PREF_CIPHERTEXT =
            "encrypted_session";

    private static final String PREF_IV =
            "session_iv";

    private static final String PREF_USER =
            "user_info";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private SwipeRefreshLayout swipeRefreshLayout;
    private NativeBiometricBridge biometricBridge;

    private boolean isRefreshing = false;

    private PermissionRequest pendingPermissionRequest;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private String pendingBiometricAction = "";

    private final Runnable sessionSyncRunnable =
            new Runnable() {
                @Override
                public void run() {
                    syncCurrentSupabaseSession();

                    handler.postDelayed(
                            this,
                            3000
                    );
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();

        window.setStatusBarColor(
                Color.rgb(246, 243, 236)
        );

        window.setNavigationBarColor(
                Color.rgb(246, 243, 236)
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            int flags =
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                flags |=
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }

            window.getDecorView()
                    .setSystemUiVisibility(flags);
        }

        FrameLayout root =
                new FrameLayout(this);

        swipeRefreshLayout =
                new SwipeRefreshLayout(this);

        webView =
                new WebView(this);

        FrameLayout.LayoutParams webViewParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        swipeRefreshLayout.addView(
                webView,
                webViewParams
        );

        FrameLayout.LayoutParams swipeParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        root.addView(
                swipeRefreshLayout,
                swipeParams
        );

        setContentView(root);

        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {

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

                        top =
                                insets.getSystemWindowInsetTop();

                        bottom =
                                insets.getSystemWindowInsetBottom();
                    }

                    view.setPadding(
                            0,
                            top,
                            0,
                            bottom
                    );

                    return insets;
                }
        );

        WebSettings settings =
                webView.getSettings();

        settings.setJavaScriptEnabled(true);

        CookieManager
                .getInstance()
                .setAcceptThirdPartyCookies(
                        webView,
                        true
                );

        CookieManager
                .getInstance()
                .setAcceptCookie(true);

        settings.setSupportMultipleWindows(false);

        settings.setJavaScriptCanOpenWindowsAutomatically(
                false
        );

        settings.setDomStorageEnabled(true);

        settings.setDatabaseEnabled(true);

        settings.setAllowFileAccess(true);

        settings.setAllowContentAccess(true);

        settings.setLoadWithOverviewMode(false);

        settings.setUseWideViewPort(false);

        settings.setCacheMode(
                WebSettings.LOAD_DEFAULT
        );

        settings.setBuiltInZoomControls(false);

        settings.setDisplayZoomControls(false);

        webView.setOverScrollMode(
                View.OVER_SCROLL_NEVER
        );

        webView.setBackgroundColor(
                Color.WHITE
        );

        biometricBridge =
                new NativeBiometricBridge(this);

        webView.addJavascriptInterface(
                biometricBridge,
                "EdalenNative"
        );

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {

                        Uri uri =
                                request.getUrl();

                        /*
                         * WhatsApp links
                         *
                         * Open WhatsApp links outside
                         * the WebView so Android/WhatsApp
                         * can handle them correctly.
                         */
                        if (uri != null
                                && uri.getHost() != null
                                && (
                                "wa.link".equalsIgnoreCase(
                                        uri.getHost()
                                )
                                        ||
                                "wa.me".equalsIgnoreCase(
                                        uri.getHost()
                                )
                                        ||
                                "api.whatsapp.com".equalsIgnoreCase(
                                        uri.getHost()
                                )
                        )) {

                            Intent whatsappIntent =
                                    new Intent(
                                            Intent.ACTION_VIEW,
                                            uri
                                    );

                            try {

                                startActivity(
                                        whatsappIntent
                                );

                            } catch (Exception e) {

                                /*
                                 * If WhatsApp is not installed,
                                 * allow Android/browser to handle
                                 * the link.
                                 */
                                try {

                                    whatsappIntent.setPackage(
                                            null
                                    );

                                    startActivity(
                                            whatsappIntent
                                    );

                                } catch (Exception ignored) {

                                    return false;
                                }
                            }

                            return true;
                        }

                        /*
                         * Jitsi meeting links
                         */
                        if (uri != null
                                && "meet.jit.si"
                                .equalsIgnoreCase(
                                        uri.getHost()
                                )) {

                            Intent intent =
                                    new Intent(
                                            Intent.ACTION_VIEW,
                                            uri
                                    );

                            intent.setPackage(
                                    "org.jitsi.meet"
                            );

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

                        super.onPageStarted(
                                view,
                                url,
                                favicon
                        );

                        isRefreshing = true;

                        if (swipeRefreshLayout != null) {

                            swipeRefreshLayout
                                    .setRefreshing(true);
                        }
                    }

                    @Override
                    public void onPageFinished(
                            WebView view,
                            String url
                    ) {

                        super.onPageFinished(
                                view,
                                url
                        );

                        isRefreshing = false;

                        if (swipeRefreshLayout != null) {

                            swipeRefreshLayout
                                    .setRefreshing(false);
                        }

                        installNativeBiometricJavascript();

                        handler.postDelayed(
                                () -> enforceNativeBiometricLock(),
                                700
                        );

                        syncCurrentSupabaseSession();
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

                            swipeRefreshLayout
                                    .setRefreshing(false);
                        }
                    }
                }
        );

        swipeRefreshLayout.setEnabled(false);

        swipeRefreshLayout.setOnRefreshListener(
                this::refreshPageSafely
        );

        swipeRefreshLayout.setOnChildScrollUpCallback(
                (parent, child) ->
                        webView.getScrollY() > 0
        );

        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public void onPermissionRequest(
                            PermissionRequest request
                    ) {

                        runOnUiThread(() -> {

                            String[] resources =
                                    request.getResources();

                            boolean needsCamera = false;
                            boolean needsAudio = false;

                            for (String resource : resources) {

                                if (PermissionRequest
                                        .RESOURCE_VIDEO_CAPTURE
                                        .equals(resource)) {

                                    needsCamera = true;
                                }

                                if (PermissionRequest
                                        .RESOURCE_AUDIO_CAPTURE
                                        .equals(resource)) {

                                    needsAudio = true;
                                }
                            }

                            boolean cameraAllowed =
                                    !needsCamera
                                            ||
                                    checkSelfPermission(
                                            Manifest.permission.CAMERA
                                    )
                                            ==
                                    PackageManager.PERMISSION_GRANTED;

                            boolean microphoneAllowed =
                                    !needsAudio
                                            ||
                                    checkSelfPermission(
                                            Manifest.permission.RECORD_AUDIO
                                    )
                                            ==
                                    PackageManager.PERMISSION_GRANTED;

                            if (cameraAllowed
                                    && microphoneAllowed) {

                                request.grant(resources);

                            } else {

                                pendingPermissionRequest =
                                        request;

                                requestMediaPermissions(
                                        needsCamera,
                                        needsAudio
                                );
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

                            filePathCallback
                                    .onReceiveValue(null);
                        }

                        filePathCallback =
                                callback;

                        Intent intent =
                                new Intent(
                                        Intent.ACTION_OPEN_DOCUMENT
                                );

                        intent.addCategory(
                                Intent.CATEGORY_OPENABLE
                        );

                        intent.setType("*/*");

                        intent.putExtra(
                                Intent.EXTRA_ALLOW_MULTIPLE,
                                false
                        );

                        try {

                            startActivityForResult(
                                    intent,
                                    FILE_CHOOSER_REQUEST
                            );

                        } catch (Exception exception) {

                            filePathCallback = null;

                            callback.onReceiveValue(
                                    null
                            );

                            return false;
                        }

                        return true;
                    }
                }
        );

        Button refreshButton =
                new Button(this);

        refreshButton.setText("\u21BB");

        refreshButton.setTextSize(23);

        refreshButton.setTextColor(
                Color.WHITE
        );

        refreshButton.setGravity(
                Gravity.CENTER
        );

        refreshButton.setPadding(
                0,
                0,
                0,
                0
        );

        refreshButton.setMinWidth(0);

        refreshButton.setMinHeight(0);

        refreshButton.setAllCaps(false);

        refreshButton.setContentDescription(
                "Refresh page"
        );

        GradientDrawable refreshBackground =
                new GradientDrawable();

        refreshBackground.setShape(
                GradientDrawable.OVAL
        );

        refreshBackground.setColor(
                Color.rgb(25, 45, 75)
        );

        refreshButton.setBackground(
                refreshBackground
        );

        refreshButton.setOnClickListener(
                view -> refreshPageSafely()
        );

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

        root.addView(
                refreshButton,
                refreshParams
        );

        refreshButton.setVisibility(
                View.GONE
        );

        webView.loadUrl(APP_URL);
    }

    private void refreshPageSafely() {

        if (webView == null
                || isRefreshing) {

            return;
        }

        isRefreshing = true;

        if (swipeRefreshLayout != null) {

            swipeRefreshLayout
                    .setRefreshing(true);
        }

        webView.post(() -> {

            if (webView != null) {

                webView.reload();
            }
        });
    }

    private void installNativeBiometricJavascript() {

        if (webView == null) {
            return;
        }

        String script =
                "(function(){"
                        + "if(window.__edalenNativeBiometricInstalled)return;"
                        + "if(!window.EdalenNative)return;"
                        + "window.__edalenNativeBiometricInstalled=true;"

                        + "window.biometricSupported=function(){"
                        + "return !!EdalenNative.isBiometricAvailable();"
                        + "};"

                        + "window.biometricLoginInfo=function(){"
                        + "if(!EdalenNative.isBiometricAvailable())return null;"
                        + "if(!EdalenNative.hasStoredSession())return null;"
                        + "var u=EdalenNative.getStoredUser();"
                        + "if(!u)return null;"
                        + "return {id:u.id,name:u.name||'',username:u.username||''};"
                        + "};"

                        + "window.biometricSettingsCardHtml=function(u){"
                        + "if(!u||!u.id)return '';"
                        + "if(!EdalenNative.isBiometricAvailable())"
                        + "return '<div class=\"card\"><div class=\"card-title\">Biometric login</div>"
                        + "<p style=\"color:var(--text-soft);\">This device does not have an available fingerprint or Face ID biometric.</p></div>';"

                        + "var enabled=EdalenNative.hasStoredSession();"

                        + "return '<div class=\"card\"><div class=\"card-title\">Biometric login</div>'"
                        + "+'<p style=\"color:var(--text-soft);\">'"
                        + "+(enabled"
                        + "? 'Fingerprint / Face ID login is <b>on</b> for this device.'"
                        + ": 'Turn on fingerprint or Face ID to log in without typing your password.')"
                        + "+'</p>'"
                        + "+(enabled"
                        + "? '<button class=\"btn btn-ghost\" data-action=\"disable-biometric\">Turn off on this device</button>'"
                        + ": '<button class=\"btn btn-primary\" data-action=\"enable-biometric\">Enable fingerprint login</button>')"
                        + "+'</div>';"
                        + "};"

                        + "window.enableBiometricLogin=function(){"
                        + "if(!EdalenNative.isBiometricAvailable()){"
                        + "if(typeof toast==='function')toast('No fingerprint or Face ID is available on this device.');"
                        + "return;"
                        + "}"
                        + "if(typeof toast==='function')toast('Touch the fingerprint sensor or use Face ID to continue.');"
                        + "EdalenNative.enableBiometric();"
                        + "};"

                        + "window.disableBiometricLogin=function(){"
                        + "EdalenNative.disableBiometric();"
                        + "if(typeof toast==='function')toast('Fingerprint login turned off on this device.');"
                        + "if(typeof render==='function')render();"
                        + "};"

                        + "window.attemptBiometricLoginAtAuth=function(){"
                        + "if(!EdalenNative.hasStoredSession()){"
                        + "if(typeof toast==='function')toast('Biometric login isn\\'t set up on this device.');"
                        + "return;"
                        + "}"
                        + "if(typeof toast==='function')toast('Hold on pls… verifying your fingerprint or Face ID.');"
                        + "EdalenNative.authenticateBiometric('login');"
                        + "};"

                        + "window.attempt
