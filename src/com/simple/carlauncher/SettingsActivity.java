package com.simple.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Launcher settings: name, plate, pico y placa, apps behind each button, voice, permissions. */
public class SettingsActivity extends Activity {

    static final String CAR_FILE = "car.png";
    static final String CAR_LIT_FILE = "car_lit.png";
    static final String CAR_TOP_FILE = "car_top.png";
    static final String CAR_TOP_LIT_FILE = "car_top_lit.png";
    static final String CAR_CHASE_FILE = "car_chase.png", CAR_CHASE_LIT_FILE = "car_chase_lit.png";
    private static final int PICK_IMAGE = 7, PICK_IMAGE_LIT = 8, PICK_PACK = 9;
    private static final int[] DAY_ORDER = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY};
    private static final String[] DAY_SHORT = {"L", "M", "X", "J", "V", "S"};

    private final List<Runnable> refreshers = new ArrayList<>();
    private TextView ppStatus;
    private final TextView[] dayChips = new TextView[DAY_ORDER.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        root.setBackground(Ui.background(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, 48, false, new Runnable() { public void run() { finish(); } }),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, "Ajustes", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        if (getIntent().getBooleanExtra("welcome", false)) {
            TextView w = note("¡Bienvenido a T-Launcher! Escribe tu nombre y tu placa, permite la ubicación "
                    + "(para el clima y el pico y placa) y elige qué app abre cada botón. Puedes volver aquí "
                    + "tocando el reloj o el engranaje.");
            w.setTextColor(Color.WHITE);
            w.setTextSize(15);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x403FA9FF);
            bg.setCornerRadius(dp(14));
            w.setBackground(bg);
            w.setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
            wl.topMargin = dp(8);
            root.addView(w, wl);
        }

        LinearLayout cols = new LinearLayout(this);
        cols.addView(scroll(buildLeft()), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1f);
        rl.leftMargin = dp(12);
        cols.addView(scroll(buildRight()), rl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1f);
        cl.topMargin = dp(8);
        root.addView(cols, cl);
        setContentView(root);

        if (getIntent().getBooleanExtra("welcome", false) && !hasLocation()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    // ---------- Left: car, plate and pico y placa ----------

    private View buildLeft() {
        final SettingsActivity a = this;
        LinearLayout col = column();
        col.addView(section("TU CARRO"));
        LinearLayout car = card();
        car.addView(row("Tu nombre", new Value() { public String get() {
            String n = Prefs.name(a);
            return n.isEmpty() ? "Escribir" : n;
        }}, new Runnable() { public void run() {
            Ui.askText(a, "¿Cómo te llamas?", "Nombre", Prefs.name(a),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, new Ui.TextChosen() {
                        public void onText(String s) {
                            Prefs.put(a, "name", s);
                            refresh();
                        }
                    });
        }}));
        car.addView(row("Placa", new Value() { public String get() {
            String p = Prefs.plate(a);
            int d = Prefs.plateDigit(p);
            return p.isEmpty() ? "Escribir" : d < 0 ? p : p + " · termina en " + d;
        }}, new Runnable() { public void run() {
            Ui.askText(a, "Placa del vehículo", "ABC123", Prefs.plate(a),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, new Ui.TextChosen() {
                        public void onText(String s) {
                            Prefs.put(a, "plate", s.replace(" ", "").replace("-", "").toUpperCase());
                            refresh();
                        }
                    });
        }}));
        car.addView(row("Importar paquete del carro (.zip)", new Value() { public String get() {
            return new File(getFilesDir(), CAR_CHASE_FILE).exists() || new File(getFilesDir(), CAR_TOP_FILE).exists() ? "Cargado · toca para cambiar" : "Créalo en el generador web";
        }}, new Runnable() { public void run() {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(i, "Paquete de imágenes del carro (.zip)"), PICK_PACK);
            } catch (Exception e) {
                Toast.makeText(a, "No hay app para elegir archivos", Toast.LENGTH_SHORT).show();
            }
        }}, new Runnable() { public void run() {
            for (String f : new String[]{CAR_FILE, CAR_LIT_FILE, CAR_TOP_FILE, CAR_TOP_LIT_FILE, CAR_CHASE_FILE, CAR_CHASE_LIT_FILE}) new File(getFilesDir(), f).delete();
            Toast.makeText(a, "Imágenes del carro borradas", Toast.LENGTH_SHORT).show();
            refresh();
        }}));
        car.addView(row("Foto del carro", new Value() { public String get() {
            return new File(getFilesDir(), CAR_FILE).exists()
                    ? "Personalizada (mantén para quitar)" : "Genérica · toca para elegir";
        }}, new Runnable() { public void run() { pickImage(PICK_IMAGE); } }, new Runnable() { public void run() {
            new File(getFilesDir(), CAR_FILE).delete();
            Toast.makeText(a, "Se usará el carro genérico", Toast.LENGTH_SHORT).show();
            refresh();
        }}));
        car.addView(row("Foto con luces encendidas", new Value() { public String get() {
            return new File(getFilesDir(), CAR_LIT_FILE).exists()
                    ? "Lista (mantén para quitar)" : "Opcional · toca para elegir";
        }}, new Runnable() { public void run() { pickImage(PICK_IMAGE_LIT); } }, new Runnable() { public void run() {
            new File(getFilesDir(), CAR_LIT_FILE).delete();
            refresh();
        }}));
        car.addView(row("Detectar luces del carro", new Value() { public String get() {
            return (Lights.isOn(a) ? "Encendidas · " : "Apagadas · ")
                    + (Lights.isConfigured(a) ? "configurado" : "toca para configurar");
        }}, new Runnable() { public void run() { calibrateStep1(); } }, new Runnable() { public void run() {
            Lights.clear(a);
            Toast.makeText(a, "Detección de luces borrada", Toast.LENGTH_SHORT).show();
            refresh();
        }}));
        car.addView(row("Color del carro", new Value() { public String get() {
            String p = Prefs.get(a).getString("paint", "plata");
            return p.substring(0, 1).toUpperCase() + p.substring(1);
        }}, new Runnable() { public void run() { choosePaint(); } }));
        car.addView(row("Mapa en la pantalla", new Value() { public String get() {
            return "app".equals(Prefs.get(a).getString("map_mode", "map"))
                    ? "Apagado · uso mi app de mapas" : "Mapa integrado";
        }}, new Runnable() { public void run() { chooseMapMode(); } }));
        car.addView(row("Tema de la pantalla", new Value() { public String get() {
            String t = Prefs.get(a).getString("theme", "auto");
            return "dark".equals(t) ? "Siempre oscuro" : "light".equals(t) ? "Siempre claro" : "Automático (día / noche / luces)";
        }}, new Runnable() { public void run() { chooseTheme(); } }));
        car.addView(row("Kilometraje y aceite", new Value() { public String get() {
            return Odometer.isConfigured(a) ? OilActivity.fmt(Odometer.currentKm(a)) + " km" : "Configurar";
        }}, new Runnable() { public void run() { startActivity(new Intent(a, OilActivity.class)); } }));
        col.addView(car);

        col.addView(section("PICO Y PLACA"));
        LinearLayout pp = card();
        ppStatus = Ui.text(this, "", 16, Color.WHITE);
        ppStatus.setSingleLine(false);
        ppStatus.setGravity(Gravity.START);
        ppStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0x26FFFFFF);
        sbg.setCornerRadius(dp(12));
        ppStatus.setBackground(sbg);
        pp.addView(ppStatus, new LinearLayout.LayoutParams(-1, -2));
        refreshers.add(new Runnable() { public void run() {
            PicoPlaca.Status s = PicoPlaca.today(a);
            ppStatus.setText(s.headline() + (s.city.isEmpty() ? "" : " · " + s.city) + "\n" + s.detail()
                    + (s.hint != null ? "\n" + s.hint : "")
                    + (s.stale ? "\n⚠ La rotación pudo cambiar: verifica o usa el modo manual" : ""));
        }});

        pp.addView(row("Ciudad", new Value() { public String get() { return cityLabel(); } },
                new Runnable() { public void run() { chooseCity(); } }));
        pp.addView(row("Tipo de carro (México y Chile)", new Value() { public String get() { return classLabel(); } },
                new Runnable() { public void run() { chooseClass(); } }));
        TextView daysLabel = note("Mis días de pico y placa (modo manual o ciudades sin datos):");
        daysLabel.setPadding(dp(4), dp(10), dp(4), dp(6));
        pp.addView(daysLabel);
        LinearLayout days = new LinearLayout(this);
        for (int i = 0; i < DAY_ORDER.length; i++) {
            final int bit = 1 << DAY_ORDER[i];
            TextView chip = Ui.text(this, DAY_SHORT[i], 17, Color.WHITE);
            chip.setPadding(0, dp(10), 0, dp(10));
            Ui.pressable(chip, 0.9f);
            chip.setOnClickListener(Ui.click(new Runnable() { public void run() {
                Prefs.get(a).edit().putInt("pp_days", Prefs.ppManualDays(a) ^ bit).apply();
                refresh();
            }}));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            days.addView(chip, lp);
            dayChips[i] = chip;
        }
        pp.addView(days);
        pp.addView(row("Mi horario", new Value() { public String get() {
            int[][] h = PicoRules.parseHours(Prefs.ppManualHours(a));
            return h == null ? "Inválido" : PicoRules.hoursText(h);
        }}, new Runnable() { public void run() {
            Ui.askText(a, "Horario (ej: 6-21  ó  7-9, 18-20)", "6-21", Prefs.ppManualHours(a),
                    InputType.TYPE_CLASS_TEXT, new Ui.TextChosen() {
                        public void onText(String s) {
                            if (PicoRules.parseHours(s) == null) {
                                Toast.makeText(a, "Formato no válido. Ejemplo: 6-21 ó 7-9, 18-20", Toast.LENGTH_LONG).show();
                            } else {
                                Prefs.put(a, "pp_hours", s);
                            }
                            refresh();
                        }
                    });
        }}));
        pp.addView(note("Reglas incluidas (oct. 2026). Colombia: Bogotá, Medellín y Valle de Aburrá, Cali, Bucaramanga, "
                + "Cartagena y Manizales. México: Hoy No Circula (CDMX y área metropolitana, según tu holograma). "
                + "Chile: restricción de Santiago (mayo a agosto; marca tus días según el calendario del año y tu sello verde). "
                + "Ecuador: Quito. Buenos Aires y Lima: sin restricción por placa. Las reglas cambian: si no coinciden, "
                + "usa el modo manual. Sin restricción en festivos (excepto el Hoy No Circula)."));
        col.addView(pp);
        return col;
    }

    private String cityLabel() {
        String mode = Prefs.ppMode(this);
        if (Prefs.PP_OFF.equals(mode)) return "Apagado";
        if (Prefs.PP_MANUAL.equals(mode)) return "Manual";
        if (mode.startsWith("city:")) {
            PicoRules.City c = PicoRules.byId(mode.substring(5));
            return c == null ? "Automática (GPS)" : c.name;
        }
        PicoRules.City c = PicoPlaca.detectCity(this);
        return "Automática (GPS)" + (c == null ? "" : " · " + c.name);
    }

    private static final String[] CLASS_VALUES = {"", "h00", "h0", "h1", "h2", "cl_new", "cl_old", "cl_none"};
    private static final String[] CLASS_LABELS = {"No aplica (Colombia y otros países)", "México: holograma 00", "México: holograma 0",
            "México: holograma 1", "México: holograma 2", "Chile: sello verde, inscrito desde sep. 2011",
            "Chile: sello verde, inscrito antes de sep. 2011", "Chile: sin sello verde"};

    private String classLabel() {
        int i = java.util.Arrays.asList(CLASS_VALUES).indexOf(Prefs.get(this).getString("pp_class", ""));
        return i <= 0 ? "No aplica" : CLASS_LABELS[i];
    }

    private void chooseClass() {
        int checked = Math.max(0, java.util.Arrays.asList(CLASS_VALUES).indexOf(Prefs.get(this).getString("pp_class", "")));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Tipo de carro")
                .setSingleChoiceItems(CLASS_LABELS, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Prefs.put(SettingsActivity.this, "pp_class", CLASS_VALUES[which]);
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void chooseCity() {
        List<String> labels = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        labels.add("Automática por GPS");
        values.add(Prefs.PP_AUTO);
        for (PicoRules.City c : PicoRules.CITIES) {
            if (c.rule == null) continue;
            labels.add(c.name);
            values.add("city:" + c.id);
        }
        labels.add("Manual (yo elijo días y horario)");
        values.add(Prefs.PP_MANUAL);
        labels.add("Apagado");
        values.add(Prefs.PP_OFF);
        int checked = Math.max(0, values.indexOf(Prefs.ppMode(this)));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Ciudad para la restricción por placa")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Prefs.put(SettingsActivity.this, "pp_mode", values.get(which));
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Right: buttons, voice, permissions ----------

    private View buildRight() {
        final SettingsActivity a = this;
        LinearLayout col = column();
        col.addView(section("BOTONES"));
        LinearLayout apps = card();
        apps.addView(slotRow("Radio FM", Prefs.APP_RADIO));
        apps.addView(slotRow("Bluetooth / teléfono", Prefs.APP_PHONE));
        apps.addView(slotRow("Música", Prefs.APP_MUSIC));
        apps.addView(slotRow("Mapas / navegación", Prefs.APP_MAPS));
        apps.addView(addrRow("Dirección de Casa", "addr_home"));
        apps.addView(addrRow("Dirección de Trabajo", "addr_work"));
        apps.addView(slotRow("Ecualizador / sonido", Prefs.APP_EQ));
        apps.addView(note("También puedes mantener presionado un botón de la barra inferior para cambiar su app."));
        col.addView(apps);

        col.addView(section("VOZ"));
        LinearLayout voice = card();
        voice.addView(row("Saludo al encender el carro", new Value() { public String get() {
            return Prefs.voice(a) ? "Activado" : "Desactivado";
        }}, new Runnable() { public void run() {
            Prefs.get(a).edit().putBoolean("voice", !Prefs.voice(a)).apply();
            refresh();
        }}));
        voice.addView(row("Probar el saludo ahora", new Value() { public String get() { return "▶"; } },
                new Runnable() { public void run() {
                    startForegroundService(new Intent(a, CarService.class).setAction(CarService.ACTION_GREET_NOW));
                }}));
        voice.addView(row("Indicaciones de navegación por voz", new Value() { public String get() {
            return Prefs.get(a).getBoolean("nav_voice", true) ? "Activadas" : "Desactivadas";
        }}, new Runnable() { public void run() {
            Prefs.get(a).edit().putBoolean("nav_voice", !Prefs.get(a).getBoolean("nav_voice", true)).apply();
            refresh();
        }}));
        voice.addView(row("Probar la voz de navegación", new Value() { public String get() { return "▶"; } },
                new Runnable() { public void run() { testNavVoice(); } }));
        voice.addView(row("Voz en español latino", new Value() { public String get() { return "Instalar / elegir ›"; } },
                new Runnable() { public void run() {
                    try {
                        startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                    }
                }}));
        voice.addView(note("Dice el clima, el pico y placa del día, el aviso de aceite y recuerda el cinturón. Solo usa voces de "
                + "Latinoamérica (Colombia, México, Estados Unidos…), nunca la de España. Si al probar suena española, el radio no "
                + "tiene una voz latina: instala SherpaTTS (gratis, F-Droid), descarga en ella la voz \"es_MX\" y elígela como motor "
                + "en \"Voz en español latino\". También sirve Servicios de voz de Google con \"Español (Estados Unidos)\"."));
        col.addView(voice);

        col.addView(section("PERMISOS"));
        LinearLayout perms = card();
        perms.addView(row("Ubicación (clima, ciudad, km)", new Value() { public String get() {
            return hasLocation() ? "✓ Permitida" : "Toca para permitir";
        }}, new Runnable() { public void run() {
            if (!hasLocation()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            } else {
                openAppDetails(a);
            }
        }}));
        perms.addView(row("Ver la música que suena", new Value() { public String get() {
            return NowPlaying.hasAccess(a) ? "✓ Permitido" : "Toca para permitir";
        }}, new Runnable() { public void run() { openNotificationAccess(a); } }));
        perms.addView(row("Usar como pantalla de inicio", new Value() { public String get() {
            return isDefaultHome() ? "✓ Predeterminado" : "Toca para elegir";
        }}, new Runnable() { public void run() {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }}));
        perms.addView(note("Si Android no te deja activar \"Ver la música\" (ajuste restringido): Ajustes › Apps › "
                + "T-Launcher › menú ⋮ › \"Permitir ajustes restringidos\" y vuelve a intentarlo."));
        col.addView(perms);

        col.addView(section("CRÉDITOS"));
        LinearLayout cred = card();
        cred.addView(note("T-Launcher v16.4 · creado por Jesus Torregroza (Colombia) · proyecto personal, gratuito y sin fines comerciales. "
                + "No es un producto de Tesla, Inc. ni de General Motors / Chevrolet."));
        cred.addView(note("• Basado en CleanLauncher de Jhon Jairo (github.com/jhontandazo008/CleanLauncher), el launcher original para radios Android."));
        cred.addView(note("• Diseño de la interfaz basado en \"Tesla Dashboard UI Component Library\" de Nami (Figma Community, CC BY 4.0)."));
        cred.addView(note("• Carro 3D de la versión personal: \"2012 Chevrolet Spark GT 1.2 LT\" de Ddiaz Design (Sketchfab, CC BY-NC-SA 4.0)."));
        cred.addView(note("• Mapas © colaboradores de OpenStreetMap, servidos por OpenFreeMap · Rutas: OSRM · Búsqueda: Photon y Nominatim · "
                + "Clima: Open-Meteo · Letra Inter (SIL OFL)."));
        col.addView(cred);
        col.addView(section("ACTUALIZACIONES"));
        LinearLayout upd = card();
        upd.addView(row("Buscar actualizaciones", new Value() { public String get() {
            return "Versión " + Updater.currentVersion(a) + (Updater.isPersonal(a) ? " (personal)" : "");
        }}, new Runnable() { public void run() { Updater.check(a, true); } }));
        upd.addView(note("La app revisa sola cada día si hay una versión nueva y te avisa con el carro detenido."));
        col.addView(upd);
        TextView version = note("T-Launcher v16.4 · por Jesus Torregroza");
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(16), 0, dp(24));
        col.addView(version);
        return col;
    }

    private View addrRow(final String label, final String key) {
        final SettingsActivity a = this;
        return row(label, new Value() { public String get() {
            String v = Prefs.get(a).getString(key, "");
            return v.isEmpty() ? "Escribir" : v;
        }}, new Runnable() { public void run() {
            Ui.askText(a, label, "Ej: Calle 80 # 50-20, Barranquilla", Prefs.get(a).getString(key, ""),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, new Ui.TextChosen() {
                        public void onText(String s) {
                            Prefs.put(a, key, s);
                            refresh();
                        }
                    });
        }});
    }

    private View slotRow(final String label, final String slot) {
        final SettingsActivity a = this;
        return row(label, new Value() { public String get() {
            return Prefs.appLabel(a, Prefs.app(a, slot));
        }}, new Runnable() { public void run() {
            Ui.pickApp(a, label, new Ui.AppChosen() {
                public void onApp(String c) {
                    Prefs.setApp(a, slot, c);
                    refresh();
                }
            });
        }});
    }

    // ---------- Car photo ----------

    private void pickImage(int request) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, request == PICK_IMAGE_LIT
                    ? "Foto del carro con las luces encendidas" : "Foto de tu carro (mejor PNG sin fondo)"), request);
        } catch (Exception e) {
            Toast.makeText(this, "No hay app para elegir imágenes", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_PACK && result == RESULT_OK && data != null && data.getData() != null) {
            importPack(data.getData());
            refresh();
            return;
        }
        if ((request != PICK_IMAGE && request != PICK_IMAGE_LIT) || result != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1400) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o2);
            }
            if (b == null) throw new IllegalStateException("not an image");
            String name = request == PICK_IMAGE_LIT ? CAR_LIT_FILE : CAR_FILE;
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Toast.makeText(this, "¡Listo! Foto guardada", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    /** Extracts car.png, car_lit.png, car_top.png and car_top_lit.png from a .zip (any folder inside it). */
    private void importPack(Uri uri) {
        java.util.Set<String> wanted = new java.util.HashSet<>(java.util.Arrays.asList(
                CAR_FILE, CAR_LIT_FILE, CAR_TOP_FILE, CAR_TOP_LIT_FILE, CAR_CHASE_FILE, CAR_CHASE_LIT_FILE));
        int n = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                name = name.substring(name.lastIndexOf('/') + 1).toLowerCase();
                if (e.isDirectory() || !wanted.contains(name)) continue;
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
                    for (int r; (r = zip.read(buf)) > 0; ) out.write(buf, 0, r);
                }
                n++;
            }
        } catch (Exception ex) {
            n = -1;
        }
        Toast.makeText(this, n > 0 ? "¡Listo! " + n + " imágenes del carro cargadas"
                : "Ese archivo no es un paquete de T-Launcher (car.png, car_chase.png…)", Toast.LENGTH_LONG).show();
    }

    // ---------- Theme ----------

    private void chooseTheme() {
        final String[] values = {"auto", "dark", "light"};
        String[] labels = {"Automático (oscuro de noche o con las luces)", "Siempre oscuro", "Siempre claro"};
        int checked = java.util.Arrays.asList(values).indexOf(Prefs.get(this).getString("theme", "auto"));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Tema de la pantalla")
                .setSingleChoiceItems(labels, Math.max(0, checked), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Prefs.put(SettingsActivity.this, "theme", values[which]);
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void choosePaint() {
        final String[] values = {"plata", "blanco", "negro", "gris", "rojo", "azul", "amarillo", "naranja", "verde"};
        String[] labels = {"Plata", "Blanco", "Negro", "Gris", "Rojo", "Azul", "Amarillo", "Naranja", "Verde"};
        int checked = java.util.Arrays.asList(values).indexOf(Prefs.get(this).getString("paint", "plata"));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Color del carro")
                .setSingleChoiceItems(labels, Math.max(0, checked), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Prefs.put(SettingsActivity.this, "paint", values[which]);
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void chooseMapMode() {
        final String[] values = {"map", "app"};
        String[] labels = {"Mapa integrado (gratis, sin cuenta)",
                "Sin mapa: botón grande para abrir mi app (Waze, Google Maps…)"};
        int checked = "app".equals(Prefs.get(this).getString("map_mode", "map")) ? 1 : 0;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Mapa en la pantalla")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Prefs.put(SettingsActivity.this, "map_mode", values[which]);
                        d.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Headlights calibration ----------

    private java.util.Map<String, String> snapOff;

    private void calibrateStep1() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Detectar luces · paso 1 de 3")
                .setMessage("Con el carro encendido, APAGA todas las luces (también las de posición) y toca Siguiente.")
                .setPositiveButton("Siguiente", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        snapOff = Lights.snapshot(SettingsActivity.this);
                        calibrateStep2();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void calibrateStep2() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Detectar luces · paso 2 de 3")
                .setMessage("Ahora ENCIENDE las luces (de posición o bajas), espera 2 segundos y toca Siguiente.")
                .setPositiveButton("Siguiente", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        java.util.Map<String, String> on = Lights.snapshot(SettingsActivity.this);
                        calibrateChoose(Lights.diff(snapOff, on));
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void calibrateChoose(final List<Lights.Change> changes) {
        if (changes.isEmpty()) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("No se detectó ningún cambio")
                    .setMessage("El radio no publicó ninguna señal al encender las luces. Puede que el cable de "
                            + "iluminación (ILL) no esté conectado, o que tu radio la maneje por dentro. Puedes usar el "
                            + "tema automático por hora del día.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        final int n = Math.min(changes.size(), 8);
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = changes.get(i).label();
        final int[] picked = {0};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Señal encontrada (la primera es la más probable)")
                .setSingleChoiceItems(labels, 0, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { picked[0] = which; }
                })
                .setPositiveButton("Usar esta", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Lights.save(SettingsActivity.this, changes.get(picked[0]));
                        calibrateStep3();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void calibrateStep3() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Detectar luces · paso 3 de 3")
                .setMessage("Para confirmar, APAGA las luces otra vez y toca Comprobar.")
                .setPositiveButton("Comprobar", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = !Lights.isOn(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this, ok
                                ? "¡Listo! La pantalla mostrará las luces encendidas cuando las prendas."
                                : "La señal no volvió a apagado. Repite la detección y elige otra opción de la lista.",
                                Toast.LENGTH_LONG).show();
                        refresh();
                    }
                })
                .show();
    }

    // ---------- Permissions helpers ----------

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    static void openNotificationAccess(Activity a) {
        Toast.makeText(a, "Activa \"Inicio\" en la lista", Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(a, MediaListener.class).flattenToString());
            a.startActivity(i);
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e2) {
                openAppDetails(a);
            }
        }
    }

    static void openAppDetails(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (hasLocation()) CarService.start(this);
        refresh();
    }

    // ---------- Building blocks ----------

    interface Value {
        String get();
    }

    private View row(String label, Value value, Runnable action) {
        return row(label, value, action, null);
    }

    private View row(String label, Value value, Runnable action, Runnable longAction) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(14), dp(12), dp(14));
        TextView l = Ui.text(this, label, 16, Color.WHITE);
        l.setGravity(Gravity.START);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(this, "", 15, 0xFF9FC6FF);
        v.setGravity(Gravity.END);
        v.setPadding(dp(8), 0, 0, 0);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1.1f));
        refreshers.add(new Runnable() { public void run() { v.setText(value.get() + "  ›"); } });
        Ui.pressable(r, 0.98f);
        r.setOnClickListener(Ui.click(action));
        if (longAction != null) r.setOnLongClickListener(Ui.longClick(longAction));
        return r;
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, s, 13, 0xB3FFFFFF);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.START);
        t.setPadding(dp(6), dp(14), 0, dp(6));
        return t;
    }

    private TextView note(String s) {
        TextView t = Ui.text(this, s, 12, 0x99FFFFFF);
        t.setSingleLine(false);
        t.setGravity(Gravity.START);
        t.setPadding(dp(12), dp(6), dp(12), dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(8), dp(8), dp(8), dp(8));
        c.setBackground(Ui.card(this));
        return c;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        s.addView(content, new ScrollView.LayoutParams(-1, -2));
        return s;
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
        int mask = Prefs.ppManualDays(this);
        for (int i = 0; i < DAY_ORDER.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor((mask & (1 << DAY_ORDER[i])) != 0 ? 0xFFFF9800 : 0x1FFFFFFF);
            dayChips[i].setBackground(bg);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
    private android.speech.tts.TextToSpeech testTts;

    private void testNavVoice() {
        if (testTts != null) testTts.shutdown();
        testTts = new android.speech.tts.TextToSpeech(getApplicationContext(), new android.speech.tts.TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    Toast.makeText(SettingsActivity.this, "El radio no tiene motor de voz", Toast.LENGTH_LONG).show();
                    return;
                }
                boolean latin = Voice.pickLatin(testTts);
                if (!latin) Voice.pickAnySpanish(testTts);
                String loc = "";
                try { loc = testTts.getVoice().getLocale().getDisplayName(new java.util.Locale("es")); } catch (Exception ignored) { }
                Toast.makeText(SettingsActivity.this, latin ? "Voz latina: " + loc
                        : "No hay voz latina instalada; suena " + loc + ". Instala una (ver nota).", Toast.LENGTH_LONG).show();
                testTts.speak("En trescientos metros, gira a la derecha hacia la Calle setenta y dos.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "t");
            }
        });
    }

}
