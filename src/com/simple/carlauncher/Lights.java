package com.simple.carlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headlight state and day/night theme.
 *
 * Every head unit reports the "ILL" (illumination) wire differently, but most of them write it to
 * some Android setting (a vendor key, the screen brightness, a night-mode flag…). The calibration
 * in Ajustes takes a snapshot of all settings with the lights off and another with the lights on;
 * the setting that changed is remembered and watched from then on.
 */
final class Lights {

    private static final String[] TABLES = {"system", "global", "secure"};
    // Keys that change on their own and would give false positives.
    private static final String[] NOISE = {"time", "alarm", "boot", "count", "uptime", "last", "timestamp",
            "date", "location", "wifi", "data_activity", "battery", "doze", "volume", "mute", "ringer"};
    private static final String[] HINTS = {"ill", "light", "lamp", "night", "dark", "backlight", "dim", "mcu", "headl"};

    private Lights() { }

    // ---------- Current state ----------

    static boolean isConfigured(Context c) {
        return prefs(c).contains("key");
    }

    static boolean isOn(Context c) {
        SharedPreferences p = prefs(c);
        String key = p.getString("key", null);
        if (key == null) {
            // NWD radios (the original project): bit 2 of mcu_no_source_device_state.
            String v = read(c, "system", "mcu_no_source_device_state");
            Integer n = toInt(v);
            return n != null && (n & 2) != 0;
        }
        String cur = read(c, p.getString("table", "system"), key);
        String on = p.getString("on", null), off = p.getString("off", null);
        if (cur == null || on == null) return false;
        if (cur.equals(on)) return true;
        Integer ci = toInt(cur), oi = toInt(on), fi = toInt(off);
        if (ci != null && oi != null && fi != null) {
            int mask = oi ^ fi;
            if (Integer.bitCount(mask) == 1) return (ci & mask) == (oi & mask); // a single flag bit
            return Math.abs(ci - oi) < Math.abs(ci - fi);                        // e.g. brightness levels
        }
        return false;
    }

    /** Dark theme? Setting "theme": auto (default) / dark / light. */
    static boolean night(Context c) {
        String t = Prefs.get(c).getString("theme", "auto");
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        if (isOn(c)) return true;
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (ui == Configuration.UI_MODE_NIGHT_YES) return true;
        return nightByClock();
    }

    /** Night theme from 6:01 p. m. to 6:00 a. m. (Colombia: the sun sets ~6 p. m. all year). */
    static boolean nightByClock() {
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return m >= 18 * 60 + 1 || m < 6 * 60 + 1;
    }

    /** Lights shown on the car: the real headlights, or simulated like Tesla whenever the night theme is on. */
    static boolean shownOn(Context c) {
        return isOn(c) || night(c);
    }

    // ---------- Calibration ----------

    /** All readable settings as "table/key" → value. */
    static Map<String, String> snapshot(Context c) {
        Map<String, String> m = new HashMap<>();
        ContentResolver cr = c.getContentResolver();
        for (String t : TABLES) {
            Cursor cur = null;
            try {
                cur = cr.query(Uri.parse("content://settings/" + t), new String[]{"name", "value"}, null, null, null);
                while (cur != null && cur.moveToNext()) {
                    m.put(t + "/" + cur.getString(0), cur.getString(1));
                }
            } catch (Exception ignored) {
                // Some tables may be blocked; the others still help.
            } finally {
                if (cur != null) cur.close();
            }
        }
        return m;
    }

    static final class Change {
        String table, key, off, on;
        int score;

        String label() {
            return key + "  (" + off + " → " + on + ")";
        }
    }

    /** Settings that differ between the two snapshots, most likely candidates first. */
    static List<Change> diff(Map<String, String> off, Map<String, String> on) {
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, String> e : on.entrySet()) {
            String before = off.get(e.getKey());
            String after = e.getValue();
            if (after == null || after.equals(before)) continue;
            String full = e.getKey();
            Change ch = new Change();
            ch.table = full.substring(0, full.indexOf('/'));
            ch.key = full.substring(full.indexOf('/') + 1);
            ch.off = before == null ? "" : before;
            ch.on = after;
            String k = ch.key.toLowerCase();
            boolean noisy = false;
            for (String n : NOISE) if (k.contains(n)) noisy = true;
            if (noisy) continue;
            for (String h : HINTS) if (k.contains(h)) ch.score += 10;
            if (toInt(ch.on) != null && toInt(ch.off) != null) ch.score += 2;
            if (k.contains("brightness")) ch.score += 1;
            out.add(ch);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Change>() {
            public int compare(Change a, Change b) {
                return b.score - a.score;
            }
        });
        return out;
    }

    static void save(Context c, Change ch) {
        prefs(c).edit().putString("table", ch.table).putString("key", ch.key)
                .putString("off", ch.off).putString("on", ch.on).apply();
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    static String describe(Context c) {
        SharedPreferences p = prefs(c);
        String key = p.getString("key", null);
        return key == null ? "Sin configurar" : key;
    }

    // ---------- Helpers ----------

    static String read(Context c, String table, String key) {
        try {
            ContentResolver cr = c.getContentResolver();
            if ("global".equals(table)) return Settings.Global.getString(cr, key);
            if ("secure".equals(table)) return Settings.Secure.getString(cr, key);
            return Settings.System.getString(cr, key);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer toInt(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lights", Context.MODE_PRIVATE);
    }
}
