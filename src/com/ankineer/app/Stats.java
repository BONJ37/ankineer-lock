package com.ankineer.app;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Daily history: cards, minutes, goal reached, finished sessions, emergencies.
 * Filled at every AnkiDroid read; other days are back-filled from AnkiDroid's history
 * (up to 365 days back for the heatmap). Days before install are marked "pre"
 * (activity shown, goal not evaluated). The JSON is kept in memory: instant reads.
 */
final class Stats {
    static final int HISTORY_DAYS = 365;
    static final int REFRESH_RECENT_DAYS = 7;

    static final class Day {
        String day;
        int cards, minutes, sessions, goalCards, goalMinutes;
        int emStd, emPwd, emXtr;
        int ret = -1, retN;            // today's retention (‰, -1 = no review) and number of reviews
        boolean met, known, pre;
    }

    private Stats() {}

    static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private static JSONObject cache;
    private static String cacheSrc;

    /** JSON in memory (re-parsed only if the preferences changed elsewhere). */
    private static synchronized JSONObject all(Context c) {
        String src = Prefs.statsJson(c);
        if (cache == null || cacheSrc == null || !cacheSrc.equals(src)) {
            try {
                cache = new JSONObject(src);
            } catch (Exception e) {
                cache = new JSONObject();
            }
            cacheSrc = src;
        }
        return cache;
    }

    private static synchronized void save(Context c, JSONObject all) {
        String s = all.toString();
        cache = all;
        cacheSrc = s;
        Prefs.setStatsJson(c, s);
    }

    private static JSONObject entry(JSONObject all, String day) throws Exception {
        JSONObject e = all.optJSONObject(day);
        if (e == null) { e = new JSONObject(); all.put(day, e); }
        return e;
    }

    static synchronized void recordToday(Context c, int cards, int minutes, boolean met) {
        try {
            JSONObject all = all(c);
            JSONObject e = entry(all, Prefs.today());
            int r = GoalChecker.retentionToday(c), rn = GoalChecker.retentionTodayCount(c);
            if (e.optInt("c", -1) == cards && e.optInt("m", -1) == minutes && e.optBoolean("met", false) == (met || e.optBoolean("met", false))
                    && e.optInt("gc", -1) == Prefs.dailyCards(c) && e.optInt("gm", -1) == Prefs.dailyMinutes(c)
                    && e.optInt("r", -2) == r && e.optInt("rn", -1) == rn) return;   // nothing new
            e.put("r", r);
            e.put("rn", rn);
            e.put("c", cards);
            e.put("m", minutes);
            e.put("met", met || e.optBoolean("met", false));
            e.put("gc", Prefs.dailyCards(c));
            e.put("gm", Prefs.dailyMinutes(c));
            e.remove("pre");
            save(c, all);
        } catch (Exception ignored) {}
    }

    static synchronized void addSession(Context c) { bump(c, "s"); }

    static synchronized void addEmergency(Context c, String type) {
        bump(c, Emergency.PASSWORD.equals(type) ? "ePwd" : Emergency.EXTREME.equals(type) ? "eXtr" : "eStd");
    }

    private static void bump(Context c, String key) {
        try {
            JSONObject all = all(c);
            JSONObject e = entry(all, Prefs.today());
            e.put(key, e.optInt(key, 0) + 1);
            save(c, all);
        } catch (Exception ignored) {}
    }

    static Day get(Context c, String day) {
        Day d = new Day();
        d.day = day;
        JSONObject e = all(c).optJSONObject(day);
        if (e != null) {
            d.known = true;
            d.pre = e.optBoolean("pre", false);
            d.cards = e.optInt("c", 0);
            d.minutes = e.optInt("m", 0);
            d.sessions = e.optInt("s", 0);
            d.goalCards = e.optInt("gc", Prefs.dailyCards(c));
            d.goalMinutes = e.optInt("gm", Prefs.dailyMinutes(c));
            // goal reached: recorded by the app, or computed afterwards from AnkiDroid's history
            // (that day's thresholds, or the current thresholds for days before install)
            // today: only the real lock state (due-card options included)
            d.met = e.optBoolean("met", false) || (!day.equals(Prefs.today()) && d.cards >= d.goalCards && d.minutes >= d.goalMinutes);
            d.ret = e.optInt("r", -1);
            d.retN = e.optInt("rn", 0);
            d.emStd = e.optInt("eStd", 0);
            d.emPwd = e.optInt("ePwd", 0);
            d.emXtr = e.optInt("eXtr", 0);
        }
        return d;
    }

