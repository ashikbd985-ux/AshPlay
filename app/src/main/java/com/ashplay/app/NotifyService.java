
package com.ashplay.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class NotifyService extends Service {

    public static final String CH_SERVICE =
            "ashplay_service_silent_v3";

    private static final String CH_ALERT =
            "ashplay_alert_silent_v3";

    private static final int ID_FG = 1;
    private static final int ID_ALERT = 2;

    public static final String EXTRA_FROM_CALL = "from_call";
    public static final String EXTRA_OPEN_CHAT = "open_chat";
    public static final String EXTRA_ACCEPT_CALL_ID = "accept_call_id";
    public static final String EXTRA_CALL_ID = "call_id";

    public static final String ACTION_DECLINE_CALL =
            "com.ashplay.app.DECLINE_CALL";

    public static volatile boolean appVisible = false;

    private static NotifyService instance;

    private final Handler main =
            new Handler(Looper.getMainLooper());

    private volatile boolean running = false;

    private Thread worker;

    private SharedPreferences prefs;

    private NotificationManager nm;

    private long cursor = -1;

    private int badgeCount = 0;

    private String latestTitle = "AshPlay";

    private String latestText = "নতুন নোটিফিকেশন";

    private final Set<String> knownCalls =
            new HashSet<>();

    public static void start(android.content.Context context) {
        try {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, NotifyService.class)
            );
        } catch (Exception ignored) {
        }
    }

    public static void onAppVisible() {
        NotifyService service = instance;
        if (service != null) {
            service.main.post(service::clearAllAlerts);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        prefs = getSharedPreferences(
                AppConfig.APP_PREFS,
                MODE_PRIVATE
        );

        nm = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);

        cursor = prefs.getLong("cursor", -1);
        badgeCount = prefs.getInt("badge_count", 0);

        // আগের কল ID পুনরায় চালুর পরেও সংরক্ষণ
        String savedCalls = prefs.getString("known_calls", "");

        if (savedCalls != null && !savedCalls.isEmpty()) {
            for (String id : savedCalls.split(",")) {
                if (id != null && !id.trim().isEmpty()) {
                    knownCalls.add(id.trim());
                }
            }
        }

        createChannels();

        try {
            Notification notification =
                    buildForegroundNotification();

            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                        this,
                        ID_FG,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );
            } else {
                startForeground(ID_FG, notification);
            }

        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        if (!running) {
            running = true;

            worker = new Thread(
                    this::pollingLoop,
                    "AshPlay-Local-Notifications"
            );

            worker.setDaemon(true);
            worker.start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;

        if (worker != null) {
            worker.interrupt();
        }

        main.removeCallbacksAndMessages(null);

        if (instance == this) {
            instance = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // প্রতি ৩ সেকেন্ডে সার্ভার পরীক্ষা
    private void pollingLoop() {

        SSLSocketFactory factory = null;

        try {
            factory = buildSocketFactory();
        } catch (Exception ignored) {
        }

        while (running) {
            try {
                String cookie = null;

                try {
                    cookie = CookieManager.getInstance()
                            .getCookie(AppConfig.WEBSITE_URL);
                } catch (Exception ignored) {
                }

                if (cookie != null && !cookie.isEmpty()) {
                    Response response =
                            pollServer(factory, cookie);

                    if (response.code == 200
                            && response.body != null) {
                        handleResponse(
                                new JSONObject(response.body)
                        );
                    }
                }

            } catch (Exception ignored) {
            }

            if (!running) {
                break;
            }

            try {
                Thread.sleep(AppConfig.POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static class Response {
        int code;
        String body;
    }

    // লোকাল সার্ভারের সঙ্গে সংযোগ
    private Response pollServer(
            SSLSocketFactory factory,
            String cookie
    ) throws Exception {

        StringBuilder url = new StringBuilder(
                AppConfig.WEBSITE_URL
        );

        url.append("/notify.php?wait=")
                .append(AppConfig.LONG_POLL_SECONDS);

        if (cursor >= 0) {
            url.append("&after=")
                    .append(cursor);
        }

        if (!knownCalls.isEmpty()) {
            url.append("&calls=")
                    .append(URLEncoder.encode(
                            android.text.TextUtils.join(
                                    ",",
                                    knownCalls
                            ),
                            "UTF-8"
                    ));
        }

        HttpsURLConnection connection =
                (HttpsURLConnection)
                        new URL(url.toString()).openConnection();

        try {
            if (factory != null) {
                connection.setSSLSocketFactory(factory);
            }

            connection.setHostnameVerifier(
                    (host, session) ->
                            AppConfig.WEBSITE_HOST
                                    .equalsIgnoreCase(host)
            );

            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);

            connection.setRequestProperty("Cookie", cookie);
            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            connection.setUseCaches(false);

            Response response = new Response();
            response.code = connection.getResponseCode();

            if (response.code == 200) {
                try (InputStream input =
                             connection.getInputStream()) {

                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                    byte[] buffer = new byte[4096];
                    int length;

                    while ((length = input.read(buffer)) > 0
                            && output.size() < 512 * 1024) {
                        output.write(buffer, 0, length);
                    }

                    response.body = output.toString("UTF-8");
                }
            }

            return response;

        } finally {
            connection.disconnect();
        }
    }

    // নতুন কল ও মেসেজ শনাক্ত
    private void handleResponse(JSONObject json) {

        if (!json.optBoolean("success")) {
            return;
        }

        int newEvents = 0;

        String eventTitle = null;
        String eventText = null;

        JSONArray calls = json.optJSONArray("calls");

        Set<String> currentCalls = new HashSet<>();

        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {

                JSONObject call = calls.optJSONObject(i);

                if (call == null) {
                    continue;
                }

                String id = call.optString("id");

                if (id.isEmpty()) {
                    continue;
                }

                currentCalls.add(id);

                if (!knownCalls.contains(id)) {
                    newEvents++;

                    eventTitle = call.optString(
                            "name",
                            "AshPlay"
                    );

                    boolean video = "video".equals(
                            call.optString("type")
                    );

                    eventText = video
                            ? "আপনাকে ভিডিও কল করেছেন"
                            : "আপনাকে অডিও কল করেছেন";
                }
            }
        }

        knownCalls.clear();
        knownCalls.addAll(currentCalls);

        // সার্ভিস বন্ধ হলেও কল ID মনে রাখবে
        prefs.edit()
                .putString(
                        "known_calls",
                        android.text.TextUtils.join(",", knownCalls)
                )
                .apply();

        // নতুন মেসেজ
        JSONArray messages =
                json.optJSONArray("messages");

        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {

                JSONObject message =
                        messages.optJSONObject(i);

                if (message == null) {
                    continue;
                }

                newEvents++;

                eventTitle = message.optString(
                        "name",
                        "AshPlay"
                );

                eventText = message.optString(
                        "preview",
                        "আপনাকে নতুন মেসেজ পাঠিয়েছেন"
                );
            }
        }

        if (newEvents > 0 && !appVisible) {

            final int count = newEvents;
            final String title = eventTitle;
            final String body = eventText;

            main.post(() -> {
                badgeCount += count;

                latestTitle = title;
                latestText = body;

                saveBadgeCount();
                showBadgeNotification();
            });
        }

        cursor = json.optLong("cursor", cursor);

        prefs.edit()
                .putLong("cursor", cursor)
                .apply();
    }

    private void saveBadgeCount() {
        prefs.edit()
                .putInt("badge_count", badgeCount)
                .apply();
    }

    // অ্যাপ আইকনের ব্যাজ
    private void showBadgeNotification() {

        if (appVisible || badgeCount <= 0) {
            return;
        }

        Intent open = new Intent(
                this,
                MainActivity.class
        );

        open.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        20,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        Notification notification =
                new NotificationCompat.Builder(
                        this,
                        CH_ALERT
                )
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(latestTitle)
                        .setContentText(latestText)
                        .setNumber(badgeCount)
                        .setBadgeIconType(
                                NotificationCompat.BADGE_ICON_SMALL
                        )
                        .setCategory(
                                NotificationCompat.CATEGORY_MESSAGE
                        )
                        .setPriority(
                                NotificationCompat.PRIORITY_DEFAULT
                        )
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .setAutoCancel(false)
                        .setShowWhen(true)
                        .setContentIntent(pendingIntent)
                        .build();

        try {
            nm.notify(ID_ALERT, notification);
        } catch (Exception ignored) {
        }
    }

    // অ্যাপ খুললে ব্যাজ মুছে যাবে
    private void clearAllAlerts() {

        badgeCount = 0;
        saveBadgeCount();

        try {
            nm.cancel(ID_ALERT);
        } catch (Exception ignored) {
        }
    }

    // নীরব নোটিফিকেশন চ্যানেল
    private void createChannels() {

        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel service =
                new NotificationChannel(
                        CH_SERVICE,
                        "AshPlay Background Connection",
                        NotificationManager.IMPORTANCE_MIN
                );

        service.setDescription(
                "লোকাল সার্ভারের সঙ্গে নীরব সংযোগ"
        );

        service.setShowBadge(false);
        service.setSound(null, null);
        service.enableVibration(false);
        service.enableLights(false);

        service.setLockscreenVisibility(
                Notification.VISIBILITY_SECRET
        );

        nm.createNotificationChannel(service);

        NotificationChannel alert =
                new NotificationChannel(
                        CH_ALERT,
                        "AshPlay Notifications",
                        NotificationManager.IMPORTANCE_DEFAULT
                );

        alert.setDescription(
                "নতুন কল ও মেসেজের নীরব নোটিফিকেশন"
        );

        alert.setShowBadge(true);
        alert.setSound(null, null);
        alert.enableVibration(false);
        alert.enableLights(false);

        alert.setLockscreenVisibility(
                Notification.VISIBILITY_PRIVATE
        );

        nm.createNotificationChannel(alert);
    }

    // Foreground service-এর নীরব নোটিফিকেশন
    private Notification buildForegroundNotification() {

        Intent open = new Intent(
                this,
                MainActivity.class
        );

        open.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        PendingIntent pi =
                PendingIntent.getActivity(
                        this,
                        10,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        return new NotificationCompat.Builder(
                this,
                CH_SERVICE
        )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AshPlay")
                .setContentText(
                        "নীরব নোটিফিকেশন সংযোগ চালু"
                )
                .setPriority(
                        NotificationCompat.PRIORITY_MIN
                )
                .setSilent(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
    }

    // লোকাল HTTPS সার্টিফিকেট যাচাই
    private SSLSocketFactory buildSocketFactory()
            throws Exception {

        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm()
                );

        tmf.init((KeyStore) null);

        X509TrustManager defaultManager = null;

        for (TrustManager manager : tmf.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                defaultManager = (X509TrustManager) manager;
                break;
            }
        }

        final X509TrustManager base = defaultManager;

        final SharedPreferences trust =
                getSharedPreferences(
                        AppConfig.SSL_PREFS,
                        MODE_PRIVATE
                );

        X509TrustManager pinned =
                new X509TrustManager() {

                    @Override
                    public void checkClientTrusted(
                            X509Certificate[] chain,
                            String authType
                    ) throws CertificateException {
                        throw new CertificateException(
                                "Client certificates not supported"
                        );
                    }

                    @Override
                    public void checkServerTrusted(
                            X509Certificate[] chain,
                            String authType
                    ) throws CertificateException {

                        try {
                            if (base == null) {
                                throw new CertificateException(
                                        "No default trust manager"
                                );
                            }

                            base.checkServerTrusted(
                                    chain,
                                    authType
                            );

                        } catch (CertificateException e) {

                            String saved = trust.getString(
                                    AppConfig.WEBSITE_HOST,
                                    null
                            );

                            String current =
                                    chain != null && chain.length > 0
                                            ? fingerprint(chain[0])
                                            : null;

                            if (saved == null
                                    || current == null
                                    || !saved.equalsIgnoreCase(current)) {
                                throw e;
                            }
                        }
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return base == null
                                ? new X509Certificate[0]
                                : base.getAcceptedIssuers();
                    }
                };

        SSLContext context = SSLContext.getInstance("TLS");

        context.init(
                null,
                new TrustManager[]{pinned},
                null
        );

        return context.getSocketFactory();
    }

    private static String fingerprint(
            X509Certificate certificate
    ) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(certificate.getEncoded());

            StringBuilder result = new StringBuilder();

            for (byte b : digest) {
                result.append(
                        String.format(
                                Locale.US,
                                "%02X",
                                b
                        )
                );
            }

            return result.toString();

        } catch (Exception e) {
            return null;
        }
    }
}
