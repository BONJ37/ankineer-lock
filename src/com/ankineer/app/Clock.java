package com.ankineer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Horloge de confiance : changer l'heure du téléphone ne doit pas permettre de sauter une attente d'urgence,
 * d'allonger une urgence ou de revenir à « hier » (objectif déjà atteint).
 *  - Heure automatique (réseau) activée → on suit l'heure du téléphone (non modifiable à la main).
 *  - Sinon → heure ancrée au démarrage + temps écoulé depuis (SystemClock.elapsedRealtime, impossible à changer).
 * L'ancre est gardée dans les préférences et refaite après un redémarrage — sans jamais revenir en arrière
 * (heure auto désactivée : reculer la date puis redémarrer ne ramène pas à « hier »).
 * Un compteur de temps allumé cumulé (insensible aux changements d'heure) empêche aussi d'avancer la date pour
 * remettre à zéro les urgences « une fois par jour » : voir {@link #carriesOver(long)}.
 */
final class Clock {
    private static long anchorWall = -1, anchorEl = -1;
    private static long lastAutoCheck = -1;
    private static boolean autoTime = true;
    private static Context app;
    private static long upBase, maxTrusted = -1, lastSaveEl = -1;
    /** Une utilisation « du jour » reste comptée pendant au moins 12 h de temps allumé si l'heure est manuelle. */
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
        if (el < upLast) {                       // redémarrage : on cumule le temps allumé de la session précédente
            upBase += upLast;
            p.edit().putLong("clkUpBase", upBase).putLong("clkUpLast", el).apply();
        }
        Prefs.loadCutoff(app);
    }

    /** Maintenant, en millisecondes, selon l'horloge de confiance. */
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
        // heure réseau : on se recale dessus (corrections automatiques légitimes)
        if (reboot || (autoTime && Math.abs(wall - trusted) > 60000)) {
            // heure manuelle + redémarrage : jamais avant la dernière heure de confiance connue
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

    /** Temps allumé cumulé depuis l'installation (ms), insensible à l'heure du téléphone. */
    static synchronized long uptime() { return upBase + SystemClock.elapsedRealtime(); }

    static synchronized boolean autoTime() { now(); return autoTime; }

    /**
     * Une utilisation faite au temps allumé {@code usedUptime} compte-t-elle encore pour « aujourd'hui »
     * même si la date a changé ? Oui si l'heure est réglée à la main et que moins de 12 h allumées se sont écoulées.
     */
    static boolean carriesOver(long usedUptime) {
        if (usedUptime <= 0 || autoTime()) return false;
        long d = uptime() - usedUptime;
        return d >= 0 && d < CARRY_MS;
    }

    /** Vrai si l'heure du téléphone a été déplacée à la main de plus d'une minute (heure auto désactivée). */
    static synchronized boolean tampered() {
        if (autoTime || anchorEl < 0) return false;
        return Math.abs(System.currentTimeMillis() - now()) > 60000;
    }
}
