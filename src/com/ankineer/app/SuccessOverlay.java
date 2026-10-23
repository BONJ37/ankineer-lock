package com.ankineer.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Big success window shown by the service as soon as a goal is completed (daily goal or session),
 * even over AnkiDroid: congratulations, numbers, motivational quote. Closes itself after 25 s.
 */
final class SuccessOverlay {
    static final int DAILY = 0, SESSION = 1;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View root;

    SuccessOverlay(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return root != null; }

    private final Runnable autoClose = new Runnable() {
        @Override public void run() { hide(); }
    };

    void show(int kind, GoalChecker.Result r) {
        try {
            hide();
            L.load(svc);
            Ui.applyTheme(svc);
            root = build(kind, r);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            if (wm != null) wm.addView(root, lp);
            main.removeCallbacks(autoClose);
            main.postDelayed(autoClose, 25000);
        } catch (Throwable t) {
            root = null;
            CrashLog.save(svc, t);
        }
    }

    void hide() {
        main.removeCallbacks(autoClose);
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
    }

    private View build(int kind, GoalChecker.Result r) {
        Context c = svc;
        FrameLayout shade = new FrameLayout(c);
        shade.setBackgroundColor(0xCC000000);
        shade.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        });

        LinearLayout card = Ui.column(c);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(c, Ui.CARD, 26, Ui.GREEN));
        int p = Ui.dp(c, 24);
        card.setPadding(p, p, p, p);
        card.setClickable(true);

        TextView emoji = Ui.text(c, kind == SESSION ? "🏁" : "🎉", 56, Ui.TEXT, false);
        emoji.setGravity(Gravity.CENTER);
        card.addView(emoji);

        TextView title = Ui.text(c, kind == SESSION ? L.t("Session terminée !", "Session complete!")
                : L.t("Objectif du jour atteint !", "Daily goal reached!"), 26, Ui.GREEN, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title, Ui.topMargin(c, 6));

        String detail;
        if (kind == SESSION && r != null) {
            boolean min = Prefs.SESSION_MINUTES.equals(r.sessionType);
            detail = r.sessionTarget + (min ? " minutes" : L.t(" cartes", " cards")) + L.t(" faites. Bravo !", " done. Well done!");
        } else if (r != null) {
            detail = r.cards + L.t(" cartes · ", " cards · ") + r.minutes + " min"
                    + (Prefs.armed(svc) ? L.t("\nTon téléphone est libre jusqu'à demain.", "\nYour phone is free until tomorrow.") : "");
            int streak = Stats.streak(svc);
            if (streak > 1) detail += "\n🔥 " + streak + L.t(" jours d'affilée", " days in a row");
        } else {
            detail = "";
        }
        TextView d = Ui.text(c, detail, 16, Ui.TEXT, false);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(Ui.dp(c, 3), 1f);
        card.addView(d, Ui.topMargin(c, 10));

        TextView q = Ui.text(c, "💬 " + Quotes.ofTheHour((int) (System.currentTimeMillis() / 60000L % Quotes.count())), 15, Ui.MUTED, false);
        q.setGravity(Gravity.CENTER);
        q.setLineSpacing(Ui.dp(c, 3), 1f);
        q.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC));
        card.addView(q, Ui.topMargin(c, 16));

        Button ok = Ui.button(c, L.t("Continuer", "Continue"), true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        });
        card.addView(ok);

        TextView brand = Ui.text(c, "Ankineer Lock", 11, Ui.MUTED, false);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, Ui.topMargin(c, 8));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        int m = Ui.dp(c, 22);
        lp.leftMargin = m;
        lp.rightMargin = m;
        shade.addView(card, lp);
        return shade;
    }
}
