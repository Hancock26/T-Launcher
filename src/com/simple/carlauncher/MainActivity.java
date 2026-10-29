package com.simple.carlauncher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Calendar;

/**
 * Home screen in the style of a modern EV dashboard: car panel, live map and control bar.
 * The screen itself is assets/home.html in a WebView; this activity feeds it through the
 * "Car" JavaScript bridge (state once per second, actions back).
 */
public class MainActivity extends Activity implements LocationListener {

    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000;
    private static boolean askedLocation; // ask once per start, never in a loop
    private volatile long lastSessionRefresh;

    private final Handler handler = new Handler();
    private WebView web;
    private NowPlaying nowPlaying;
    private LocationManager lm;
    private long lastWeatherFetch;

    // Written on the main thread, read by the bridge thread.
    private volatile double lat, lon, speedKmh;
    private volatile float bearing;
    private volatile boolean fix;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Lights.night(this) ? Color.BLACK : 0xFFE9EBEE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { return true; } // no text selection
        });
        web.setHapticFeedbackEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Car");
        web.loadUrl("file:///android_asset/home.html");
        setContentView(web);

        installBundledCar();
        nowPlaying = NowPlaying.get(this);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        CarService.start(this);

        if (!Prefs.get(this).getBoolean("welcome_done", false)) {
            Prefs.get(this).edit().putBoolean("welcome_done", true).apply();
            askedLocation = true; // the welcome screen asks for it
            startActivity(new Intent(this, SettingsActivity.class).putExtra("welcome", true));
        }
    }

    /** Personal builds carry the user's car images in assets/car/: copy them once if none are loaded. */
    private void installBundledCar() {
        if (new File(getFilesDir(), SettingsActivity.CAR_FILE).exists()) return;
        if (Prefs.get(this).getBoolean("bundled_car_done", false)) return;
        String[] names = {SettingsActivity.CAR_FILE, SettingsActivity.CAR_LIT_FILE,
                SettingsActivity.CAR_TOP_FILE, SettingsActivity.CAR_TOP_LIT_FILE};
        for (String n : names) {
            try (java.io.InputStream in = getAssets().open("car/" + n);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(new File(getFilesDir(), n))) {
                byte[] buf = new byte[16384];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
            } catch (Exception ignored) {
                // General build: no bundled images.
            }
        }
        Prefs.get(this).edit().putBoolean("bundled_car_done", true).apply();
    }

    // ---------- Bridge ----------

    public final class Bridge {

        @JavascriptInterface
        public String state() {
            Context c = MainActivity.this;
            try {
                JSONObject j = new JSONObject();
                j.put("night", Lights.night(c));
                j.put("lights", Lights.shownOn(c));
                j.put("fix", fix);
                j.put("lat", lat);
                j.put("lon", lon);
                j.put("speed", speedKmh);
                j.put("bearing", bearing);
                j.put("mapMode", Prefs.get(c).getString("map_mode", "map"));
                j.put("paint", Prefs.get(c).getString("paint", "plata"));
                String maps = Prefs.app(c, Prefs.APP_MAPS);
                j.put("mapsLabel", maps == null ? "" : Prefs.appLabel(c, maps));

                Weather w = Weather.cached(c);
                if (w != null) {
                    JSONObject wx = new JSONObject();
                    wx.put("temp", w.temp);
                    wx.put("code", w.code);
                    wx.put("day", w.isDay);
                    wx.put("city", w.city);
                    j.put("wx", wx);
                }

                JSONObject oil = new JSONObject();
                boolean conf = Odometer.isConfigured(c);
                int left = conf ? Odometer.remainingKm(c) : 0;
                oil.put("configured", conf);
                oil.put("left", left);
                oil.put("warn", conf && left <= Odometer.WARN_KM);
                j.put("oil", oil);

                PicoPlaca.Status ps = PicoPlaca.today(c);
                JSONObject pico = new JSONObject();
                boolean restricted = ps.state == PicoPlaca.State.RESTRICTED;
                pico.put("show", restricted);
                if (restricted) {
                    pico.put("text", (ps.result.activeNow ? "Pico y placa ahora · " : "Hoy pico y placa · ")
                            + PicoRules.hoursText(ps.result.today));
                }
                j.put("pico", pico);

                // Sessions appear/disappear and access may be granted later: look again every few seconds.
                long nowMs = android.os.SystemClock.elapsedRealtime();
                if (!nowPlaying.hasSession() && nowMs - lastSessionRefresh > 4000) {
                    lastSessionRefresh = nowMs;
                    runOnUiThread(new Runnable() { public void run() { nowPlaying.refresh(); } });
                }
                AudioManager amx = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                boolean audioActive = amx.isMusicActive();
                JSONObject media = new JSONObject();
                NowPlaying.Source src = nowPlaying.current();
                NowPlaying.Track t = src == null ? null : nowPlaying.track(src);
                media.put("access", NowPlaying.hasAccess(c));
                media.put("src", src == null ? "Música" : nowPlaying.label(src));
                boolean noTitle = t == null || t.title.isEmpty();
                // Players without a media session (some head-unit Bluetooth/radio apps): at least show that audio plays.
                media.put("title", noTitle ? (audioActive ? "Reproduciendo" : "") : t.title);
                media.put("artist", noTitle ? (audioActive ? "Audio del radio o Bluetooth" : "") : t.artist);
                media.put("playing", (t != null && t.playing) || (noTitle && audioActive));
                media.put("dur", t == null ? 0 : t.duration);
                media.put("pos", t == null ? 0 : t.positionNow());
                media.put("artId", t != null && t.art != null ? t.title + "|" + t.artist + "|" + t.art.getGenerationId() : "");
                j.put("media", media);

                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                j.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                j.put("volMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

                File photo = new File(getFilesDir(), SettingsActivity.CAR_FILE);
                File lit = new File(getFilesDir(), SettingsActivity.CAR_LIT_FILE);
                File top = new File(getFilesDir(), SettingsActivity.CAR_TOP_FILE);
                File chase = new File(getFilesDir(), SettingsActivity.CAR_CHASE_FILE);
                j.put("carV", photo.exists() ? photo.lastModified() + ":" + (lit.exists() ? lit.lastModified() : 0)
                        + ":" + (top.exists() ? top.lastModified() : 0) + ":" + (chase.exists() ? chase.lastModified() : 0) : "");
                j.put("name", Prefs.name(c));
                JSONObject addr = new JSONObject();
                addr.put("home", Prefs.get(c).getString("addr_home", "").trim());
                addr.put("work", Prefs.get(c).getString("addr_work", "").trim());
                j.put("addr", addr);
                NavInfo.Info n = NavInfo.current();
                JSONObject nav = new JSONObject();
                nav.put("active", n != null);
                if (n != null) {
                    nav.put("app", n.app);
                    nav.put("title", n.title);
                    nav.put("text", n.text);
                    nav.put("iconId", n.icon == null ? "" : n.app + n.icon.getGenerationId());
                }
                j.put("nav", nav);
                return j.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** Album art of the current track as a data URL. */
        @JavascriptInterface
        public String art() {
            NowPlaying.Source src = nowPlaying.current();
            NowPlaying.Track t = src == null ? null : nowPlaying.track(src);
            if (t == null || t.art == null) return "";
            Bitmap b = t.art;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 256) b = Bitmap.createScaledBitmap(b, b.getWidth() * 256 / max, b.getHeight() * 256 / max, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        }

        /** The user's car photo ("base" or "lit") as a data URL, or "". */
        @JavascriptInterface
        public String carImage(String which) {
            String name = "lit".equals(which) ? SettingsActivity.CAR_LIT_FILE
                    : "top".equals(which) ? SettingsActivity.CAR_TOP_FILE
                    : "toplit".equals(which) ? SettingsActivity.CAR_TOP_LIT_FILE
                    : "chase".equals(which) ? SettingsActivity.CAR_CHASE_FILE
                    : "chaselit".equals(which) ? SettingsActivity.CAR_CHASE_LIT_FILE : SettingsActivity.CAR_FILE;
            File f = new File(getFilesDir(), name);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return "data:image/png;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        /** Turn arrow of the Waze / Google Maps navigation notification as a data URL. */
        @JavascriptInterface
        public String navIcon() {
            NavInfo.Info n = NavInfo.current();
            if (n == null || n.icon == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            n.icon.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        }

        /**
         * Address search with Android's own geocoder (Google's address database on radios with Google services):
         * much better than OpenStreetMap for Colombian addresses ("Calle 72 # 46-30"). Asynchronous: the result
         * arrives in window.onGeo(id, json).
         */
        @JavascriptInterface
        public boolean geocode(final String id, final String q, final double lat, final double lon) {
            if (!android.location.Geocoder.isPresent()) return false;
            new Thread(new Runnable() {
                public void run() {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    try {
                        android.location.Geocoder gc = new android.location.Geocoder(MainActivity.this, new java.util.Locale("es", "CO"));
                        java.util.List<android.location.Address> list = null;
                        if (lat != 0 || lon != 0) list = gc.getFromLocationName(q, 6, lat - 0.35, lon - 0.35, lat + 0.35, lon + 0.35);
                        if (list == null || list.isEmpty()) list = gc.getFromLocationName(q + ", Colombia", 6);
                        if (list != null) for (android.location.Address ad : list) {
                            if (!ad.hasLatitude()) continue;
                            JSONObject o = new JSONObject();
                            String line = ad.getMaxAddressLineIndex() >= 0 ? ad.getAddressLine(0) : "";
                            String name = ad.getFeatureName() != null && !ad.getFeatureName().matches("[0-9 -]+") ? ad.getFeatureName() : line;
                            if (line != null && line.contains(",")) name = ad.getFeatureName() != null && !ad.getFeatureName().matches("[0-9 -]+")
                                    && !line.startsWith(ad.getFeatureName()) ? ad.getFeatureName() : line.substring(0, line.indexOf(','));
                            o.put("name", name == null ? q : name);
                            o.put("sub", line == null ? "" : line);
                            o.put("lat", ad.getLatitude());
                            o.put("lon", ad.getLongitude());
                            o.put("src", "google");
                            arr.put(o);
                        }
                    } catch (Exception ignored) {
                        // no network / no geocoder backend: the other sources still answer
                    }
                    final String js = "window.onGeo && window.onGeo(" + JSONObject.quote(id) + "," + JSONObject.quote(arr.toString()) + ")";
                    runOnUiThread(new Runnable() {
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
            return true;
        }

        /** Which voice the navigation uses (shown in Ajustes). */
        @JavascriptInterface
        public String voiceInfo() {
            return navVoiceName == null ? "" : navVoiceName;
        }

        /** Spoken turn instruction (Tesla-style voice guidance), over the music which is ducked. */
        @JavascriptInterface
        public void say(final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    speakNav(text);
                }
            });
        }

        @JavascriptInterface
        public void open(final String slot) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Ui.openSlot(MainActivity.this, slot, titleFor(slot));
                }
            });
        }

        @JavascriptInterface
        public void action(final String a) {
            runOnUiThread(new Runnable() {
                public void run() {
                    doAction(a);
                }
            });
        }
    }

    private static String titleFor(String slot) {
        if (Prefs.APP_PHONE.equals(slot)) return "¿Qué app abre Bluetooth / teléfono?";
        if (Prefs.APP_RADIO.equals(slot)) return "¿Qué app es la radio FM?";
        if (Prefs.APP_MUSIC.equals(slot)) return "¿Qué app de música quieres abrir?";
        if (Prefs.APP_MAPS.equals(slot)) return "¿Qué app de mapas usas?";
        if (Prefs.APP_EQ.equals(slot)) return "¿Qué app es el ecualizador / sonido?";
        return "Elige una app";
    }

    private void doAction(String a) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (a.startsWith("pick:")) {
            final String slot = a.substring(5);
            Ui.pickApp(this, titleFor(slot), new Ui.AppChosen() {
                public void onApp(String c) { Prefs.setApp(MainActivity.this, slot, c); }
            });
        } else if ("apps".equals(a)) {
            startActivity(new Intent(this, AppsActivity.class));
        } else if ("screenoff".equals(a)) {
            startActivity(new Intent(this, ScreenOffActivity.class));
        } else if ("settings".equals(a)) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if ("eq".equals(a)) {
            Ui.openSlot(this, Prefs.APP_EQ, titleFor(Prefs.APP_EQ));
        } else if (a.startsWith("navto:")) {
            openMapsAt(a.substring(6));
        } else if (a.startsWith("nav:")) {
            navigateTo(a.substring(4));
        } else if ("oil".equals(a)) {
            startActivity(new Intent(this, OilActivity.class));
        } else if ("prev".equals(a)) {
            nowPlaying.skip(false);
        } else if ("next".equals(a)) {
            nowPlaying.skip(true);
        } else if ("playpause".equals(a)) {
            nowPlaying.playPause();
        } else if ("openmusic".equals(a)) {
            openMusic();
        } else if ("volup".equals(a)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
        } else if ("voldown".equals(a)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
        }
    }

    // ---------- Voice guidance ----------
    private android.speech.tts.TextToSpeech navTts;
    private boolean navTtsReady;
    private String navPending;
    private volatile String navVoiceName;

    private void speakNav(String text) {
        if (text == null || text.isEmpty() || !Prefs.get(this).getBoolean("nav_voice", true)) return;
        if (navTts == null) {
            navPending = text;
            navTts = new android.speech.tts.TextToSpeech(getApplicationContext(), new android.speech.tts.TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status != android.speech.tts.TextToSpeech.SUCCESS) return;
                    Voice.pickAnySpanish(navTts);
                    try {
                        android.speech.tts.Voice v = navTts.getVoice();
                        navVoiceName = v == null ? "" : v.getLocale().toString();
                    } catch (Exception ignored) { }
                    navTts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    navTtsReady = true;
                    if (navPending != null) { navTts.speak(navPending, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nav"); navPending = null; }
                }
            });
            return;
        }
        if (navTtsReady) navTts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nav");
        else navPending = text;
    }

    /** "lat,lon,label": hand the destination over to Waze / Google Maps (the app chosen in Ajustes). */
    private void openMapsAt(String v) {
        String[] p = v.split(",", 3);
        if (p.length < 2) return;
        String label = p.length > 2 ? p[2] : "Destino";
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:" + p[0] + "," + p[1] + "?q=" + p[0] + "," + p[1]
                + "(" + android.net.Uri.encode(label) + ")"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String comp = Prefs.app(this, Prefs.APP_MAPS);
        if (comp != null) {
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp);
            if (cn != null) i.setPackage(cn.getPackageName());
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            i.setPackage(null);
            try { startActivity(i); } catch (Exception ignored) { android.widget.Toast.makeText(this, "No hay app de mapas instalada", android.widget.Toast.LENGTH_SHORT).show(); }
        }
    }

    /** Navigation to the saved Casa / Trabajo address in the chosen maps app (asks for it the first time). */
    private void navigateTo(final String place) {
        final String key = "addr_" + place;
        String addr = Prefs.get(this).getString(key, "").trim();
        if (addr.isEmpty()) {
            Ui.askText(this, "home".equals(place) ? "Dirección de tu casa" : "Dirección de tu trabajo",
                    "Ej: Calle 80 # 50-20, Barranquilla", "", android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS, new Ui.TextChosen() {
                        public void onText(String s) {
                            if (s.isEmpty()) return;
                            Prefs.put(MainActivity.this, key, s);
                            navigateTo(place);
                        }
                    });
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(addr)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String comp = Prefs.app(this, Prefs.APP_MAPS);
        if (comp != null) {
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp);
            if (cn != null) i.setPackage(cn.getPackageName());
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            i.setPackage(null);
            try {
                startActivity(i);
            } catch (Exception e2) {
                android.widget.Toast.makeText(this, "No hay app de mapas instalada", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openMusic() {
        if (!NowPlaying.hasAccess(this)) {
            SettingsActivity.openNotificationAccess(this);
            return;
        }
        String pkg = nowPlaying.playingPackage();
        Intent i = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i);
        else Ui.openSlot(this, Prefs.APP_MUSIC, titleFor(Prefs.APP_MUSIC));
    }

    // ---------- GPS (speed and map) ----------

    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Location best = null;
            for (String p : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) {
                lat = best.getLatitude();
                lon = best.getLongitude();
                fix = true;
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (Exception ignored) { }
    }

    @Override
    public void onLocationChanged(Location l) {
        lat = l.getLatitude();
        lon = l.getLongitude();
        fix = true;
        speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6 : 0;
        if (speedKmh < 2) speedKmh = 0; // parked GPS jitter
        if (l.hasBearing() && speedKmh > 3) bearing = l.getBearing();
    }

    public void onStatusChanged(String p, int s, Bundle b) { }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) { }

    // ---------- Lifecycle ----------

    private final Runnable periodic = new Runnable() {
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastWeatherFetch == 0 || now - lastWeatherFetch > WEATHER_EVERY_MS) {
                lastWeatherFetch = now;
                Weather.fetch(MainActivity.this, null);
            }
            handler.postDelayed(this, 60 * 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (!askedLocation
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askedLocation = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        startGps();
        handler.post(periodic);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        lm.removeUpdates(this);
        handler.removeCallbacks(periodic);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            CarService.start(this);
            startGps();
            lastWeatherFetch = 0;
            handler.post(periodic);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navTts != null) navTts.shutdown();
        web.destroy();
    }

    static void pressable(View v) {
        Ui.pressable(v, 0.94f);
    }

    @Override
    public void onBackPressed() {
        // Home screen: nothing to go back to.
    }

}