    /** Start (day change hour) of a "yyyy-MM-dd" day. */
    static long dayStart(String day) throws Exception {
        Calendar cal = Calendar.getInstance();
        cal.setTime(FMT.parse(day));
        cal.set(Calendar.HOUR_OF_DAY, Prefs.DAY_CUTOFF_HOUR);
        return cal.getTimeInMillis();
    }

    static String addDays(String day, int n) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTime(FMT.parse(day));
            cal.add(Calendar.DAY_OF_MONTH, n);
            return FMT.format(cal.getTime());
        } catch (Exception e) {
            return day;
        }
    }

    /** First day shown in statistics (365 days of history, or install if older). */
    static String firstDay(Context c) {
        String back = addDays(Prefs.today(), -(HISTORY_DAYS - 1));
        String inst = Prefs.installDay(c);
        return inst.compareTo(back) < 0 ? inst : back;
    }

    /**
     * Back-fills past days from AnkiDroid's history (off the main thread).
     * The last 7 days are always re-read (so a late PC sync is taken into account);
     * older days are read only once.
     */
    static void backfill(Context c) {
        if (!GoalChecker.isAnkiInstalled(c) || !GoalChecker.hasPermission(c)) return;
        try {
            String today = Prefs.today();
            String recent = addDays(today, -REFRESH_RECENT_DAYS);
            String install = Prefs.installDay(c);
            String d = firstDay(c);
            boolean changed = false;
            int done = 0;
            for (int guard = 0; guard < HISTORY_DAYS + 400 && d.compareTo(today) < 0; guard++, d = addDays(d, 1)) {
                JSONObject e;
                synchronized (Stats.class) { e = all(c).optJSONObject(d); }
                if (e != null && e.optBoolean("final", false) && e.optInt("v", 0) >= 2) continue;   // v2: retention added
                int[] v = GoalChecker.studiedBetween(c, dayStart(d), dayStart(addDays(d, 1)));
                int[] ret = GoalChecker.retentionBetween(c, dayStart(d), dayStart(addDays(d, 1)));
                synchronized (Stats.class) {
                    JSONObject all = all(c);
                    e = entry(all, d);
                    boolean pre = d.compareTo(install) < 0;
                    int gc = e.optInt("gc", Prefs.dailyCards(c)), gm = e.optInt("gm", Prefs.dailyMinutes(c));
                    e.put("c", v[0]);
                    e.put("m", v[1]);
                    e.put("r", ret[0]);
                    e.put("rn", Math.max(0, ret[1]));
                    e.put("v", 2);
                    if (pre) {
                        e.put("pre", true);
                    } else {
                        e.put("gc", gc);
                        e.put("gm", gm);
                        e.put("met", e.optBoolean("met", false) || (v[0] >= gc && v[1] >= gm));
                    }
                    if (d.compareTo(recent) < 0) e.put("final", true);
                    changed = true;
                    if (++done % 30 == 0) save(c, all);   // regular save during the first back-fill
                }
            }
            if (changed) synchronized (Stats.class) { save(c, all(c)); }
            GoalChecker.readRetention(c, true);
        } catch (Exception ignored) {}
    }

    /** Consecutive days (up to yesterday, + today if reached) with the goal reached. */
    static int streak(Context c) {
        String d = Prefs.today();
        int n = 0;
        if (get(c, d).met) n++;
        d = addDays(d, -1);
        for (int i = 0; i < 2000; i++, d = addDays(d, -1)) {
            if (!get(c, d).met) break;
            n++;
        }
        return n;
    }

    /** Longest streak of reached goals over the whole known history (AnkiDroid included). */
    static int bestStreak(Context c) {
        String d = firstDay(c), today = Prefs.today();
        int best = 0, cur = 0;
        for (int i = 0; i < 3000 && d.compareTo(today) <= 0; i++, d = addDays(d, 1)) {
            if (get(c, d).met) { cur++; best = Math.max(best, cur); } else cur = 0;
        }
        return best;
    }

    static String label(String day) {
        try {
            Date dt = FMT.parse(day);
            return new SimpleDateFormat("EEEE d MMMM yyyy", L.en ? Locale.ENGLISH : Locale.FRENCH).format(dt);
        } catch (Exception e) {
            return day;
        }
    }
}
