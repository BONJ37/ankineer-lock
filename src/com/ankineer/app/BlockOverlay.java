package com.ankineer.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Block screen drawn directly by the accessibility service
 * ("accessibility overlay" window): no extra permission, and it
 * works even on phones that prevent opening an activity
 * from the background (Xiaomi, Oppo, Huawei…).
 *
 * Emergencies: by code (built-in keypad: the Android keyboard cannot
 * show above this window) · extreme emergency. Code and extreme emergency first go
 * through a big coloured warning.
 */
final class BlockOverlay {
    private static final int MODE_NORMAL = 0, MODE_PIN = 1, MODE_EXTREME = 2;
    private static final int READ_SECONDS = 5;   // mandatory reading time before the extreme emergency

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    private View root;
    private LinearLayout normalBox, warnBox, pinBox, xtrBox;
    private TextView lblTv, subTv, statusTv, emLabel, emInfo, warnDetail, pinDots, pinMsg;
    private Ui.Bar bar;
    private Button btnStd, btnPwd, btnXtr, btnXtrGo, btnWarnCancel, btnRepair;
    private boolean closing = false;
    private boolean confirmStd = false;
    private int mode = MODE_NORMAL;
    private long warnShownAt;
    private final StringBuilder pin = new StringBuilder();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateState();
            if (root != null) h.postDelayed(this, 1000);
        }
    };

    BlockOverlay(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return root != null; }

    void show(String pkg) {
        if (root == null) {
            build();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            try {
                wm.addView(root, lp);
            } catch (Exception e) {
                root = null;
                Toast.makeText(svc, L.t("Ankineer : application bloquée jusqu'à ton objectif Anki", "Ankineer: app blocked until your Anki goal"), Toast.LENGTH_LONG).show();
                return;
            }
            h.removeCallbacks(tick);
            h.post(tick);
        }
        closing = false;
        confirmStd = false;
        setMode(MODE_NORMAL);
        String name = pkg != null ? Apps.label(svc, pkg) : L.t("Cette application", "This app");
        subTv.setText(name + (Prefs.sessionActive(svc)
                ? L.t(" est bloquée jusqu'à la fin de ta session.", " is blocked until your session ends.")
                : L.t(" est bloquée jusqu'à ton objectif Anki.", " is blocked until your Anki goal.")));
        lblTv.setText(Prefs.sessionActive(svc) ? L.t("SESSION EN COURS", "SESSION RUNNING") : L.t("OBJECTIF DU JOUR", "DAILY GOAL"));
        statusTv.setText(Prefs.lastStatus(svc));
        statusTv.setTextColor(Ui.TEXT);
        runCheck();
    }

    void hide() {
        h.removeCallbacks(tick);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
            root = null;
        }
        confirmStd = false;
        pin.setLength(0);
    }

    // ------------------------------------------------------------------ construction

    private void build() {
        final Context c = svc;
        L.load(c);
        Ui.applyTheme(c);
        ScrollView sv = new ScrollView(c);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        int p = Ui.dp(c, 24);
        col.setPadding(p, Ui.dp(c, 40), p, p);
        sv.addView(col, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));

        // ======== vue normale
        normalBox = Ui.column(c);
        normalBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView lock = Ui.text(c, "🔒", 56, Ui.TEXT, false);
        lock.setGravity(Gravity.CENTER);
        normalBox.addView(lock);
        TextView title = Ui.text(c, L.t("Pas encore !", "Not yet!"), 28, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        normalBox.addView(title, Ui.topMargin(c, 8));
        subTv = Ui.text(c, "", 15, Ui.MUTED, false);
        subTv.setGravity(Gravity.CENTER);
        normalBox.addView(subTv, Ui.topMargin(c, 6));

        LinearLayout card = Ui.card(c);
        card.setLayoutParams(Ui.topMargin(c, 22));
        lblTv = Ui.text(c, "", 12, Ui.MUTED, true);
        lblTv.setLetterSpacing(0.08f);
        card.addView(lblTv);
        statusTv = Ui.text(c, "", 18, Ui.TEXT, true);
        card.addView(statusTv, Ui.topMargin(c, 6));
        bar = new Ui.Bar(c);
        bar.setTrack(Ui.STROKE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 6));
        blp.topMargin = Ui.dp(c, 12);
        card.addView(bar, blp);
        normalBox.addView(card);

        // AnkiDroid unreadable (access revoked, AnkiDroid reinstalled…): direct path to the fix
        btnRepair = Ui.button(c, L.t("\uD83D\uDD27  Réparer l'accès à AnkiDroid", "\uD83D\uDD27  Repair AnkiDroid access"), true);
        btnRepair.setBackground(Ui.round(c, Ui.AMBER, 12, 0));
        btnRepair.setVisibility(View.GONE);
        btnRepair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAnkineer(MainActivity.INSTALL); }
        });
        normalBox.addView(btnRepair);

        Button anki = Ui.button(c, L.t("Ouvrir AnkiDroid", "Open AnkiDroid"), true);
        anki.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAnki(); }
        });
        normalBox.addView(anki);
        Button check = Ui.button(c, L.t("J'ai fini — vérifier", "I'm done — check"), false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                statusTv.setText(L.t("Vérification…", "Checking…"));
                statusTv.setTextColor(Ui.TEXT);
                runCheck();
            }
        });
        normalBox.addView(check);
        Button close = Ui.button(c, L.t("Fermer (retour à l'accueil)", "Close (back to home)"), false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        });
        normalBox.addView(close);

        // urgences
        emLabel = Ui.text(c, L.t("BESOIN D'UNE URGENCE ?", "NEED AN EMERGENCY?"), 12, Ui.MUTED, true);
        emLabel.setLetterSpacing(0.08f);
        emLabel.setGravity(Gravity.CENTER);
        normalBox.addView(emLabel, Ui.topMargin(c, 26));
        emInfo = Ui.text(c, "", 14, Ui.AMBER, true);
        emInfo.setGravity(Gravity.CENTER);
        normalBox.addView(emInfo, Ui.topMargin(c, 6));
        btnStd = emButton(c);
        btnPwd = emButton(c);
        btnXtr = emButton(c);
        normalBox.addView(btnStd);
        normalBox.addView(btnPwd);
        normalBox.addView(btnXtr);
        btnStd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onStandard(); }
        });
        btnPwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (Emergency.passwordAvailable(svc)) setMode(MODE_PIN); }
        });
        btnXtr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (Emergency.extremeAvailable(svc)) setMode(MODE_EXTREME); }
        });
        col.addView(normalBox);

        // ======== big warning (code / extreme emergency)
        warnBox = Ui.column(c);
        warnBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView warnIcon = Ui.text(c, "⚠️", 54, Ui.TEXT, false);
        warnIcon.setGravity(Gravity.CENTER);
        warnBox.addView(warnIcon);
        TextView warnTitle = Ui.text(c, L.t("ATTENTION", "WARNING"), 36, Ui.RED, true);
        warnTitle.setGravity(Gravity.CENTER);
        warnTitle.setLetterSpacing(0.06f);
        warnBox.addView(warnTitle, Ui.topMargin(c, 4));
        TextView w1 = Ui.text(c, L.t("C'est une autorisation exceptionnelle, pas une pause.",
                "This is an exceptional permission, not a break."), 22, Ui.AMBER, true);
        w1.setGravity(Gravity.CENTER);
        warnBox.addView(w1, Ui.topMargin(c, 12));
        TextView w2 = Ui.text(c, L.t("Il faut revenir à la révision rapidement !", "Get back to your reviews quickly!"), 24, Ui.RED, true);
        w2.setGravity(Gravity.CENTER);
        warnBox.addView(w2, Ui.topMargin(c, 12));
        warnDetail = Ui.text(c, "", 15, Ui.MUTED, true);
        warnDetail.setGravity(Gravity.CENTER);
        warnBox.addView(warnDetail, Ui.topMargin(c, 14));

        // numeric keypad
        pinBox = Ui.column(c);
        pinBox.setGravity(Gravity.CENTER_HORIZONTAL);
        pinDots = Ui.text(c, "", 30, Ui.TEXT, true);
        pinDots.setGravity(Gravity.CENTER);
        pinDots.setLetterSpacing(0.3f);
        pinBox.addView(pinDots, Ui.topMargin(c, 16));
        pinMsg = Ui.text(c, "", 14, Ui.RED, true);
        pinMsg.setGravity(Gravity.CENTER);
        pinBox.addView(pinMsg, Ui.topMargin(c, 4));
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✓"}};
        for (String[] row : keys) {
            LinearLayout r = new LinearLayout(c);
            for (int i = 0; i < row.length; i++) {
                final String k = row[i];
                Button b = new Button(c);
                b.setText(k);
                b.setTextSize(22);
                b.setAllCaps(false);
                b.setStateListAnimator(null);
                b.setTypeface(Typeface.DEFAULT_BOLD);
                boolean ok = "✓".equals(k);
                b.setTextColor(ok ? 0xFFFFFFFF : Ui.TEXT);
                b.setBackground(ok ? Ui.round(c, Ui.ACCENT, 14, 0) : Ui.round(c, Ui.CARD, 14, Ui.STROKE));
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onKey(k); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 58), 1f);
                if (i > 0) lp.leftMargin = Ui.dp(c, 10);
                r.addView(b, lp);
            }
            LinearLayout.LayoutParams rl = Ui.topMargin(c, 10);
            pinBox.addView(r, rl);
        }
        warnBox.addView(pinBox);

        // extreme emergency confirmation
        xtrBox = Ui.column(c);
        btnXtrGo = Ui.button(c, "", true);
        btnXtrGo.setBackground(Ui.round(c, Ui.RED, 12, 0));
        btnXtrGo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (System.currentTimeMillis() - warnShownAt < READ_SECONDS * 1000L) return;
                if (Emergency.tryExtreme(svc)) granted();
                else setMode(MODE_NORMAL);
            }
        });
        xtrBox.addView(btnXtrGo, Ui.topMargin(c, 18));
        warnBox.addView(xtrBox);

        btnWarnCancel = Ui.button(c, L.t("Annuler — je retourne réviser", "Cancel — back to reviewing"), false);
        btnWarnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(MODE_NORMAL); }
        });
        warnBox.addView(btnWarnCancel, Ui.topMargin(c, 6));
        col.addView(warnBox);

        TextView brand = Ui.text(c, "Developed by Ankineer", 11, Ui.MUTED, false);
        brand.setGravity(Gravity.CENTER);
        col.addView(brand, Ui.topMargin(c, 18));

        root = sv;
    }

    private Button emButton(Context c) {
        Button b = Ui.button(c, "", false);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    // ------------------------------------------------------------------ modes

    private void setMode(int m) {
        mode = m;
        pin.setLength(0);
        normalBox.setVisibility(m == MODE_NORMAL ? View.VISIBLE : View.GONE);
        warnBox.setVisibility(m == MODE_NORMAL ? View.GONE : View.VISIBLE);
        pinBox.setVisibility(m == MODE_PIN ? View.VISIBLE : View.GONE);
        xtrBox.setVisibility(m == MODE_EXTREME ? View.VISIBLE : View.GONE);
        btnWarnCancel.setVisibility(View.VISIBLE);
        warnShownAt = System.currentTimeMillis();
        if (m == MODE_PIN) {
            warnDetail.setTextColor(Ui.MUTED);
            warnDetail.setText(L.t("Code : téléphone libre ", "Code: phone free for ") + Emergency.passwordMinutes(svc)
                    + L.t(" min · une seule fois par jour", " min · once a day only"));
            pinMsg.setText(L.t("Entre ton code", "Enter your code"));
            pinMsg.setTextColor(Ui.MUTED);
            renderPin();
        } else if (m == MODE_EXTREME) {
            warnDetail.setTextColor(Ui.MUTED);
            warnDetail.setText(L.t("Extrême urgence : téléphone libre ", "Extreme emergency: phone free for ") + Emergency.EXTREME_MINUTES
                    + L.t(" min · une seule fois par jour", " min · once a day only"));
            renderExtreme();
        }
        if (root instanceof ScrollView) ((ScrollView) root).scrollTo(0, 0);
    }

    private void renderPin() {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) d.append('●');
        for (int i = pin.length(); i < Emergency.PIN_MIN_LEN; i++) d.append('○');
        pinDots.setText(d.toString());
    }

    private void renderExtreme() {
        long left = READ_SECONDS - (System.currentTimeMillis() - warnShownAt) / 1000;
        boolean ready = left <= 0;
        btnXtrGo.setEnabled(ready);
        btnXtrGo.setAlpha(ready ? 1f : 0.5f);
        btnXtrGo.setText(ready ? L.t("J'ai compris — débloquer 5 min", "I understand — unlock 5 min")
                : L.t("Lis l'avertissement… ", "Read the warning… ") + left);
    }

    private void onKey(String k) {
        if ("⌫".equals(k)) {
            if (pin.length() > 0) pin.setLength(pin.length() - 1);
        } else if ("✓".equals(k)) {
            if (pin.length() < Emergency.PIN_MIN_LEN) {
                pinMsg.setText(L.t("Au moins 4 chiffres", "At least 4 digits"));
                pinMsg.setTextColor(Ui.AMBER);
                return;
            }
            int r = Emergency.tryPassword(svc, pin.toString());
            pin.setLength(0);
            if (r == Emergency.PIN_OK) { granted(); return; }
            if (r == Emergency.PIN_WRONG) {
                int left = Emergency.MAX_PIN_FAILS - Emergency.pinFailsToday(svc);
                pinMsg.setText(L.t("Code incorrect · ", "Wrong code · ") + left + L.t(" essai(s) restant(s) aujourd'hui", " attempt(s) left today"));
            } else if (r == Emergency.PIN_BLOCKED) {
                pinMsg.setText(L.t("Trop d'essais : code bloqué jusqu'à demain 4h", "Too many attempts: code locked until tomorrow 4 am"));
            } else {
                pinMsg.setText(L.t("Code indisponible aujourd'hui", "Code unavailable today"));
            }
            pinMsg.setTextColor(Ui.RED);
        } else if (pin.length() < Emergency.PIN_MAX_LEN) {
            pin.append(k);
        }
        renderPin();
    }

    /** Emergency granted: reminder, then close the screen. */
    private void granted() {
        closing = true;
        pinBox.setVisibility(View.GONE);
        xtrBox.setVisibility(View.GONE);
        btnWarnCancel.setVisibility(View.GONE);
        warnDetail.setTextColor(Ui.GREEN);
        warnDetail.setTextSize(18);
        warnDetail.setText(L.t("✓ Autorisé jusqu'à ", "✓ Allowed until ") + Prefs.hhmm(Prefs.emergencyUntil(svc))
                + L.t("\nReviens vite à ta révision !", "\nGet back to your reviews soon!"));
        h.postDelayed(new Runnable() {
            @Override public void run() { hide(); }
        }, 3000);
    }

    // ------------------------------------------------------------------ update

    private void runCheck() {
        final Context ctx = svc.getApplicationContext();
        Ui.safeThread(svc, new Runnable() {
            @Override public void run() {
                final GoalChecker.Result r = GoalChecker.check(ctx);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (root == null || closing) return;
                        statusTv.setText(r.text);
                        statusTv.setTextColor(r.apiOk ? Ui.TEXT : Ui.AMBER);
                        btnRepair.setVisibility(r.apiOk ? View.GONE : View.VISIBLE);
                        bar.set(r.fraction, r.session ? ProgressWidget.C_SESSION : Ui.ACCENT);
                        updateState();
                    }
                });
            }
        }).start();
    }

    private void updateState() {
        if (root == null || closing) return;
        if (!Prefs.lockActive(svc)) {
            int reason = Prefs.unlockReason(svc);
            if (Prefs.emergencyActive(svc)) {
                release(L.t("Urgence : libre jusqu'à ", "Emergency: free until ") + Prefs.hhmm(Prefs.emergencyUntil(svc))
                        + L.t(" — reviens vite !", " — come back soon!"));
            } else if (System.currentTimeMillis() - Prefs.sessionDoneAt(svc) < 15000) {
                release(L.t("Session terminée ✓", "Session complete ✓"));
            } else if (reason == Prefs.REASON_GOAL) {
                release(L.t("Objectif atteint ✓", "Goal reached ✓"));
            } else {
                release(L.t("Blocage terminé", "Lock over"));
            }
            return;
        }
        if (mode == MODE_EXTREME) { renderExtreme(); return; }
        if (mode == MODE_PIN) return;

        GoalChecker.Result snap = GoalChecker.snapshot(svc);      // memory only
        if (snap.apiOk && !snap.text.contentEquals(statusTv.getText())) {
            statusTv.setText(snap.text);
            statusTv.setTextColor(Ui.TEXT);
        }
        bar.set(snap.fraction, snap.session ? ProgressWidget.C_SESSION : Ui.ACCENT);
        renderEmergencies();
    }

    private void renderEmergencies() {
        boolean std = Emergency.standardEnabled(svc), pwd = Emergency.passwordEnabled(svc), xtr = Emergency.extremeEnabled(svc);
        btnStd.setVisibility(std ? View.VISIBLE : View.GONE);
        btnPwd.setVisibility(pwd ? View.VISIBLE : View.GONE);
        btnXtr.setVisibility(xtr ? View.VISIBLE : View.GONE);

        long req = Prefs.emergencyRequestedAt(svc);
        int max = Emergency.standardMaxPerDay(svc), used = Emergency.standardUsedToday(svc);
        if (!std) {
            emInfo.setVisibility(View.GONE);
        } else if (req > 0) {
            long s = Math.max(0, req + Emergency.pendingWaitMs(svc) - Clock.now()) / 1000;
            emInfo.setText(String.format(Locale.FRANCE, L.t("Déverrouillage d'urgence dans %d:%02d", "Emergency unlock in %d:%02d"), s / 60, s % 60));
            emInfo.setVisibility(View.VISIBLE);
            btnStd.setText(L.t("Annuler l'urgence (elle ne sera pas comptée)", "Cancel the emergency (it won't count)"));
            Ui.enable(btnStd, true);
        } else if (confirmStd) {
            emInfo.setText(L.t("Après ", "After a ") + Emergency.waitMinutes(svc) + L.t(" min d'attente, tout est libre pendant ", "-min wait, everything is free for ")
                    + Emergency.standardMinutes(svc) + " min."
                    + (used > 0 ? L.t(" Déjà utilisée ", " Already used ") + used + L.t("× aujourd'hui.", "× today.") : ""));
            emInfo.setVisibility(View.VISIBLE);
            btnStd.setText(L.t("Confirmer l'urgence avec attente", "Confirm emergency with wait"));
            Ui.enable(btnStd, true);
        } else {
            emInfo.setVisibility(View.GONE);
            boolean avail = Emergency.standardAvailable(svc);
            btnStd.setText(avail
                    ? "⏳  " + L.t("Urgence avec attente — ", "Emergency with wait — ") + Emergency.waitMinutes(svc) + L.t(" min puis ", " min, then ")
                    + Emergency.standardMinutes(svc) + L.t(" min libres", " min free") + (max > 0 ? "  (" + used + "/" + max + ")" : "")
                    : "⏳  " + L.t("Urgence avec attente — limite du jour atteinte", "Emergency with wait — daily limit reached"));
            Ui.enable(btnStd, avail);
        }

        if (pwd) {
            boolean usedP = Emergency.passwordUsedToday(svc), blocked = Emergency.pinFailsToday(svc) >= Emergency.MAX_PIN_FAILS;
            btnPwd.setText("🔑  " + (usedP ? L.t("Code déjà utilisé aujourd'hui", "Code already used today")
                    : blocked ? L.t("Code bloqué jusqu'à demain (trop d'essais)", "Code locked until tomorrow (too many attempts)")
                    : L.t("Débloquer avec le code — ", "Unlock with the code — ") + Emergency.passwordMinutes(svc) + " min"));
            Ui.enable(btnPwd, Emergency.passwordAvailable(svc));
        }
        if (xtr) {
            boolean usedX = Emergency.extremeUsedToday(svc);
            btnXtr.setText("🚨  " + (usedX ? L.t("Extrême urgence déjà utilisée aujourd'hui", "Extreme emergency already used today")
                    : L.t("Extrême urgence — 5 min tout de suite", "Extreme emergency — 5 min right now")));
            btnXtr.setTextColor(usedX ? Ui.MUTED : Ui.RED);
            Ui.enable(btnXtr, !usedX);
        }
        boolean any = std || pwd || xtr;
        emLabel.setText(any ? L.t("BESOIN D'UNE URGENCE ?", "NEED AN EMERGENCY?")
                : L.t("AUCUNE URGENCE ACTIVÉE (RÉGLABLE DANS ANKINEER LOCK → URGENCE)", "NO EMERGENCY ENABLED (SET IT IN ANKINEER LOCK → EMERGENCY)"));
    }

    private void onStandard() {
        if (Prefs.emergencyRequestedAt(svc) > 0) {
            Prefs.cancelEmergency(svc);
        } else if (!confirmStd) {
            if (!Emergency.standardAvailable(svc)) return;
            confirmStd = true;
        } else {
            confirmStd = false;
            Emergency.requestStandard(svc);
        }
        updateState();
    }

    private void release(String msg) {
        if (closing) return;
        closing = true;
        if (mode != MODE_NORMAL) setMode(MODE_NORMAL);
        statusTv.setText(msg);
        statusTv.setTextColor(Ui.GREEN);
        h.postDelayed(new Runnable() {
            @Override public void run() { hide(); }
        }, 1500);
    }

    /** Opens Ankineer Lock (always allowed) on a given page. */
    private void openAnkineer(int page) {
        hide();
        Intent i = new Intent(svc, MainActivity.class);
        i.putExtra("page", page);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { svc.startActivity(i); } catch (Exception ignored) {}
    }

    private void openAnki() {
        Intent li = svc.getPackageManager().getLaunchIntentForPackage(GoalChecker.ANKI_PKG);
        hide();
        if (li == null) {
            Toast.makeText(svc, L.t("AnkiDroid n'est pas installé", "AnkiDroid is not installed"), Toast.LENGTH_SHORT).show();
            return;
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { svc.startActivity(li); } catch (Exception ignored) {}
    }
}
