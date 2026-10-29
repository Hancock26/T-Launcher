package com.simple.carlauncher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speaks the welcome message with Android's text-to-speech in Colombian Spanish, lowering the
 * music while it talks. If the head unit has no Spanish voice it plays the recorded clips
 * (res/raw/v_*.mp3) instead, which cover greeting, weather, oil and seat belt.
 */
final class Voice {

    private static final String TAG = "Voice";
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private Voice() { }

    static void speak(Context ctx, String text, List<String> fallbackClips) {
        Context c = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRS).build();
        TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(c, new TextToSpeech.OnInitListener() { public void onInit(int status) {
            final TextToSpeech tts = holder[0];
            if (status != TextToSpeech.SUCCESS || !setSpanish(tts)) {
                Log.d(TAG, "no Spanish TTS, playing clips");
                if (tts != null) tts.shutdown();
                main.post(new Runnable() { public void run() { playClips(c, fallbackClips); } });
                return;
            }
            tts.setAudioAttributes(ATTRS);
            tts.setSpeechRate(0.95f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { }
                public void onDone(String id) { finish(); }
                public void onError(String id) { finish(); }

                private void finish() {
                    am.abandonAudioFocusRequest(focus);
                    main.post(new Runnable() { public void run() { tts.shutdown(); } });
                }
            });
            am.requestAudioFocus(focus);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "greet");
        }});
    }

    /** Latin-American Spanish only (never the Spain voice) for the greeting. */
    private static boolean setSpanish(TextToSpeech tts) {
        return pickLatin(tts);
    }

    private static final String[] LATIN = {"CO", "US", "MX", "419", "AR", "CL", "PE", "VE", "EC"};

    /**
     * Picks a Latin-American Spanish voice. A plain setLanguage("es_CO") is not enough: when the engine has no
     * Colombian voice it silently falls back to Spain's, so the installed voices are checked one by one.
     * Returns false when only Spain's Spanish (or nothing) exists.
     */
    static boolean pickLatin(TextToSpeech tts) {
        try {
            java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
            if (voices != null) {
                for (String country : LATIN) {
                    android.speech.tts.Voice best = null;
                    for (android.speech.tts.Voice v : voices) {
                        Locale l = v.getLocale();
                        if (l == null || !"es".equals(l.getLanguage()) || !country.equalsIgnoreCase(l.getCountry())) continue;
                        if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                        // Prefer voices that work offline, then the higher quality.
                        if (best == null || (best.isNetworkConnectionRequired() && !v.isNetworkConnectionRequired())
                                || (best.isNetworkConnectionRequired() == v.isNetworkConnectionRequired() && v.getQuality() > best.getQuality())) best = v;
                    }
                    if (best != null && tts.setVoice(best) == TextToSpeech.SUCCESS) return true;
                }
            }
        } catch (Exception ignored) {
            // Old engines without getVoices(): fall through to the locale check.
        }
        for (String country : LATIN) {
            try {
                Locale l = new Locale("es", country);
                if (tts.isLanguageAvailable(l) >= TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                    tts.setLanguage(l);
                    return true;
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** Last resort for navigation prompts: any Spanish voice. */
    static void pickAnySpanish(TextToSpeech tts) {
        if (pickLatin(tts)) return;
        try { tts.setLanguage(new Locale("es")); } catch (Exception ignored) { }
    }

    // ---------- Recorded clips fallback ----------

    private static final List<Integer> queue = new ArrayList<>();
    private static MediaPlayer player;

    private static void playClips(Context c, List<String> clips) {
        queue.clear();
        for (String name : clips) {
            int id = c.getResources().getIdentifier(name, "raw", c.getPackageName());
            if (id != 0) queue.add(id);
        }
        playNext(c);
    }

    private static void playNext(Context c) {
        if (player != null) {
            player.release();
            player = null;
        }
        if (queue.isEmpty()) return;
        int id = queue.remove(0);
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(ATTRS);
            player.setDataSource(c, android.net.Uri.parse("android.resource://" + c.getPackageName() + "/" + id));
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { playNext(c); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    playNext(c);
                    return true;
                }
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.w(TAG, "clip failed", e);
            playNext(c);
        }
    }
}
