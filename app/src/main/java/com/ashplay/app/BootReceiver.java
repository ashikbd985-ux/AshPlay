package com.ashplay.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ফোন চালু হলে বা অ্যাপ আপডেট হলে নোটিফিকেশন সার্ভিস নিজে থেকে চালু করে। */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        // শুধু তখনই চালু হবে যখন ইউজার আগে অন্তত একবার অ্যাপ খুলেছে
        if (context.getSharedPreferences(AppConfig.APP_PREFS, Context.MODE_PRIVATE)
                .getBoolean("service_wanted", false)) {
            NotifyService.start(context);
        }
    }
}
