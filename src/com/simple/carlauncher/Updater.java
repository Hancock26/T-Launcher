package com.simple.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * New-version notice: asks GitHub for the latest Release of Hancock26/T-Launcher (at most twice a day, only parked)
 * and, if it is newer, offers to download T-Launcher.apk and open the installer. The personal build never updates
 * itself, so the owner's car pictures are never replaced by the public version.
 */
final class Updater {

    private static final String API = "https://api.github.com/repos/Hancock26/T-Launcher/releases/latest";
    private static final long EVERY_MS = 12L * 60 * 60 * 1000;
    private static volatile boolean busy;

    private Updater() { }

    static String currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isPersonal(Context c) {
        return currentVersion(c).contains("personal");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    /** Automatic check (start of the app). manual = from Ajustes: always asks and reports "ya tienes la última". */
    static void check(final Activity a, final boolean manual) {
        if (busy) return;
        if (isPersonal(a)) {
            if (manual) Toast.makeText(a, "La versión personal no se actualiza sola (así no pierdes tu carro).", Toast.LENGTH_LONG).show();
            return;
        }
        final SharedPreferences p = prefs(a);
        if (!manual && System.currentTimeMillis() - p.getLong("last", 0) < EVERY_MS) return;
        busy = true;
        if (manual) Toast.makeText(a, "Buscando actualizaciones…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                String tag = null, notes = "", apk = null;
                try {
                    JSONObject j = new JSONObject(get(API));
                    tag = j.optString("tag_name", "");
                    notes = j.optString("body", "");
                    JSONArray as = j.optJSONArray("assets");
                    for (int i = 0; as != null && i < as.length(); i++) {
                        JSONObject o = as.getJSONObject(i);
                        if (o.optString("name", "").toLowerCase().endsWith(".apk")) { apk = o.optString("browser_download_url", null); break; }
                    }
                    p.edit().putLong("last", System.currentTimeMillis()).apply();
                } catch (Exception e) {
                    tag = null;
                }
                final String fTag = tag, fNotes = notes, fApk = apk;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (a.isFinishing()) return;
                        if (fTag == null) {
                            if (manual) Toast.makeText(a, "No se pudo revisar (¿hay internet?)", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String latest = fTag.replaceFirst("^[vV]", "");
                        boolean newer = compare(latest, currentVersion(a)) > 0 && fApk != null;
                        if (!newer) {
                            if (manual) Toast.makeText(a, "Ya tienes la última versión (" + currentVersion(a) + ")", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!manual && fTag.equals(p.getString("skip", ""))) return;
                        offer(a, fTag, latest, fNotes, fApk);
                    }
                });
            }
        }).start();
    }

    private static void offer(final Activity a, final String tag, final String latest, String notes, final String apk) {
        String body = notes == null ? "" : notes.replaceAll("[#*`>]", "").trim();
        if (body.length() > 500) body = body.substring(0, 500) + "…";
        new AlertDialog.Builder(a)
                .setTitle("Nueva versión de T-Launcher: " + latest)
                .setMessage("Tienes la " + currentVersion(a) + ". "
                        + (body.isEmpty() ? "" : "\n\nNovedades:\n" + body)
                        + "\n\nSe descarga aquí mismo y Android te pide confirmar la instalación. Tus ajustes se conservan.")
                .setPositiveButton("Descargar e instalar", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { download(a, tag, apk); }
                })
                .setNeutralButton("Omitir esta versión", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { prefs(a).edit().putString("skip", tag).apply(); }
                })
                .setNegativeButton("Después", null)
                .show();
    }

    private static void download(final Activity a, String tag, String url) {
        // Android 8+: T-Launcher needs "instalar apps desconocidas" once; without it the installer can't open.
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Permite a T-Launcher instalar apps y vuelve a tocar Descargar (Ajustes › Buscar actualizaciones).", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) { }
            prefs(a).edit().putLong("last", 0).apply(); // ask again next time
            return;
        }
        try {
            final DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("T-Launcher " + tag)
                    .setDescription("Descargando la nueva versión")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, "T-Launcher-" + tag + ".apk");
            final long id = dm.enqueue(r);
            final Context app = a.getApplicationContext();
            app.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try { app.unregisterReceiver(this); } catch (Exception ignored) { }
                    Uri u = dm.getUriForDownloadedFile(id);
                    if (u == null) { Toast.makeText(c, "La descarga falló. Intenta de nuevo.", Toast.LENGTH_LONG).show(); return; }
                    Intent inst = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(inst); } catch (Exception e) { Toast.makeText(c, "No se pudo abrir el instalador", Toast.LENGTH_LONG).show(); }
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            Toast.makeText(a, "Descargando T-Launcher " + tag + "…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // no DownloadManager on this radio: the browser can do it
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
        }
    }

    /** "16.10" > "16.9"; anything after a '-' (e.g. "-personal") is ignored. */
    static int compare(String x, String y) {
        String[] a = x.split("-")[0].split("\\."), b = y.split("-")[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int p = i < a.length ? num(a[i]) : 0, q = i < b.length ? num(b[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "T-Launcher");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
