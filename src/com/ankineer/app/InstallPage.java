package com.ankineer.app;

import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Installation : protocole complet, étape par étape, avec l'état de chaque étape. */
final class InstallPage implements MainActivity.Page {
    private final MainActivity a;
    private final View root;
    private final TextView[] status = new TextView[8];
    private TextView summary;
    private Button testBtn;

    InstallPage(MainActivity act) {
        a = act;
        LinearLayout col = Ui.column(a);

        LinearLayout sum = Ui.card(a);
        summary = Ui.text(a, "", 16, Ui.TEXT, true);
        sum.addView(summary);
        a.note(sum, L.t("Suis les étapes dans l'ordre. Chaque étape affiche ✓ quand elle est faite.",
                "Follow the steps in order. Each step shows ✓ once it's done."));
        col.addView(sum);

        step(col, 0, L.t("Installer AnkiDroid et autoriser l'accès", "Install AnkiDroid and allow access"),
                L.t("Ankineer Lock lit ta progression dans AnkiDroid avec son API officielle.\n"
                                + "• AnkiDroid doit être installé AVANT Ankineer Lock.\n"
                                + "• Dans AnkiDroid → Paramètres → Avancé, l'option « API AnkiDroid » doit rester activée (elle l'est par défaut).\n"
                                + "• Appuie sur le bouton ci-dessous puis sur « Autoriser ».",
                        "Ankineer Lock reads your progress in AnkiDroid through its official API.\n"
                                + "• AnkiDroid must be installed BEFORE Ankineer Lock.\n"
                                + "• In AnkiDroid → Settings → Advanced, “AnkiDroid API” must stay enabled (it is by default).\n"
                                + "• Tap the button below, then “Allow”."),
                L.t("Autoriser l'accès à AnkiDroid", "Allow AnkiDroid access"), new Runnable() {
                    @Override public void run() {
                        if (!GoalChecker.isAnkiInstalled(a)) {
                            Toast.makeText(a, L.t("Installe AnkiDroid d'abord.", "Install AnkiDroid first."), Toast.LENGTH_LONG).show();
                            return;
                        }
                        a.requestPermissions(new String[]{GoalChecker.PERMISSION}, 42);
                    }
                });

        step(col, 1, L.t("Autoriser les paramètres restreints", "Allow restricted settings"),
                L.t("Android protège les apps installées hors Play Store : le service d'Ankineer Lock apparaît grisé tant que tu ne l'autorises pas.\n"
                                + "• Ouvre « Infos de l'app » avec le bouton ci-dessous.\n"
                                + "• Touche le menu ⋮ en haut à droite → « Autoriser les paramètres restreints ».\n"
                                + "• Si tu ne vois pas ce menu, essaie d'abord d'activer le service (étape 3) : Android le fera apparaître.",
                        "Android protects apps installed outside the Play Store: Ankineer Lock's service stays greyed out until you allow it.\n"
                                + "• Open “App info” with the button below.\n"
                                + "• Tap the ⋮ menu at the top right → “Allow restricted settings”.\n"
                                + "• If you don't see this menu, try enabling the service first (step 3): Android will then show it."),
                L.t("Ouvrir Infos de l'app", "Open App info"), new Runnable() {
                    @Override public void run() {
                        if (strictBlocked()) return;
                        a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
                    }
                });

        step(col, 2, L.t("Activer le service d'accessibilité", "Enable the accessibility service"),
                L.t("C'est lui qui voit quelle app est ouverte et la bloque si elle n'est pas autorisée. Il ne lit ni n'envoie le contenu de tes écrans.\n"
                                + "• Ouvre Accessibilité → « Applications installées » (ou « Services téléchargés »).\n"
                                + "• Choisis « Ankineer – Verrou Anki » et active-le.\n"
                                + "• Après une mise à jour d'Ankineer Lock, désactive puis réactive-le si l'étape n'affiche pas ✓.",
                        "It sees which app is open and blocks it if it isn't allowed. It never reads or sends your screen content.\n"
                                + "• Open Accessibility → “Installed apps” (or “Downloaded services”).\n"
                                + "• Pick “Ankineer – Anki Lock” and turn it on.\n"
                                + "• After updating Ankineer Lock, turn it off and on again if this step doesn't show ✓."),
                L.t("Ouvrir Accessibilité", "Open Accessibility"), new Runnable() {
                    @Override public void run() {
                        if (strictBlocked()) return;
                        a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                });

        step(col, 3, L.t("Désactiver l'optimisation de batterie", "Turn off battery optimisation"),
                L.t("Sinon certains téléphones (Xiaomi, Oppo, Huawei, Samsung…) arrêtent le service en arrière-plan et le verrou ne fonctionne plus.\n"
                                + "• Appuie sur le bouton puis choisis « Autoriser » / « Non optimisée ».\n"
                                + "• Sur Xiaomi : active aussi « Démarrage automatique » pour Ankineer Lock.",
                        "Otherwise some phones (Xiaomi, Oppo, Huawei, Samsung…) stop the service in the background and the lock stops working.\n"
                                + "• Tap the button, then choose “Allow” / “Not optimised”.\n"
                                + "• On Xiaomi: also enable “Autostart” for Ankineer Lock."),
                L.t("Ne pas optimiser la batterie", "Don't optimise battery"), new Runnable() {
                    @Override public void run() {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            try { a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
                        }
                    }
                });

        step(col, 4, L.t("Rappels : notifications et démarrage automatique", "Reminders: notifications and autostart"),
                L.t("Pour recevoir les rappels chaque heure :\n"
                                + "• autorise les notifications (Android 13 et plus) ;\n"
                                + "• sur Xiaomi, Oppo, Vivo, Huawei, Honor : active « Démarrage automatique » pour Ankineer Lock, "
                                + "sinon le système supprime les rappels programmés.",
                        "To get the hourly reminders:\n"
                                + "• allow notifications (Android 13 and later);\n"
                                + "• on Xiaomi, Oppo, Vivo, Huawei, Honor: enable “Autostart” for Ankineer Lock, "
                                + "otherwise the system deletes scheduled reminders."),
                L.t("Autoriser notifications / démarrage auto", "Allow notifications / autostart"), new Runnable() {
                    @Override public void run() {
                        if (!Reminders.permitted(a)) {
                            try { a.requestPermissions(new String[]{Reminders.PERM}, 79); } catch (Exception ignored) {}
                            return;
                        }
                        if (Apps.openAutostartSettings(a)) return;          // écran sans risque : permis même en mode strict
                        if (strictBlocked()) return;
                        {
                            try {
                                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
                            } catch (Exception ignored) {}
                        }
                    }
                });

        step(col, 5, L.t("Choisir les applications autorisées", "Choose the allowed apps"),
                L.t("Ces apps restent utilisables même verrouillé. Téléphone, SMS, AnkiDroid et l'écran d'accueil le sont toujours.",
                        "These apps stay usable even when locked. Phone, SMS, AnkiDroid and the home screen always are."),
                L.t("Choisir les applications", "Choose apps"), new Runnable() {
                    @Override public void run() { a.startActivity(new Intent(a, AppPickerActivity.class)); }
                });

        step(col, 6, L.t("Régler ton objectif du jour", "Set your daily goal"),
                L.t("Minimum 50 cartes et 15 minutes ; tu peux les augmenter. Options des cartes dues, langue et thème sont aussi dans Paramètres.",
                        "At least 50 cards and 15 minutes; you can raise them. Due-card options, language and theme are in Settings too."),
                L.t("Ouvrir Paramètres", "Open Settings"), new Runnable() {
                    @Override public void run() { a.show(MainActivity.SETTINGS); }
                });

        testBtn = step(col, 7, L.t("Tester, puis lancement automatique", "Test, then automatic start"),
                L.t("Le test bloque tout pendant 1 minute, même si ton objectif est atteint : ouvre Chrome ou YouTube pour voir l'écran de blocage.\n"
                                + "Dès que les étapes 1, 3, 4 et 6 sont faites, le verrou se lance tout seul (option dans Paramètres → Verrou). "
                                + "Tu peux aussi le lancer à la main depuis l'Accueil.",
                        "The test blocks everything for 1 minute, even if your goal is reached: open Chrome or YouTube to see the block screen.\n"
                                + "As soon as steps 1, 3, 4 and 6 are done, the lock starts on its own (option in Settings → Lock). "
                                + "You can also start it by hand from Home."),
                L.t("Tester le blocage (1 min)", "Test the lock (1 min)"), new Runnable() {
                    @Override public void run() {
                        if (!Apps.isServiceEnabled(a) || !LockService.running) {
                            Toast.makeText(a, L.t("Active d'abord le service (étape 3).", "Enable the service first (step 3)."), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Prefs.startTest(a, 60000);
                        Toast.makeText(a, L.t("Test lancé : ouvre une app non autorisée.", "Test started: open a non-allowed app."), Toast.LENGTH_LONG).show();
                        refresh();
                        a.startTickerIfNeeded();
                    }
                });

        root = a.scroll(col);
    }

    /**
     * Étapes encore à faire avant le lancement automatique du verrou (liste vide = installation terminée) :
     * AnkiDroid lu, service actif, batterie non optimisée, applications autorisées choisies.
     */
    static java.util.List<String> missingSteps(android.content.Context c, GoalChecker.Result r) {
        java.util.List<String> m = new java.util.ArrayList<String>();
        if (!GoalChecker.isAnkiInstalled(c) || !GoalChecker.hasPermission(c) || r == null || !r.apiOk) m.add("1");
        if (!Apps.isServiceEnabled(c) || !LockService.running) m.add("3");
        try {
            PowerManager pm = (PowerManager) c.getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName())) m.add("4");
        } catch (Exception ignored) {}
        if (!Prefs.appsChosen(c)) m.add("6");
        return m;
    }

    static boolean setupComplete(android.content.Context c, GoalChecker.Result r) { return missingSteps(c, r).isEmpty(); }

    /** Mode strict + verrou : ces écrans des Paramètres Android sont bloqués (ils permettraient de couper le service). */
    private boolean strictBlocked() {
        if (Prefs.isEditable(a) || !Prefs.strict(a)) return false;
        Toast.makeText(a, L.t("Verrou actif en mode strict : ces réglages Android sont bloqués jusqu'à ton objectif (la batterie et les mises à jour restent possibles).",
                "Lock active in strict mode: these Android settings are blocked until your goal (battery and updates stay possible)."), Toast.LENGTH_LONG).show();
        return true;
    }

    private Button step(LinearLayout col, int i, String title, String body, String action, final Runnable run) {
        LinearLayout c = Ui.card(a);
        c.addView(Ui.stepTitle(a, String.valueOf(i + 1), title));
        TextView b = Ui.text(a, body, 13.5f, Ui.MUTED, false);
        b.setLineSpacing(Ui.dp(a, 2), 1f);
        c.addView(b);
        status[i] = Ui.text(a, "", 14, Ui.TEXT, true);
        c.addView(status[i], Ui.topMargin(a, 10));
        Button btn = Ui.button(a, action, false);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        c.addView(btn);
        col.addView(c);
        return btn;
    }

    private void set(int i, boolean ok, String okText, String koText) {
        status[i].setText(ok ? "✓ " + okText : "✗ " + koText);
        status[i].setTextColor(ok ? Ui.GREEN : Ui.AMBER);
    }

    @Override public View view() { return root; }

    @Override
    public void tick() {
        long t = Prefs.testUntil(a) - Clock.now();
        testBtn.setText(t > 0 ? L.t("Test en cours… ", "Test running… ") + (t / 1000 + 1) + " s" : L.t("Tester le blocage (1 min)", "Test the lock (1 min)"));
    }

    @Override
    public void refresh() {
        boolean installed = GoalChecker.isAnkiInstalled(a), perm = GoalChecker.hasPermission(a);
        GoalChecker.Result r = a.lastRes;
        boolean api = r != null && r.apiOk;
        boolean service = Apps.isServiceEnabled(a), run = LockService.running;
        PowerManager pm = (PowerManager) a.getSystemService(android.content.Context.POWER_SERVICE);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());

        set(0, installed && perm && api, L.t("AnkiDroid connecté, progression lue", "AnkiDroid connected, progress read"),
                !installed ? L.t("AnkiDroid introuvable", "AnkiDroid not found") : !perm ? L.t("Accès pas encore autorisé", "Access not granted yet")
                        : L.t("Lecture impossible : ", "Can't read: ") + (r != null && r.error != null ? r.error : "…"));
        set(1, service, L.t("Fait (le service a pu être activé)", "Done (the service could be enabled)"),
                L.t("À faire si le service est grisé", "To do if the service is greyed out"));
        set(2, service && run, L.t("Service actif", "Service running"),
                service ? L.t("Activé mais arrêté : désactive puis réactive-le", "Enabled but stopped: turn it off and on again") : L.t("Service désactivé", "Service off"));
        set(3, battery, L.t("Batterie non optimisée", "Battery not optimised"), L.t("Batterie optimisée (risque d'arrêt)", "Battery optimised (may stop)"));
        boolean notif = Reminders.permitted(a);
        set(4, notif, Apps.hasAutostartScreen(a) ? L.t("Notifications autorisées — vérifie aussi le démarrage auto", "Notifications allowed — also check autostart")
                : L.t("Notifications autorisées", "Notifications allowed"), L.t("Notifications non autorisées : pas de rappels", "Notifications not allowed: no reminders"));
        int n = Prefs.whitelist(a).size();
        set(5, true, n + L.t(" app(s) autorisée(s)", " allowed app(s)"), "");
        set(6, true, Prefs.dailyCards(a) + L.t(" cartes · ", " cards · ") + Prefs.dailyMinutes(a) + " min", "");
        java.util.List<String> miss = missingSteps(a, r);
        set(7, Prefs.armed(a), L.t("Verrou lancé", "Lock started"),
                !Prefs.autoArm(a) ? L.t("Verrou pas encore lancé (Accueil)", "Lock not started yet (Home)")
                        : miss.isEmpty() ? L.t("Lancement automatique imminent…", "Starting automatically…")
                        : L.t("Lancement automatique après les étapes ", "Automatic start after steps ") + android.text.TextUtils.join(", ", miss));
        tick();

        int done = 0;
        if (installed && perm && api) done++;
        if (service) done++;
        if (service && run) done++;
        if (battery) done++;
        if (notif) done++;
        done += 2;
        if (Prefs.armed(a)) done++;
        summary.setText(done == 8 ? L.t("✓ Installation terminée", "✓ Setup complete") : L.t("Installation : ", "Setup: ") + done + " / 8");
        summary.setTextColor(done == 8 ? Ui.GREEN : Ui.TEXT);
    }
}
