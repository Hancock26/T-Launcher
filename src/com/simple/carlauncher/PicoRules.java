package com.simple.carlauncher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * "Pico y placa" rules for private cars in Colombian cities (pure Java, no Android).
 *
 * Cities rotate their digits every quarter/semester, so every city carries the date until which
 * its data is known to be valid; after that the app still shows it but flags it as possibly
 * outdated and invites the user to switch to manual mode. No restriction on Colombian holidays.
 *
 * Data checked on 25 Oct 2026 against local press / mayors' offices.
 */
final class PicoRules {

    private PicoRules() { }

    // ---------- Rules ----------

    /** Restricted time ranges (minutes from midnight) for that plate digit on that day, or null. */
    interface Rule {
        int[][] ranges(Calendar day, int digit);
    }

    /** Fixed digits per weekday (Mon..Fri), same hours every day. */
    static final class WeekdayRule implements Rule {
        final int[][] digits; // index 0 = Monday … 4 = Friday
        final int[][] hours;
        Rule saturday;

        WeekdayRule(int[][] hours, int[] mon, int[] tue, int[] wed, int[] thu, int[] fri) {
            this.hours = hours;
            this.digits = new int[][]{mon, tue, wed, thu, fri};
        }

        public int[][] ranges(Calendar day, int digit) {
            int dow = day.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY) return saturday == null ? null : saturday.ranges(day, digit);
            if (dow == Calendar.SUNDAY) return null;
            for (int d : digits[dow - Calendar.MONDAY]) if (d == digit) return hours;
            return null;
        }
    }

    /** Bogotá: odd days of the month → plates 1-5; even days → 6-0. Monday to Friday. */
    static final class ParityRule implements Rule {
        final int[][] hours;

        ParityRule(int[][] hours) {
            this.hours = hours;
        }

        public int[][] ranges(Calendar day, int digit) {
            int dow = day.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return null;
            boolean oddDay = day.get(Calendar.DAY_OF_MONTH) % 2 == 1;
            boolean low = digit >= 1 && digit <= 5;
            return oddDay == low ? hours : null;
        }
    }

    /** Saturdays rotating through pairs (1-2, 3-4, 5-6, 7-8, 9-0) week after week (Bucaramanga). */
    static final class SaturdayCycle implements Rule {
        final int[][] hours;
        final Calendar reference;
        final int referencePair; // 0 = 1-2, 1 = 3-4, 2 = 5-6, 3 = 7-8, 4 = 9-0

        SaturdayCycle(int[][] hours, int y, int m, int d, int referencePair) {
            this.hours = hours;
            this.reference = date(y, m, d);
            this.referencePair = referencePair;
        }

        public int[][] ranges(Calendar day, int digit) {
            long weeks = Math.round((dayNumber(day) - dayNumber(reference)) / 7.0);
            int pair = (int) (((referencePair + weeks) % 5 + 5) % 5);
            int a = pair * 2 + 1, b = (pair * 2 + 2) % 10;
            return digit == a || digit == b ? hours : null;
        }
    }

    /** Manual mode: the user picks the weekdays and the hours. */
    static final class ManualRule implements Rule {
        final int daysMask;
        final int[][] hours;

        ManualRule(int daysMask, int[][] hours) {
            this.daysMask = daysMask;
            this.hours = hours;
        }

        public int[][] ranges(Calendar day, int digit) {
            return (daysMask & (1 << day.get(Calendar.DAY_OF_WEEK))) != 0 ? hours : null;
        }
    }

    /**
     * Vehicle class chosen in Ajustes, needed where the rule depends on the car and not only on the plate:
     * México "h00", "h0", "h1", "h2" (holograma); Chile "cl_new" (sello verde desde sep. 2011),
     * "cl_old" (sello verde antes), "cl_none" (sin sello verde). Empty = not set.
     */
    static volatile String vclass = "";

    /**
     * México, Hoy No Circula (CDMX y municipios conurbados), 5:00–22:00.
     * Mon 5-6, Tue 7-8, Wed 3-4, Thu 1-2, Fri 9-0 for hologramas 1 and 2. Saturdays: holograma 2 always;
     * holograma 1 odd plates on the 1st and 3rd Saturday, even plates on the 2nd and 4th. Hologramas 0 / 00 exempt.
     */
    static final class HoyNoCircula implements Rule {
        final int[][] hours = h(5, 0, 22, 0);
        final WeekdayRule week = new WeekdayRule(hours, d(5, 6), d(7, 8), d(3, 4), d(1, 2), d(9, 0));

        public int[][] ranges(Calendar day, int digit) {
            String c = vclass;
            if ("h0".equals(c) || "h00".equals(c)) return null;
            int dow = day.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY) {
                if ("h2".equals(c)) return hours;
                int nth = (day.get(Calendar.DAY_OF_MONTH) - 1) / 7 + 1;
                boolean odd = digit % 2 == 1;
                if (nth == 1 || nth == 3) return odd ? hours : null;
                if (nth == 2 || nth == 4) return odd ? null : hours;
                return null;
            }
            return week.ranges(day, digit);
        }
    }

    /**
     * Chile, restricción vehicular de Santiago: 1 May – 31 Aug, Mon–Fri 7:30–21:00. Cars with sello verde
     * registered from Sept 2011 are exempt (outside environmental emergencies). The digits change every year,
     * so the user marks the days of the current calendar in manual mode; this rule only limits it to the season.
     */
    static final class SantiagoSeason implements Rule {
        final Rule inner;

        SantiagoSeason(Rule inner) {
            this.inner = inner;
        }

        public int[][] ranges(Calendar day, int digit) {
            if ("cl_new".equals(vclass)) return null;
            int m = day.get(Calendar.MONTH) + 1;
            if (m < 5 || m > 8) return null;
            return inner == null ? null : inner.ranges(day, digit);
        }

        static boolean inSeason(Calendar day) {
            int m = day.get(Calendar.MONTH) + 1;
            return m >= 5 && m <= 8;
        }
    }

    /** City without pico y placa for private cars. */
    static final Rule NONE = new Rule() {
        public int[][] ranges(Calendar day, int digit) {
            return null;
        }
    };

    // ---------- Cities ----------

    static final class City {
        final String id, name;
        final double lat, lon, radiusKm;
        final Rule rule;          // null = no data for this city
        final Calendar validUntil; // null = no known expiry
        String country = "CO";     // holidays of this country
        String label = "pico y placa";

        City(String id, String name, double lat, double lon, double radiusKm, Rule rule, Calendar validUntil) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.radiusKm = radiusKm;
            this.rule = rule;
            this.validUntil = validUntil;
        }

        City in(String country, String label) {
            this.country = country;
            this.label = label;
            return this;
        }
    }

    static final List<City> CITIES = new ArrayList<>();

    static {
        // Bogotá: par/impar, Mon-Fri 6:00-21:00.
        CITIES.add(new City("bogota", "Bogotá", 4.6534, -74.0836, 22,
                new ParityRule(h(6, 0, 21, 0)), date(2026, 12, 31)));

        // Medellín and Valle de Aburrá: rotation from 3 Aug 2026, Mon-Fri 5:00-20:00.
        CITIES.add(new City("medellin", "Medellín", 6.2442, -75.5812, 20,
                new WeekdayRule(h(5, 0, 20, 0), d(5, 8), d(1, 4), d(0, 2), d(3, 6), d(7, 9)),
                date(2027, 1, 31)));

        // Cali: Mon-Fri 6:00-19:00 (rotation in force in October 2026).
        CITIES.add(new City("cali", "Cali", 3.4372, -76.5225, 14,
                new WeekdayRule(h(6, 0, 19, 0), d(9, 0), d(1, 2), d(3, 4), d(5, 6), d(7, 8)),
                date(2026, 12, 31)));

        // Bucaramanga: from 5 Oct 2026, Mon-Fri 6:00-20:00, Saturdays 9:00-13:00 rotating.
        WeekdayRule bga = new WeekdayRule(h(6, 0, 20, 0), d(5, 6), d(7, 8), d(9, 0), d(1, 2), d(3, 4));
        bga.saturday = new SaturdayCycle(h(9, 0, 13, 0), 2026, 10, 10, 4); // 10 Oct 2026: 9 y 0
        CITIES.add(new City("bucaramanga", "Bucaramanga", 7.1193, -73.1227, 6, bga, date(2027, 1, 10)));

        // Cartagena: 5 Oct 2026 – 22 Jan 2027, 7:00-9:00 and 18:00-20:00.
        CITIES.add(new City("cartagena", "Cartagena", 10.3910, -75.4794, 14,
                new WeekdayRule(new int[][]{{7 * 60, 9 * 60}, {18 * 60, 20 * 60}},
                        d(1, 2), d(3, 4), d(5, 6), d(7, 8), d(9, 0)),
                date(2027, 1, 22)));

        // Manizales: no pico y placa for private cars (2026).
        CITIES.add(new City("manizales", "Manizales", 5.0703, -75.5138, 10, NONE, date(2026, 12, 31)));

        // ---- Other countries (checked Oct 2026) ----
        // México: Hoy No Circula, CDMX and the metropolitan area.
        CITIES.add(new City("cdmx", "Ciudad de México", 19.4326, -99.1332, 40, new HoyNoCircula(), date(2027, 6, 30))
                .in("MX", "Hoy No Circula"));
        // Chile: Santiago, May–August; the digits are announced every year (manual days).
        CITIES.add(new City("santiago", "Santiago de Chile", -33.4489, -70.6693, 28, new SantiagoSeason(null), null)
                .in("CL", "restricción vehicular"));
        // Ecuador: Quito, Mon–Fri 6:00–9:30 and 16:00–20:00.
        CITIES.add(new City("quito", "Quito", -0.1807, -78.4678, 20,
                new WeekdayRule(new int[][]{{6 * 60, 9 * 60 + 30}, {16 * 60, 20 * 60}}, d(1, 2), d(3, 4), d(5, 6), d(7, 8), d(9, 0)),
                date(2027, 6, 30)).in("EC", "pico y placa"));
        // Argentina: Buenos Aires has no plate restriction for private cars.
        CITIES.add(new City("buenosaires", "Buenos Aires", -34.6037, -58.3816, 35, NONE, null).in("AR", "restricción"));
        CITIES.add(new City("lima", "Lima", -12.0464, -77.0428, 30, NONE, null).in("PE", "restricción"));

        // Cities recognised by GPS but without verified data: the user sets them up manually.
        unknown("soacha", "Soacha", 4.5794, -74.2168, 5);
        unknown("barranquilla", "Barranquilla", 10.9685, -74.7813, 14);
        unknown("pereira", "Pereira", 4.8133, -75.6961, 10);
        unknown("armenia", "Armenia", 4.5339, -75.6811, 8);
        unknown("cucuta", "Cúcuta", 7.8939, -72.5078, 12);
        unknown("ibague", "Ibagué", 4.4389, -75.2322, 10);
        unknown("villavicencio", "Villavicencio", 4.1420, -73.6266, 10);
        unknown("santamarta", "Santa Marta", 11.2408, -74.1990, 10);
        unknown("pasto", "Pasto", 1.2136, -77.2811, 8);
        unknown("neiva", "Neiva", 2.9273, -75.2819, 8);
        unknown("popayan", "Popayán", 2.4448, -76.6147, 8);
        unknown("tunja", "Tunja", 5.5353, -73.3678, 7);
        unknown("monteria", "Montería", 8.7479, -75.8814, 8);
        unknown("valledupar", "Valledupar", 10.4631, -73.2532, 8);
        unknown("sincelejo", "Sincelejo", 9.3047, -75.3978, 6);
    }

    private static void unknown(String id, String name, double lat, double lon, double r) {
        CITIES.add(new City(id, name, lat, lon, r, null, null));
    }

    static City byId(String id) {
        for (City c : CITIES) if (c.id.equals(id)) return c;
        return null;
    }

    /** The city whose area contains the point (closest relative to its size), or null. */
    static City locate(double lat, double lon) {
        City best = null;
        double bestRatio = 1;
        for (City c : CITIES) {
            double ratio = distanceKm(lat, lon, c.lat, c.lon) / c.radiusKm;
            if (ratio <= bestRatio) {
                best = c;
                bestRatio = ratio;
            }
        }
        return best;
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // ---------- Evaluation ----------

    static final class Result {
        int[][] today;          // restricted ranges today, null if free
        String holiday;         // today's holiday name, or null
        boolean activeNow;      // restricted right now
        Calendar next;          // next restricted day (after today), or null
        int[][] nextRanges;
    }

    static Result evaluate(Rule rule, int digit, Calendar now) {
        return evaluate(rule, digit, now, "CO");
    }

    static Result evaluate(Rule rule, int digit, Calendar now, String country) {
        Result r = new Result();
        Calendar day = startOfDay(now);
        r.holiday = holidayName(day, country);
        r.today = r.holiday == null ? rule.ranges(day, digit) : null;
        if (r.today != null) {
            int min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            for (int[] h : r.today) if (min >= h[0] && min < h[1]) r.activeNow = true;
        }
        for (int i = 1; i <= 21; i++) {
            Calendar d = (Calendar) day.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            if (holidayName(d, country) != null) continue;
            int[][] rg = rule.ranges(d, digit);
            if (rg != null) {
                r.next = d;
                r.nextRanges = rg;
                break;
            }
        }
        return r;
    }

    // ---------- Text ----------

    private static final String[] DAYS = {"", "domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"};

    static String dayName(Calendar c) {
        return DAYS[c.get(Calendar.DAY_OF_WEEK)];
    }

    /** "6:00 a. m. – 9:00 p. m." / "7:00 – 9:00 a. m. y 6:00 – 8:00 p. m." */
    static String hoursText(int[][] ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.length; i++) {
            if (i > 0) sb.append(" y ");
            sb.append(clock(ranges[i][0])).append(" – ").append(clock(ranges[i][1]));
        }
        return sb.toString();
    }

    static String clock(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        String ampm = h < 12 || h == 24 ? "a. m." : "p. m.";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m, ampm);
    }

    /** For the voice: "de 6 de la mañana a 9 de la noche". */
    static String spokenHours(int[][] ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.length; i++) {
            if (i > 0) sb.append(", y ");
            sb.append("de ").append(spokenClock(ranges[i][0])).append(" a ").append(spokenClock(ranges[i][1]));
        }
        return sb.toString();
    }

    private static String spokenClock(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        String part = h < 12 ? "de la mañana" : h < 19 ? "de la tarde" : "de la noche";
        return h12 + (m == 0 ? "" : m == 30 ? " y media" : " y " + m) + " " + part;
    }

    /** Parses "6-21", "7-9, 18-20", "6:30-9:00". Returns null if invalid. */
    static int[][] parseHours(String s) {
        if (s == null) return null;
        List<int[]> out = new ArrayList<>();
        for (String part : s.split("[,;y]")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            String[] ab = part.split("-|–|a");
            if (ab.length != 2) return null;
            int a = parseClock(ab[0]), b = parseClock(ab[1]);
            if (a < 0 || b < 0 || b <= a) return null;
            out.add(new int[]{a, b});
        }
        return out.isEmpty() ? null : out.toArray(new int[0][]);
    }

    private static int parseClock(String s) {
        s = s.trim().replace(".", ":").replace("h", "");
        try {
            String[] hm = s.split(":");
            int h = Integer.parseInt(hm[0].trim());
            int m = hm.length > 1 && !hm[1].trim().isEmpty() ? Integer.parseInt(hm[1].trim()) : 0;
            if (h < 0 || h > 24 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------- Colombian holidays (Ley 51 de 1983 "Ley Emiliani") ----------

    static String holidayName(Calendar day) {
        return holidayName(day, "CO");
    }

    /** Holiday of that country (no restriction that day), or null. México: Hoy No Circula also applies on holidays. */
    static String holidayName(Calendar day, String country) {
        int y = day.get(Calendar.YEAR);
        long n = dayNumber(day);
        List<Object[]> list = "CO".equals(country) ? holidays(y) : "CL".equals(country) ? holidaysCL(y)
                : "EC".equals(country) ? holidaysEC(y) : null;
        if (list == null) return null;
        for (Object[] h : list) if (dayNumber((Calendar) h[0]) == n) return (String) h[1];
        return null;
    }

    /** Chile (restriction season May–August matters most). */
    static List<Object[]> holidaysCL(int y) {
        List<Object[]> l = new ArrayList<>();
        add(l, date(y, 1, 1), "Año Nuevo");
        Calendar easter = easter(y);
        add(l, plus(easter, -2), "Viernes Santo");
        add(l, plus(easter, -1), "Sábado Santo");
        add(l, date(y, 5, 1), "Día del Trabajo");
        add(l, date(y, 5, 21), "Glorias Navales");
        add(l, date(y, 6, 21), "Pueblos Indígenas");
        add(l, nearestMonday(date(y, 6, 29)), "San Pedro y San Pablo");
        add(l, date(y, 7, 16), "Virgen del Carmen");
        add(l, date(y, 8, 15), "Asunción de la Virgen");
        add(l, date(y, 9, 18), "Fiestas Patrias");
        add(l, date(y, 9, 19), "Glorias del Ejército");
        add(l, date(y, 11, 1), "Todos los Santos");
        add(l, date(y, 12, 8), "Inmaculada Concepción");
        add(l, date(y, 12, 25), "Navidad");
        return l;
    }

    /** Ecuador (fixed dates; the government sometimes moves some of them). */
    static List<Object[]> holidaysEC(int y) {
        List<Object[]> l = new ArrayList<>();
        Calendar easter = easter(y);
        add(l, date(y, 1, 1), "Año Nuevo");
        add(l, plus(easter, -48), "Carnaval");
        add(l, plus(easter, -47), "Carnaval");
        add(l, plus(easter, -2), "Viernes Santo");
        add(l, date(y, 5, 1), "Día del Trabajo");
        add(l, date(y, 5, 24), "Batalla de Pichincha");
        add(l, date(y, 8, 10), "Primer Grito de Independencia");
        add(l, date(y, 10, 9), "Independencia de Guayaquil");
        add(l, date(y, 11, 2), "Día de los Difuntos");
        add(l, date(y, 11, 3), "Independencia de Cuenca");
        add(l, date(y, 12, 25), "Navidad");
        return l;
    }

    private static Calendar nearestMonday(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.TUESDAY || dow == Calendar.WEDNESDAY) return plus(c, -(dow - Calendar.MONDAY));
        if (dow == Calendar.THURSDAY || dow == Calendar.FRIDAY) return plus(c, 9 - dow);
        return c;
    }

    private static int cachedYear = -1;
    private static List<Object[]> cachedHolidays;

    static synchronized List<Object[]> holidays(int y) {
        if (y == cachedYear) return cachedHolidays;
        List<Object[]> l = new ArrayList<>();
        // Fixed dates.
        add(l, date(y, 1, 1), "Año Nuevo");
        add(l, date(y, 5, 1), "Día del Trabajo");
        add(l, date(y, 7, 20), "Independencia");
        add(l, date(y, 8, 7), "Batalla de Boyacá");
        add(l, date(y, 12, 8), "Inmaculada Concepción");
        add(l, date(y, 12, 25), "Navidad");
        // Moved to the following Monday.
        add(l, nextMonday(date(y, 1, 6)), "Reyes Magos");
        add(l, nextMonday(date(y, 3, 19)), "San José");
        add(l, nextMonday(date(y, 6, 29)), "San Pedro y San Pablo");
        add(l, nextMonday(date(y, 8, 15)), "Asunción de la Virgen");
        add(l, nextMonday(date(y, 10, 12)), "Día de la Raza");
        add(l, nextMonday(date(y, 11, 1)), "Todos los Santos");
        add(l, nextMonday(date(y, 11, 11)), "Independencia de Cartagena");
        // Easter based.
        Calendar easter = easter(y);
        add(l, plus(easter, -3), "Jueves Santo");
        add(l, plus(easter, -2), "Viernes Santo");
        add(l, plus(easter, 43), "Ascensión del Señor");
        add(l, plus(easter, 64), "Corpus Christi");
        add(l, plus(easter, 71), "Sagrado Corazón");
        cachedYear = y;
        cachedHolidays = l;
        return l;
    }

    private static void add(List<Object[]> l, Calendar c, String name) {
        l.add(new Object[]{c, name});
    }

    private static Calendar nextMonday(Calendar c) {
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_MONTH, 1);
        return c;
    }

    private static Calendar plus(Calendar c, int days) {
        Calendar r = (Calendar) c.clone();
        r.add(Calendar.DAY_OF_MONTH, days);
        return r;
    }

    /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    static Calendar easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return date(y, month, day);
    }

    // ---------- Date helpers ----------

    static Calendar date(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 0, 0, 0);
        return c;
    }

    static Calendar startOfDay(Calendar c) {
        return date(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Days since 1970-01-01 for the calendar date (time zone independent). */
    static long dayNumber(Calendar c) {
        long y = c.get(Calendar.YEAR), m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH);
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    private static int[] d(int a, int b) {
        return new int[]{a, b};
    }

    private static int[][] h(int h1, int m1, int h2, int m2) {
        return new int[][]{{h1 * 60 + m1, h2 * 60 + m2}};
    }
}
