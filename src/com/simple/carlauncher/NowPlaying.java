package com.simple.carlauncher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks what is playing and routes controls back to it. Works with any app that publishes a
 * media session: Spotify, YouTube Music, the head unit's own radio/USB/Bluetooth players on most
 * Android 10+ units… Older players that only send com.android.music broadcasts are also read.
 * Needs "notification access" for this app (Android shows the sessions only to listeners).
 */
final class NowPlaying {

    enum Source { APP, LOCAL }

    interface Listener {
        void onNowPlayingChanged();
    }

    /** State reported by one source. */
    static final class Track {
        String title = "", artist = "";
        Bitmap art;
        boolean playing;
        long updated;
        long duration;      // ms, 0 = unknown
        long position;      // ms at positionTime
        long positionTime;  // elapsedRealtime of the position
        float speed = 1f;

        /** Current playback position, moving forward while playing. */
        long positionNow() {
            if (duration <= 0) return 0;
            long p = position + (playing ? (long) ((SystemClock.elapsedRealtime() - positionTime) * speed) : 0);
            return Math.max(0, Math.min(duration, p));
        }
    }

    private static NowPlaying instance;

    static NowPlaying get(Context c) {
        if (instance == null) instance = new NowPlaying(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final List<Listener> listeners = new ArrayList<>();
    private final Track app = new Track(), local = new Track();

    private final MediaSessionManager sessions;
    private final ComponentName notificationListener;
    private MediaController controller;
    private boolean sessionListenerAdded;

    private NowPlaying(Context c) {
        ctx = c;
        sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        notificationListener = new ComponentName(c, MediaListener.class);

        IntentFilter f = new IntentFilter();
        f.addAction("com.android.music.metachanged");
        f.addAction("com.android.music.playstatechanged");
        c.registerReceiver(receiver, f);
        refreshSessions();
    }

    /** True when the user allowed this app to read notifications (needed for media sessions). */
    static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                if (i.hasExtra("track")) local.title = nz(i.getStringExtra("track"));
                if (i.hasExtra("artist")) local.artist = nz(i.getStringExtra("artist"));
                local.playing = i.getBooleanExtra("playing", local.playing);
                local.updated = SystemClock.elapsedRealtime();
                notifyListeners();
            } catch (RuntimeException e) {
                // Factory apps may attach extras we cannot unparcel; never crash the launcher for it.
                android.util.Log.w("NowPlaying", "Ignoring broadcast", e);
            }
        }
    };

    // ---------- App media sessions ----------

    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        public void onMetadataChanged(MediaMetadata m) { readSession(); }
        public void onPlaybackStateChanged(PlaybackState s) { readSession(); }
        public void onSessionDestroyed() { refreshSessions(); }
    };

    private void refreshSessions() {
        List<MediaController> list = null;
        try {
            if (!sessionListenerAdded) {
                sessions.addOnActiveSessionsChangedListener(new MediaSessionManager.OnActiveSessionsChangedListener() {
                    public void onActiveSessionsChanged(List<MediaController> l) {
                        refreshSessions();
                    }
                }, notificationListener);
                sessionListenerAdded = true;
            }
            list = sessions.getActiveSessions(notificationListener);
        } catch (SecurityException ignored) {
            // Notification access not granted yet.
        }
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                if ("com.android.server.telecom".equals(c.getPackageName())) continue;
                if (pick == null) pick = c;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
        }
        if (controller != null) controller.unregisterCallback(sessionCallback);
        controller = pick;
        if (controller != null) controller.registerCallback(sessionCallback);
        readSession();
    }

    private void readSession() {
        if (controller == null) {
            app.title = "";
            app.artist = "";
            app.art = null;
            app.playing = false;
            app.updated = 0;
        } else {
            MediaMetadata m = controller.getMetadata();
            PlaybackState s = controller.getPlaybackState();
            app.title = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            app.artist = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            app.art = art;
            app.duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (s != null) {
                app.position = s.getPosition();
                app.positionTime = s.getLastPositionUpdateTime() > 0 ? s.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
                app.speed = s.getPlaybackSpeed() > 0 ? s.getPlaybackSpeed() : 1f;
            }
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (playing || !app.title.isEmpty()) app.updated = SystemClock.elapsedRealtime();
            app.playing = playing;
        }
        notifyListeners();
    }

    // ---------- Public API ----------

    /** The source to show: whatever is playing (most recent first), else the last one used. */
    Source current() {
        Source best = null;
        long bestTime = -1;
        for (Source s : Source.values()) {
            Track t = track(s);
            if (t.playing && t.updated > bestTime) { best = s; bestTime = t.updated; }
        }
        if (best != null) return best;
        for (Source s : Source.values()) {
            Track t = track(s);
            if (t.updated > 0 && t.updated > bestTime) { best = s; bestTime = t.updated; }
        }
        return best;
    }

    Track track(Source s) {
        return s == Source.APP ? app : local;
    }

    String label(Source s) {
        if (s == Source.APP && controller != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(controller.getPackageName(), 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return "Música";
    }

    void playPause() {
        Source s = current();
        if (s == Source.APP && controller != null) {
            if (app.playing) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    void skip(boolean next) {
        Source s = current();
        if (s == Source.APP && controller != null) {
            if (next) controller.getTransportControls().skipToNext();
            else controller.getTransportControls().skipToPrevious();
        } else {
            mediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    /** Package of the app that is playing, or null. */
    String playingPackage() {
        return current() == Source.APP && controller != null ? controller.getPackageName() : null;
    }

    /** Re-reads the active sessions; also re-registers the listener once notification access exists. */
    void refresh() {
        refreshSessions();
    }

    boolean hasSession() {
        return controller != null;
    }

    void addListener(Listener l) {
        listeners.add(l);
        refreshSessions();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onNowPlayingChanged();
    }

    private void mediaKey(int keyCode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
