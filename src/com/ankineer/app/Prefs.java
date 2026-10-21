package com.ankineer.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Réglages et état du verrou (objectif du jour, session, urgence, statistiques, widget, langue, thème). */
final class Prefs {
    /** Seuils minimum de l'objectif du jour : on peut les augmenter, jamais descendre en dessous. */
    static final int MIN_DAILY_CARDS = 50;
    static final int MIN_DAILY_MINUTES = 15;

    static final String SESSION_CARDS = "cards";
    static final String SESSION_MINUTES = "minutes";

    static final long EMERGENCY_WAIT_MS = 5 * 60 * 1000L;
    static final long EMERGENCY_DURATION_MS = 20 * 60 * 1000L;
    /** Heure de changement de jour d'AnkiDroid (« Le jour suivant commence à »), lue dans AnkiDroid ; 4h par défaut. */
    static volatile int DAY_CUTOFF_HOUR = 4;

    static void loadCutoff(Context c) { DAY_CUTOFF_HOUR = Math.max(0, Math.min(23, sp(c).getInt("rollover", 4))); }
    static void saveCutoff(Context c, int h) {
        if (h < 0 || h > 23) return;
        DAY_CUTOFF_HOUR = h;
        sp(c).edit().putInt("rollover", h).putLong("rolloverAt", System.currentTimeMillis()).apply();
    }
    static long cutoffReadAt(Context c) { return sp(c).getLong("rolloverAt", 0); }

