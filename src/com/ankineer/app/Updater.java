package com.ankineer.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Installs the update through Android's installer (PackageInstaller session: in-place update). */
public class Updater extends BroadcastReceiver {
    static final String ACTION = "com.ankineer.app.UPDATE_STATUS";
    private static final int FLAG_MUTABLE = 0x02000000;     // PendingIntent.FLAG_MUTABLE (Android 12+)

    /** Starts the session; returns null if Android will ask for confirmation, otherwise an error message. */
    static String install(Context c, File apk) {
        PackageInstaller.Session s = null;
        try {
            Prefs.sp(c).edit().remove("updMsg").apply();
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            params.setSize(apk.length());
            int id = pi.createSession(params);
            s = pi.openSession(id);
            OutputStream out = s.openWrite("ankineer-lock.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            in.close();
            out.close();
            Intent i = new Intent(c, Updater.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(p.getIntentSender());
            s.close();
            return null;
        } catch (Exception e) {
            if (s != null) try { s.abandon(); } catch (Exception ignored) {}
            return L.t("Mise à jour impossible : ", "Update failed: ") + e.getMessage();
        }
    }

    static String lastMessage(Context c) { return Prefs.sp(c).getString("updMsg", null); }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception ignored) {}
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;    // the app is replaced (and restarted)
        L.load(c);
        String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String msg = status == PackageInstaller.STATUS_FAILURE_ABORTED ? L.t("mise à jour annulée.", "update cancelled.")
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT ? L.t("conflit avec l'app installée (signature différente ?).", "conflict with the installed app (different signature?).")
                : status == PackageInstaller.STATUS_FAILURE_STORAGE ? L.t("espace de stockage insuffisant.", "not enough storage.")
                : status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? L.t("APK incompatible avec ce téléphone.", "APK incompatible with this phone.")
                : (m != null ? m : L.t("échec de l'installation.", "install failed."));
        Prefs.sp(c).edit().putString("updMsg", msg).apply();
        Toast.makeText(c, "Ankineer Lock : " + msg, Toast.LENGTH_LONG).show();
    }
}
