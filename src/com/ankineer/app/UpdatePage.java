package com.ankineer.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Mise à jour sans désinstaller : l'utilisateur choisit le nouvel APK, Ankineer Lock vérifie qu'il s'agit bien
 * d'Ankineer Lock (même nom, même signature, version plus récente) puis lance la mise à jour par l'installateur
 * d'Android. Réglages, statistiques, code et service d'accessibilité sont conservés.
 */
final class UpdatePage implements MainActivity.Page {
    static final int REQ_PICK = 4242;

    private final MainActivity a;
    private final View root;
    private final TextView currentTv, fileTv, stateTv;
    private final Button pickBtn, installBtn;
    private File apk;           // copie vérifiée prête à installer
    private boolean busy;

    UpdatePage(MainActivity act) {
        a = act;
        LinearLayout col = Ui.column(a);

        LinearLayout cur = Ui.card(a);
        cur.addView(Ui.text(a, L.t("Version installée", "Installed version"), 14, Ui.MUTED, true));
        currentTv = Ui.text(a, "", 22, Ui.TEXT, true);
        cur.addView(currentTv, Ui.topMargin(a, 4));
        col.addView(cur);

        a.section(col, L.t("Installer une nouvelle version", "Install a new version"));
        LinearLayout up = Ui.card(a);
        TextView how = Ui.text(a, L.t("1. Télécharge le nouvel APK d'Ankineer Lock (par ex. dans Téléchargements).\n"
                        + "2. Appuie sur « Choisir le nouvel APK » et sélectionne-le.\n"
                        + "3. Appuie sur « Mettre à jour » puis confirme « Mettre à jour » dans la fenêtre d'Android.\n\n"
                        + "Pas besoin de désinstaller : tes réglages, statistiques, code d'urgence et le service d'accessibilité sont gardés.",
                "1. Download the new Ankineer Lock APK (e.g. to Downloads).\n"
                        + "2. Tap “Choose the new APK” and select it.\n"
                        + "3. Tap “Update”, then confirm “Update” in Android's window.\n\n"
                        + "No need to uninstall: your settings, stats, emergency code and accessibility service are kept."),
                13.5f, Ui.MUTED, false);
        how.setLineSpacing(Ui.dp(a, 2), 1f);
        up.addView(how);
        pickBtn = Ui.button(a, L.t("📂  Choisir le nouvel APK", "📂  Choose the new APK"), false);
        up.addView(pickBtn);
        fileTv = Ui.text(a, "", 14, Ui.TEXT, true);
        up.addView(fileTv, Ui.topMargin(a, 10));
        installBtn = Ui.button(a, L.t("⬆  Mettre à jour", "⬆  Update"), true);
        up.addView(installBtn);
        stateTv = Ui.text(a, "", 13, Ui.MUTED, false);
        up.addView(stateTv, Ui.topMargin(a, 8));
        a.note(up, L.t("La première fois, Android demande d'autoriser Ankineer Lock à « installer des applications inconnues » : "
                        + "active l'interrupteur puis reviens ici. Après la mise à jour, rouvre Ankineer Lock.",
                "The first time, Android asks you to allow Ankineer Lock to “install unknown apps”: "
                        + "turn the switch on, then come back. After the update, reopen Ankineer Lock."));
        col.addView(up);

        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(); }
        });
        installBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { install(); }
        });
        root = a.scroll(col);
    }

    // ------------------------------------------------------------------ choix du fichier

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        try {
            a.startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(a, L.t("Aucun sélecteur de fichiers disponible.", "No file picker available."), Toast.LENGTH_LONG).show();
        }
    }

    /** Résultat du sélecteur de fichiers (transmis par MainActivity). */
    void onPicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        apk = null;
        busy = true;
        fileTv.setTextColor(Ui.TEXT);
        fileTv.setText(L.t("Vérification du fichier…", "Checking the file…"));
        refresh();
        Ui.safeThread(a, new Runnable() {
            @Override public void run() {
                final String[] msg = new String[1];
                final File f = copyAndVerify(uri, msg);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        apk = f;
                        fileTv.setTextColor(f != null ? Ui.GREEN : Ui.RED);
                        fileTv.setText(msg[0]);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private File copyAndVerify(Uri uri, String[] msg) {
        File out = new File(a.getCacheDir(), "update.apk");
        try {
            InputStream in = a.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("null");
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
        } catch (Exception e) {
            msg[0] = "✗ " + L.t("Impossible de lire ce fichier.", "Can't read this file.");
            return null;
        }
        PackageManager pm = a.getPackageManager();
        @SuppressWarnings("deprecation")
        PackageInfo neu = pm.getPackageArchiveInfo(out.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (neu == null) {
            msg[0] = "✗ " + L.t("Ce fichier n'est pas un APK valide.", "This file isn't a valid APK.");
            return null;
        }
        if (!a.getPackageName().equals(neu.packageName)) {
            msg[0] = "✗ " + L.t("Ce n'est pas Ankineer Lock (", "This isn't Ankineer Lock (") + neu.packageName + ").";
            return null;
        }
        PackageInfo mine = current();
        if (mine != null && neu.versionCode < mine.versionCode) {
            msg[0] = "✗ " + L.t("Version plus ancienne (", "Older version (") + neu.versionName
                    + L.t(") : Android refuse de revenir en arrière.", "): Android won't downgrade.");
            return null;
        }
        if (mine != null && neu.signatures != null && neu.signatures.length > 0 && mine.signatures != null
                && !sameSignatures(mine.signatures, neu.signatures)) {
            msg[0] = "✗ " + L.t("Signature différente : cet APK ne peut pas remplacer l'app installée sans la désinstaller.",
                    "Different signature: this APK can't replace the installed app without uninstalling it.");
            return null;
        }
        boolean same = mine != null && neu.versionCode == mine.versionCode;
        msg[0] = "✓ Ankineer Lock " + neu.versionName + (same ? L.t(" (même version : réinstallation)", " (same version: reinstall)")
                : L.t(" — prêt à installer", " — ready to install"));
        return out;
    }

    private static boolean sameSignatures(Signature[] x, Signature[] y) {
        String[] a = new String[x.length], b = new String[y.length];
        for (int i = 0; i < x.length; i++) a[i] = x[i].toCharsString();
        for (int i = 0; i < y.length; i++) b[i] = y[i].toCharsString();
        Arrays.sort(a);
        Arrays.sort(b);
        return Arrays.equals(a, b);
    }

    @SuppressWarnings("deprecation")
    private PackageInfo current() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), PackageManager.GET_SIGNATURES);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ installation

    /** Android 8+ : Ankineer Lock doit être autorisé à installer des applications. */
    private boolean canInstall() {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        try {
            return (Boolean) a.getPackageManager().getClass().getMethod("canRequestPackageInstalls").invoke(a.getPackageManager());
        } catch (Exception e) {
            return true;
        }
    }

    private void install() {
        if (apk == null || busy) return;
        if (!canInstall()) {
            Toast.makeText(a, L.t("Autorise « Installer des applications inconnues » pour Ankineer Lock, puis reviens ici.",
                    "Allow “Install unknown apps” for Ankineer Lock, then come back here."), Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        busy = true;
        stateTv.setText(L.t("Préparation de la mise à jour…", "Preparing the update…"));
        refresh();
        final File f = apk;
        Ui.safeThread(a, new Runnable() {
            @Override public void run() {
                final String err = Updater.install(a.getApplicationContext(), f);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        stateTv.setTextColor(err == null ? Ui.MUTED : Ui.RED);
                        stateTv.setText(err == null ? L.t("Confirme « Mettre à jour » dans la fenêtre d'Android.", "Confirm “Update” in Android's window.")
                                : "✗ " + err);
                        refresh();
                    }
                });
            }
        }).start();
    }

    @Override public View view() { return root; }

    @Override public void tick() {}

    @Override
    public void refresh() {
        PackageInfo p = current();
        currentTv.setText(p != null ? "Ankineer Lock " + p.versionName : "Ankineer Lock");
        String last = Updater.lastMessage(a);
        if (last != null && apk == null && !busy) {
            stateTv.setTextColor(Ui.RED);
            stateTv.setText("✗ " + last);
        }
        Ui.enable(pickBtn, !busy);
        Ui.enable(installBtn, apk != null && !busy);
        installBtn.setVisibility(apk != null ? View.VISIBLE : View.GONE);
    }
}
