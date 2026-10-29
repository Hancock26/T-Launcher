package com.simple.carlauncher;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Lets the launcher read active media sessions (now playing) and the turn-by-turn notification
 * of Waze / Google Maps. Refreshes them as soon as access is live.
 */
public class MediaListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                NowPlaying.get(MediaListener.this).refresh();
            }
        });
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) NavInfo.posted(this, s);
        } catch (Exception ignored) {
            // Not fatal: the next update of the navigation notification fills it in.
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            NavInfo.posted(this, sbn);
        } catch (Exception ignored) {
            // A malformed notification from another app must never crash the launcher.
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NavInfo.removed(sbn);
    }
}
