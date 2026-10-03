package com.edalenacademy.app;

import android.Manifest;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
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

    /* Fullscreen video support (the player's own fullscreen button). */
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int savedSystemUiVisibility;

    /* Native full-screen video player (used by the lesson page's "Enlarge video" button). */
    private Dialog videoDialog;
    private WebView videoWebView;

    /* Notes saved from the page while storage permission was still needed (Android 9 and below). */
    private static final int STORAGE_PERMISSION_REQUEST = 2002;
    private byte[] pendingSaveBytes;
    private String pendingSaveName;
    private String pendingSaveMime;

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

        /* Lets the page hand a generated file (notes PDF/Word) to the app to save. */
        webView.addJavascriptInterface(new DownloadBridge(), "EdalenApp");

        /* Normal downloads (class recordings, attachments, etc.). */
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, userAgent, contentDisposition, mimeType));

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
            /* Makes the video player's fullscreen button work. */
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                View decor = getWindow().getDecorView();
                savedSystemUiVisibility = decor.getSystemUiVisibility();

                view.setBackgroundColor(Color.BLACK);
                ((FrameLayout) decor).addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                swipeRefreshLayout.setVisibility(View.GONE);

                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }

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

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    private GradientDrawable pill() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(200, 0, 0, 0));
        g.setCornerRadius(dpToPx(24));
        return g;
    }

    private Button overlayButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setPadding(dpToPx(14), 0, dpToPx(14), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(pill());
        return b;
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void showVideoOverlay(String url) {
        if (url == null || !url.startsWith("https://")) return;
        if (videoDialog != null) {
            videoDialog.dismiss();
        }

        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        final FrameLayout stage = new FrameLayout(this);
        final WebView wv = new WebView(this);
        stage.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setBackgroundColor(Color.BLACK);

        final View[] custom = new View[1];
        final WebChromeClient.CustomViewCallback[] customCb =
                new WebChromeClient.CustomViewCallback[1];

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                /* Keep the player on the video: block links that try to leave it. */
                return request.isForMainFrame();
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (custom[0] != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                custom[0] = view;
                customCb[0] = callback;
                stage.setVisibility(View.GONE);
                frame.addView(view, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (custom[0] == null) return;
                frame.removeView(custom[0]);
                custom[0] = null;
                if (customCb[0] != null) {
                    try {
                        customCb[0].onCustomViewHidden();
                    } catch (Exception ignored) {
                    }
                    customCb[0] = null;
                }
                stage.setVisibility(View.VISIBLE);
            }
        });

        /* Close and Rotate buttons (rotate turns the video sideways without changing the phone's orientation). */
        Button close = overlayButton("\u2715 Close");
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(40), Gravity.TOP | Gravity.START);
        cp.setMargins(dpToPx(12), dpToPx(12), 0, 0);
        frame.addView(close, cp);
        close.setOnClickListener(v -> d.dismiss());

        Button rotate = overlayButton("\u27F3 Rotate");
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(40), Gravity.BOTTOM | Gravity.START);
        rp.setMargins(dpToPx(12), 0, 0, dpToPx(12));
        frame.addView(rotate, rp);
        final boolean[] sideways = {false};
        rotate.setOnClickListener(v -> {
            int w = frame.getWidth();
            int h = frame.getHeight();
            sideways[0] = !sideways[0];
            if (sideways[0]) {
                stage.setLayoutParams(new FrameLayout.LayoutParams(h, w));
                stage.setPivotX(0f);
                stage.setPivotY(0f);
                stage.setRotation(90f);
                stage.setTranslationX(w);
            } else {
                stage.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                stage.setRotation(0f);
                stage.setTranslationX(0f);
            }
            stage.requestLayout();
        });

        d.setContentView(frame);
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (custom[0] != null) {
                    wv.getWebChromeClient().onHideCustomView();
                } else {
                    d.dismiss();
                }
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_BACK;
        });
        d.setOnDismissListener(dialog -> {
            try {
                wv.loadUrl("about:blank");
                wv.destroy();
            } catch (Exception ignored) {
            }
            if (videoDialog == d) {
                videoDialog = null;
                videoWebView = null;
            }
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.__vlRestore&&window.__vlRestore();", null);
            }
        });

        videoDialog = d;
        videoWebView = wv;

        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d.show();
        d.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        String safe = url.replace("'", "%27").replace("\"", "%22")
                .replace("<", "%3C").replace(">", "%3E");
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "iframe{position:absolute;left:0;top:0;width:100%;height:100%;border:0}</style>"
                + "</head><body><iframe src='" + safe + "' "
                + "allow='autoplay; encrypted-media; picture-in-picture; fullscreen' "
                + "allowfullscreen></iframe></body></html>";
        /* The site address is used as the page origin so the video provider accepts the embed. */
        wv.loadDataWithBaseURL(APP_URL, html, "text/html", "utf-8", null);
    }

    /* Leaves fullscreen video and restores the normal screen. */
    private void exitCustomView() {
        if (customView == null) return;

        View decor = getWindow().getDecorView();
        ((FrameLayout) decor).removeView(customView);
        customView = null;

        if (customViewCallback != null) {
            try {
                customViewCallback.onCustomViewHidden();
            } catch (Exception ignored) {
            }
            customViewCallback = null;
        }

        swipeRefreshLayout.setVisibility(View.VISIBLE);
        decor.setSystemUiVisibility(savedSystemUiVisibility);
    }

    /* Receives files generated by the page (notes as PDF / Word). */
    private class DownloadBridge {
        /* Called by the lesson page: plays the video in a full-screen player owned by the app. */
        @JavascriptInterface
        public void openVideo(String url) {
            runOnUiThread(() -> showVideoOverlay(url));
        }

        @JavascriptInterface
        public void saveFile(String base64, String fileName, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (bytes.length == 0 || bytes.length > 15 * 1024 * 1024) {
                    toastOnUi("Could not save the file.");
                    return;
                }
                String safeName = sanitizeFileName(fileName);
                String safeMime = (mimeType == null || mimeType.isEmpty())
                        ? "application/octet-stream" : mimeType;
                saveBytesToDownloads(bytes, safeName, safeMime);
            } catch (Exception e) {
                toastOnUi("Could not save the file.");
            }
        }
    }

    private String sanitizeFileName(String name) {
        String n = (name == null) ? "" : name.replaceAll("[\\/:*?\"<>|\r\n]+", " ").trim();
        if (n.isEmpty()) n = "Edalen notes.pdf";
        if (n.length() > 100) n = n.substring(n.length() - 100);
        return n;
    }

    private void toastOnUi(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void saveBytesToDownloads(byte[] bytes, String fileName, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("insert failed");
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("no stream");
                    out.write(bytes);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingSaveBytes = bytes;
                    pendingSaveName = fileName;
                    pendingSaveMime = mimeType;
                    runOnUiThread(() -> requestPermissions(
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            STORAGE_PERMISSION_REQUEST));
                    return;
                }
                java.io.File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (java.io.FileOutputStream out =
                             new java.io.FileOutputStream(new java.io.File(dir, fileName))) {
                    out.write(bytes);
                }
            }
            toastOnUi("Saved to your Downloads folder: " + fileName);
        } catch (Exception e) {
            toastOnUi("Could not save the file.");
        }
    }

    private void handleDownload(
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType
    ) {
        try {
            if (url == null) return;

            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma > 0 && url.substring(0, comma).contains(";base64")) {
                    byte[] bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
                    String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    saveBytesToDownloads(bytes, sanitizeFileName(name),
                            mimeType == null ? "application/octet-stream" : mimeType);
                }
                return;
            }

            if (url.startsWith("blob:")) {
                /* The page saves these through the EdalenApp bridge instead. */
                toastOnUi("Could not download this file. Please try again.");
                return;
            }

            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(request);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            /* If the in-app download fails, let the phone's browser/app handle it. */
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                Toast.makeText(this, "Download failed.", Toast.LENGTH_SHORT).show();
            }
        }
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

        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            byte[] bytes = pendingSaveBytes;
            String name = pendingSaveName;
            String mime = pendingSaveMime;
            pendingSaveBytes = null;
            pendingSaveName = null;
            pendingSaveMime = null;

            if (bytes != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveBytesToDownloads(bytes, name, mime);
            } else {
                Toast.makeText(this, "Storage permission is needed to save the file.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

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
        if (customView != null) {
            exitCustomView();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        exitCustomView();
        if (videoDialog != null) {
            try {
                videoDialog.dismiss();
            } catch (Exception ignored) {
            }
            videoDialog = null;
        }

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
