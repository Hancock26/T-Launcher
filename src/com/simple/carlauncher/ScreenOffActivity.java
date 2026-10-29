package com.simple.carlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Screen-off mode that survives steering-wheel keys. On NWD/Allwinner radios the backlight is
 * switched off; on other head units the screen goes black at minimum brightness.
 *
 * The factory screen-off uses the kernel "backlight switch", and the NWD kernel turns the
 * backlight back on (and swallows the key) on every key press. Here the LCD backlight is
 * switched off one level lower, so the kernel still thinks the screen is on and lets
 * next/previous/volume keys through. Touch wakes the screen; so do reverse gear and calls.
 */
public class ScreenOffActivity extends Activity {

    private static final String TAG = "ScreenOff";
    private boolean off;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        setContentView(black);

        IntentFilter f = new IntentFilter();
        f.addAction("com.android.action.ACTION_BACKCAR_STATE_CHANGE"); // reverse gear
        f.addAction("com.bt.ACTION_BT_INCOMING_CALL");
        f.addAction("com.bt.ACTION_BT_OUTGOING_CALL");
        f.addAction(Intent.ACTION_SCREEN_OFF); // ACC off / sleep
        registerReceiver(wake, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        off = setBacklight(false);
        if (!off) {
            // Other head units: just a black screen at minimum brightness.
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0f;
            getWindow().setAttributes(lp);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Anything covering this screen (reverse camera, call, another app) must be visible.
        setBacklight(true);
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(wake);
        setBacklight(true);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    /** android.os.Hardware is an NWD/Allwinner framework addition; call it reflectively. */
    static boolean setBacklight(boolean on) {
        try {
            Object r = Class.forName("android.os.Hardware")
                    .getMethod("setLcdBacklightEnable", int.class)
                    .invoke(null, on ? 1 : 0);
            Log.d(TAG, "setLcdBacklightEnable(" + (on ? 1 : 0) + ") -> " + r);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "backlight control unavailable", e);
            return false;
        }
    }
}
