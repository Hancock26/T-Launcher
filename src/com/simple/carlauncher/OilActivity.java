package com.simple.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Oil-change status and setup (dashboard km, last change, interval). */
public class OilActivity extends Activity {

    private static final int[] INTERVALS = {3000, 5000, 7500, 10000};

    private TextView remaining, next, current, last;
    private ProgressBar bar;
    private GradientDrawable statusBg;
    private final TextView[] intervalChips = new TextView[INTERVALS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackground(Ui.background(this));

        LinearLayout bar0 = new LinearLayout(this);
        bar0.setGravity(Gravity.CENTER_VERTICAL);
        bar0.addView(Ui.iconButton(this, R.drawable.ic_back, 48, false, new Runnable() { public void run() { finish(); } }),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, "Cambio de aceite", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), 0, 0, 0);
        bar0.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar0, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(12);
        root.addView(body, blp);
        body.addView(buildStatus(), new LinearLayout.LayoutParams(0, -1, 1.2f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, -1, 1f);
        alp.leftMargin = dp(12);
        body.addView(buildActions(), alp);

        setContentView(root);
    }

    private View buildStatus() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(28), dp(20), dp(28), dp(20));
        statusBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0x993FA9FF, 0x667B4DFF});
        statusBg.setCornerRadius(dp(18));
        card.setBackground(statusBg);

        TextView label = Ui.text(this, "FALTAN", 14, 0xCCFFFFFF);
        label.setLetterSpacing(0.15f);
        card.addView(label);
        remaining = Ui.text(this, "", 64, Color.WHITE);
        remaining.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        card.addView(remaining);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(14));
        lp.setMargins(0, dp(10), 0, dp(16));
        card.addView(bar, lp);

        next = Ui.text(this, "", 18, Color.WHITE);
        card.addView(next);
        current = Ui.text(this, "", 15, 0xB3FFFFFF);
        current.setPadding(0, dp(6), 0, 0);
        card.addView(current);
        last = Ui.text(this, "", 15, 0xB3FFFFFF);
        last.setPadding(0, dp(2), 0, 0);
        card.addView(last);
        return card;
    }

    private View buildActions() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(Ui.card(this));

        final OilActivity self = this;
        card.addView(action("Hice el cambio de aceite hoy", 0xFF2ECC71, new Runnable() { public void run() {
            Odometer.setLastChangeKm(self, Odometer.currentKm(self));
            Toast.makeText(self, "¡Listo! Registrado el cambio de aceite", Toast.LENGTH_SHORT).show();
            refresh();
        }}));
        card.addView(action("Ajustar kilometraje del tablero", 0x334F7BFF, new Runnable() { public void run() {
            askKm("Kilometraje actual del tablero", Odometer.currentKm(self), new KmListener() { public void onKm(int km) {
                boolean first = !Odometer.isConfigured(self);
                Odometer.setCurrentKm(self, km);
                if (first) Odometer.setLastChangeKm(self, km);
                refresh();
            }});
        }}));
        card.addView(action("Último cambio fue a los… km", 0x334F7BFF, new Runnable() { public void run() {
            askKm("Kilometraje del último cambio", Odometer.lastChangeKm(self), new KmListener() { public void onKm(int km) {
                Odometer.setLastChangeKm(self, km);
                refresh();
            }});
        }}));

        TextView every = Ui.text(this, "Cambiar cada", 15, 0xCCFFFFFF);
        every.setGravity(Gravity.START);
        every.setPadding(dp(4), dp(14), 0, dp(8));
        card.addView(every);
        LinearLayout chips = new LinearLayout(this);
        for (int i = 0; i < INTERVALS.length; i++) {
            int km = INTERVALS[i];
            TextView c = Ui.text(this, fmt(km), 15, Color.WHITE);
            c.setPadding(0, dp(12), 0, dp(12));
            Ui.pressable(c, 0.92f);
            c.setOnClickListener(Ui.click(new Runnable() { public void run() {
                Odometer.setIntervalKm(OilActivity.this, km);
                refresh();
            }}));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            chips.addView(c, lp);
            intervalChips[i] = c;
        }
        card.addView(chips);

        TextView note = Ui.text(this, "Los km se cuentan con el GPS de la radio. Ajusta el tablero de vez en cuando para mayor precisión.", 12, 0x80FFFFFF);
        note.setSingleLine(false);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        card.addView(note);
        return card;
    }

    private TextView action(String label, int color, Runnable r) {
        TextView b = Ui.text(this, label, 17, Color.WHITE);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        Ui.pressable(b, 0.96f);
        b.setOnClickListener(Ui.click(r));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    interface KmListener {
        void onKm(int km);
    }

    private void askKm(String title, int initial, KmListener l) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(initial > 0 ? String.valueOf(initial) : "");
        input.setSelectAllOnFocus(true);
        input.setTextSize(24);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Guardar", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            l.onKm(Integer.parseInt(input.getText().toString().trim()));
                        } catch (NumberFormatException ignored) { }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void refresh() {
        if (!Odometer.isConfigured(this)) {
            remaining.setText("—");
            next.setText("Configura el kilometraje del tablero");
            current.setText("");
            last.setText("");
            bar.setProgress(0);
        } else {
            int rem = Odometer.remainingKm(this), interval = Odometer.intervalKm(this);
            remaining.setText(rem >= 0 ? fmt(rem) + " km" : "¡Ya toca!");
            next.setText("Próximo cambio a los " + fmt(Odometer.lastChangeKm(this) + interval) + " km");
            current.setText("Kilometraje actual (aprox.): " + fmt(Odometer.currentKm(this)) + " km");
            last.setText("Último cambio: " + fmt(Odometer.lastChangeKm(this)) + " km");
            bar.setProgress(Math.max(0, Math.min(1000, (interval - rem) * 1000 / Math.max(1, interval))));
            statusBg.setColors(rem < 0 ? new int[]{0xCCE53935, 0x99B71C1C}
                    : rem <= Odometer.WARN_KM ? new int[]{0xCCFF9800, 0x99E65100}
                    : new int[]{0x993FA9FF, 0x667B4DFF});
        }
        for (int i = 0; i < INTERVALS.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(INTERVALS[i] == Odometer.intervalKm(this) ? 0xFF4F7BFF : 0x1FFFFFFF);
            intervalChips[i].setBackground(bg);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    static String fmt(int km) {
        return String.format(new Locale("es", "CO"), "%,d", km).replace(',', '.');
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
