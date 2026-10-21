package com.ankineer.app;

import android.content.Intent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Paramètres : objectif du jour, cartes dues, rappels, widgets, langue, thème, verrou. */
final class SettingsPage implements MainActivity.Page {
    private final MainActivity a;
    private final View root;
    private EditText cardsEt, minutesEt;
    private TextView savedTv, wlTv;
    private Switch swDueSufficient, swDueRequired, swDark, swWidget, swStrict, swRem, swRemDue, swRemGoal, swRemQuote, swAuto, swProtect, swCelebrate;
    private TextView protectTv;
    private EditText qFromEt, qToEt;
    private TextView remInfo;
    private Button[] styleBtn = new Button[4];
    private Button fr, en, apps;
    private boolean binding;

    SettingsPage(MainActivity act) {
        a = act;
        LinearLayout col = Ui.column(a);

        // ---- objectif du jour
        a.section(col, L.t("Objectif du jour", "Daily goal"));
        LinearLayout g = Ui.card(a);
        g.addView(Ui.text(a, L.t("Le téléphone reste verrouillé jusqu'à atteindre LES DEUX seuils.",
                "Your phone stays locked until you reach BOTH thresholds."), 13, Ui.MUTED, false));
        g.addView(Ui.text(a, L.t("Cartes étudiées (minimum ", "Cards studied (minimum ") + Prefs.MIN_DAILY_CARDS + ")", 14, Ui.TEXT, true), Ui.topMargin(a, 12));
        cardsEt = a.numberField(String.valueOf(Prefs.MIN_DAILY_CARDS));
        g.addView(cardsEt, Ui.topMargin(a, 6));
        g.addView(Ui.text(a, L.t("Minutes d'étude (minimum ", "Study minutes (minimum ") + Prefs.MIN_DAILY_MINUTES + ")", 14, Ui.TEXT, true), Ui.topMargin(a, 12));
        minutesEt = a.numberField(String.valueOf(Prefs.MIN_DAILY_MINUTES));
        minutesEt.setImeOptions(EditorInfo.IME_ACTION_DONE);
        g.addView(minutesEt, Ui.topMargin(a, 6));
        Button save = Ui.button(a, L.t("Enregistrer l'objectif", "Save goal"), true);
        g.addView(save);
        Button reset = Ui.button(a, L.t("Réinitialiser au minimum (", "Reset to the minimum (") + Prefs.MIN_DAILY_CARDS
                + L.t(" cartes · ", " cards · ") + Prefs.MIN_DAILY_MINUTES + " min)", false);
        g.addView(reset);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetGoal(); }
        });
        savedTv = Ui.text(a, "", 13, Ui.GREEN, true);
        g.addView(savedTv, Ui.topMargin(a, 8));
        a.note(g, L.t("Mêmes chiffres qu'AnkiDroid (« Étudié X cartes en Y minutes aujourd'hui ») : téléphone + PC dès qu'AnkiDroid est synchronisé. "
                        + "Tu peux augmenter les seuils à tout moment ; les baisser seulement quand le téléphone est libre.",
                "Same numbers as AnkiDroid (“Studied X cards in Y minutes today”): phone + PC once AnkiDroid is synced. "
                        + "You can raise the thresholds anytime; lower them only while your phone is free."));
        col.addView(g);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveGoal(); }
        });
        minutesEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_DONE) { saveGoal(); return true; }
                return false;
            }
        });

        // ---- cartes dues
        a.section(col, L.t("Cartes dues", "Due cards"));
        LinearLayout d = Ui.card(a);
        swDueSufficient = a.makeSwitch(L.t("Débloquer dès que toutes les cartes dues sont faites", "Unlock as soon as all due cards are done"));
        d.addView(swDueSufficient);
        a.note(d, L.t("S'il ne reste plus rien à réviser, les seuils cartes et minutes ne s'appliquent pas.",
                "If there is nothing left to review, the card and minute thresholds don't apply."));
        swDueRequired = a.makeSwitch(L.t("Exiger aussi 0 carte due pour débloquer", "Also require 0 due cards to unlock"));
        d.addView(swDueRequired, Ui.topMargin(a, 14));
        a.note(d, L.t("En plus des cartes et des minutes, il faudra avoir terminé toutes les cartes dues du jour.",
                "On top of cards and minutes, you'll also have to finish all of today's due cards."));
        col.addView(d);

        // ---- rappels
        a.section(col, L.t("Rappels", "Reminders"));
        LinearLayout rm = Ui.card(a);
        swRem = a.makeSwitch(L.t("Rappels chaque heure", "Hourly reminders"));
        rm.addView(swRem);
        swRemDue = a.makeSwitch(L.t("S'il reste des cartes dues", "If due cards remain"));
        rm.addView(swRemDue, Ui.topMargin(a, 12));
        swRemGoal = a.makeSwitch(L.t("Si l'objectif minimum n'est pas atteint", "If the minimum goal isn't reached"));
        rm.addView(swRemGoal, Ui.topMargin(a, 12));
        swRemQuote = a.makeSwitch(L.t("Ajouter une citation de motivation", "Add a motivational quote"));
        rm.addView(swRemQuote, Ui.topMargin(a, 12));
        rm.addView(Ui.text(a, L.t("Heures calmes (aucun rappel) : de … à … h", "Quiet hours (no reminders): from … to … h"), 14, Ui.TEXT, true), Ui.topMargin(a, 16));
        LinearLayout qh = new LinearLayout(a);
        qFromEt = a.numberField("23");
        qToEt = a.numberField("8");
        LinearLayout.LayoutParams q1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        q2.leftMargin = Ui.dp(a, 8);
        qh.addView(qFromEt, q1);
        qh.addView(qToEt, q2);
        rm.addView(qh, Ui.topMargin(a, 6));
        Button qSave = Ui.button(a, L.t("Enregistrer les heures calmes", "Save quiet hours"), false);
        rm.addView(qSave);
        Button test = Ui.button(a, L.t("Tester un rappel maintenant", "Test a reminder now"), false);
        rm.addView(test);
        remInfo = Ui.text(a, "", 13, Ui.MUTED, false);
        rm.addView(remInfo, Ui.topMargin(a, 8));
        a.note(rm, L.t("Deux notifications distinctes, au plus une de chaque par heure : « Encore N cartes dues » et « Objectif minimum pas encore atteint ». Elles disparaissent d'elles-mêmes une fois l'objectif atteint.",
                "Two separate notifications, at most one of each per hour: “Still N due cards” and “Minimum goal not reached yet”. They clear themselves once the goal is reached."));
        col.addView(rm);
        qSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Reminders.setQuiet(a, parse(qFromEt, Reminders.quietFrom(a)), parse(qToEt, Reminders.quietTo(a)));
                qFromEt.clearFocus(); qToEt.clearFocus();
                refresh();
                Toast.makeText(a, L.t("Heures calmes enregistrées", "Quiet hours saved"), Toast.LENGTH_SHORT).show();
            }
        });
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!askNotifPermission()) return;
                final android.content.Context ctx = a.getApplicationContext();
                Ui.safeThread(a, new Runnable() {
                    @Override public void run() { Reminders.run(ctx, true); }
                }).start();
            }
        });

        // ---- widgets d'écran d'accueil
        a.section(col, L.t("Widgets d'écran d'accueil", "Home screen widgets"));
        LinearLayout hw = Ui.card(a);
        hw.addView(Ui.text(a, L.t("Style", "Style"), 14, Ui.TEXT, true));
        String[] names = {L.t("Sombre", "Dark"), L.t("Clair", "Light"), L.t("Verre", "Glass"), L.t("Dégradé", "Gradient")};
        LinearLayout row1 = new LinearLayout(a), row2 = new LinearLayout(a);
        for (int i = 0; i < 4; i++) {
            styleBtn[i] = a.chip(names[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f);
            if (i % 2 == 1) lp.leftMargin = Ui.dp(a, 8);
            (i < 2 ? row1 : row2).addView(styleBtn[i], lp);
            final int st = i;
            styleBtn[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setHomeWidgetStyle(a, st);
                    refresh();
                    final android.content.Context ctx = a.getApplicationContext();
                    Ui.safeThread(a, new Runnable() {
                        @Override public void run() { WidgetUpdater.updateAll(ctx, true); }
                    }).start();
                }
            });
        }
        hw.addView(row1, Ui.topMargin(a, 8));
        hw.addView(row2, Ui.topMargin(a, 8));
        String[] wn = {L.t("Ajouter : anneaux (2×2)", "Add: rings (2×2)"), L.t("Ajouter : mini (1×1)", "Add: mini (1×1)"),
                L.t("Ajouter : barres (4×1)", "Add: bars (4×1)"), L.t("Ajouter : semaine (4×2)", "Add: week (4×2)")};
        final Class<?>[] wc = {WidgetRings.class, WidgetMini.class, WidgetBars.class, WidgetWeek.class};
        for (int i = 0; i < 4; i++) {
            Button b = Ui.button(a, wn[i], false);
            hw.addView(b);
            final Class<?> k = wc[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pinWidget(k); }
            });
        }
        a.note(hw, L.t("Tu peux aussi appui long sur l'écran d'accueil → Widgets → Ankineer Lock. Touche un widget pour ouvrir l'app.",
                "You can also long-press your home screen → Widgets → Ankineer Lock. Tap a widget to open the app."));
        col.addView(hw);

        // ---- apparence
        a.section(col, L.t("Apparence", "Appearance"));
        LinearLayout ap = Ui.card(a);
        ap.addView(Ui.text(a, L.t("Langue", "Language"), 14, Ui.TEXT, true));
        LinearLayout seg = new LinearLayout(a);
        fr = a.chip("Français");
        en = a.chip("English");
        LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f);
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f);
        s2.leftMargin = Ui.dp(a, 8);
        seg.addView(fr, s1);
        seg.addView(en, s2);
        ap.addView(seg, Ui.topMargin(a, 8));
        swDark = a.makeSwitch(L.t("Mode sombre", "Dark mode"));
        ap.addView(swDark, Ui.topMargin(a, 16));
        col.addView(ap);

        // ---- verrou
        a.section(col, L.t("Verrou", "Lock"));
        LinearLayout lk = Ui.card(a);
        swWidget = a.makeSwitch(L.t("Cercle de progression en haut de l'écran", "Progress circle at the top of the screen"));
        lk.addView(swWidget);
        a.note(lk, L.t("Bleu = cartes, violet = minutes, orange = session. Touche-le pour ouvrir Ankineer Lock, glisse-le pour le déplacer.",
                "Blue = cards, purple = minutes, orange = session. Tap it to open Ankineer Lock, drag it to move it."));
        swStrict = a.makeSwitch(L.t("Bloquer Paramètres et désinstallation pendant le verrou", "Block Settings and uninstall while locked"));
        lk.addView(swStrict, Ui.topMargin(a, 14));
        swCelebrate = a.makeSwitch(L.t("Fenêtre de succès quand un objectif est terminé", "Success window when a goal is completed"));
        lk.addView(swCelebrate, Ui.topMargin(a, 14));
        swAuto = a.makeSwitch(L.t("Lancer le verrou automatiquement", "Start the lock automatically"));
        lk.addView(swAuto, Ui.topMargin(a, 14));
        a.note(lk, L.t("Dès que l'installation est terminée (étapes 1, 3, 4 et 6), le verrou se lance tout seul. "
                        + "Si tu l'arrêtes à la main, il se relance le lendemain.",
                "As soon as setup is complete (steps 1, 3, 4 and 6), the lock starts on its own. "
                        + "If you stop it by hand, it starts again the next day."));
        swProtect = a.makeSwitch(L.t("Bloquer la désinstallation pour toujours", "Block uninstalling forever"));
        lk.addView(swProtect, Ui.topMargin(a, 14));
        protectTv = a.note(lk, "");
        wlTv = Ui.text(a, "", 14, Ui.MUTED, false);
        lk.addView(wlTv, Ui.topMargin(a, 14));
        apps = Ui.button(a, L.t("Choisir les applications autorisées", "Choose allowed apps"), false);
        lk.addView(apps);
        col.addView(lk);

        root = a.scroll(col);
        wire();
    }

    private void wire() {
        swDueSufficient.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (on && !Prefs.isEditable(a)) { refuse(b, false); return; }      // assouplir : seulement si libre
                Prefs.setDueSufficient(a, on);
                a.runCheck();
            }
        });
        swDueRequired.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (!on && !Prefs.isEditable(a)) { refuse(b, true); return; }
                Prefs.setDueRequired(a, on);
                a.runCheck();
            }
        });
        fr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setLang(false); }
        });
        en.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setLang(true); }
        });
        swDark.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                Prefs.setDarkMode(a, on);
                a.rebuild();
            }
        });
        swWidget.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!binding) Prefs.setWidgetEnabled(a, on);
            }
        });
        swStrict.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (!on && !Prefs.isEditable(a)) { refuse(b, true); return; }
                Prefs.setStrict(a, on);
            }
        });
        apps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { a.startActivity(new Intent(a, AppPickerActivity.class)); }
        });
        swRem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (on) askNotifPermission();
                Reminders.setEnabled(a, on);
                refresh();
            }
        });
        swRemDue.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!binding) Reminders.setDueOn(a, on); }
        });
        swRemGoal.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!binding) Reminders.setGoalOn(a, on); }
        });
        swRemQuote.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!binding) Reminders.setQuoteOn(a, on); }
        });
        swCelebrate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!binding) Prefs.sp(a).edit().putBoolean("celebrate", on).apply();
            }
        });
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                Prefs.setAutoArm(a, on);           // n'arrête jamais un verrou déjà lancé
                if (on) a.runCheck();
                refresh();
            }
        });
        swProtect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (on) {
                    Prefs.setProtectForever(a);    // renforcer = toujours permis (annule aussi un arrêt demandé)
                    Toast.makeText(a, L.t("🔒 Désinstallation bloquée", "🔒 Uninstall blocked"), Toast.LENGTH_SHORT).show();
                    refresh();
                    return;
                }
                if (!Prefs.isEditable(a)) { refuse(b, true); return; }
                binding = true;
                b.setChecked(true);
                binding = false;
                new android.app.AlertDialog.Builder(a)
                        .setTitle(L.t("Arrêter la protection ?", "Stop the protection?"))
                        .setMessage(L.t("Pour éviter une désinstallation sur un coup de tête, la protection reste active encore 24 heures, puis s'arrête toute seule. "
                                        + "Tu peux annuler en réactivant l'interrupteur.",
                                "To avoid an impulsive uninstall, the protection stays on for 24 more hours, then stops on its own. "
                                        + "You can cancel by turning the switch back on."))
                        .setPositiveButton(L.t("Arrêter dans 24 h", "Stop in 24 h"), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                Prefs.requestProtectOff(a);
                                refresh();
                            }
                        })
                        .setNegativeButton(L.t("Garder", "Keep"), null)
                        .show();
            }
        });
    }

    /** Android 13+ : demande l'autorisation des notifications. Renvoie true si déjà accordée. */
    private boolean askNotifPermission() {
        if (Reminders.permitted(a)) return true;
        try { a.requestPermissions(new String[]{Reminders.PERM}, 77); } catch (Exception ignored) {}
        return false;
    }

    /** Android 8+ : propose d'ajouter directement le widget sur l'écran d'accueil. */
    private void pinWidget(Class<?> k) {
        try {
            android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(a);
            boolean ok = (Boolean) m.getClass().getMethod("isRequestPinAppWidgetSupported").invoke(m);
            if (ok) {
                m.getClass().getMethod("requestPinAppWidget", android.content.ComponentName.class, android.os.Bundle.class, android.app.PendingIntent.class)
                        .invoke(m, new android.content.ComponentName(a, k), null, null);
                return;
            }
        } catch (Throwable ignored) {}
        Toast.makeText(a, L.t("Appui long sur l'écran d'accueil → Widgets → Ankineer Lock", "Long-press your home screen → Widgets → Ankineer Lock"),
                Toast.LENGTH_LONG).show();
    }

    private void refuse(CompoundButton b, boolean keep) {
        Toast.makeText(a, L.t("Possible seulement quand le téléphone est libre.", "Only possible while your phone is free."), Toast.LENGTH_SHORT).show();
        binding = true;
        b.setChecked(keep);
        binding = false;
    }

    private void setLang(boolean english) {
        if (Prefs.english(a) == english) return;
        Prefs.setEnglish(a, english);
        L.load(a);
        a.rebuild();
    }

    /** Enregistre les deux seuils après validation complète (aucune sauvegarde pendant la frappe). */
    private void saveGoal() {
        int curC = Prefs.dailyCards(a), curM = Prefs.dailyMinutes(a);
        int c = parse(cardsEt, curC), m = parse(minutesEt, curM);
        String msg = null;
        if (c < Prefs.MIN_DAILY_CARDS) { c = Prefs.MIN_DAILY_CARDS; msg = L.t("Minimum ", "Minimum ") + Prefs.MIN_DAILY_CARDS + L.t(" cartes.", " cards."); }
        if (m < Prefs.MIN_DAILY_MINUTES) { m = Prefs.MIN_DAILY_MINUTES; msg = L.t("Minimum ", "Minimum ") + Prefs.MIN_DAILY_MINUTES + " minutes."; }
        if (c > 5000) c = 5000;
        if (m > 600) m = 600;
        if (!Prefs.isEditable(a) && (c < curC || m < curM)) {
            c = Math.max(c, curC);
            m = Math.max(m, curM);
            msg = L.t("Pendant le verrou, tu peux seulement augmenter les seuils.", "While locked you can only raise the thresholds.");
        }
        Prefs.setDailyCards(a, c);
        Prefs.setDailyMinutes(a, m);
        cardsEt.setText(String.valueOf(Prefs.dailyCards(a)));
        minutesEt.setText(String.valueOf(Prefs.dailyMinutes(a)));
        cardsEt.clearFocus();
        minutesEt.clearFocus();
        InputMethodManager imm = (InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(minutesEt.getWindowToken(), 0);
        savedTv.setTextColor(msg == null ? Ui.GREEN : Ui.AMBER);
        savedTv.setText(msg == null ? L.t("✓ Objectif enregistré : ", "✓ Goal saved: ") + Prefs.dailyCards(a) + L.t(" cartes · ", " cards · ")
                + Prefs.dailyMinutes(a) + " min" : msg);
        a.runCheck();
    }

    /** Remet les deux seuils au minimum (baisser l'objectif = seulement téléphone libre). */
    private void resetGoal() {
        if (!Prefs.isEditable(a)) {
            savedTv.setTextColor(Ui.AMBER);
            savedTv.setText(L.t("Pendant le verrou, tu peux seulement augmenter les seuils.", "While locked you can only raise the thresholds."));
            return;
        }
        Prefs.setDailyCards(a, Prefs.MIN_DAILY_CARDS);
        Prefs.setDailyMinutes(a, Prefs.MIN_DAILY_MINUTES);
        cardsEt.setText(String.valueOf(Prefs.dailyCards(a)));
        minutesEt.setText(String.valueOf(Prefs.dailyMinutes(a)));
        savedTv.setTextColor(Ui.GREEN);
        savedTv.setText(L.t("✓ Objectif remis au minimum : ", "✓ Goal reset to the minimum: ") + Prefs.dailyCards(a) + L.t(" cartes · ", " cards · ")
                + Prefs.dailyMinutes(a) + " min");
        a.runCheck();
    }

    private static int parse(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override public View view() { return root; }

    @Override public void tick() {}

    @Override
    public void refresh() {
        binding = true;
        if (!cardsEt.hasFocus()) cardsEt.setText(String.valueOf(Prefs.dailyCards(a)));
        if (!minutesEt.hasFocus()) minutesEt.setText(String.valueOf(Prefs.dailyMinutes(a)));
        swDueSufficient.setChecked(Prefs.dueSufficient(a));
        swDueRequired.setChecked(Prefs.dueRequired(a));
        boolean english = Prefs.english(a);
        a.styleChip(fr, !english);
        a.styleChip(en, english);
        swDark.setChecked(Prefs.darkMode(a));
        swWidget.setChecked(Prefs.widgetEnabled(a));
        swStrict.setChecked(Prefs.strict(a));
        int n = Prefs.whitelist(a).size();
        wlTv.setText(n + L.t(" app(s) autorisée(s) + téléphone, SMS, AnkiDroid et écran d'accueil (toujours).",
                " allowed app(s) + phone, SMS, AnkiDroid and home screen (always)."));
        Ui.enable(apps, Prefs.isEditable(a));
        boolean ron = Reminders.enabled(a);
        swRem.setChecked(ron);
        swRemDue.setChecked(Reminders.dueOn(a));
        swRemGoal.setChecked(Reminders.goalOn(a));
        swRemQuote.setChecked(Reminders.quoteOn(a));
        Ui.enable(swRemDue, ron);
        Ui.enable(swRemGoal, ron);
        Ui.enable(swRemQuote, ron);
        swAuto.setChecked(Prefs.autoArm(a));
        swCelebrate.setChecked(Prefs.sp(a).getBoolean("celebrate", true));
        boolean prot = Prefs.protectForever(a);
        swProtect.setChecked(prot);
        long offAt = Prefs.protectOffTime(a);
        protectTv.setText(!prot
                ? L.t("Bloque, même téléphone libre : la désinstallation d'Ankineer Lock (écran de désinstallation, Play Store, "
                        + "Infos de l'appli / Forcer l'arrêt) et le réglage d'accessibilité qui coupe le service. Pour l'arrêter : téléphone libre + 24 h d'attente.",
                "Blocks, even when your phone is free: uninstalling Ankineer Lock (uninstall screen, Play Store, App info / Force stop) "
                        + "and the accessibility setting that turns the service off. To stop it: phone free + 24 h wait.")
                : offAt > 0 ? L.t("⏳ Protection active, arrêt prévu le ", "⏳ Protection on, stops on ") + Prefs.hhmmDay(offAt)
                + L.t(" (réactive l'interrupteur pour annuler).", " (turn the switch back on to cancel).")
                : L.t("🔒 Active : Ankineer Lock ne peut pas être désinstallé ni coupé, même téléphone libre. "
                        + "Les réglages d'accessibilité sont aussi bloqués.",
                "🔒 On: Ankineer Lock can't be uninstalled or turned off, even when your phone is free. "
                        + "Accessibility settings are blocked too."));
        protectTv.setTextColor(prot ? Ui.GREEN : Ui.MUTED);
        if (!qFromEt.hasFocus()) qFromEt.setText(String.valueOf(Reminders.quietFrom(a)));
        if (!qToEt.hasFocus()) qToEt.setText(String.valueOf(Reminders.quietTo(a)));
        if (!Reminders.permitted(a)) {
            remInfo.setTextColor(Ui.AMBER);
            remInfo.setText(L.t("⚠ Notifications non autorisées : active-les pour recevoir les rappels.", "⚠ Notifications not allowed: enable them to get reminders."));
        } else {
            remInfo.setTextColor(Ui.MUTED);
            remInfo.setText(ron ? L.t("Actifs · de ", "On · from ") + Reminders.quietTo(a) + L.t(" h à ", " h to ") + Reminders.quietFrom(a) + " h" : L.t("Désactivés", "Off"));
        }
        int st = Prefs.homeWidgetStyle(a);
        for (int i = 0; i < 4; i++) a.styleChip(styleBtn[i], i == st);
        binding = false;
    }
}
