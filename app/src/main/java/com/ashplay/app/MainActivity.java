
package com.ashplay.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String WEBSITE_URL = "https://192.168.168.10";
    private static final String WEBSITE_HOST = "192.168.168.10";

    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private PermissionRequest pendingPermissionRequest;
    private int previousOrientation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    SslError error) {
                handler.proceed();
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return !isAllowedUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, String url) {
                return !isAllowedUrl(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onShowCustomView(
                    View view, CustomViewCallback callback) {

                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;
                previousOrientation = getRequestedOrientation();

                setRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                );

                setContentView(view);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() ->
                        handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(
                    PermissionRequest request) {

                if (pendingPermissionRequest == request) {
                    pendingPermissionRequest = null;
                }
            }
        });

        setContentView(webView);
        webView.loadUrl(WEBSITE_URL);

        getOnBackPressedDispatcher().addCallback(
                this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (customView != null) {
                            hideCustomView();
                        } else if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finish();
                        }
                    }
                }
        );
    }

    private void handleWebPermissionRequest(
            PermissionRequest request) {

        if (isFinishing() || isDestroyed() || webView == null) {
            request.deny();
            return;
        }

        Uri origin = request.getOrigin();

        if (origin == null
                || !"https".equalsIgnoreCase(origin.getScheme())
                || !WEBSITE_HOST.equalsIgnoreCase(origin.getHost())) {
            request.deny();
            return;
        }

        List<String> androidPermissions = new ArrayList<>();
        List<String> webResources = new ArrayList<>();

        for (String resource : request.getResources()) {

            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {

                if (!androidPermissions.contains(
                        Manifest.permission.RECORD_AUDIO)) {
                    androidPermissions.add(
                            Manifest.permission.RECORD_AUDIO);
                }

                webResources.add(resource);

            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {

                if (!androidPermissions.contains(
                        Manifest.permission.CAMERA)) {
                    androidPermissions.add(
                            Manifest.permission.CAMERA);
                }

                webResources.add(resource);
            }
        }

        if (webResources.isEmpty()) {
            request.deny();
            return;
        }

        boolean allGranted = true;

        for (String permission : androidPermissions) {

            if (ContextCompat.checkSelfPermission(
                    this, permission)
                    != PackageManager.PERMISSION_GRANTED) {

                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            request.grant(webResources.toArray(new String[0]));
            return;
        }

        if (pendingPermissionRequest != null
                && pendingPermissionRequest != request) {
            pendingPermissionRequest.deny();
        }

        pendingPermissionRequest = request;

        requestPermissions(
                androidPermissions.toArray(new String[0]),
                1001
        );
    }

    private void hideSystemUI() {

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && customView != null) {
            hideSystemUI();
        }
    }

    private boolean isAllowedUrl(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && WEBSITE_HOST.equalsIgnoreCase(uri.getHost());
    }

    private void hideCustomView() {

        if (customView == null) {
            return;
        }

        View view = customView;
        WebChromeClient.CustomViewCallback callback = customViewCallback;

        customView = null;
        customViewCallback = null;

        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE
        );

        setRequestedOrientation(previousOrientation);
        setContentView(webView);

        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);

        if (requestCode != 1001) {
            return;
        }

        PermissionRequest request = pendingPermissionRequest;
        pendingPermissionRequest = null;

        if (request == null || isFinishing() || isDestroyed()) {
            return;
        }

        boolean audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        boolean cameraGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;

        List<String> grantedResources = new ArrayList<>();

        for (String resource : request.getResources()) {

            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && audioGranted) {
                grantedResources.add(resource);

            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && cameraGranted) {
                grantedResources.add(resource);
            }
        }

        if (!grantedResources.isEmpty()) {
            request.grant(grantedResources.toArray(new String[0]));
        } else {
            request.deny();
        }
    }

    @Override
    protected void onDestroy() {

        if (pendingPermissionRequest != null) {
            pendingPermissionRequest.deny();
            pendingPermissionRequest = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
