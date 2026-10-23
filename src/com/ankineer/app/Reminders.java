package com.ankineer.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Calendar;

/**
 * Hourly reminders (two separate notifications):
 *  1. "You still have N due cards" (if any are left);
 *  2. "Minimum goal not reached yet" (cards / minutes).
 * Adjustable quiet hours (no reminder at night). Scheduled with AlarmManager, rescheduled after a reboot.
 */
final class Reminders {
    static final String CHANNEL = "ankineer_reminders";
    static final int ID_DUE = 41, ID_GOAL = 42, ID_SERVICE = 43;
    static final String PERM = "android.permission.POST_NOTIFICATIONS";
    static final String ACTION = "com.ankineer.app.REMINDER";
    /** "Service off" alert: at every hourly reminder (margin for alarm drift), instead of every 3 h. */
    static final long SERVICE_ALERT_MS = 50 * 60000L;

    private Reminders() {}

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }

    // ------------------------------------------------------------------ settings
    static boolean enabled(Context c) { return sp(c).getBoolean("remOn", true); }
    static void setEnabled(Context c, boolean on) { sp(c).edit().putBoolean("remOn", on).apply(); schedule(c); }
    static boolean dueOn(Context c) { return sp(c).getBoolean("remDue", true); }
    static void setDueOn(Context c, boolean on) { sp(c).edit().putBoolean("remDue", on).apply(); }
    static boolean goalOn(Context c) { return sp(c).getBoolean("remGoal", true); }
    static boolean quoteOn(Context c) { return sp(c).getBoolean("remQuote", true); }
    static void setQuoteOn(Context c, boolean on) { sp(c).edit().putBoolean("remQuote", on).apply(); }

    /** Adds the motivational quote of the hour (if the option is on). */
    private static String withQuote(Context c, String text, int offset) {
        return quoteOn(c) ? text + "\n\n💬 " + Quotes.ofTheHour(offset) : text;
    }
    static void setGoalOn(Context c, boolean on) { sp(c).edit().putBoolean("remGoal", on).apply(); }
    /** Quiet hours: from quietFrom (included) to quietTo (excluded), e.g. 23 → 8. */
    static int quietFrom(Context c) { return sp(c).getInt("remQf", 23); }
    static int quietTo(Context c) { return sp(c).getInt("remQt", 8); }
    static void setQuiet(Context c, int from, int to) {
        sp(c).edit().putInt("remQf", Math.max(0, Math.min(23, from))).putInt("remQt", Math.max(0, Math.min(23, to))).apply();
    }

    static boolean isQuiet(Context c, int hour) {
        int f = quietFrom(c), t = quietTo(c);
        if (f == t) return false;
        return f < t ? (hour >= f && hour < t) : (hour >= f || hour < t);
    }

    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ programmation

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ReminderAlarmReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Schedules (or cancels) the next reminder, at the next full hour (+5 s). Single alarm, rescheduled at every
     * run: unlike a "repeating" alarm, Android doesn't batch it with others (no 1 h 30 drift).
     */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c);
        am.cancel(pi);
        if (!enabled(c)) return;
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, 1);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 5);
        cal.set(Calendar.MILLISECOND, 0);
        long at = cal.getTimeInMillis();
        try {
            if (exactAllowed(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Android 12+: exact alarms only if allowed (USE_EXACT_ALARM grants it at install on 13+). */
    private static boolean exactAllowed(AlarmManager am) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return (Boolean) am.getClass().getMethod("canScheduleExactAlarms").invoke(am);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ envoi

    /** Called every hour (off the main thread). {@code force} = "test" button (ignores quiet hours). */
    static void run(Context c, boolean force) {
        Clock.init(c);
        L.load(c);
        if (!force && !enabled(c)) return;
        if (!force && isQuiet(c, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) return;
        if (!permitted(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // lock service stopped (crash) or switched off by Android while the lock is started:
        // warn at every hourly run (at most once an hour)
        if (Apps.isServiceEnabled(c) && !LockService.running) {
            // process just restarted by the alarm: Android reconnects the service within a few seconds
            for (int i = 0; i < 10 && !LockService.running; i++) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
        boolean enabledSvc = Apps.isServiceEnabled(c);
        boolean stopped = enabledSvc && !LockService.running;
        boolean switchedOff = !enabledSvc && sp(c).getBoolean("svcSeen", false) && Prefs.armed(c);
        if (stopped || switchedOff) {
            long last = sp(c).getLong("remSvcAt", 0), t = System.currentTimeMillis();
            if (force || t - last > SERVICE_ALERT_MS || t < last) {
                sp(c).edit().putLong("remSvcAt", t).apply();
                notify(c, nm, ID_SERVICE, "⚠ " + (switchedOff
                                ? L.t("Le service d'accessibilité est coupé", "The accessibility service is off")
                                : L.t("Le verrou Ankineer est arrêté", "The Ankineer lock is stopped")),
                        switchedOff
                                ? L.t("Android a désactivé « Ankineer – Verrou Anki » : le verrou ne bloque plus rien. Ouvre Ankineer Lock → Installation, étape 3, et réactive-le.",
                                "Android turned “Ankineer – Anki Lock” off: the lock no longer blocks anything. Open Ankineer Lock → Setup, step 3, and turn it back on.")
                                : L.t("Android a arrêté le service. Ouvre Ankineer Lock → Installation, étape 3 : désactive puis réactive « Ankineer – Verrou Anki ».",
                                "Android stopped the service. Open Ankineer Lock → Setup, step 3: turn “Ankineer – Anki Lock” off and on again."), false, true);
            }
        } else {
            nm.cancel(ID_SERVICE);
        }
        GoalChecker.Result r = GoalChecker.check(c);
        if (!r.apiOk) {
            // AnkiDroid unreadable: warn (at most every 3 h) instead of staying silent
            long now = System.currentTimeMillis(), last = sp(c).getLong("remErrAt", 0);
            if (force || now - last > 3 * 3600000L || now < last) {
                sp(c).edit().putLong("remErrAt", now).apply();
                nm.cancel(ID_DUE);
                notify(c, nm, ID_GOAL, "⚠ " + L.t("Ankineer Lock ne lit plus AnkiDroid", "Ankineer Lock can't read AnkiDroid"),
                        (r.error != null ? r.error + "\n" : "") + L.t("Ouvre Ankineer Lock → Installation, étape 1, pour réparer.",
                                "Open Ankineer Lock → Setup, step 1, to fix it."), false, true);
            }
            return;
        }
        sp(c).edit().remove("remErrAt").apply();
        int due = -1;
        if (dueOn(c) || force) {
            try { due = GoalChecker.totalDue(c); } catch (Exception ignored) {}
        }
        // 1) due cards
        if ((dueOn(c) || force) && due > 0) {
            notify(c, nm, ID_DUE, "📚 " + L.t("Encore ", "Still ") + due + (due > 1 ? L.t(" cartes dues", " due cards") : L.t(" carte due", " due card")),
                    withQuote(c, L.t("Ouvre AnkiDroid pour les terminer.", "Open AnkiDroid to finish them."), 0), true);
        } else {
            nm.cancel(ID_DUE);
        }
        // 2) objectif minimum
        if ((goalOn(c) || force) && !r.dailyMet && !Prefs.goalDoneToday(c)) {
            int lc = Math.max(0, r.goalCards - r.cards), lm = Math.max(0, r.goalMinutes - r.minutes);
            String left = (lc > 0 ? lc + L.t(" cartes", " cards") : "") + (lc > 0 && lm > 0 ? L.t(" et ", " and ") : "") + (lm > 0 ? lm + " min" : "");
            notify(c, nm, ID_GOAL, "🎯 " + L.t("Objectif minimum pas encore atteint", "Minimum goal not reached yet"),
                    withQuote(c, L.t("Cartes ", "Cards ") + r.cards + "/" + r.goalCards + "  ·  Minutes " + r.minutes + "/" + r.goalMinutes
                            + (left.length() > 0 ? L.t("  —  encore ", "  —  ") + left + L.t("", " to go") : ""), 1), true);
        } else {
            nm.cancel(ID_GOAL);
        }
        if (force && due <= 0 && (r.dailyMet || Prefs.goalDoneToday(c))) {
            notify(c, nm, ID_GOAL, "✓ " + L.t("Tout est fait pour aujourd'hui", "All done for today"),
                    L.t("Aucun rappel nécessaire : bravo !", "No reminder needed: well done!"), false);
        }
        WidgetUpdater.updateAll(c, true);
    }

    private static void notify(Context c, NotificationManager nm, int id, String title, String text, boolean openAnki) {
        notify(c, nm, id, title, text, openAnki, false);
    }

    private static void notify(Context c, NotificationManager nm, int id, String title, String text, boolean openAnki, boolean openSetup) {
        Notification.Builder b = builder(c, nm);
        Intent open = openAnki ? c.getPackageManager().getLaunchIntentForPackage(GoalChecker.ANKI_PKG) : null;
        if (open == null) open = new Intent(c, MainActivity.class);
        if (openSetup) open.putExtra("page", MainActivity.INSTALL);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent app = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pa = PendingIntent.getActivity(c, 100 + id, app, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_stat_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(Ui.ACCENT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setShowWhen(true)
                .addAction(0, "Ankineer Lock", pa);
        if (openAnki) b.addAction(0, L.t("Ouvrir AnkiDroid", "Open AnkiDroid"), pi);
        try { nm.notify(id, b.build()); } catch (Exception ignored) {}
    }

    /** Builder compatible with Android 6 → 15 (notification channel created by reflection on Android 8+). */
    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, L.t("Rappels de révision", "Review reminders"), 3 /* IMPORTANCE_DEFAULT */);
                nm.getClass().getMethod("createNotificationChannel", ch).invoke(nm, channel);
                return Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception ignored) {}
        }
        return new Notification.Builder(c).setPriority(Notification.PRIORITY_DEFAULT);
    }
}
