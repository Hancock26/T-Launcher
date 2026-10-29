package com.simple.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared look (neon blue/purple) and small helpers for all screens. */
final class Ui {

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Drawable background(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2C8C, 0xFF20206E, 0xFF371764});
        GradientDrawable glowLeft = new GradientDrawable();
        glowLeft.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowLeft.setGradientRadius(dp(c, 520));
        glowLeft.setGradientCenter(0.15f, 0.1f);
        glowLeft.setColors(new int[]{0x553FA9FF, 0x003FA9FF});
        GradientDrawable glowRight = new GradientDrawable();
        glowRight.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowRight.setGradientRadius(dp(c, 560));
        glowRight.setGradientCenter(0.95f, 0.95f);
        glowRight.setColors(new int[]{0x66B04CFF, 0x00B04CFF});
        return new LayerDrawable(new Drawable[]{base, glowLeft, glowRight});
    }

    static GradientDrawable card(Context c) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4D5B6CFF, 0x333B3FB8});
        bg.setCornerRadius(dp(c, 18));
        bg.setStroke(dp(c, 1), 0x337F9CFF);
        return bg;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    /** Round icon button. filled = white disc with dark icon (primary action). */
    static ImageView iconButton(Context c, int icon, int sizeDp, boolean filled, Runnable action) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        int pad = Math.round(sizeDp * 0.24f);
        b.setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad));
        b.setBackground(circle(filled ? Color.WHITE : 0x26FFFFFF));
        b.setColorFilter(filled ? 0xFF2A1F6E : Color.WHITE);
        pressable(b, 0.88f);
        b.setOnClickListener(click(action));
        return b;
    }

    static void pressable(View v, float scale) {
        v.setClickable(true);
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(scale).scaleY(scale).alpha(0.8f).setDuration(90).start();
                } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
                }
                return false;
            }
        });
    }

    // Android has no java.lang.invoke.LambdaMetafactory and this project is built without
    // desugaring, so the code uses anonymous classes instead of lambdas (no "->" or "::").

    static View.OnClickListener click(final Runnable r) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        };
    }

    static View.OnLongClickListener longClick(final Runnable r) {
        return new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                r.run();
                return true;
            }
        };
    }

    static void launch(Context c, String component) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(ComponentName.unflattenFromString(component));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "No disponible", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Buttons that open an app chosen by the user ----------

    interface AppChosen {
        void onApp(String component); // null = "none"
    }

    /** Opens the app assigned to the button; the first time, asks which one. */
    static void openSlot(Activity a, String slot, String title) {
        String comp = Prefs.app(a, slot);
        if (comp == null) {
            pickApp(a, title, new AppChosen() {
                public void onApp(String chosen) {
                    Prefs.setApp(a, slot, chosen);
                    if (chosen != null) launch(a, chosen);
                }
            });
        } else {
            launch(a, comp);
        }
    }

    /** List of installed apps with icons; the first row clears the button. */
    static void pickApp(Activity a, String title, AppChosen cb) {
        PackageManager pm = a.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (!ri.activityInfo.packageName.equals(a.getPackageName())) apps.add(ri);
        }
        Collator collator = Collator.getInstance();
        Collections.sort(apps, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo x, ResolveInfo y) {
                return collator.compare(x.loadLabel(pm).toString(), y.loadLabel(pm).toString());
            }
        });

        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return apps.size() + 1; }
            public Object getItem(int p) { return p; }
            public long getItemId(int p) { return p; }
            public View getView(int p, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(a, 20), dp(a, 10), dp(a, 20), dp(a, 10));
                ImageView icon = new ImageView(a);
                TextView label = new TextView(a);
                label.setTextSize(18);
                label.setTextColor(Color.WHITE);
                label.setPadding(dp(a, 16), 0, 0, 0);
                if (p == 0) {
                    icon.setImageResource(R.drawable.ic_backspace);
                    icon.setColorFilter(0x99FFFFFF);
                    label.setText("Ninguna (dejar sin asignar)");
                } else {
                    ResolveInfo ri = apps.get(p - 1);
                    icon.setImageDrawable(ri.loadIcon(pm));
                    label.setText(ri.loadLabel(pm));
                }
                row.addView(icon, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
                row.addView(label);
                return row;
            }
        };
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setAdapter(adapter, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            cb.onApp(null);
                        } else {
                            ResolveInfo ri = apps.get(which - 1);
                            cb.onApp(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToString());
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** One-line text input dialog. */
    interface TextChosen {
        void onText(String s);
    }

    static void askText(Activity a, String title, String hint, String initial, int inputType, TextChosen cb) {
        android.widget.EditText input = new android.widget.EditText(a);
        input.setInputType(inputType);
        input.setHint(hint);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        input.setTextSize(24);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Guardar", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        cb.onText(input.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
