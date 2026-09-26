package com.edalenacademy.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Toast;

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

    /*
     * New alias for the corrected biometric storage.
     */
    private static final String BIOMETRIC_KEY_ALIAS =
            "EdalenAcademyBiometricSessionKeyV3";

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

    private PermissionRequest pendingPermissionRequest;
    private boolean isRefreshing = false;
    private String pendingBiometricAction = "";

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Runnable sessionSyncRunnable =
            new Runnable() {
                @Override
                public void run() {
                    syncCurrentSupabaseSession();
                    handler.postDelayed(this, 3000);
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

        FrameLayout.LayoutParams webParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        swipeRefreshLayout.addView(
                webView,
                webParams
        );

        root.addView(
                swipeRefreshLayout,
                webParams
        );

        setContentView(root);

        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {

                    int top = 0;
                    int bottom = 0;

                    if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.R) {

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
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setCacheMode(
                WebSettings.LOAD_DEFAULT
        );
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        CookieManager
                .getInstance()
                .setAcceptCookie(true);

        CookieManager
                .getInstance()
                .setAcceptThirdPartyCookies(
                        webView,
                        true
                );

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

                        if (uri == null) {
                            return false;
                        }

                        String host =
                                uri.getHost();

                        /*
                         * WhatsApp links.
                         */
                        if (host != null
                                && (
                                "wa.link"
                                        .equalsIgnoreCase(host)
                                        ||
                                "wa.me"
                                        .equalsIgnoreCase(host)
                                        ||
                                "api.whatsapp.com"
                                        .equalsIgnoreCase(host)
                        )) {

                            openExternal(uri);

                            return true;
                        }

                        /*
                         * Jitsi meetings.
                         */
                        if ("meet.jit.si"
                                .equalsIgnoreCase(host)) {

                            Intent intent =
                                    new Intent(
                                            Intent.ACTION_VIEW,
                                            uri
                                    );

                            intent.setPackage(
                                    "org.jitsi.meet"
                            );

                            try {

                                startActivity(
                                        intent
                                );

                            } catch (Exception e) {

                                intent.setPackage(null);

                                try {

                                    startActivity(
                                            intent
                                    );

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

                        /*
                         * Install the native biometric
                         * functions into the WebView.
                         */
                        installNativeBiometricJavascript();

                        handler.postDelayed(
                                () ->
                                        enforceNativeBiometricLock(),
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

        swipeRefreshLayout
                .setOnChildScrollUpCallback(
                        (parent, child) ->
                                webView != null
                                        &&
                                webView.getScrollY() > 0
                );

        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public void onPermissionRequest(
                            PermissionRequest request
                    ) {

                        runOnUiThread(
                                () -> {

                                    String[] resources =
                                            request.getResources();

                                    boolean needsCamera =
                                            false;

                                    boolean needsAudio =
                                            false;

                                    for (
                                            String resource
                                            : resources
                                    ) {

                                        if (
                                                PermissionRequest
                                                        .RESOURCE_VIDEO_CAPTURE
                                                        .equals(resource)
                                        ) {

                                            needsCamera =
                                                    true;
                                        }

                                        if (
                                                PermissionRequest
                                                        .RESOURCE_AUDIO_CAPTURE
                                                        .equals(resource)
                                        ) {

                                            needsAudio =
                                                    true;
                                        }
                                    }

                                    boolean cameraAllowed =
                                            !needsCamera
                                                    ||
                                            checkSelfPermission(
                                                    Manifest.permission.CAMERA
                                            )
                                                    ==
                                            PackageManager
                                                    .PERMISSION_GRANTED;

                                    boolean microphoneAllowed =
                                            !needsAudio
                                                    ||
                                            checkSelfPermission(
                                                    Manifest.permission.RECORD_AUDIO
                                            )
                                                    ==
                                            PackageManager
                                                    .PERMISSION_GRANTED;

                                    if (
                                            cameraAllowed
                                                    &&
                                            microphoneAllowed
                                    ) {

                                        request.grant(
                                                resources
                                        );

                                    } else {

                                        pendingPermissionRequest =
                                                request;

                                        requestMediaPermissions(
                                                needsCamera,
                                                needsAudio
                                        );
                                    }
                                }
                        );
                    }

                    @Override
                    public boolean onShowFileChooser(
                            WebView view,
                            ValueCallback<Uri[]> callback,
                            FileChooserParams params
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

                        } catch (Exception e) {

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
        refreshButton.setTextColor(Color.WHITE);
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
                view ->
                        refreshPageSafely()
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

        webView.loadUrl(
                APP_URL
        );
    }

    private void openExternal(Uri uri) {

        try {

            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            uri
                    );

            startActivity(intent);

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "No app is available to open this link.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void refreshPageSafely() {

        if (
                webView == null
                        ||
                isRefreshing
        ) {

            return;
        }

        isRefreshing = true;

        if (swipeRefreshLayout != null) {

            swipeRefreshLayout
                    .setRefreshing(true);
        }

        webView.reload();
    }

    /*
     * ============================================================
     * NATIVE BIOMETRIC JAVASCRIPT BRIDGE
     * ============================================================
     *
     * This is the important correction.
     *
     * The HTML originally checks WebAuthn/passkeys using:
     *
     *     window.PublicKeyCredential
     *
     * That is not the native Android biometric system.
     *
     * The APK now exposes its own native biometric functions
     * through EdalenNative.
     */

    private void installNativeBiometricJavascript() {

        if (webView == null) {
            return;
        }

        String script =
                "(function(){"
                        + "if(!window.EdalenNative)return;"

                        /*
                         * Tell the HTML application that native
                         * Android biometric authentication is available.
                         */
                        + "window.biometricSupported=function(){"
                        + "return !!(window.EdalenNative&&EdalenNative.isBiometricAvailable&&EdalenNative.isBiometricAvailable());"
                        + "};"

                        /*
                         * Native biometric settings card.
                         * This replaces the WebAuthn/passkey card.
                         */
                        + "window.biometricSettingsCardHtml=function(u){"
                        + "if(!u||!u.id)return '';"

                        + "var enabled=false;"
                        + "try{"
                        + "enabled=!!(window.EdalenNative&&EdalenNative.hasStoredSession&&EdalenNative.hasStoredSession());"
                        + "}catch(e){enabled=false;}"

                        + "return '<div class=\"card\">'"
                        + "+'<div class=\"card-title\">Biometric login</div>'"
                        + "+'<p style=\"color:var(--text-soft);\">'"
                        + "+(enabled"
                        + "?'Fingerprint / Face ID login is <b>on</b> for this device. You will be asked to verify before the app opens.'"
                        + ":'Turn on fingerprint or Face ID to secure this app on this device. You can then use your fingerprint or Face ID instead of typing your password.')"
                        + "+'</p>'"
                        + "+(enabled"
                        + "?'<'+'button class=\"btn btn-ghost\" data-action=\"disable-biometric\">Turn off biometric login</button>'"
                        + ":'<'+'button class=\"btn btn-primary\" data-action=\"enable-biometric\">Enable fingerprint login</button>')"
                        + "+'</div>';"
                        + "};"

                        /*
                         * Enable native biometric login.
                         */
                        + "window.enableBiometricLogin=function(){"
                        + "try{"
                        + "if(!window.EdalenNative||!EdalenNative.enableBiometric){"
                        + "if(typeof toast==='function')toast('Native biometric login is unavailable.');"
                        + "return;"
                        + "}"

                        + "if(typeof toast==='function')toast('Hold on pls… verifying your fingerprint or Face ID.');"

                        + "EdalenNative.enableBiometric();"

                        + "}catch(e){"
                        + "if(typeof toast==='function')toast('Could not start biometric login.');"
                        + "}"
                        + "};"

                        /*
                         * Disable native biometric login.
                         */
                        + "window.disableBiometricLogin=function(){"
                        + "try{"
                        + "if(window.EdalenNative&&EdalenNative.disableBiometric){"
                        + "EdalenNative.disableBiometric();"
                        + "}"
                        + "}catch(e){"
                        + "if(typeof toast==='function')toast('Could not remove biometric login.');"
                        + "}"
                        + "};"

                        /*
                         * Unlock an already signed-in account.
                         */
                        + "window.attemptBiometricUnlock=function(){"
                        + "try{"

                        + "if(!window.EdalenNative||!EdalenNative.hasStoredSession||!EdalenNative.hasStoredSession()){"
                        + "if(typeof toast==='function')toast('Biometric login is not set up on this device.');"
                        + "return;"
                        + "}"

                        + "if(typeof toast==='function')toast('Hold on pls… verifying your fingerprint or Face ID.');"

                        + "EdalenNative.authenticateBiometric('unlock');"

                        + "}catch(e){"
                        + "if(typeof toast==='function')toast('Biometric login is unavailable.');"
                        + "}"
                        + "};"

                        /*
                         * Fingerprint login from the normal login screen.
                         */
                        + "window.attemptBiometricLoginAtAuth=function(){"
                        + "try{"

                        + "if(!window.EdalenNative||!EdalenNative.hasStoredSession||!EdalenNative.hasStoredSession()){"
                        + "if(typeof toast==='function')toast('No biometric login is saved on this device.');"
                        + "return;"
                        + "}"

                        + "if(typeof toast==='function')toast('Hold on pls… verifying your fingerprint or Face ID.');"

                        + "EdalenNative.authenticateBiometric('login');"

                        + "}catch(e){"
                        + "if(typeof toast==='function')toast('Biometric login is unavailable.');"
                        + "}"
                        + "};"

                        /*
                         * Information used by the login screen.
                         */
                        + "window.biometricLoginInfo=function(){"
                        + "try{"
                        + "return window.EdalenNative&&EdalenNative.isBiometricAvailable&&EdalenNative.isBiometricAvailable()"
                        + "?{available:true}"
                        + ":null;"
                        + "}catch(e){"
                        + "return null;"
                        + "}"
                        + "};"

                        /*
                         * Called by Java after successful biometric
                         * enrollment or login.
                         */
                        + "window.nativeBiometricSuccess=function(action){"

                        + "if(action==='enable'){"
                        + "if(typeof toast==='function')toast('Fingerprint / Face ID login enabled on this device.');"
                        + "if(typeof render==='function')render();"
                        + "return;"
                        + "}"

                        + "if(window.AppState){"
                        + "AppState.biometricLockPending=false;"
                        + "}"

                        + "if(typeof toast==='function')toast('Hold on pls… signing you in.');"

                        + "if(typeof render==='function')render();"

                        + "};"

                        /*
                         * Called by Java when something goes wrong.
                         */
                        + "window.nativeBiometricError=function(message){"
                        + "if(typeof toast==='function'){"
                        + "toast(message||'Biometric verification failed.');"
                        + "}"
                        + "};"

                        /*
                         * Re-render the current screen so the
                         * WebAuthn "unsupported" message disappears.
                         */
                        + "if(typeof render==='function'){"
                        + "setTimeout(function(){"
                        + "try{render();}catch(e){}"
                        + "},50);"
                        + "}"

                        + "})();";

        webView.evaluateJavascript(
                script,
                null
        );
    }

    private void enforceNativeBiometricLock() {

        if (
                webView == null
                        ||
                biometricBridge == null
                        ||
                !biometricBridge.hasStoredSession()
        ) {

            return;
        }

        String script =
                "(function(){"
                        + "if(window.AppState&&AppState.session&&AppState.user){"
                        + "AppState.biometricLockPending=true;"
                        + "if(typeof render==='function')render();"
                        + "}"
                        + "})();";

        webView.evaluateJavascript(
                script,
                null
        );
    }

    private void syncCurrentSupabaseSession() {

        if (webView == null) {
            return;
        }

        String script =
                "(async function(){"
                        + "try{"
                        + "if(!window.sb||!sb.auth)return '';"
                        + "var r=await sb.auth.getSession();"
                        + "if(!r||!r.data||!r.data.session)return '';"
                        + "var s=r.data.session;"
                        + "var u=(window.AppState&&AppState.user)?AppState.user:null;"
                        + "var o={access_token:s.access_token,refresh_token:s.refresh_token,user:u?{id:u.id,name:u.name||'',username:u.username||'',email:u.email||''}:null};"
                        + "return btoa(unescape(encodeURIComponent(JSON.stringify(o))));"
                        + "}catch(e){return '';}"
                        + "})()";

        webView.evaluateJavascript(
                script,
                value -> {

                    try {

                        if (
                                value == null
                                        ||
                                "null".equals(value)
                                        ||
                                "\"\"".equals(value)
                        ) {

                            return;
                        }

                        Object parsed =
                                new JSONTokener(value)
                                        .nextValue();

                        if (
                                !(parsed instanceof String)
                        ) {

                            return;
                        }

                        String encoded =
                                (String) parsed;

                        if (
                                encoded == null
                                        ||
                                encoded.trim().isEmpty()
                        ) {

                            return;
                        }

                        JSONObject session =
                                decodeBase64Json(
                                        encoded
                                );

                        if (session == null) {
                            return;
                        }

                        if (
                                !session.has(
                                        "access_token"
                                )
                                        ||
                                !session.has(
                                        "refresh_token"
                                )
                        ) {

                            return;
                        }

                        saveEncryptedSession(
                                session
                        );

                    } catch (Exception ignored) {
                    }
                }
        );
    }

    private void captureCurrentSessionForBiometric() {

        if (webView == null) {

            biometricBridge.sendBiometricError(
                    "The login session is not ready yet. Please wait a moment and try again."
            );

            return;
        }

        String script =
                "(async function(){"
                        + "try{"
                        + "if(!window.sb||!sb.auth)throw new Error('Authentication service is not ready.');"
                        + "var r=await sb.auth.getSession();"
                        + "if(!r||!r.data||!r.data.session)throw new Error('Please sign in first.');"
                        + "var s=r.data.session;"
                        + "if(!s.access_token||!s.refresh_token)throw new Error('The current login session is incomplete.');"
                        + "var u=(window.AppState&&AppState.user)?AppState.user:null;"
                        + "var o={access_token:s.access_token,refresh_token:s.refresh_token,user:u?{id:u.id,name:u.name||'',username:u.username||'',email:u.email||''}:null};"
                        + "return btoa(unescape(encodeURIComponent(JSON.stringify(o))));"
                        + "}catch(e){"
                        + "return 'ERROR:'+btoa(unescape(encodeURIComponent(e.message||'Could not capture the current sign-in.')));"
                        + "}"
                        + "})()";

        webView.evaluateJavascript(
                script,
                value -> {

                    try {

                        if (
                                value == null
                                        ||
                                "null".equals(value)
                        ) {

                            biometricBridge
                                    .sendBiometricError(
                                            "The app could not read the current login session."
                                    );

                            return;
                        }

                        Object parsed =
                                new JSONTokener(value)
                                        .nextValue();

                        if (
                                !(parsed instanceof String)
                        ) {

                            biometricBridge
                                    .sendBiometricError(
                                            "The app could not read the current login session."
                                    );

                            return;
                        }

                        String encoded =
                                (String) parsed;

                        if (
                                encoded == null
                                        ||
                                encoded.trim().isEmpty()
                        ) {

                            biometricBridge
                                    .sendBiometricError(
                                            "The current login session is empty. Please log in again."
                                    );

                            return;
                        }

                        if (
                                encoded.startsWith(
                                        "ERROR:"
                                )
                        ) {

                            String errorText =
                                    encoded.substring(6);

                            String message =
                                    decodeBase64Text(
                                            errorText
                                    );

                            biometricBridge
                                    .sendBiometricError(
                                            message == null
                                                    ||
                                            message.trim().isEmpty()
                                                    ?
                                            "Could not capture the current sign-in."
                                                    :
                                            message
                                    );

                            return;
                        }

                        JSONObject session =
                                decodeBase64Json(
                                        encoded
                                );

                        if (session == null) {

                            biometricBridge
                                    .sendBiometricError(
                                            "Could not read the current login session."
                                    );

                            return;
                        }

                        String accessToken =
                                session.optString(
                                        "access_token",
                                        ""
                                );

                        String refreshToken =
                                session.optString(
                                        "refresh_token",
                                        ""
                                );

                        if (
                               
