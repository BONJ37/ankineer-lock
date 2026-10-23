package com.ankineer.app;

import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Emergency" section: rules of the three emergencies.
 * Relaxing (enabling, shortening the wait, lengthening the duration, changing the code…) = phone free only.
 * Tightening = always possible.
 */
final class EmergencyPage implements MainActivity.Page {
    private final MainActivity a;
    private final View root;
    private TextView todayTv, pinState, stdSaved;
    private Switch swStd, swPwd, swXtr;
    private EditText waitEt, stdDurEt, pin1, pin2;
    private final Button[] perDay = new Button[4];
    private final Button[] pwdDur = new Button[4];
    private static final int[] PER_DAY = {0, 1, 2, 3};
    private static final int[] PWD_MIN = {10, 15, 20, 30};
    private boolean binding;

    EmergencyPage(MainActivity act) {
        a = act;
        LinearLayout col = Ui.column(a);

        // ---- aujourd'hui
        LinearLayout today = Ui.card(a);
        today.addView(Ui.text(a, L.t("Aujourd'hui", "Today"), 16, Ui.TEXT, true));
        todayTv = Ui.text(a, "", 14, Ui.TEXT, false);
        todayTv.setLineSpacing(Ui.dp(a, 3), 1f);
        today.addView(todayTv, Ui.topMargin(a, 8));
        a.note(today, L.t("Le code et l'extrême urgence affichent d'abord un grand avertissement en couleur : "
                        + "« Attention, c'est une autorisation exceptionnelle — il faut revenir à la révision rapidement ». "
                        + "Pendant une urgence, le cercle en haut de l'écran devient un compte à rebours rouge.",
                "The code and the extreme emergency first show a big coloured warning: "
                        + "“Warning, this is an exceptional permission — get back to your reviews quickly”. "
                        + "During an emergency, the circle at the top of the screen becomes a red countdown."));
        col.addView(today);

        // ---- emergency with a wait (removed: the card is built but never shown)
        LinearLayout st = Ui.card(a);
        swStd = a.makeSwitch(L.t("Activer l'urgence avec attente", "Enable emergency with wait"));
        st.addView(swStd);
        a.note(st, L.t("Tu attends quelques minutes (le temps de réfléchir), puis le téléphone est libre un moment. Annulée = non comptée.",
                "You wait a few minutes (time to think it over), then the phone is free for a while. Cancelled = not counted."));
        st.addView(Ui.text(a, L.t("Attente avant déblocage (minutes)", "Wait before unlocking (minutes)"), 14, Ui.TEXT, true), Ui.topMargin(a, 12));
        waitEt = a.numberField("5");
        st.addView(waitEt, Ui.topMargin(a, 6));
        st.addView(Ui.text(a, L.t("Durée libre ensuite (minutes)", "Free time afterwards (minutes)"), 14, Ui.TEXT, true), Ui.topMargin(a, 12));
        stdDurEt = a.numberField("20");
        st.addView(stdDurEt, Ui.topMargin(a, 6));
        Button saveStd = Ui.button(a, L.t("Enregistrer les durées", "Save durations"), true);
        st.addView(saveStd);
        stdSaved = Ui.text(a, "", 13, Ui.GREEN, true);
        st.addView(stdSaved, Ui.topMargin(a, 6));
        st.addView(Ui.text(a, L.t("Nombre par jour", "Times per day"), 14, Ui.TEXT, true), Ui.topMargin(a, 12));
        LinearLayout pd = new LinearLayout(a);
        for (int i = 0; i < PER_DAY.length; i++) {
            final int v = PER_DAY[i];
            perDay[i] = a.chip(v == 0 ? L.t("Illimité", "Unlimited") : String.valueOf(v));
            perDay[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { setPerDay(v); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 42), i == 0 ? 1.6f : 1f);
            if (i > 0) lp.leftMargin = Ui.dp(a, 8);
            pd.addView(perDay[i], lp);
        }
        st.addView(pd, Ui.topMargin(a, 8));
        if (Emergency.STANDARD_SUPPORTED) col.addView(st);
        saveStd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveStandard(); }
        });

        // ---- code
        a.section(col, L.t("Déblocage par code", "Unlock with a code"));
        LinearLayout pw = Ui.card(a);
        swPwd = a.makeSwitch(L.t("Activer le déblocage par code", "Enable unlock with a code"));
        pw.addView(swPwd);
        a.note(pw, L.t("Le code libère le téléphone tout de suite, UNE seule fois par jour. 5 essais maximum par jour. "
                        + "Conseil : fais choisir le code par quelqu'un de confiance, pour ne pas pouvoir te débloquer seul.",
                "The code frees the phone right away, ONCE a day only. 5 attempts max per day. "
                        + "Tip: have someone you trust choose the code, so you can't unlock on your own."));
        pinState = Ui.text(a, "", 14, Ui.TEXT, true);
        pw.addView(pinState, Ui.topMargin(a, 10));
        pin1 = pinField(L.t("Nouveau code (4 à 8 chiffres)", "New code (4 to 8 digits)"));
        pw.addView(pin1, Ui.topMargin(a, 8));
        pin2 = pinField(L.t("Confirmer le code", "Confirm the code"));
        pw.addView(pin2, Ui.topMargin(a, 8));
        Button savePin = Ui.button(a, L.t("Enregistrer le code", "Save the code"), true);
        pw.addView(savePin);
        Button clearPin = Ui.button(a, L.t("Supprimer le code", "Delete the code"), false);
        pw.addView(clearPin);
        pw.addView(Ui.text(a, L.t("Durée libre après le code", "Free time after the code"), 14, Ui.TEXT, true), Ui.topMargin(a, 14));
        LinearLayout pdur = new LinearLayout(a);
        for (int i = 0; i < PWD_MIN.length; i++) {
            final int v = PWD_MIN[i];
            pwdDur[i] = a.chip(v + " min");
            pwdDur[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { setPwdMinutes(v); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f);
            if (i > 0) lp.leftMargin = Ui.dp(a, 8);
            pdur.addView(pwdDur[i], lp);
        }
        pw.addView(pdur, Ui.topMargin(a, 8));
        col.addView(pw);
        savePin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { savePin(); }
        });
        clearPin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Emergency.clearPin(a);                       // removing a way to unlock = always allowed
                Toast.makeText(a, L.t("Code supprimé.", "Code deleted."), Toast.LENGTH_SHORT).show();
                refresh();
            }
        });

        // ---- extreme emergency
        a.section(col, L.t("Extrême urgence", "Extreme emergency"));
        LinearLayout xt = Ui.card(a);
        swXtr = a.makeSwitch(L.t("Activer l'extrême urgence (5 min)", "Enable extreme emergency (5 min)"));
        xt.addView(swXtr);
        a.note(xt, L.t("5 minutes de liberté tout de suite, UNE seule fois par jour, après l'avertissement et 5 secondes de lecture. "
                        + "Pour une vraie urgence (appel, itinéraire, paiement…).",
                "5 minutes of freedom right away, ONCE a day only, after the warning and 5 seconds of reading. "
                        + "For a real emergency (call, directions, payment…)."));
        col.addView(xt);

        a.note(col, L.t("Pendant une urgence, le verrou ne peut pas être arrêté ni l'objectif baissé, et en mode strict "
                        + "les Paramètres Android restent bloqués.",
                "During an emergency the lock can't be stopped nor the goal lowered, and in strict mode "
                        + "Android Settings stay blocked."));

        root = a.scroll(col);
        wire();
    }

    private EditText pinField(String hint) {
        EditText e = a.numberField(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return e;
    }

    /** Relaxing refused during the lock. */
    private boolean allow(boolean relax) {
        if (!relax || Prefs.isEditable(a)) return true;
        Toast.makeText(a, L.t("Pendant le verrou, tu peux seulement rendre les urgences plus strictes.",
                "While locked you can only make emergencies stricter."), Toast.LENGTH_LONG).show();
        return false;
    }

    private void wire() {
        swStd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (!allow(on)) { refresh(); return; }
                Emergency.setStandardEnabled(a, on);
                refresh();
            }
        });
        swPwd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (on && !Emergency.hasPin(a)) {
                    Toast.makeText(a, L.t("Enregistre d'abord un code.", "Save a code first."), Toast.LENGTH_SHORT).show();
                    refresh();
                    return;
                }
                if (!allow(on)) { refresh(); return; }
                Emergency.setPasswordEnabled(a, on);
                refresh();
            }
        });
        swXtr.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (binding) return;
                if (!allow(on)) { refresh(); return; }
                Emergency.setExtremeEnabled(a, on);
                refresh();
            }
        });
    }

    private void saveStandard() {
        int curW = Emergency.waitMinutes(a), curD = Emergency.standardMinutes(a);
        int w = parse(waitEt, curW), d = parse(stdDurEt, curD);
        w = Math.max(1, Math.min(60, w));
        d = Math.max(1, Math.min(60, d));
        if (!allow(w < curW || d > curD)) { refresh(); return; }
        Emergency.setWaitMinutes(a, w);
        Emergency.setStandardMinutes(a, d);
        stdSaved.setText(L.t("✓ Enregistré : ", "✓ Saved: ") + w + L.t(" min d'attente, puis ", " min wait, then ") + d + L.t(" min libres", " min free"));
        refresh();
    }

    private void setPerDay(int v) {
        int cur = Emergency.standardMaxPerDay(a);
        boolean relax = (v == 0 && cur != 0) || (cur != 0 && v > cur);
        if (!allow(relax)) return;
        Emergency.setStandardMaxPerDay(a, v);
        refresh();
    }

    private void setPwdMinutes(int v) {
        if (!allow(v > Emergency.passwordMinutes(a))) return;
        Emergency.setPasswordMinutes(a, v);
        refresh();
    }

    private void savePin() {
        if (!allow(true)) return;                            // new code = new way to unlock
        String p1 = pin1.getText().toString().trim(), p2 = pin2.getText().toString().trim();
        if (!Emergency.validPin(p1)) {
            Toast.makeText(a, L.t("Le code doit faire 4 à 8 chiffres.", "The code must be 4 to 8 digits."), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!p1.equals(p2)) {
            Toast.makeText(a, L.t("Les deux codes ne correspondent pas.", "The two codes don't match."), Toast.LENGTH_SHORT).show();
            return;
        }
        Emergency.setPin(a, p1);
        Emergency.setPasswordEnabled(a, true);
        pin1.setText("");
        pin2.setText("");
        Toast.makeText(a, L.t("Code enregistré ✓", "Code saved ✓"), Toast.LENGTH_SHORT).show();
        refresh();
    }

    private static int parse(EditText e, int fallback) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException x) { return fallback; }
    }

    @Override public View view() { return root; }

    @Override public void tick() {}

    @Override
    public void refresh() {
        binding = true;
        swStd.setChecked(Emergency.standardEnabled(a));
        swPwd.setChecked(Emergency.passwordEnabled(a));
        swXtr.setChecked(Emergency.extremeEnabled(a));
        if (!waitEt.hasFocus()) waitEt.setText(String.valueOf(Emergency.waitMinutes(a)));
        if (!stdDurEt.hasFocus()) stdDurEt.setText(String.valueOf(Emergency.standardMinutes(a)));
        int max = Emergency.standardMaxPerDay(a);
        for (int i = 0; i < perDay.length; i++) a.styleChip(perDay[i], PER_DAY[i] == max);
        int pm = Emergency.passwordMinutes(a);
        for (int i = 0; i < pwdDur.length; i++) a.styleChip(pwdDur[i], PWD_MIN[i] == pm);
        pinState.setText(Emergency.hasPin(a) ? L.t("Code enregistré ✓", "Code saved ✓") : L.t("Aucun code enregistré", "No code saved"));
        pinState.setTextColor(Emergency.hasPin(a) ? Ui.GREEN : Ui.MUTED);

        StringBuilder t = new StringBuilder();
        if (Prefs.emergencyActive(a)) {
            t.append(L.t("🔴 Urgence en cours (", "🔴 Emergency running (")).append(Emergency.typeLabel(Emergency.activeType(a)))
                    .append(L.t(") jusqu'à ", ") until ")).append(Prefs.hhmm(Prefs.emergencyUntil(a))).append("\n");
        }
        int used = Emergency.standardUsedToday(a);
        if (Emergency.STANDARD_SUPPORTED) t.append("⏳ ").append(L.t("Avec attente : ", "With wait: ")).append(!Emergency.standardEnabled(a) ? L.t("désactivée", "off")
                : used + L.t(" utilisée(s)", " used") + (max > 0 ? " / " + max : L.t(" (illimité)", " (unlimited)"))).append("\n");
        t.append("🔑 ").append(L.t("Code : ", "Code: ")).append(!Emergency.passwordEnabled(a) ? L.t("désactivé", "off")
                : Emergency.passwordUsedToday(a) ? L.t("utilisé aujourd'hui", "used today")
                : Emergency.pinFailsToday(a) >= Emergency.MAX_PIN_FAILS ? L.t("bloqué (trop d'essais)", "locked (too many attempts)")
                : L.t("disponible", "available")).append("\n");
        t.append("🚨 ").append(L.t("Extrême urgence : ", "Extreme emergency: ")).append(!Emergency.extremeEnabled(a) ? L.t("désactivée", "off")
                : Emergency.extremeUsedToday(a) ? L.t("utilisée aujourd'hui", "used today") : L.t("disponible", "available"));
        todayTv.setText(t.toString());
        binding = false;
    }
}
