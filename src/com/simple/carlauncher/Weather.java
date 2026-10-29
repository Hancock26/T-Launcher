package com.simple.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/** Current weather from Open-Meteo (free, no API key), located by GPS or, failing that, by IP. */
public class Weather {

    public interface Listener {
        void onWeather(Weather w);
    }

    public final int temp, feelsLike, max, min, humidity, wind, code;
    public final boolean isDay;
    public final String city;

    private Weather(JSONObject j) {
        temp = j.optInt("temp");
        feelsLike = j.optInt("feels");
        max = j.optInt("max");
        min = j.optInt("min");
        humidity = j.optInt("hum");
        wind = j.optInt("wind");
        code = j.optInt("code");
        isDay = j.optBoolean("day", true);
        city = j.optString("city", "");
    }

    public String description() {
        if (code == 0) return isDay ? "Despejado" : "Noche despejada";
        if (code == 1) return "Mayormente despejado";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 51 && code <= 57) return "Llovizna";
        if (code >= 61 && code <= 67) return "Lluvia";
        if (code >= 71 && code <= 77) return "Nieve";
        if (code >= 80 && code <= 82) return "Chubascos";
        if (code == 85 || code == 86) return "Nevadas";
        if (code >= 95) return "Tormenta";
        return "—";
    }

    /** For the voice: "Está nublado, con 18 grados." */
    public String spoken() {
        String d = description().toLowerCase();
        String verb = code <= 3 || code == 45 || code == 48 ? "Está " : "Hay ";
        if (code == 0 && !isDay) { verb = "La noche está "; d = "despejada"; }
        if (code == 1 || code == 2) verb = "Está ";
        return verb + d + ", con " + temp + (Math.abs(temp) == 1 ? " grado." : " grados.");
    }

    public int icon() {
        if (code <= 1) return isDay ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code <= 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        return R.drawable.ic_w_rain;
    }

    /** Last saved weather, so the card is filled instantly on start. */
    public static Weather cached(Context c) {
        try {
            String s = prefs(c).getString("json", null);
            return s == null ? null : new Weather(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    public static void fetch(Context ctx, Listener l) {
        Context c = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() { public void run() {
            try {
                double lat, lon;
                String city = "";
                double[] pos = PicoPlaca.lastPosition(c);
                if (pos != null) {
                    lat = pos[0];
                    lon = pos[1];
                    city = cityName(c, lat, lon);
                    if (city == null || city.isEmpty()) {
                        PicoRules.City known = PicoRules.locate(lat, lon);
                        city = known == null ? "" : known.name;
                    }
                } else {
                    JSONObject ip = new JSONObject(get("https://ipapi.co/json/"));
                    lat = ip.getDouble("latitude");
                    lon = ip.getDouble("longitude");
                    city = ip.optString("city", "");
                }
                JSONObject r = new JSONObject(get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                        + "weather_code,wind_speed_10m,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto",
                        lat, lon)));
                JSONObject cur = r.getJSONObject("current");
                JSONObject day = r.getJSONObject("daily");
                JSONObject j = new JSONObject();
                j.put("temp", Math.round(cur.getDouble("temperature_2m")));
                j.put("feels", Math.round(cur.getDouble("apparent_temperature")));
                j.put("hum", cur.getInt("relative_humidity_2m"));
                j.put("wind", Math.round(cur.getDouble("wind_speed_10m")));
                j.put("code", cur.getInt("weather_code"));
                j.put("day", cur.getInt("is_day") == 1);
                j.put("max", Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)));
                j.put("min", Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)));
                j.put("city", city);
                prefs(c).edit().putString("json", j.toString()).apply();
                Weather w = new Weather(j);
                if (l != null) main.post(new Runnable() { public void run() { l.onWeather(w); } });
            } catch (Exception ignored) {
                // No internet: keep showing the cached weather.
            }
        }}).start();
    }

    private static String cityName(Context c, double lat, double lon) {
        try {
            List<Address> a = new Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (a != null && !a.isEmpty()) {
                String city = a.get(0).getLocality();
                return city != null ? city : a.get(0).getSubAdminArea();
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", "CarLauncher");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
