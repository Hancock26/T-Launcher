package com.simple.carlauncher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * Turn-by-turn instructions published by Waze and Google Maps in their ongoing navigation
 * notification (distance, street and the turn arrow). Shown in the launcher's drive view.
 */
final class NavInfo {

    static final class Info {
        String app, title, text;
        Bitmap icon;
        long at;
    }

    private static volatile Info current;
    private static final String[] IDLE = {"en ejecución", "en ejecucion", "toca para", "is running", "tap to", "running in",
            "segundo plano", "background", "conduciendo", "driving mode", "buscando gps", "searching for gps"};

    private NavInfo() { }

    static String appName(String pkg) {
        if ("com.waze".equals(pkg)) return "Waze";
        if ("com.google.android.apps.maps".equals(pkg)) return "Google Maps";
        if ("com.google.android.apps.mapslite".equals(pkg)) return "Maps Go";
        if ("com.here.app.maps".equals(pkg)) return "HERE";
        if ("net.osmand".equals(pkg) || "net.osmand.plus".equals(pkg)) return "OsmAnd";
        if ("com.sygic.aura".equals(pkg)) return "Sygic";
        return null;
    }

    static Info current() {
        Info i = current;
        // Safety net in case the removal callback was missed.
        if (i != null && System.currentTimeMillis() - i.at > 10 * 60000) { current = null; return null; }
        return i;
    }

    static void posted(android.content.Context c, StatusBarNotification sbn) {
        String app = appName(sbn.getPackageName());
        if (app == null) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
        Bundle x = n.extras;
        if (x == null) return;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null) text = x.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (title == null && text == null) return;
        // Waze / Maps also keep an idle notification ("En ejecución. Toca para abrir"): not an instruction.
        String all = ((title == null ? "" : title) + " " + (text == null ? "" : text)).toLowerCase();
        for (String idle : IDLE) {
            if (all.contains(idle)) { removed(sbn); return; }
        }
        Info i = new Info();
        i.app = app;
        i.title = title == null ? "" : title.toString();
        i.text = text == null ? "" : text.toString();
        i.icon = largeIcon(c, n);
        i.at = System.currentTimeMillis();
        current = i;
    }

    static void removed(StatusBarNotification sbn) {
        Info i = current;
        String app = appName(sbn.getPackageName());
        if (i != null && app != null && app.equals(i.app)) current = null;
    }

    private static Bitmap largeIcon(android.content.Context c, Notification n) {
        try {
            Icon ic = n.getLargeIcon();
            if (ic == null) return null;
            Drawable d = ic.loadDrawable(c);
            if (d == null) return null;
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
            int w = Math.max(1, Math.min(192, d.getIntrinsicWidth())), h = Math.max(1, Math.min(192, d.getIntrinsicHeight()));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(cv);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
