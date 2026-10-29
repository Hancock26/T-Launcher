package com.simple.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mileage for the oil-change reminder. The user enters the dashboard reading once; from then on
 * CarService adds the distance driven, measured with GPS.
 */
final class Odometer {

    static final int DEFAULT_INTERVAL_KM = 5000;
    static final int WARN_KM = 500;

    private Odometer() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("odometer", Context.MODE_PRIVATE);
    }

    static boolean isConfigured(Context c) {
        return prefs(c).contains("base_km");
    }

    static void addKm(Context c, float km) {
        SharedPreferences p = prefs(c);
        p.edit().putFloat("gps_km", p.getFloat("gps_km", 0) + km).apply();
    }

    /** Estimated dashboard reading: the value the user entered plus GPS distance since. */
    static int currentKm(Context c) {
        SharedPreferences p = prefs(c);
        return Math.round(p.getInt("base_km", 0) + p.getFloat("gps_km", 0));
    }

    static void setCurrentKm(Context c, int km) {
        prefs(c).edit().putInt("base_km", km).putFloat("gps_km", 0).apply();
    }

    static int lastChangeKm(Context c) {
        return prefs(c).getInt("last_change_km", currentKm(c));
    }

    static void setLastChangeKm(Context c, int km) {
        prefs(c).edit().putInt("last_change_km", km).apply();
    }

    static int intervalKm(Context c) {
        return prefs(c).getInt("interval_km", DEFAULT_INTERVAL_KM);
    }

    static void setIntervalKm(Context c, int km) {
        prefs(c).edit().putInt("interval_km", km).apply();
    }

    /** Km left until the next oil change (negative when overdue). */
    static int remainingKm(Context c) {
        return lastChangeKm(c) + intervalKm(c) - currentKm(c);
    }
}
