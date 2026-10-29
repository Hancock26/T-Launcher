package com.simple.carlauncher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Always-on service (foreground, so Android 8+ does not stop it while another app is open):
 *  - GPS odometer for the oil-change reminder and the city for pico y placa.
 *  - Spoken welcome when the car is switched on (boot, or screen on after a long sleep, which
 *    is what most Android head units do on ACC on/off).
 */
public class CarService extends Service implements LocationListener {

    private static final String TAG = "CarService";
    static final String ACTION_GREET_NOW = "com.simple.carlauncher.GREET_NOW";
    private static final long GREET_DELAY_MS = 15_000;
    private static final long MIN_SLEEP_MS = 15 * 60_000; // screen off at least 15 min = new trip
    private static final long MIN_INTERVAL_MS = 10 * 60_000;

    private final Handler handler = new Handler();
    private LocationManager lm;
    private Location last;
    private float pendingMeters;
    private long lastPositionSave;
    private boolean listening;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, CarService.class));
        } catch (Exception e) {
            Log.w(TAG, "cannot start", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, notification());
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startGps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(ACTION_GREET_NOW);
        registerReceiver(events, f);

        // Fresh boot = car just switched on.
        if (SystemClock.elapsedRealtime() < 3 * 60_000) schedule(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startGps(); // permission may have been granted since onCreate
        if (intent != null && ACTION_GREET_NOW.equals(intent.getAction())) schedule(true);
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("car", "Servicio del carro", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "car")
                .setSmallIcon(R.drawable.ic_compass)
                .setContentTitle("T-Launcher activo")
                .setContentText("Kilometraje, pico y placa y saludo de bienvenida")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    // ---------- Car on / off ----------

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            SharedPreferences p = prefs();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                p.edit().putLong("screen_off", System.currentTimeMillis()).apply();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                long off = p.getLong("screen_off", 0);
                if (off > 0 && System.currentTimeMillis() - off >= MIN_SLEEP_MS) schedule(false);
            } else if (ACTION_GREET_NOW.equals(a)) {
                schedule(true);
            }
        }
    };

    private void schedule(boolean now) {
        if (!now) {
            if (!Prefs.voice(this)) return;
            long last = prefs().getLong("last_greet", 0);
            if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return;
        }
        handler.removeCallbacks(greet);
        handler.postDelayed(greet, now ? 500 : GREET_DELAY_MS);
        if (!now) Weather.fetch(this, null); // refresh before speaking
    }

    private final Runnable greet = new Runnable() {
        public void run() {
            prefs().edit().putLong("last_greet", System.currentTimeMillis()).apply();
            List<String> clips = new ArrayList<>();
            Voice.speak(CarService.this, buildMessage(clips), clips);
        }
    };

    /** Builds the spoken text and, in parallel, the list of recorded clips for the fallback. */
    private String buildMessage(List<String> clips) {
        StringBuilder sb = new StringBuilder();
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String name = Prefs.name(this);
        sb.append(h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches");
        sb.append(name.isEmpty() ? ". " : ", " + name + ". ");
        clips.add(h < 12 ? "v_greet_morning" : h < 19 ? "v_greet_afternoon" : "v_greet_night");

        Weather w = Weather.cached(this);
        if (w != null) {
            sb.append(w.spoken()).append(" ");
            clips.add("v_cond_" + conditionClip(w.code));
            int t = Math.max(-5, Math.min(40, w.temp));
            clips.add(t < 0 ? "v_temp_m" + (-t) : "v_temp_" + t);
        }
        if (Odometer.isConfigured(this)) {
            int left = Odometer.remainingKm(this);
            if (left < 0) {
                sb.append("Ya toca el cambio de aceite. ");
                clips.add("v_oil_due");
            } else if (left <= Odometer.WARN_KM) {
                sb.append("Faltan ").append(left).append(" kilómetros para el cambio de aceite. ");
                clips.add("v_oil_soon");
            }
        }
        String pico = PicoPlaca.today(this).spoken();
        if (pico != null) sb.append(pico).append(" ");
        sb.append("Abróchate el cinturón y maneja con cuidado.");
        clips.add("v_seatbelt");
        clips.add("v_careful");
        Log.d(TAG, "greeting: " + sb);
        return sb.toString();
    }

    private static String conditionClip(int code) {
        if (code == 0) return "clear";
        if (code == 1) return "mostly";
        if (code == 2) return "partly";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code >= 95) return "storm";
        return "cloudy";
    }

    // ---------- GPS odometer ----------

    private void startGps() {
        if (listening) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 10, this);
            listening = true;
        } catch (Exception e) {
            Log.w(TAG, "no GPS", e);
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        if (System.currentTimeMillis() - lastPositionSave > 5 * 60_000) {
            PicoPlaca.savePosition(this, l.getLatitude(), l.getLongitude());
            lastPositionSave = System.currentTimeMillis();
        }
        if (!l.hasAccuracy() || l.getAccuracy() > 25) return;
        if (last != null) {
            float d = last.distanceTo(l);
            float secs = Math.max(1, (l.getTime() - last.getTime()) / 1000f);
            float kmh = d / secs * 3.6f;
            boolean moving = (l.hasSpeed() ? l.getSpeed() * 3.6f : kmh) > 4;
            // Count only real driving; drop parked drift and impossible jumps.
            if (moving && kmh < 200) pendingMeters += d;
        }
        last = l;
        if (pendingMeters >= 100) { // persist every 100 m
            Odometer.addKm(this, pendingMeters / 1000f);
            pendingMeters = 0;
        }
    }

    public void onStatusChanged(String p, int s, Bundle b) { }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) { }

    private SharedPreferences prefs() {
        return getSharedPreferences("greeter", MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(events);
        lm.removeUpdates(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Starts the service when the radio boots, before the launcher is shown. */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            start(c);
        }
    }
}