    static final int REASON_NONE = 0;
    static final int REASON_GOAL = 1;
    static final int REASON_EMERGENCY = 2;

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("ankineer", Context.MODE_PRIVATE);
    }

    static String ankiDay(long now) {
        long shifted = now - DAY_CUTOFF_HOUR * 3600000L;
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(shifted));
    }

    static String today() { return ankiDay(Clock.now()); }

    static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(t));
    }

    static String hhmmDay(long t) {
        return new SimpleDateFormat(L.en ? "EEE d MMM, HH:mm" : "EEE d MMM à HH:mm", L.en ? Locale.ENGLISH : Locale.FRENCH).format(new Date(t));
    }

    // ---- Objectif du jour : cartes ET minutes (+ cartes dues en option) ----
    static int dailyCards(Context c) { return Math.max(MIN_DAILY_CARDS, sp(c).getInt("dailyCards", MIN_DAILY_CARDS)); }
    static void setDailyCards(Context c, int n) { sp(c).edit().putInt("dailyCards", Math.max(MIN_DAILY_CARDS, n)).apply(); }
    static int dailyMinutes(Context c) { return Math.max(MIN_DAILY_MINUTES, sp(c).getInt("dailyMinutes", MIN_DAILY_MINUTES)); }
    static void setDailyMinutes(Context c, int n) { sp(c).edit().putInt("dailyMinutes", Math.max(MIN_DAILY_MINUTES, n)).apply(); }
    /** Réglage : « 0 carte due » suffit à débloquer, même si les seuils cartes/minutes ne sont pas atteints. */
    static boolean dueSufficient(Context c) { return sp(c).getBoolean("dueSufficient", false); }
    static void setDueSufficient(Context c, boolean on) { sp(c).edit().putBoolean("dueSufficient", on).apply(); }

    // ---- Apparence ----
    static boolean english(Context c) { return "en".equals(sp(c).getString("lang", "fr")); }
    static void setEnglish(Context c, boolean en) { sp(c).edit().putString("lang", en ? "en" : "fr").apply(); }
    static boolean darkMode(Context c) { return sp(c).getBoolean("dark", true); }
    static void setDarkMode(Context c, boolean on) { sp(c).edit().putBoolean("dark", on).apply(); }

    // ---- Statistiques : jour d'installation + historique quotidien (JSON) ----
    static String installDay(Context c) {
        SharedPreferences p = sp(c);
        String d = p.getString("installDay", null);
        if (d == null) { d = today(); p.edit().putString("installDay", d).apply(); }
        return d;
    }
    static String statsJson(Context c) { return sp(c).getString("stats", "{}"); }
    static void setStatsJson(Context c, String json) { sp(c).edit().putString("stats", json).apply(); }

    /** Réglage : exiger aussi « 0 carte due » pour débloquer. */
    static boolean dueRequired(Context c) { return sp(c).getBoolean("dueRequired", false); }
    static void setDueRequired(Context c, boolean on) { sp(c).edit().putBoolean("dueRequired", on).apply(); }

    /** Identifie l'objectif actuel : le rendre plus exigeant annule un « objectif atteint » déjà enregistré. */
    static String goalSignature(Context c) {
        return "d3:" + dailyCards(c) + ":" + dailyMinutes(c) + ":" + (dueRequired(c) ? 1 : 0) + ":" + (dueSufficient(c) ? 1 : 0);
    }

    // ---- Session : objectif ponctuel (cartes ou minutes) qui verrouille jusqu'à sa fin ----
    static boolean sessionActive(Context c) { return sp(c).getBoolean("sessOn", false); }
    static String sessionType(Context c) { return sp(c).getString("sessType", SESSION_CARDS); }
    static int sessionTarget(Context c) { return sp(c).getInt("sessTarget", 20); }
    static int sessionBaseCards(Context c) { return sp(c).getInt("sessBaseCards", 0); }
    static int sessionBaseMinutes(Context c) { return sp(c).getInt("sessBaseMin", 0); }
    static String sessionDay(Context c) { return sp(c).getString("sessDay", ""); }
    static long sessionStartedAt(Context c) { return sp(c).getLong("sessStart", 0); }
    /** Dernier choix de l'utilisateur (pré-remplit le formulaire). */
    static int lastSessionValue(Context c, String type) {
        return sp(c).getInt(SESSION_MINUTES.equals(type) ? "sessLastMin" : "sessLastCards", SESSION_MINUTES.equals(type) ? 25 : 30);
    }

    static void startSession(Context c, String type, int target, int baseCards, int baseMinutes) {
        sp(c).edit().putBoolean("sessOn", true).putString("sessType", type).putInt("sessTarget", target)
                .putInt("sessBaseCards", baseCards).putInt("sessBaseMin", baseMinutes)
                .putString("sessDay", today()).putLong("sessStart", System.currentTimeMillis())
                .putInt(SESSION_MINUTES.equals(type) ? "sessLastMin" : "sessLastCards", target).apply();
    }

    /** Progression lue dans l'historique pour la session démarrée à {@code start} (ms, heure murale). */
    static void saveSessionProgress(Context c, long start, int cards, int minutes) {
        sp(c).edit().putLong("sessProgStart", start).putInt("sessProgCards", cards).putInt("sessProgMin", minutes).apply();
    }
    /** {cartes, minutes} de la session en cours, ou null si pas encore lue. */
    static int[] sessionProgress(Context c) {
        SharedPreferences p = sp(c);
        if (p.getLong("sessProgStart", -1) != sessionStartedAt(c)) return null;
        return new int[]{p.getInt("sessProgCards", 0), p.getInt("sessProgMin", 0)};
    }

    static void endSession(Context c, boolean completed) {
        SharedPreferences.Editor e = sp(c).edit().putBoolean("sessOn", false);
        if (completed) e.putLong("sessDoneAt", System.currentTimeMillis());
        e.apply();
    }

    static long sessionDoneAt(Context c) { return sp(c).getLong("sessDoneAt", 0); }

    // ---- Apps autorisées ----
    static Set<String> whitelist(Context c) {
        return new HashSet<String>(sp(c).getStringSet("whitelist", new HashSet<String>()));
    }
    static void setWhitelist(Context c, Set<String> s) {
        sp(c).edit().putStringSet("whitelist", new HashSet<String>(s)).apply();
    }

    static boolean strict(Context c) { return sp(c).getBoolean("strict", true); }
    static void setStrict(Context c, boolean b) { sp(c).edit().putBoolean("strict", b).apply(); }

    static boolean initialized(Context c) { return sp(c).getBoolean("init", false); }
    static void setInitialized(Context c) { sp(c).edit().putBoolean("init", true).apply(); }

    // ---- État du verrou ----
    static synchronized int unlockReason(Context c) {
        long now = Clock.now();
        SharedPreferences p = sp(c);
        long req = p.getLong("emReq", 0);
        if (req > 0 && !Emergency.STANDARD_SUPPORTED) {     // demande restée d'une ancienne version : annulée
            p.edit().putLong("emReq", 0).apply();
            req = 0;
        }
        long wait = p.getLong("emReqWait", EMERGENCY_WAIT_MS);
        if (req > 0 && now >= req + wait) {
            // l'urgence avec attente aboutit : elle n'est comptée que maintenant
            long dur = p.getLong("emReqDur", EMERGENCY_DURATION_MS);
            String day = ankiDay(now);
            int n = emergencyCountToday(c) + 1;
            long until = Math.max(req + wait + dur, p.getLong("emUntil", 0));
            p.edit().putLong("emUntil", until).putLong("emStart", req + wait).putString("emType", Emergency.STANDARD)
                    .putLong("emReq", 0).putString("emDay", day).putInt("emCount", n).putLong("emUp", Clock.uptime()).apply();
            Stats.addEmergency(c, Emergency.STANDARD);
        }
        if (goalDoneToday(c)) return REASON_GOAL;
        if (p.getLong("emUntil", 0) > now) return REASON_EMERGENCY;
        return REASON_NONE;
    }

    static boolean goalDoneToday(Context c) {
        SharedPreferences p = sp(c);
        return today().equals(p.getString("doneDay", "")) && goalSignature(c).equals(p.getString("doneSig", ""));
    }

    static boolean isUnlocked(Context c) { return unlockReason(c) != REASON_NONE; }

    static void markGoalDone(Context c) {
        sp(c).edit().putString("doneDay", today()).putString("doneSig", goalSignature(c)).apply();
    }

    // ---- Lancement / test ----
    static boolean armed(Context c) { return sp(c).getBoolean("armed", false); }
    static void setArmed(Context c, boolean on) {
        SharedPreferences.Editor e = sp(c).edit().putBoolean("armed", on);
        if (on) e.putBoolean("everArmed", true).remove("stopDay");
        else e.putString("stopDay", today());          // arrêt manuel : pas de relance automatique avant demain
        e.apply();
    }

    // ---- Lancement automatique du verrou (dès que l'installation est terminée)
    static boolean autoArm(Context c) { return sp(c).getBoolean("autoArm", true); }
    static void setAutoArm(Context c, boolean on) { sp(c).edit().putBoolean("autoArm", on).apply(); }
    /** Les applications autorisées ont été vues au moins une fois (ou un verrou a déjà été lancé à la main). */
    static boolean appsChosen(Context c) { return sp(c).getBoolean("appsChosen", false) || sp(c).getBoolean("everArmed", false); }
    static void setAppsChosen(Context c) { sp(c).edit().putBoolean("appsChosen", true).apply(); }

    /**
     * Lance le verrou tout seul si l'option est active, que l'installation est terminée et que l'utilisateur
     * ne l'a pas arrêté lui-même aujourd'hui. Renvoie true s'il vient d'être lancé.
     */
    static synchronized boolean autoArmIfReady(Context c, boolean setupComplete) {
        if (!setupComplete || !autoArm(c) || armed(c)) return false;
        if (today().equals(sp(c).getString("stopDay", ""))) return false;
        setArmed(c, true);
        return true;
    }

    // ---- Protection permanente contre la désinstallation
    static final long PROTECT_OFF_DELAY_MS = 24L * 3600000L;

    /** Active ? (une demande d'arrêt ne prend effet qu'après 24 h, insensible aux changements d'heure). */
    static synchronized boolean protectForever(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("protect", false)) return false;
        long off = p.getLong("protectOffAt", 0);
        if (off > 0) {
            long offUp = p.getLong("protectOffUp", 0);
            boolean elapsed = Clock.now() >= off + PROTECT_OFF_DELAY_MS
                    && (Clock.autoTime() || Clock.uptime() - offUp >= PROTECT_OFF_DELAY_MS);
            if (elapsed) {
                p.edit().putBoolean("protect", false).remove("protectOffAt").remove("protectOffUp").apply();
                return false;
            }
        }
        return true;
    }
    static void setProtectForever(Context c) {
        sp(c).edit().putBoolean("protect", true).remove("protectOffAt").remove("protectOffUp").apply();
    }
    static void requestProtectOff(Context c) {
        sp(c).edit().putLong("protectOffAt", Clock.now()).putLong("protectOffUp", Clock.uptime()).apply();
    }
    /** Heure (confiance) à laquelle la protection s'arrêtera, ou 0 si aucun arrêt n'est demandé. */
    static long protectOffTime(Context c) {
        long off = sp(c).getLong("protectOffAt", 0);
        return off > 0 && sp(c).getBoolean("protect", false) ? off + PROTECT_OFF_DELAY_MS : 0;
    }

    static long testUntil(Context c) { return sp(c).getLong("testUntil", 0); }
    static void startTest(Context c, long durationMs) {
        sp(c).edit().putLong("testUntil", Clock.now() + durationMs).apply();
    }
    static boolean testActive(Context c) { return testUntil(c) > Clock.now(); }

    static boolean emergencyActive(Context c) {
        unlockReason(c);                                   // convertit une demande arrivée à échéance
        return sp(c).getLong("emUntil", 0) > Clock.now();
    }

    /** Verrou « de fond », sans tenir compte d'une urgence : test, session, ou objectif du jour non atteint. */
    static boolean baseLocked(Context c) {
        if (testActive(c)) return true;
        if (sessionActive(c)) return true;
        return armed(c) && !goalDoneToday(c);
    }

    /**
     * Le blocage des apps s'applique-t-il en ce moment ?
     * test > urgence (libère les apps) > session en cours > objectif du jour non atteint (si lancé).
     */
    static boolean lockActive(Context c) {
        if (testActive(c)) return true;
        if (emergencyActive(c)) return false;
        return baseLocked(c);
    }

    /**
     * Les réglages qui assouplissent le verrou ne sont modifiables que si le verrou de fond est levé.
     * Une urgence libère les apps mais ne permet PAS d'arrêter le verrou ni de baisser l'objectif.
     */
    static boolean isEditable(Context c) { return !Apps.isServiceEnabled(c) || !baseLocked(c); }

    // ---- Urgence ----
    static long emergencyRequestedAt(Context c) { return sp(c).getLong("emReq", 0); }
    static long emergencyUntil(Context c) { return sp(c).getLong("emUntil", 0); }

    static void cancelEmergency(Context c) { sp(c).edit().putLong("emReq", 0).apply(); }   // annulée = non comptée

    static int emergencyCountToday(Context c) {
        SharedPreferences p = sp(c);
        boolean same = today().equals(p.getString("emDay", "")) || Clock.carriesOver(p.getLong("emUp", 0));
        return same ? p.getInt("emCount", 0) : 0;
    }

    // ---- Temps d'étude dans AnkiDroid (objectif minutes) ----
    static synchronized void addStudyMs(Context c, long ms) {
        if (ms <= 0) return;
        SharedPreferences p = sp(c);
        String day = today();
        long base = day.equals(p.getString("studyDay", "")) ? p.getLong("studyMs", 0) : 0;
        p.edit().putString("studyDay", day).putLong("studyMs", base + ms).apply();
    }

    /** Temps enregistré + temps en cours non encore sauvegardé par le service. */
    static long studyMsToday(Context c) {
        SharedPreferences p = sp(c);
        long base = today().equals(p.getString("studyDay", "")) ? p.getLong("studyMs", 0) : 0;
        return base + LockService.pendingStudyMs;
    }

    // ---- Historique AnkiDroid du jour (cartes + minutes, PC compris après synchro) ----
    static void saveStudied(Context c, int cards, int minutes) {
        sp(c).edit().putString("rvDay", today()).putInt("rvCards", cards).putInt("rvMin", minutes).apply();
    }
    /** -1 si pas encore lu aujourd'hui. */
    static int studiedMinutesToday(Context c) {
        SharedPreferences p = sp(c);
        return today().equals(p.getString("rvDay", "")) ? p.getInt("rvMin", -1) : -1;
    }
    static int studiedCardsToday(Context c) {
        SharedPreferences p = sp(c);
        return today().equals(p.getString("rvDay", "")) ? p.getInt("rvCards", -1) : -1;
    }

    static void saveDue(Context c, int due) {
        sp(c).edit().putString("dueReadDay", today()).putInt("dueNow", due).apply();
    }
    /** -1 si pas encore lu aujourd'hui. */
    static int dueToday(Context c) {
        SharedPreferences p = sp(c);
        return today().equals(p.getString("dueReadDay", "")) ? p.getInt("dueNow", -1) : -1;
    }

    // ---- Point de départ des cartes dues (pour la barre de progression) ----
    static synchronized int dueBaseline(Context c, int due) {
        SharedPreferences p = sp(c);
        String day = today();
        int base = day.equals(p.getString("dueDay", "")) ? p.getInt("dueBase", 0) : 0;
        if (due > base) {
            base = due;
            p.edit().putString("dueDay", day).putInt("dueBase", base).apply();
        }
        return base;
    }

    // ---- Widget ----
    static boolean widgetEnabled(Context c) { return sp(c).getBoolean("widget", true); }
    static void setWidgetEnabled(Context c, boolean on) { sp(c).edit().putBoolean("widget", on).apply(); }
    /** Style des widgets d'écran d'accueil : 0 sombre, 1 clair, 2 verre, 3 dégradé. */
    static int homeWidgetStyle(Context c) { return Math.max(0, Math.min(3, sp(c).getInt("wStyle", 0))); }
    static void setHomeWidgetStyle(Context c, int s) { sp(c).edit().putInt("wStyle", s).apply(); }
    static int widgetX(Context c) { return sp(c).getInt("widgetX", 0); }
    static int widgetY(Context c) { return sp(c).getInt("widgetY", -1); }
    static void setWidgetPos(Context c, int x, int y) {
        sp(c).edit().putInt("widgetX", x).putInt("widgetY", y).apply();
    }

    // ---- Dernier statut lu dans AnkiDroid ----
    static void saveStatus(Context c, String text) { sp(c).edit().putString("status", text).apply(); }
    static String lastStatus(Context c) { return sp(c).getString("status", "Vérification…"); }

    // ---- Diagnostic : dernière app vue par le service ----
    static void recordEvent(Context c, String pkg) {
        sp(c).edit().putString("lastPkg", pkg).putLong("lastPkgAt", System.currentTimeMillis()).apply();
    }
    static String lastEventPkg(Context c) { return sp(c).getString("lastPkg", null); }
    static long lastEventAt(Context c) { return sp(c).getLong("lastPkgAt", 0); }

}
