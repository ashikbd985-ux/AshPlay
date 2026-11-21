package com.ashplay.app;

/** সার্ভারের ঠিকানা ও ব্যাকগ্রাউন্ড নোটিফিকেশনের সেটিং এক জায়গায়। */
public final class AppConfig {
    private AppConfig() {}

    public static final String WEBSITE_URL = "https://192.168.168.10";
    public static final String WEBSITE_HOST = "192.168.168.10";

    /** MainActivity-র সংরক্ষিত সার্টিফিকেট ফিঙ্গারপ্রিন্ট (ssl trust) — সার্ভিসও একই সার্টিফিকেট বিশ্বাস করে। */
    public static final String SSL_PREFS = "ashplay_ssl_trust";
    public static final String APP_PREFS = "ashplay_app";

    /** কত সেকেন্ড পরপর সার্ভারকে জিজ্ঞেস করবে (wait=0 মোডে)। */
    public static final int POLL_INTERVAL_MS = 3000;

    /**
     * 0  = সাধারণ পোলিং (প্রতি POLL_INTERVAL_MS পরপর, সব সার্ভারে নিরাপদ)।
     * 20 = long-poll: সার্ভার ২০ সেকেন্ড পর্যন্ত ধরে রাখে, নতুন কিছু এলেই সাথে সাথে জানায়।
     *      শুধু তখনই দিন যখন সার্ভার একসাথে অনেক রিকোয়েস্ট চালাতে পারে (php-fpm-এ যথেষ্ট worker আছে)।
     */
    public static final int LONG_POLL_SECONDS = 0;
}
