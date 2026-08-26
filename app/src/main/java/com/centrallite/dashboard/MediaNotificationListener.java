package com.centrallite.dashboard;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class MediaNotificationListener extends NotificationListenerService {
    public static final String ACTION_MEDIA_INFO = "com.centrallite.dashboard.MEDIA_INFO";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !"com.android.chrome".equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        Object mediaSession = extras.getParcelable("android.mediaSession");
        boolean looksLikeMedia = mediaSession != null ||
                ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0 && titleCs != null);
        if (!looksLikeMedia) return;

        Intent intent = new Intent(ACTION_MEDIA_INFO);
        intent.setPackage(getPackageName());
        intent.putExtra("active", true);
        intent.putExtra("title", titleCs != null ? titleCs.toString() : "");
        intent.putExtra("text", textCs != null ? textCs.toString() : "Google Chrome");
        sendBroadcast(intent);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !"com.android.chrome".equals(sbn.getPackageName())) return;
        Intent intent = new Intent(ACTION_MEDIA_INFO);
        intent.setPackage(getPackageName());
        intent.putExtra("active", false);
        sendBroadcast(intent);
    }
}
