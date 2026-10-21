package com.ankineer.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Journal du dernier plantage : enregistré même si l'app se ferme d'un coup, affiché au lancement suivant
 * (Accueil / À propos) avec un bouton « Copier le rapport » pour l'envoyer au développeur.
 */
final class CrashLog {
    private static volatile boolean installed;

    private CrashLog() {}

    /** Capture les plantages non gérés (n'importe quel fil), puis laisse Android fermer l'app comme d'habitude. */
    static void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable t) {
                try { save(app, t, true); } catch (Throwable ignored) {}
                if (previous != null) previous.uncaughtException(th, t);
            }
        });
    }

    static void save(Context c, Throwable t) { save(c, t, false); }

    private static void save(Context c, Throwable t, boolean now) {
        String r = format(c, t);
        android.content.SharedPreferences.Editor e = Prefs.sp(c).edit().putString("crash", r).putLong("crashAt", System.currentTimeMillis());
        if (now) e.commit(); else e.apply();
    }

    static String last(Context c) { return Prefs.sp(c).getString("crash", null); }

    static void clear(Context c) { Prefs.sp(c).edit().remove("crash").remove("crashAt").apply(); }

    static String format(Context c, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        if (trace.length() > 3500) trace = trace.substring(0, 3500) + "\n…";
        String version = "?";
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        return "Ankineer Lock " + version + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n" + Prefs.hhmm(System.currentTimeMillis()) + "\n" + trace;
    }

    /** Résumé d'une ligne : type d'erreur + première ligne de notre code. */
    static String summary(Throwable t) {
        String s = t.getClass().getSimpleName() + (t.getMessage() != null ? " : " + t.getMessage() : "");
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("com.ankineer.app")) {
                s += "\n" + e.getClassName().replace("com.ankineer.app.", "") + "." + e.getMethodName() + " (ligne " + e.getLineNumber() + ")";
                break;
            }
        }
        return s;
    }

    static void copy(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Ankineer Lock", text));
            Toast.makeText(c, L.t("Rapport copié : colle-le dans ton message.", "Report copied: paste it into your message."), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
