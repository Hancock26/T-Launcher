package com.simple.carlauncher;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise / sunset for a place (NOAA approximation, about a minute of error), so the night theme
 * follows the real dusk anywhere in Latin America instead of a fixed 6:01 p. m.
 */
final class Sun {

    private Sun() { }

    /** Minutes from local midnight of {sunrise, sunset}, or null in polar day/night. */
    static int[] riseSet(double lat, double lon, Calendar day) {
        int n = day.get(Calendar.DAY_OF_YEAR);
        double gamma = 2 * Math.PI / 365 * (n - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) - Math.sin(latR) * Math.sin(decl)) / (Math.cos(latR) * Math.cos(decl));
        if (cosH > 1 || cosH < -1) return null;
        double ha = Math.toDegrees(Math.acos(cosH));
        TimeZone tz = day.getTimeZone();
        double offsetMin = tz.getOffset(day.getTimeInMillis()) / 60000.0;
        double noon = 720 - 4 * lon - eqTime + offsetMin;
        return new int[]{(int) Math.round(noon - 4 * ha), (int) Math.round(noon + 4 * ha)};
    }

    /** Night from one minute after sunset until sunrise. */
    static boolean isNight(double lat, double lon, Calendar now) {
        int[] rs = riseSet(lat, lon, now);
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (rs == null) return false;
        return m >= rs[1] + 1 || m < rs[0];
    }
}
