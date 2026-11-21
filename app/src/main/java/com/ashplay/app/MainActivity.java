package com.ashplay.app;

import android.Manifest;
import android.app.NotificationManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String WEBSITE_URL = AppConfig.WEBSITE_URL;
    private static final String WEBSITE_HOST = AppConfig.WEBSITE_HOST;
    private static final int MEDIA_PERMISSION_CODE = 501;

    private WebView webView;
    private FrameLayout rootLayout;
    private PermissionRequest pendingRequest;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;

    // ওয়েবসাইটের ফাইল বাটন (+ / ছবি / ভিডিও) চাপলে ফোনের ফাইল পিকার খোলার জন্য
    private ValueCallback<Uri[]> filePathCallback;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (filePathCallback == null) return;
                        Uri[] uris = WebChromeClient.FileChooserParams
                                .parseResult(result.getResultCode(), result.getData());
                        filePathCallback.onReceiveValue(uris);
                        filePathCallback = null;
                    });

    private int previousRequestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    // ---- ব্যাকগ্রাউন্ড নোটিফিকেশন সেটআপ ----
    private int setupStep = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int pendingChatId = -1;
    private int chatAttempts = 0;
    private String pendingAcceptCallId = null;   // সবুজ "উত্তর" বাটন থেকে আসা কলের id
    private int acceptAttempts = 0;

    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> setupNext());

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> setupNext());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        // Same behavior on every Android version: draw edge-to-edge
        // and leave room for the system bars ourselves.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);

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
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {

                // আগের অসমাপ্ত অনুরোধ থাকলে বাতিল করে নতুনটা নেয়
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                try {
                    fileChooserLauncher.launch(params.createIntent());
                    return true;
                } catch (Exception exception) {
                    filePathCallback = null;
                    return false;
                }
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

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        setContentView(rootLayout);
        webView.loadUrl(WEBSITE_URL);

        // অ্যাপ বন্ধ থাকলেও কল/মেসেজ জানানোর সার্ভিস চালু
        getSharedPreferences(AppConfig.APP_PREFS, MODE_PRIVATE)
                .edit().putBoolean("service_wanted", true).apply();
        NotifyService.start(this);

        Intent launchIntent = getIntent();
        boolean fromNotification = launchIntent != null
                && (launchIntent.getBooleanExtra(NotifyService.EXTRA_FROM_CALL, false)
                || launchIntent.getIntExtra(NotifyService.EXTRA_OPEN_CHAT, -1) > 0);
        handleNotificationIntent(launchIntent);
        if (!fromNotification) {
            rootLayout.post(this::runSetupFlow);
        }

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

    // ------------------------------------------------------------------
    // নোটিফিকেশন থেকে অ্যাপ খুললে: কল হলে লক স্ক্রিনের উপরে দেখানো, মেসেজ হলে সেই চ্যাট খোলা
    // ------------------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;

        if (intent.getBooleanExtra(NotifyService.EXTRA_FROM_CALL, false)) {
            intent.removeExtra(NotifyService.EXTRA_FROM_CALL);
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }

        // সবুজ "উত্তর" বাটন: কলারের চ্যাট খুলে তারপর কল রিসিভ করা হবে
        String acceptId = intent.getStringExtra(NotifyService.EXTRA_ACCEPT_CALL_ID);
        if (acceptId != null && acceptId.matches("[a-f0-9]+")) {
            intent.removeExtra(NotifyService.EXTRA_ACCEPT_CALL_ID);
            pendingAcceptCallId = acceptId;
            acceptAttempts = 0;
            if (intent.getIntExtra(NotifyService.EXTRA_OPEN_CHAT, -1) <= 0) {
                tryAcceptPendingCall();
            }
        }

        int chatId = intent.getIntExtra(NotifyService.EXTRA_OPEN_CHAT, -1);
        if (chatId > 0) {
            intent.removeExtra(NotifyService.EXTRA_OPEN_CHAT);
            pendingChatId = chatId;
            chatAttempts = 0;
            tryOpenPendingChat();
        }
    }

    /** পেজ লোড ও লগইন শেষ না হওয়া পর্যন্ত বারবার চেষ্টা করে চ্যাট খোলে (সর্বোচ্চ ~২৪ সেকেন্ড)। */
    private void tryOpenPendingChat() {
        final int id = pendingChatId;
        if (id <= 0 || webView == null) return;
        if (chatAttempts++ > 40) { pendingChatId = -1; return; }

        String js = "(function(){try{"
                + "if(typeof me!=='undefined'&&me&&typeof openChat==='function'&&typeof closeAll==='function'&&typeof switchTab==='function'){"
                + "if(typeof chatWith!=='undefined'&&chatWith===" + id + "&&document.getElementById('pgChat')&&typeof stack!=='undefined'&&stack.some(function(s){return s.id==='pgChat';}))return 'ok';"
                + "closeAll(function(){switchTab('vUsers');openChat(" + id + ");});return 'ok';}"
                + "}catch(e){}return 'wait';})()";

        webView.evaluateJavascript(js, value -> {
            if (value != null && value.contains("ok")) {
                pendingChatId = -1;
                // চ্যাট খোলার অ্যানিমেশন শেষ হলে কল রিসিভ
                if (pendingAcceptCallId != null) uiHandler.postDelayed(this::tryAcceptPendingCall, 900);
            } else {
                uiHandler.postDelayed(this::tryOpenPendingChat, 600);
            }
        });
    }

    /** ওয়েবপেজ কলটা দেখতে পেলে acceptCall() চালায় (সর্বোচ্চ ~২৪ সেকেন্ড চেষ্টা)। */
    private void tryAcceptPendingCall() {
        final String id = pendingAcceptCallId;
        if (id == null || webView == null) return;
        if (acceptAttempts++ > 40) { pendingAcceptCallId = null; return; }

        String js = "(function(){try{"
                + "if(typeof me==='undefined'||!me||typeof acceptCall!=='function')return 'wait';"
                + "if(typeof activeCall!=='undefined'&&activeCall)return 'ok';"
                + "var pi=window.pendingIncoming;"
                + "if(pi&&pi.id==='" + id + "'&&pi.status==='ringing'){acceptCall(pi);return 'ok';}"
                + "if(typeof pollCalls==='function')pollCalls();"
                + "}catch(e){}return 'wait';})()";

        webView.evaluateJavascript(js, value -> {
            if (value != null && value.contains("ok")) {
                pendingAcceptCallId = null;
            } else {
                uiHandler.postDelayed(this::tryAcceptPendingCall, 600);
            }
        });
    }

    // ------------------------------------------------------------------
    // প্রথমবার চালুর সময় ধাপে ধাপে অনুমতি (শুধু একবার): নোটিফিকেশন → ব্যাটারি → লকস্ক্রিন → সংযোগ নোটিফিকেশন
    // ------------------------------------------------------------------

    private void runSetupFlow() {
        setupStep = 0;
        setupNext();
    }

    private void setupNext() {
        setupStep++;
        SharedPreferences p = getSharedPreferences(AppConfig.APP_PREFS, MODE_PRIVATE);
        try {
            switch (setupStep) {
                case 1:
                    if (Build.VERSION.SDK_INT >= 33
                            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                            && !p.getBoolean("ask_notif", false)) {
                        p.edit().putBoolean("ask_notif", true).apply();
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                        return;
                    }
                    setupNext();
                    return;

                case 2: {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (Build.VERSION.SDK_INT >= 23 && pm != null
                            && !pm.isIgnoringBatteryOptimizations(getPackageName())
                            && !p.getBoolean("ask_battery", false)) {
                        p.edit().putBoolean("ask_battery", true).apply();
                        askDialog("Allow background permission", "Yes", "No", () -> {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + getPackageName()));
                            try { settingsLauncher.launch(i); } catch (Exception e) { setupNext(); }
                        }, this::setupNext);
                        return;
                    }
                    setupNext();
                    return;
                }

                case 3: {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()
                            && !p.getBoolean("ask_fsi", false)) {
                        p.edit().putBoolean("ask_fsi", true).apply();
                        askDialog("Lockscreen mode", "Yes", "No", () -> {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:" + getPackageName()));
                            try { settingsLauncher.launch(i); } catch (Exception e) { setupNext(); }
                        }, this::setupNext);
                        return;
                    }
                    setupNext();
                    return;
                }

                case 4:
                    if (Build.VERSION.SDK_INT >= 26 && !p.getBoolean("ask_hide", false)) {
                        p.edit().putBoolean("ask_hide", true).apply();
                        askDialog("Notification", "Yes", "No", () -> {
                            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, NotifyService.CH_SERVICE);
                            try { settingsLauncher.launch(i); } catch (Exception e) { setupNext(); }
                        }, this::setupNext);
                        return;
                    }
                    setupNext();
                    return;

                default:
                    return;
            }
        } catch (Exception ignored) {
        }
    }

    private void askDialog(String message, String yesLabel, Runnable onYes, Runnable onNo) {
        askDialog(message, yesLabel, "পরে", onYes, onNo);
    }

    private void askDialog(String message, String yesLabel, String noLabel, Runnable onYes, Runnable onNo) {
        if (isFinishing()) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(yesLabel, (d, w) -> { if (onYes != null) onYes.run(); })
                .setNegativeButton(noLabel, (d, w) -> { if (onNo != null) onNo.run(); })
                .show();
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

        setContentView(rootLayout);
        ViewCompat.requestApplyInsets(rootLayout);

        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        NotifyService.appVisible = true;
        NotifyService.onAppVisible();     // রিং বন্ধ এবং জমে থাকা নোটিফিকেশন মুছে ফেলা
    }

    @Override
    protected void onStop() {
        NotifyService.appVisible = false;
        // লক স্ক্রিনের উপরে দেখানোর বিশেষ অবস্থা শুধু কলের সময়ের জন্য
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false);
            setTurnScreenOn(false);
        } else {
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        // লগইন কুকি ডিস্কে সেভ — সার্ভিস ও রিস্টার্টের পরেও সেশন পায়
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);

        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }

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
