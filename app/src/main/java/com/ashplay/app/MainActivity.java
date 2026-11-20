
package com.ashplay.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String WEBSITE_URL = "https://192.168.168.10";
    private static final String WEBSITE_HOST = "192.168.168.10";
    private static final int MEDIA_PERMISSION_CODE = 501;

    private WebView webView;
    private PermissionRequest pendingRequest;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;

    private int previousRequestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return !isWebsiteUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, String url) {
                return !isWebsiteUrl(Uri.parse(url));
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    SslErrorHandler handler,
                    android.net.http.SslError error) {

                Uri errorUri = Uri.parse(error.getUrl());

                if (!isWebsiteUrl(errorUri)) {
                    handler.cancel();
                    return;
                }

                String fingerprint =
                        getCertificateFingerprint(error.getCertificate());

                if (fingerprint == null) {
                    handler.cancel();
                    return;
                }

                SharedPreferences preferences =
                        getSharedPreferences(
                                "ashplay_ssl_trust", MODE_PRIVATE);

                String savedFingerprint =
                        preferences.getString(WEBSITE_HOST, null);

                if (fingerprint.equals(savedFingerprint)) {
                    handler.proceed();
                    return;
                }

                new androidx.appcompat.app.AlertDialog.Builder(
                        MainActivity.this)
                        .setMessage("Do you want to enter ?")
                        .setPositiveButton("Yes", (dialog, which) -> {
                            preferences.edit()
                                    .putString(WEBSITE_HOST, fingerprint)
                                    .apply();

                            handler.proceed();
                        })
                        .setNegativeButton("No", (dialog, which) -> {
                            handler.cancel();
                            finish();
                        })
                        .setOnCancelListener(dialog -> {
                            handler.cancel();
                            finish();
                        })
                        .show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(
                        () -> handleWebsitePermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(
                    PermissionRequest request) {
                if (pendingRequest == request) {
                    pendingRequest = null;
                }
            }

            @Override
            public void onShowCustomView(
                    View view, CustomViewCallback callback) {

                if (fullScreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                previousRequestedOrientation =
                        getRequestedOrientation();

                setRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

                fullScreenView = view;
                fullScreenCallback = callback;
                setContentView(view);

                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });

        setContentView(webView);
        webView.loadUrl(WEBSITE_URL);

        getOnBackPressedDispatcher().addCallback(
                this, new OnBackPressedCallback(true) {

            @Override
            public void handleOnBackPressed() {
                if (fullScreenView != null) {
                    exitFullScreen();
                } else if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    private String getCertificateFingerprint(
            SslCertificate certificate) {

        if (certificate == null) return null;

        try {
            Bundle state = SslCertificate.saveState(certificate);

            if (state == null) return null;

            byte[] encoded =
                    state.getByteArray("x509-certificate");

            if (encoded == null || encoded.length == 0) {
                return null;
            }

            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(encoded);

            StringBuilder result = new StringBuilder();

            for (byte value : digest) {
                result.append(String.format(
                        java.util.Locale.US, "%02X", value));
            }

            return result.toString();

        } catch (Exception exception) {
            return null;
        }
    }

    private void handleWebsitePermission(PermissionRequest request) {

        Uri origin = request.getOrigin();

        if (origin == null
                || !"https".equalsIgnoreCase(origin.getScheme())
                || !WEBSITE_HOST.equalsIgnoreCase(origin.getHost())) {
            request.deny();
            return;
        }

        List<String> webResources = new ArrayList<>();
        List<String> androidPermissions = new ArrayList<>();

        for (String resource : request.getResources()) {

            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                webResources.add(resource);
                androidPermissions.add(Manifest.permission.RECORD_AUDIO);

            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                webResources.add(resource);
                androidPermissions.add(Manifest.permission.CAMERA);
            }
        }

        if (webResources.isEmpty()) {
            request.deny();
            return;
        }

        boolean granted = true;

        for (String permission : androidPermissions) {
            if (ContextCompat.checkSelfPermission(
                    this, permission) != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }

        if (granted) {
            request.grant(webResources.toArray(new String[0]));

        } else {

            if (pendingRequest != null) {
                pendingRequest.deny();
            }

            pendingRequest = request;

            requestPermissions(
                    androidPermissions.toArray(new String[0]),
                    MEDIA_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);

        if (requestCode != MEDIA_PERMISSION_CODE
                || pendingRequest == null) {
            return;
        }

        PermissionRequest request = pendingRequest;
        pendingRequest = null;

        Uri origin = request.getOrigin();

        if (origin == null || !isWebsiteUrl(origin)) {
            request.deny();
            return;
        }

        List<String> allowed = new ArrayList<>();

        for (String resource : request.getResources()) {

            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {

                allowed.add(resource);

            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {

                allowed.add(resource);
            }
        }

        if (allowed.isEmpty()) {
            request.deny();
        } else {
            request.grant(allowed.toArray(new String[0]));
        }
    }

    private boolean isWebsiteUrl(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && WEBSITE_HOST.equalsIgnoreCase(uri.getHost());
    }

    private void exitFullScreen() {

        if (fullScreenView == null) return;

        View view = fullScreenView;

        WebChromeClient.CustomViewCallback callback =
                fullScreenCallback;

        fullScreenView = null;
        fullScreenCallback = null;

        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE);

        setRequestedOrientation(previousRequestedOrientation);

        setContentView(webView);

        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    @Override
    protected void onDestroy() {

        if (pendingRequest != null) {
            pendingRequest.deny();
            pendingRequest = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
