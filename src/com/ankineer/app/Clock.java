package com.ankineer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Trusted clock: changing the phone's time must not allow skipping an emergency wait,
 * lengthening an emergency or going back to "yesterday" (goal already reached).
 *  - Automatic (network) time on → follow the phone's time (can't be changed by hand).
 *  - Otherwise → time anchored at start + time elapsed since (SystemClock.elapsedRealtime, cannot be changed).
 * The anchor is kept in preferences and redone after a reboot — never going backwards
 * (auto time off: moving the date back then rebooting doesn't return to "yesterday").
 * A cumulative powered-on time counter (immune to clock changes) also prevents moving the date forward to
 * reset the "once a day" emergencies: see {@link #carriesOver(long)}.
 */
final class Clock {
    private static long anchorWall = -1, anchorEl = -1;
    private static long lastAutoCheck = -1;
    private static boolean autoTime = true;
    private static Context app;
    private static long upBase, maxTrusted = -1, lastSaveEl = -1;
    /** A use "of the day" stays counted for at least 12 h of powered-on time if the time is manual. */
    static final long CARRY_MS = 12L * 3600000L;

    private Clock() {}

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        SharedPreferences p = Prefs.sp(app);
        anchorWall = p.getLong("clkWall", -1);
        anchorEl = p.getLong("clkEl", -1);
        upBase = p.getLong("clkUpBase", 0);
        maxTrusted = p.getLong("clkMax", -1);
        long upLast = p.getLong("clkUpLast", 0), el = SystemClock.elapsedRealtime();
        if (el < upLast) {                       // reboot: add up the powered-on time of the previous session
            upBase += upLast;
            p.edit().putLong("clkUpBase", upBase).putLong("clkUpLast", el).apply();
        }
        Prefs.loadCutoff(app);
    }

    /** Now, in milliseconds, according to the trusted clock. */
    static synchronized long now() {
        long el = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        if (app != null && (lastAutoCheck < 0 || el - lastAutoCheck > 60000)) {
            lastAutoCheck = el;
            try {
                autoTime = Settings.Global.getInt(app.getContentResolver(), Settings.Global.AUTO_TIME, 1) == 1;
            } catch (Exception e) {
                autoTime = true;
            }
        }
        boolean reboot = anchorEl < 0 || el < anchorEl;
        long trusted = reboot ? wall : anchorWall + (el - anchorEl);
        // network time: re-sync to it (legitimate automatic corrections)
        if (reboot || (autoTime && Math.abs(wall - trusted) > 60000)) {
            // manual time + reboot: never before the last known trusted time
            long base = (reboot && !autoTime && maxTrusted > wall) ? maxTrusted : wall;
            anchorWall = base;
            anchorEl = el;
            trusted = base;
            if (app != null) Prefs.sp(app).edit().putLong("clkWall", anchorWall).putLong("clkEl", anchorEl).apply();
        }
        if (trusted > maxTrusted) maxTrusted = trusted;
        if (app != null && (lastSaveEl < 0 || el - lastSaveEl > 60000 || el < lastSaveEl)) {
            lastSaveEl = el;
            Prefs.sp(app).edit().putLong("clkMax", maxTrusted).putLong("clkUpLast", el).apply();
        }
        return trusted;
    }

    /** Cumulative powered-on time since install (ms), immune to the phone's clock. */
    static synchronized long uptime() { return upBase + SystemClock.elapsedRealtime(); }

    static synchronized boolean autoTime() { now(); return autoTime; }

    /**
     * Does a use made at powered-on time {@code usedUptime} still count for "today"
     * even if the date changed? Yes if the time is set by hand and less than 12 powered-on hours have passed.
     */
    static boolean carriesOver(long usedUptime) {
        if (usedUptime <= 0 || autoTime()) return false;
        long d = uptime() - usedUptime;
        return d >= 0 && d < CARRY_MS;
    }

    /** True if the phone's time has been moved by hand by more than one minute (auto time off). */
    static synchronized boolean tampered() {
        if (autoTime || anchorEl < 0) return false;
        return Math.abs(System.currentTimeMillis() - now()) > 60000;
    }
}
