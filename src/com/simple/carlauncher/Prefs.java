package com.simple.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/** User settings: name, plate, pico y placa mode, apps behind each button, voice. */
final class Prefs {

    // Buttons whose app the user chooses (any head unit brand).
    static final String APP_RADIO = "app_radio";
    static final String APP_PHONE = "app_phone";
    static final String APP_MUSIC = "app_music";
    static final String APP_MAPS = "app_maps";
    static final String APP_EQ = "app_eq";

    // Pico y placa modes.
    static final String PP_AUTO = "auto", PP_MANUAL = "manual", PP_OFF = "off";

    private Prefs() { }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String name(Context c) {
        return get(c).getString("name", "").trim();
    }

    static String plate(Context c) {
        return get(c).getString("plate", "").trim().toUpperCase();
    }

    /** Last digit of the plate (cars ABC123 → 3, motorcycles ABC12D → 2), or -1. */
    static int plateDigit(Context c) {
        return plateDigit(plate(c));
    }

    static int plateDigit(String plate) {
        for (int i = plate.length() - 1; i >= 0; i--) {
            char ch = plate.charAt(i);
            if (ch >= '0' && ch <= '9') return ch - '0';
        }
        return -1;
    }

    /** "auto", "manual", "off" or "city:<id>". */
    static String ppMode(Context c) {
        return get(c).getString("pp_mode", PP_AUTO);
    }

    /** Manual restriction days as a bit mask of Calendar.DAY_OF_WEEK (1 << day). */
    static int ppManualDays(Context c) {
        return get(c).getInt("pp_days", 0);
    }

    static String ppManualHours(Context c) {
        return get(c).getString("pp_hours", "6-21");
    }

    static boolean voice(Context c) {
        return get(c).getBoolean("voice", true);
    }

    static void put(Context c, String key, String value) {
        get(c).edit().putString(key, value).apply();
    }

    // ---------- Apps behind the buttons ----------

    static String app(Context c, String slot) {
        String s = get(c).getString(slot, null);
        if (s != null) return s.isEmpty() ? null : s;
        // Sensible first guesses so most buttons work out of the box.
        if (APP_MAPS.equals(slot)) {
            return firstInstalled(c, "com.waze", "com.google.android.apps.maps");
        }
        return null;
    }

    static void setApp(Context c, String slot, String component) {
        get(c).edit().putString(slot, component == null ? "" : component).apply();
    }

    /** Launcher component of the first installed package, as "pkg/cls". */
    static String firstInstalled(Context c, String... pkgs) {
        PackageManager pm = c.getPackageManager();
        for (String p : pkgs) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(p);
            List<ResolveInfo> r = pm.queryIntentActivities(i, 0);
            if (!r.isEmpty()) {
                return new ComponentName(p, r.get(0).activityInfo.name).flattenToString();
            }
        }
        return null;
    }

    static String appLabel(Context c, String component) {
        if (component == null) return "Sin asignar";
        try {
            ComponentName cn = ComponentName.unflattenFromString(component);
            PackageManager pm = c.getPackageManager();
            return pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
        } catch (Exception e) {
            return "App no encontrada";
        }
    }
}
