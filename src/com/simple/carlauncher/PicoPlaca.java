package com.simple.carlauncher;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import java.util.Calendar;

/** Today's pico y placa for this user: city (GPS, chosen or manual) + plate digit. */
final class PicoPlaca {

    enum State { NO_PLATE, OFF, NO_CITY, NO_DATA, NO_DAYS, FREE, HOLIDAY, RESTRICTED, DONE_TODAY }

    static final class Status {
        State state;
        String city = "";           // shown name
        boolean stale;              // data past its known validity date
        boolean manual;
        int digit = -1;
        PicoRules.Result result;
        String label = "pico y placa";   // "Hoy No Circula", "restricción vehicular"…
        String country = "CO";
        String hint;                     // extra line (vehicle type missing, season…)

        /** Short headline for the home card. */
        String headline() {
            switch (state) {
                case NO_PLATE: return "Escribe tu placa";
                case OFF: return "Pico y placa apagado";
                case NO_CITY: return "Buscando tu ciudad…";
                case NO_DATA: return "Sin datos de " + city;
                case NO_DAYS: return "Elige tus días";
                case HOLIDAY: return "Hoy es festivo";
                case DONE_TODAY: return "Ya terminó por hoy";
                case FREE: return "Hoy puedes circular";
                default: return result.activeNow ? "¡Tienes " + label + " ahora!" : "Hoy tienes " + label;
            }
        }

        /** Second line with hours or the next restricted day. */
        String detail() {
            switch (state) {
                case NO_PLATE: return "Toca para configurar";
                case OFF: return "Actívalo en Ajustes";
                case NO_CITY: return "Activa la ubicación o elige la ciudad";
                case NO_DATA: return "Configura tus días en Ajustes";
                case NO_DAYS: return "Modo manual: marca días y horario";
                case RESTRICTED: return PicoRules.hoursText(result.today);
                default: return next();
            }
        }

        String next() {
            if (result == null || result.next == null) return "Sin restricción próxima";
            Calendar n = result.next;
            return "Próximo: " + PicoRules.dayName(n) + " " + n.get(Calendar.DAY_OF_MONTH);
        }

        /** Spoken sentence for the welcome message, or null when there is nothing to say. */
        String spoken() {
            if (state == State.RESTRICTED) {
                return "Hoy tienes " + label + " " + PicoRules.spokenHours(result.today) + ".";
            }
            return null;
        }
    }

    private PicoPlaca() { }

    static Status today(Context c) {
        Status s = new Status();
        String mode = Prefs.ppMode(c);
        s.digit = Prefs.plateDigit(c);
        if (Prefs.PP_OFF.equals(mode)) {
            s.state = State.OFF;
            return s;
        }
        if (s.digit < 0 && !Prefs.PP_MANUAL.equals(mode)) {
            s.state = State.NO_PLATE;
            return s;
        }

        PicoRules.Rule rule = null;
        if (Prefs.PP_MANUAL.equals(mode)) {
            rule = manualRule(c);
            s.manual = true;
            if (rule == null) {
                s.state = State.NO_DAYS;
                return s;
            }
            s.city = "Mi horario";
        } else {
            PicoRules.City city = mode.startsWith("city:") ? PicoRules.byId(mode.substring(5)) : detectCity(c);
            if (city == null) {
                // Outside the known cities: fall back to the manual schedule if the user made one.
                PicoRules.Rule manual = manualRule(c);
                if (manual != null) {
                    rule = manual;
                    s.manual = true;
                    s.city = "Mi horario";
                } else {
                    s.state = hasLocation(c) ? State.NO_DATA : State.NO_CITY;
                    s.city = "tu ciudad";
                    return s;
                }
            } else {
                s.city = city.name;
                s.label = city.label;
                s.country = city.country;
                rule = city.rule;
                PicoRules.vclass = Prefs.get(c).getString("pp_class", "");
                if (rule instanceof PicoRules.HoyNoCircula && PicoRules.vclass.isEmpty())
                    s.hint = "Elige tu holograma en Ajustes › Tipo de carro";
                if (rule instanceof PicoRules.SantiagoSeason) {
                    // Digits change every year: the user's days inside the May–August season.
                    rule = new PicoRules.SantiagoSeason(manualRule(c));
                    if (PicoRules.vclass.isEmpty()) s.hint = "Elige tu sello verde en Ajustes › Tipo de carro";
                    else if (!"cl_new".equals(PicoRules.vclass) && manualRule(c) == null
                            && PicoRules.SantiagoSeason.inSeason(Calendar.getInstance()))
                        s.hint = "Marca tus días según el calendario de este año";
                    else if (!PicoRules.SantiagoSeason.inSeason(Calendar.getInstance()))
                        s.hint = "Temporada de restricción: 1 de mayo al 31 de agosto";
                }
                if (rule == PicoRules.NONE) s.hint = "En " + city.name + " no hay restricción por placa";
                if (rule == null) rule = manualRule(c);
                if (rule == null) {
                    s.state = State.NO_DATA;
                    return s;
                }
                s.manual = city.rule == null;
                s.stale = city.rule != null && city.validUntil != null
                        && Calendar.getInstance().after(city.validUntil);
            }
        }
        if (rule == null) {
            s.state = State.NO_DATA;
            return s;
        }

        s.result = PicoRules.evaluate(rule, Math.max(0, s.digit), Calendar.getInstance(), s.country);
        s.state = s.result.today != null ? State.RESTRICTED
                : s.result.holiday != null ? State.HOLIDAY : State.FREE;
        if (s.state == State.RESTRICTED && !s.result.activeNow) {
            Calendar now = Calendar.getInstance();
            int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            if (min >= s.result.today[s.result.today.length - 1][1]) s.state = State.DONE_TODAY;
        }
        return s;
    }

    static PicoRules.Rule manualRule(Context c) {
        int days = Prefs.ppManualDays(c);
        int[][] hours = PicoRules.parseHours(Prefs.ppManualHours(c));
        return days == 0 || hours == null ? null : new PicoRules.ManualRule(days, hours);
    }

    /** City around the last known position (saved by CarService or from Android). */
    static PicoRules.City detectCity(Context c) {
        double[] p = lastPosition(c);
        return p == null ? null : PicoRules.locate(p[0], p[1]);
    }

    static boolean hasLocation(Context c) {
        return lastPosition(c) != null;
    }

    static double[] lastPosition(Context c) {
        Location best = null;
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            for (String prov : lm.getAllProviders()) {
                try {
                    Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Exception ignored) { }
            }
        }
        if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        SharedPreferences p = Prefs.get(c);
        if (p.contains("last_lat")) {
            return new double[]{p.getFloat("last_lat", 0), p.getFloat("last_lon", 0)};
        }
        return null;
    }

    static void savePosition(Context c, double lat, double lon) {
        Prefs.get(c).edit().putFloat("last_lat", (float) lat).putFloat("last_lon", (float) lon).apply();
    }
}
