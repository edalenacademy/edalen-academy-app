package com.edalenacademy.app;

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

                        if (uri != null
                                && "meet.jit.si"
                                .equalsIgnoreCase(uri.getHost())) {

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

                        + "window.attemptBiometricUnlock=function(){"
                        + "if(!EdalenNative.hasStoredSession()){"
                        + "if(typeof toast==='function')toast('Biometric login isn\\'t set up on this device.');"
                        + "return;"
                        + "}"
                        + "if(typeof toast==='function')toast('Hold on pls… verifying your fingerprint or Face ID.');"
                        + "EdalenNative.authenticateBiometric('unlock');"
                        + "};"

                        + "window.nativeBiometricSuccess=function(action){"
                        + "if(action==='enable'){"
                        + "if(typeof toast==='function')toast('Fingerprint / Face ID login enabled on this device.');"
                        + "if(typeof render==='function')render();"
                        + "return;"
                        + "}"
                        + "if(typeof toast==='function')toast('Hold on pls… signing you in.');"
                        + "if(window.AppState)AppState.biometricLockPending=false;"
                        + "if(typeof render==='function')render();"
                        + "};"

                        + "window.nativeBiometricError=function(message){"
                        + "if(typeof toast==='function')toast(message||'Biometric verification failed.');"
                        + "};"

                        + "})();";

        webView.evaluateJavascript(
                script,
                null
        );
    }

    private void enforceNativeBiometricLock() {

        if (webView == null
                || !biometricBridge.hasStoredSession()) {

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
                        + "if(!window.sb||!sb.auth)return null;"
                        + "var r=await sb.auth.getSession();"
                        + "if(!r||!r.data||!r.data.session)return null;"
                        + "var s=r.data.session;"
                        + "var u=(window.AppState&&AppState.user)?AppState.user:null;"
                        + "return JSON.stringify({"
                        + "access_token:s.access_token,"
                        + "refresh_token:s.refresh_token,"
                        + "user:u?{id:u.id,name:u.name||'',username:u.username||'',email:u.email||''}:null"
                        + "});"
                        + "}catch(e){return null;}"
                        + "})()";

        webView.evaluateJavascript(
                script,
                value -> {

                    try {

                        if (value == null
                                || "null".equals(value)) {

                            return;
                        }

                        Object parsed =
                                new JSONTokener(value)
                                        .nextValue();

                        if (!(parsed instanceof String)) {
                            return;
                        }

                        String json =
                                (String) parsed;

                        if (json == null
                                || json.equals("null")
                                || json.trim().isEmpty()) {

                            return;
                        }

                        JSONObject session =
                                new JSONObject(json);

                        if (!session.has("access_token")
                                || !session.has("refresh_token")) {

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

    private void restoreStoredSession(
            String action
    ) {

        JSONObject stored =
                loadEncryptedSession();

        if (stored == null) {

            callJavascript(
                    "window.nativeBiometricError && "
                            + "window.nativeBiometricError("
                            + JSONObject.quote(
                                    "Your saved biometric sign-in is unavailable. Please log in normally."
                            )
                            + ");"
            );

            return;
        }

        String accessToken =
                stored.optString(
                        "access_token",
                        ""
                );

        String refreshToken =
                stored.optString(
                        "refresh_token",
                        ""
                );

        if (accessToken.isEmpty()
                || refreshToken.isEmpty()) {

            callJavascript(
                    "window.nativeBiometricError && "
                            + "window.nativeBiometricError("
                            + JSONObject.quote(
                                    "Your saved sign-in is unavailable. Please log in normally."
                            )
                            + ");"
            );

            return;
        }

        callJavascript(
                "window.toast && window.toast("
                        + JSONObject.quote(
                                "Hold on pls… signing you in."
                        )
                        + ");"
        );

        String sessionObject =
                "{"
                        + "access_token:"
                        + JSONObject.quote(accessToken)
                        + ",refresh_token:"
                        + JSONObject.quote(refreshToken)
                        + "}";

        String script =
                "(async function(){"
                        + "try{"
                        + "if(!window.sb||!sb.auth)throw new Error('Authentication service is not ready.');"
                        + "var r=await sb.auth.setSession("
                        + sessionObject
                        + ");"
                        + "if(r.error)throw new Error(r.error.message||'Could not restore your session.');"
                        + "if(window.AppState)AppState.session=r.data.session;"
                        + "if(typeof loadUserData==='function')await loadUserData();"
                        + "if(typeof loadAnnouncements==='function')loadAnnouncements(true);"
                        + "if(window.AppState)AppState.biometricLockPending=false;"
                        + "if(typeof navigate==='function')navigate('#/dashboard');"
                        + "if(typeof render==='function')render();"
                        + "if(window.nativeBiometricSuccess)window.nativeBiometricSuccess("
                        + JSONObject.quote(action)
                        + ");"
                        + "}catch(e){"
                        + "if(window.nativeBiometricError)window.nativeBiometricError("
                        + JSONObject.quote(
                                "Your saved sign-in has expired or is no longer valid. Please log in with your password."
                        )
                        + ");"
                        + "}"
                        + "})()";

        webView.evaluateJavascript(
                script,
                null
        );
    }

    private void captureCurrentSessionForBiometric() {

        String script =
                "(async function(){"
                        + "try{"
                        + "if(!window.sb||!sb.auth)throw new Error('Authentication service is not ready.');"
                        + "var r=await sb.auth.getSession();"
                        + "if(!r||!r.data||!r.data.session)throw new Error('Please sign in first.');"
                        + "var s=r.data.session;"
                        + "var u=(window.AppState&&AppState.user)?AppState.user:null;"
                        + "return JSON.stringify({"
                        + "access_token:s.access_token,"
                        + "refresh_token:s.refresh_token,"
                        + "user:u?{id:u.id,name:u.name||'',username:u.username||'',email:u.email||''}:null"
                        + "});"
                        + "}catch(e){return JSON.stringify({error:e.message||'Could not save the sign-in.'});}"
                        + "})()";

        webView.evaluateJavascript(
                script,
                value -> {

                    try {

                        Object parsed =
                                new JSONTokener(value)
                                        .nextValue();

                        if (!(parsed instanceof String)) {

                            biometricBridge
                                    .sendBiometricError(
                                            "Could not save the biometric sign-in."
                                    );

                            return;
                        }

                        JSONObject result =
                                new JSONObject(
                                        (String) parsed
                                );

                        if (result.has("error")) {

                            biometricBridge
                                    .sendBiometricError(
                                            result.optString(
                                                    "error",
                                                    "Could not save the biometric sign-in."
                                            )
                                    );

                            return;
                        }

                        saveEncryptedSession(
                                result
                        );

                        biometricBridge
                                .sendBiometricSuccess(
                                        "enable"
                                );

                    } catch (Exception e) {

                        biometricBridge
                                .sendBiometricError(
                                        "Could not save the biometric sign-in."
                                );
                    }
                }
        );
    }

    private void callJavascript(
            String javascript
    ) {

        if (webView == null) {
            return;
        }

        webView.post(
                () -> webView.evaluateJavascript(
                        javascript,
                        null
                )
        );
    }

    private SecretKey getOrCreateEncryptionKey()
            throws Exception {

        KeyStore keyStore =
                KeyStore.getInstance(
                        KEYSTORE_NAME
                );

        keyStore.load(null);

        if (keyStore.containsAlias(
                BIOMETRIC_KEY_ALIAS
        )) {

            return (SecretKey)
                    keyStore.getKey(
                            BIOMETRIC_KEY_ALIAS,
                            null
                    );
        }

        KeyGenerator keyGenerator =
                KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        KEYSTORE_NAME
                );

        KeyGenParameterSpec spec =
                new KeyGenParameterSpec.Builder(
                        BIOMETRIC_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT
                                |
                        KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM
                        )
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE
                        )
                        .build();

        keyGenerator.init(spec);

        return keyGenerator.generateKey();
    }

    private void saveEncryptedSession(
            JSONObject session
    ) {

        try {

            SecretKey key =
                    getOrCreateEncryptionKey();

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding"
                    );

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key
            );

            byte[] plaintext =
                    session.toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            byte[] encrypted =
                    cipher.doFinal(
                            plaintext
                    );

            byte[] iv =
                    cipher.getIV();

            SharedPreferences prefs =
                    getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                    );

            JSONObject user =
                    session.optJSONObject(
                            "user"
                    );

            prefs.edit()
                    .putString(
                            PREF_CIPHERTEXT,
                            Base64.encodeToString(
                                    encrypted,
                                    Base64.NO_WRAP
                            )
                    )
                    .putString(
                            PREF_IV,
                            Base64.encodeToString(
                                    iv,
                                    Base64.NO_WRAP
                            )
                    )
                    .putString(
                            PREF_USER,
                            user != null
                                    ? user.toString()
                                    : ""
                    )
                    .apply();

        } catch (Exception ignored) {
        }
    }

    private JSONObject loadEncryptedSession() {

        try {

            SharedPreferences prefs =
                    getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                    );

            String encryptedText =
                    prefs.getString(
                            PREF_CIPHERTEXT,
                            null
                    );

            String ivText =
                    prefs.getString(
                            PREF_IV,
                            null
                    );

            if (encryptedText == null
                    || ivText == null) {

                return null;
            }

            byte[] encrypted =
                    Base64.decode(
                            encryptedText,
                            Base64.NO_WRAP
                    );

            byte[] iv =
                    Base64.decode(
                            ivText,
                            Base64.NO_WRAP
                    );

            SecretKey key =
                    getOrCreateEncryptionKey();

            Cipher cipher =
                    Cipher.getInstance(
                            "AES/GCM/NoPadding"
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            128,
                            iv
                    )
            );

            byte[] decrypted =
                    cipher.doFinal(
                            encrypted
                    );

            String json =
                    new String(
                            decrypted,
                            StandardCharsets.UTF_8
                    );

            return new JSONObject(json);

        } catch (Exception e) {

            return null;
        }
    }

    private void clearStoredBiometricSession() {

        try {

            SharedPreferences prefs =
                    getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                    );

            prefs.edit()
                    .remove(PREF_CIPHERTEXT)
                    .remove(PREF_IV)
                    .remove(PREF_USER)
                    .apply();

        } catch (Exception ignored) {
        }
    }

    private JSONObject getStoredUser() {

        try {

            SharedPreferences prefs =
                    getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                    );

            String userText =
                    prefs.getString(
                            PREF_USER,
                            null
                    );

            if (userText == null
                    || userText.trim().isEmpty()) {

                return null;
            }

            return new JSONObject(userText);

        } catch (Exception e) {

            return null;
        }
    }

    private boolean hasStoredBiometricSession() {

        SharedPreferences prefs =
                getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                );

        String encrypted =
                prefs.getString(
                        PREF_CIPHERTEXT,
                        null
                );

        String iv =
                prefs.getString(
                        PREF_IV,
                        null
                );

        return encrypted != null
                && !encrypted.isEmpty()
                && iv != null
                && !iv.isEmpty();
    }

    private void requestMediaPermissions(
            boolean needCamera,
            boolean needAudio
    ) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            ArrayList<String> permissions =
                    new ArrayList<>();

            if (needCamera
                    &&
                    checkSelfPermission(
                            Manifest.permission.CAMERA
                    )
                            != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.CAMERA
                );
            }

            if (needAudio
                    &&
                    checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO
                    )
                            != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.RECORD_AUDIO
                );
            }

            if (!permissions.isEmpty()) {

                requestPermissions(
                        permissions.toArray(
                                new String[0]
                        ),
                        MEDIA_PERMISSION_REQUEST
                );
            }
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode ==
                FILE_CHOOSER_REQUEST) {

            if (filePathCallback == null) {
                return;
            }

            Uri[] results = null;

            if (resultCode == RESULT_OK
                    && data != null) {

                if (data.getData() != null) {

                    results =
                            new Uri[]{
                                    data.getData()
                            };
                }
            }

            filePathCallback
                    .onReceiveValue(results);

            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode ==
                MEDIA_PERMISSION_REQUEST
                &&
                pendingPermissionRequest != null) {

            boolean cameraAllowed =
                    checkSelfPermission(
                            Manifest.permission.CAMERA
                    )
                            ==
                    PackageManager.PERMISSION_GRANTED;

            boolean microphoneAllowed =
                    checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO
                    )
                            ==
                    PackageManager.PERMISSION_GRANTED;

            String[] resources =
                    pendingPermissionRequest
                            .getResources();

            boolean allowed = true;

            for (String resource : resources) {

                if (PermissionRequest
                        .RESOURCE_VIDEO_CAPTURE
                        .equals(resource)
                        && !cameraAllowed) {

                    allowed = false;
                }

                if (PermissionRequest
                        .RESOURCE_AUDIO_CAPTURE
                        .equals(resource)
                        && !microphoneAllowed) {

                    allowed = false;
                }
            }

            if (allowed) {

                pendingPermissionRequest
                        .grant(resources);

            } else {

                pendingPermissionRequest
                        .deny();
            }

            pendingPermissionRequest = null;
        }
    }

    private int dpToPx(int dp) {

        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        return Math.round(
                dp * density
        );
    }

    @Override
    public void onBackPressed() {

        if (webView != null
                && webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacksAndMessages(
                null
        );

        if (webView != null) {

            webView.removeJavascriptInterface(
                    "EdalenNative"
            );

            webView.destroy();

            webView = null;
        }

        super.onDestroy();
    }

    public class NativeBiometricBridge {

        private final MainActivity activity;

        NativeBiometricBridge(
                MainActivity activity
        ) {

            this.activity =
                    activity;
        }

        @JavascriptInterface
        public boolean isBiometricAvailable() {

            BiometricManager manager =
                    BiometricManager.from(
                            activity
                    );

            int result =
                    manager.canAuthenticate(
                            BiometricManager
                                    .Authenticators
                                    .BIOMETRIC_STRONG
                                    |
                            BiometricManager
                                    .Authenticators
                                    .BIOMETRIC_WEAK
                    );

            return result ==
                    BiometricManager
                            .BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public boolean hasStoredSession() {

            return hasStoredBiometricSession();
        }

        @JavascriptInterface
        public String getStoredUser() {

            JSONObject user =
                    MainActivity.this
                            .getStoredUser();

            return user == null
                    ? "null"
                    : user.toString();
        }

        @JavascriptInterface
        public void enableBiometric() {

            pendingBiometricAction =
                    "enable";

            authenticateNative();
        }

        @JavascriptInterface
        public void authenticateBiometric(
                String action
        ) {

            if (action == null
                    || action.trim().isEmpty()) {

                action = "login";
            }

            pendingBiometricAction =
                    action;

            authenticateNative();
        }

        @JavascriptInterface
        public void disableBiometric() {

            activity.runOnUiThread(
                    () -> {

                        clearStoredBiometricSession();

                        callJavascript(
                                "window.render && window.render();"
                        );
                    }
            );
        }

        private void authenticateNative() {

            activity.runOnUiThread(
                    () -> {

                        if (!isBiometricAvailable()) {

                            sendBiometricError(
                                    "No fingerprint or Face ID is available on this device."
                            );

                            return;
                        }

                        Executor executor =
                                ContextCompat
                                        .getMainExecutor(
                                                activity
                                        );

                        BiometricPrompt prompt =
                                new BiometricPrompt(
                                        activity,
                                        executor,
                                        new BiometricPrompt
                                                .AuthenticationCallback() {

                                            @Override
                                            public void onAuthenticationSucceeded(
                                                    BiometricPrompt.AuthenticationResult result
                                            ) {

                                                super
                                                        .onAuthenticationSucceeded(
                                                                result
                                                        );

                                                String action =
                                                        pendingBiometricAction;

                                                if ("enable"
                                                        .equals(action)) {

                                                    callJavascript(
                                                            "window.toast && window.toast("
                                                                    +
                                                                    JSONObject.quote(
                                                                            "Hold on pls… saving your biometric login."
                                                                    )
                                                                    +
                                                                    ");"
                                                    );

                                                    captureCurrentSessionForBiometric();

                                                } else {

                                                    callJavascript(
                                                            "window.toast && window.toast("
                                                                    +
                                                                    JSONObject.quote(
                                                                            "Hold on pls… signing you in."
                                                                    )
                                                                    +
                                                                    ");"
                                                    );

                                                    restoreStoredSession(
                                                            action
                                                    );
                                                }
                                            }

                                            @Override
                                            public void onAuthenticationError(
                                                    int errorCode,
                                                    CharSequence errString
                                            ) {

                                                super
                                                        .onAuthenticationError(
                                                                errorCode,
                                                                errString
                                                        );

                                                sendBiometricError(
                                                        errString != null
                                                                ? errString.toString()
                                                                : "Biometric verification was cancelled."
                                                );
                                            }

                                            @Override
                                            public void onAuthenticationFailed() {

                                                super
                                                        .onAuthenticationFailed();

                                                callJavascript(
                                                        "window.toast && window.toast("
                                                                +
                                                                JSONObject.quote(
                                                                        "Fingerprint or Face ID not recognized. Try again."
                                                                )
                                                                +
                                                                ");"
                                                );
                                            }
                                        }
                                );

                        BiometricPrompt.PromptInfo promptInfo =
                                new BiometricPrompt.PromptInfo.Builder()
                                        .setTitle(
                                                "Edalen Academy"
                                        )
                                        .setSubtitle(
                                                "Use your fingerprint or Face ID to continue"
                                        )
                                        .setNegativeButtonText(
                                                "Cancel"
                                        )
                                        .setAllowedAuthenticators(
                                                BiometricManager
                                                        .Authenticators
                                                        .BIOMETRIC_STRONG
                                                        |
                                                BiometricManager
                                                        .Authenticators
                                                        .BIOMETRIC_WEAK
                                        )
                                        .build();

                        prompt.authenticate(
                                promptInfo
                        );
                    }
            );
        }

        private void sendBiometricSuccess(
                String action
        ) {

            callJavascript(
                    "window.nativeBiometricSuccess && "
                            +
                            "window.nativeBiometricSuccess("
                            +
                            JSONObject.quote(
                                    action
                            )
                            +
                            ");"
            );
        }

        private void sendBiometricError(
                String message
        ) {

            callJavascript(
                    "window.nativeBiometricError && "
                            +
                            "window.nativeBiometricError("
                            +
                            JSONObject.quote(
                                    message
                            )
                            +
                            ");"
            );
        }
    }
}
