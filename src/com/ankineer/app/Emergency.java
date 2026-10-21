package com.ankineer.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Règles d'urgence (réglables dans la section « Urgence ») :
 *  - urgence avec attente : X min d'attente puis Y min libres, nombre par jour réglable ;
 *  - déblocage par code : libère Z min tout de suite, UNE fois par jour, code haché (jamais stocké en clair) ;
 *  - extrême urgence : 5 min tout de suite, UNE fois par jour.
 * Toutes les urgences libèrent le téléphone entier (objectif du jour et session).
 */
final class Emergency {
    static final String STANDARD = "std", PASSWORD = "pwd", EXTREME = "xtr";
    static final int EXTREME_MINUTES = 5;
    static final int MAX_PIN_FAILS = 5;
    static final int PIN_MIN_LEN = 4, PIN_MAX_LEN = 8;

    private Emergency() {}

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }

    // ------------------------------------------------------------------ réglages

    /** Urgence « avec attente » désactivée : seules restent le code et l'extrême urgence. */
    static final boolean STANDARD_SUPPORTED = false;
    static boolean standardEnabled(Context c) { return STANDARD_SUPPORTED && sp(c).getBoolean("emStdOn", true); }
    static void setStandardEnabled(Context c, boolean on) { sp(c).edit().putBoolean("emStdOn", on).apply(); }
    static int waitMinutes(Context c) { return clamp(sp(c).getInt("emWaitMin", 5), 1, 60); }
    static void setWaitMinutes(Context c, int m) { sp(c).edit().putInt("emWaitMin", clamp(m, 1, 60)).apply(); }
    static int standardMinutes(Context c) { return clamp(sp(c).getInt("emStdMin", 20), 1, 60); }
    static void setStandardMinutes(Context c, int m) { sp(c).edit().putInt("emStdMin", clamp(m, 1, 60)).apply(); }
    /** 0 = illimité. */
    static int standardMaxPerDay(Context c) { return clamp(sp(c).getInt("emStdMax", 0), 0, 10); }
    static void setStandardMaxPerDay(Context c, int n) { sp(c).edit().putInt("emStdMax", clamp(n, 0, 10)).apply(); }

    static boolean passwordEnabled(Context c) { return sp(c).getBoolean("emPwdOn", false) && hasPin(c); }
    static void setPasswordEnabled(Context c, boolean on) { sp(c).edit().putBoolean("emPwdOn", on).apply(); }
    static int passwordMinutes(Context c) { return clamp(sp(c).getInt("emPwdMin", 15), 5, 60); }
    static void setPasswordMinutes(Context c, int m) { sp(c).edit().putInt("emPwdMin", clamp(m, 5, 60)).apply(); }

    static boolean extremeEnabled(Context c) { return sp(c).getBoolean("emXtrOn", true); }
    static void setExtremeEnabled(Context c, boolean on) { sp(c).edit().putBoolean("emXtrOn", on).apply(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ code

    static boolean hasPin(Context c) { return sp(c).getString("emPinHash", null) != null; }

    static boolean validPin(String pin) {
        if (pin == null || pin.length() < PIN_MIN_LEN || pin.length() > PIN_MAX_LEN) return false;
        for (int i = 0; i < pin.length(); i++) if (!Character.isDigit(pin.charAt(i))) return false;
        return true;
    }

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        sp(c).edit().putString("emPinSalt", s).putString("emPinHash", hash(s, pin)).putInt("pinFails", 0).apply();
    }

    static void clearPin(Context c) {
        sp(c).edit().remove("emPinSalt").remove("emPinHash").putBoolean("emPwdOn", false).apply();
    }

    static boolean checkPin(Context c, String pin) {
        String salt = sp(c).getString("emPinSalt", null), h = sp(c).getString("emPinHash", null);
        return salt != null && h != null && pin != null && h.equals(hash(salt, pin));
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------------ usage du jour

    static int standardUsedToday(Context c) { return Prefs.emergencyCountToday(c); }
    // Avancer la date (heure manuelle) ne remet pas les compteurs à zéro : voir Clock.carriesOver.
    static boolean passwordUsedToday(Context c) {
        return Prefs.today().equals(sp(c).getString("pwdDay", "")) || Clock.carriesOver(sp(c).getLong("pwdUp", 0));
    }
    static boolean extremeUsedToday(Context c) {
        return Prefs.today().equals(sp(c).getString("xtrDay", "")) || Clock.carriesOver(sp(c).getLong("xtrUp", 0));
    }

    static int pinFailsToday(Context c) {
        boolean same = Prefs.today().equals(sp(c).getString("pinFailDay", "")) || Clock.carriesOver(sp(c).getLong("pinFailUp", 0));
        return same ? sp(c).getInt("pinFails", 0) : 0;
    }

    private static void addPinFail(Context c) {
        sp(c).edit().putString("pinFailDay", Prefs.today()).putLong("pinFailUp", Clock.uptime()).putInt("pinFails", pinFailsToday(c) + 1).apply();
    }

    static boolean standardAvailable(Context c) {
        int max = standardMaxPerDay(c);
        return standardEnabled(c) && (max == 0 || standardUsedToday(c) < max);
    }

    static boolean passwordAvailable(Context c) {
        return passwordEnabled(c) && !passwordUsedToday(c) && pinFailsToday(c) < MAX_PIN_FAILS;
    }

    static boolean extremeAvailable(Context c) { return extremeEnabled(c) && !extremeUsedToday(c); }

    // ------------------------------------------------------------------ actions

    /** Urgence avec attente : rien n'est compté tant qu'elle n'a pas abouti (annulable). */
    static synchronized void requestStandard(Context c) {
        if (!STANDARD_SUPPORTED || !standardAvailable(c) || Prefs.emergencyRequestedAt(c) > 0) return;
        sp(c).edit().putLong("emReq", Clock.now())
                .putLong("emReqWait", waitMinutes(c) * 60000L)
                .putLong("emReqDur", standardMinutes(c) * 60000L).apply();
    }

    static long pendingWaitMs(Context c) { return sp(c).getLong("emReqWait", Prefs.EMERGENCY_WAIT_MS); }

    /** Résultat d'une tentative par code. */
    static final int PIN_OK = 0, PIN_WRONG = 1, PIN_BLOCKED = 2, PIN_UNAVAILABLE = 3;

    static synchronized int tryPassword(Context c, String pin) {
        if (!passwordEnabled(c) || passwordUsedToday(c)) return PIN_UNAVAILABLE;
        if (pinFailsToday(c) >= MAX_PIN_FAILS) return PIN_BLOCKED;
        if (!checkPin(c, pin)) {
            addPinFail(c);
            return pinFailsToday(c) >= MAX_PIN_FAILS ? PIN_BLOCKED : PIN_WRONG;
        }
        sp(c).edit().putString("pwdDay", Prefs.today()).putLong("pwdUp", Clock.uptime()).apply();
        grant(c, PASSWORD, passwordMinutes(c));
        return PIN_OK;
    }

    static synchronized boolean tryExtreme(Context c) {
        if (!extremeAvailable(c)) return false;
        sp(c).edit().putString("xtrDay", Prefs.today()).putLong("xtrUp", Clock.uptime()).apply();
        grant(c, EXTREME, EXTREME_MINUTES);
        return true;
    }

    /** Libère le téléphone immédiatement pendant {@code minutes} (sans jamais raccourcir une urgence en cours). */
    static void grant(Context c, String type, int minutes) {
        long now = Clock.now();
        long until = Math.max(now + minutes * 60000L, Prefs.emergencyUntil(c));
        sp(c).edit().putLong("emUntil", until).putLong("emStart", now).putString("emType", type).apply();
        Stats.addEmergency(c, type);
    }

    static String activeType(Context c) { return sp(c).getString("emType", STANDARD); }
    static long activeStart(Context c) { return sp(c).getLong("emStart", 0); }

    static String typeLabel(String type) {
        if (PASSWORD.equals(type)) return L.t("code", "code");
        if (EXTREME.equals(type)) return L.t("extrême urgence", "extreme emergency");
        return L.t("urgence avec attente", "emergency with wait");
    }
}
