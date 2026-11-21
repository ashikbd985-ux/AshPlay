package com.ashplay.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * অ্যাপ বন্ধ থাকলেও চলে: লোকাল সার্ভারের notify.php-কে নিয়মিত জিজ্ঞেস করে
 * নতুন কল বা মেসেজ এসেছে কি না, এলে রিং/ভাইব্রেট/নোটিফিকেশন দেখায়। ইন্টারনেট বা Google লাগে না।
 */
public class NotifyService extends Service {

    public static final String CH_SERVICE = "ashplay_service_v2";
    private static final String CH_SERVICE_OLD = "ashplay_service_v1";
    private static final String CH_CALL = "ashplay_call_v1";
    private static final String CH_MSG = "ashplay_msg_v1";

    private static final int ID_FG = 1;
    private static final int ID_CALL = 2;
    private static final int ID_MSG_BASE = 1000;

    public static final String EXTRA_FROM_CALL = "from_call";
    public static final String EXTRA_OPEN_CHAT = "open_chat";

    /** MainActivity দৃশ্যমান থাকলে true — তখন ওয়েবপেজ নিজেই কল/মেসেজ দেখায়, তাই সার্ভিস চুপ থাকে। */
    public static volatile boolean appVisible = false;

    private static NotifyService instance;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, NotifyService.class));
        } catch (Exception ignored) {
            // ব্যাকগ্রাউন্ড থেকে শুরু করা বাধা পেলে পরের বার অ্যাপ খুললে আবার চেষ্টা হবে
        }
    }

    /** MainActivity সামনে এলে: রিং বন্ধ এবং জমে থাকা নোটিফিকেশন মুছে ফেলা। */
    public static void onAppVisible() {
        NotifyService s = instance;
        if (s != null) s.main.post(s::clearAllAlerts);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private SharedPreferences prefs;
    private NotificationManager nm;

    private long cursor = -1;
    private final Set<String> knownCalls = new LinkedHashSet<>();
    private final Map<Integer, ArrayList<String>> msgLines = new HashMap<>();

    private Ringtone ringtone;
    private Vibrator vibrator;
    private boolean ringing = false;
    private String ringingCallId = null;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(AppConfig.APP_PREFS, MODE_PRIVATE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        cursor = prefs.getLong("cursor", -1);
        createChannels();
        try {
            Notification n = buildForegroundNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID_FG, n);
            }
        } catch (Exception e) {
            stopSelf();
            return;
        }
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "ashplay-notify");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        main.removeCallbacksAndMessages(null);
        stopRing();
        releaseLocks();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AshPlay:notify");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "AshPlay:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- polling loop

    private void loop() {
        int failures = 0;
        SSLSocketFactory factory = null;
        try { factory = buildSocketFactory(); } catch (Exception ignored) {}

        while (running) {
            long pause = AppConfig.LONG_POLL_SECONDS > 0 ? 300 : AppConfig.POLL_INTERVAL_MS;
            try {
                String cookie = null;
                try { cookie = CookieManager.getInstance().getCookie(AppConfig.WEBSITE_URL); } catch (Exception ignored) {}
                if (cookie == null || cookie.isEmpty()) {
                    pause = 15000;                         // লগইন করা নেই — একটু পরপর কুকি দেখব
                } else {
                    Response r = poll(factory, cookie);
                    if (r.code == 401) {
                        pause = 15000;                     // লগআউট বা সেশন শেষ
                        failures = 0;
                    } else if (r.code == 200 && r.body != null) {
                        handle(new JSONObject(r.body));
                        failures = 0;
                    } else {
                        throw new Exception("HTTP " + r.code);
                    }
                }
            } catch (Exception e) {
                failures++;
                pause = Math.min(30000, 3000L * failures);   // সার্ভার/নেটওয়ার্ক নেই — ধীরে ধীরে বিরতি বাড়াই
            }
            if (!running) break;
            try { Thread.sleep(pause); } catch (InterruptedException e) { break; }
        }
    }

    private static final class Response {
        int code;
        String body;
    }

    private Response poll(SSLSocketFactory factory, String cookie) throws Exception {
        StringBuilder q = new StringBuilder(AppConfig.WEBSITE_URL).append("/notify.php?wait=").append(AppConfig.LONG_POLL_SECONDS);
        if (cursor >= 0) q.append("&after=").append(cursor);
        if (!knownCalls.isEmpty()) {
            q.append("&calls=").append(URLEncoder.encode(android.text.TextUtils.join(",", knownCalls), "UTF-8"));
        }
        HttpsURLConnection c = (HttpsURLConnection) new URL(q.toString()).openConnection();
        try {
            if (factory != null) c.setSSLSocketFactory(factory);
            c.setHostnameVerifier((host, session) -> AppConfig.WEBSITE_HOST.equalsIgnoreCase(host));
            c.setConnectTimeout(8000);
            c.setReadTimeout((AppConfig.LONG_POLL_SECONDS + 15) * 1000);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Accept", "application/json");
            c.setUseCaches(false);
            Response r = new Response();
            r.code = c.getResponseCode();
            if (r.code == 200) {
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                    r.body = out.toString("UTF-8");
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private void handle(JSONObject j) {
        if (!j.optBoolean("success")) return;

        // ---- কল ----
        JSONArray calls = j.optJSONArray("calls");
        Set<String> current = new LinkedHashSet<>();
        JSONObject newest = null;
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject c = calls.optJSONObject(i);
                if (c == null) continue;
                String id = c.optString("id");
                if (id.isEmpty()) continue;
                current.add(id);
                if (!knownCalls.contains(id) && newest == null) newest = c;
            }
        }
        boolean callEnded = false;
        for (String old : knownCalls) if (!current.contains(old)) callEnded = true;
        knownCalls.clear();
        knownCalls.addAll(current);

        if (newest != null && !appVisible) {
            final JSONObject call = newest;
            main.post(() -> startRing(call));
        } else if (callEnded && current.isEmpty()) {
            main.post(this::stopRingAndCancel);      // কল ধরা/কাটা/মিসড হয়ে গেছে
        }

        // ---- মেসেজ ----
        JSONArray msgs = j.optJSONArray("messages");
        if (msgs != null && msgs.length() > 0 && !appVisible) {
            Map<Integer, String> names = new HashMap<>();
            Map<Integer, ArrayList<String>> fresh = new HashMap<>();
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                int from = m.optInt("from");
                names.put(from, m.optString("name", "AshPlay"));
                ArrayList<String> l = fresh.get(from);
                if (l == null) { l = new ArrayList<>(); fresh.put(from, l); }
                l.add(m.optString("preview", ""));
            }
            final Map<Integer, String> fn = names;
            final Map<Integer, ArrayList<String>> ff = fresh;
            main.post(() -> showMessages(fn, ff));
        }

        long newCursor = j.optLong("cursor", cursor);
        cursor = newCursor;
        prefs.edit().putLong("cursor", cursor).apply();
    }

    // ---------------------------------------------------------------- ringing

    private final Runnable ringLoop = new Runnable() {
        @Override public void run() {
            if (!ringing) return;
            try { if (ringtone != null && !ringtone.isPlaying()) ringtone.play(); } catch (Exception ignored) {}
            main.postDelayed(this, 1200);
        }
    };

    private void startRing(JSONObject call) {
        String id = call.optString("id");
        int left = Math.max(5, Math.min(60, call.optInt("left", 40)));
        String name = call.optString("name", "AshPlay");
        boolean video = "video".equals(call.optString("type"));

        stopRing();
        ringing = true;
        ringingCallId = id;

        Intent open = new Intent(this, MainActivity.class)
                .setAction("com.ashplay.app.INCOMING_CALL")
                .putExtra(EXTRA_FROM_CALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CH_CALL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name)
                .setContentText(video ? "ইনকামিং ভিডিও কল" : "ইনকামিং অডিও কল")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .addAction(R.drawable.ic_notification, "উত্তর দিন", pi)
                .setTimeoutAfter(left * 1000L)
                .build();
        try { nm.notify(ID_CALL, n); } catch (Exception ignored) {}

        // রিং ও ভাইব্রেশন (ফোনের রিংগার মোড মেনে)
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int mode = am == null ? AudioManager.RINGER_MODE_NORMAL : am.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                ringtone = RingtoneManager.getRingtone(this, uri);
                if (ringtone != null) {
                    ringtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    ringtone.play();
                }
            } catch (Exception ignored) {}
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            try {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                long[] pattern = {0, 800, 500};
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                    else vibrator.vibrate(pattern, 0);
                }
            } catch (Exception ignored) {}
        }
        main.postDelayed(ringLoop, 1200);
        main.postDelayed(this::stopRingAndCancel, left * 1000L);
    }

    private void stopRing() {
        ringing = false;
        ringingCallId = null;
        main.removeCallbacks(ringLoop);
        try { if (ringtone != null) ringtone.stop(); } catch (Exception ignored) {}
        ringtone = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        vibrator = null;
    }

    private void stopRingAndCancel() {
        stopRing();
        try { nm.cancel(ID_CALL); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- messages

    private void showMessages(Map<Integer, String> names, Map<Integer, ArrayList<String>> fresh) {
        boolean first = true;
        for (Map.Entry<Integer, ArrayList<String>> e : fresh.entrySet()) {
            int from = e.getKey();
            ArrayList<String> all = msgLines.get(from);
            if (all == null) { all = new ArrayList<>(); msgLines.put(from, all); }
            all.addAll(e.getValue());
            while (all.size() > 6) all.remove(0);

            String name = names.get(from);
            String last = all.get(all.size() - 1);

            Intent open = new Intent(this, MainActivity.class)
                    .setAction("com.ashplay.app.OPEN_CHAT_" + from)
                    .putExtra(EXTRA_OPEN_CHAT, from)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 3000 + from, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle().setBigContentTitle(name);
            for (String line : all) style.addLine(line);

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_MSG)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(name)
                    .setContentText(last)
                    .setStyle(style)
                    .setNumber(all.size())
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pi);
            if (Build.VERSION.SDK_INT < 26) {
                b.setDefaults(0)
                        .setSound(messageSoundUri())
                        .setVibrate(new long[]{0, 250, 150, 250});
            }
            if (!first) b.setSilent(true);      // একসাথে অনেকজনের মেসেজ এলে শব্দ একবারই
            first = false;
            try { nm.notify(ID_MSG_BASE + from, b.build()); } catch (Exception ignored) {}
        }
    }

    private void clearAllAlerts() {
        stopRingAndCancel();
        for (Integer from : new ArrayList<>(msgLines.keySet())) {
            try { nm.cancel(ID_MSG_BASE + from); } catch (Exception ignored) {}
        }
        msgLines.clear();
    }

    // ---------------------------------------------------------------- notifications setup

    /** res/raw/message_tone.(wav|mp3|ogg) থাকলে সেটা, না থাকলে ফোনের ডিফল্ট নোটিফিকেশন শব্দ। */
    private Uri messageSoundUri() {
        int res = getResources().getIdentifier("message_tone", "raw", getPackageName());
        if (res != 0) return Uri.parse("android.resource://" + getPackageName() + "/" + res);
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;

        try { nm.deleteNotificationChannel(CH_SERVICE_OLD); } catch (Exception ignored) {}
        // সবচেয়ে ছোট গুরুত্ব: স্ট্যাটাস বারে আইকন আসে না, নোটিফিকেশন প্যানেলের নিচে চুপচাপ থাকে
        NotificationChannel service = new NotificationChannel(CH_SERVICE, "সংযোগ (লুকিয়ে রাখা যায়)", NotificationManager.IMPORTANCE_MIN);
        service.setDescription("অ্যাপ বন্ধ থাকলেও কল ও মেসেজ জানানোর জন্য চালু থাকে");
        service.setShowBadge(false);
        service.setSound(null, null);
        service.enableVibration(false);
        service.enableLights(false);
        service.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(service);

        NotificationChannel call = new NotificationChannel(CH_CALL, "ইনকামিং কল", NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("কল এলে স্ক্রিনে দেখায়");
        call.setSound(null, null);               // রিং ও ভাইব্রেশন সার্ভিস নিজে বাজায় (বারবার, কল শেষ না হওয়া পর্যন্ত)
        call.enableVibration(false);
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(call);

        NotificationChannel msg = new NotificationChannel(CH_MSG, "মেসেজ", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("নতুন মেসেজ এলে ছোট রিংটোনসহ জানায়");
        msg.setSound(messageSoundUri(),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        msg.enableVibration(true);
        msg.setVibrationPattern(new long[]{0, 250, 150, 250});
        msg.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(msg);
    }

    private Notification buildForegroundNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AshPlay")
                .setContentText("কল ও মেসেজের জন্য সংযুক্ত")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setLocalOnly(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();
    }

    // ---------------------------------------------------------------- HTTPS (স্ব-স্বাক্ষরিত সার্টিফিকেট)

    /**
     * ওয়েবভিউয়ের মতোই: সার্টিফিকেট সিস্টেম দিয়ে যাচাই হলে ঠিক আছে; না হলে MainActivity-তে ইউজার "Yes" দিয়ে যে
     * সার্টিফিকেটের SHA-256 ফিঙ্গারপ্রিন্ট সেভ করেছে, শুধু সেটা মিললেই গ্রহণ করে।
     */
    private SSLSocketFactory buildSocketFactory() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509TrustManager def = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) { def = (X509TrustManager) tm; break; }
        }
        final X509TrustManager base = def;
        final SharedPreferences trust = getSharedPreferences(AppConfig.SSL_PREFS, MODE_PRIVATE);

        X509TrustManager pinned = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client certs not supported");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    if (base == null) throw new CertificateException("no default trust manager");
                    base.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    String saved = trust.getString(AppConfig.WEBSITE_HOST, null);
                    String fp = chain != null && chain.length > 0 ? fingerprint(chain[0]) : null;
                    if (saved == null || fp == null || !saved.equalsIgnoreCase(fp)) throw e;
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return base == null ? new X509Certificate[0] : base.getAcceptedIssuers();
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinned}, null);
        return ctx.getSocketFactory();
    }

    private static String fingerprint(X509Certificate cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
