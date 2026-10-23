package com.ankineer.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small UI helpers (static UI, no animation). */
final class Ui {
    // Theme colours (changed by applyTheme: dark or light)
    static int BG = 0xFF0E1117;
    static int CARD = 0xFF171B23;
    static int STROKE = 0xFF262C38;
    static int TEXT = 0xFFE8ECF2;
    static int MUTED = 0xFF8A93A3;
    static boolean DARK = true;
    static final int ACCENT = 0xFF5B8CFF;
    static int GREEN = 0xFF3DDC97;
    static int RED = 0xFFFF6B6B;
    static int AMBER = 0xFFFFB547;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Applies the theme chosen in settings (call before building a view). */
    static void applyTheme(Context c) {
        DARK = Prefs.darkMode(c);
        if (DARK) {
            BG = 0xFF0E1117; CARD = 0xFF171B23; STROKE = 0xFF262C38; TEXT = 0xFFE8ECF2; MUTED = 0xFF8A93A3;
            GREEN = 0xFF3DDC97; RED = 0xFFFF6B6B; AMBER = 0xFFFFB547;
        } else {
            BG = 0xFFF3F5F9; CARD = 0xFFFFFFFF; STROKE = 0xFFDFE3EA; TEXT = 0xFF161B26; MUTED = 0xFF6A7283;
            GREEN = 0xFF17955B; RED = 0xFFD43B3B; AMBER = 0xFFB36A00;
        }
    }

    static void paintWindow(Activity a) {
        applyTheme(a);
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(DARK ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    static GradientDrawable round(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(c, CARD, 16, STROKE));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static View stepTitle(Context c, String num, String title) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(c, num, 13, 0xFFFFFFFF, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ACCENT);
        badge.setBackground(g);
        int s = dp(c, 24);
        row.addView(badge, new LinearLayout.LayoutParams(s, s));
        TextView t = text(c, title, 16, TEXT, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 10);
        row.addView(t, lp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(c, 8);
        row.setLayoutParams(rlp);
        return row;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setBackground(primary ? round(c, ACCENT, 12, 0) : round(c, 0x00000000, 12, STROKE));
        b.setMinHeight(dp(c, 48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    /** Thin progress bar, no animation. */
    static final class Bar extends View {
        private final android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private float fraction;

        Bar(Context c) {
            super(c);
            bg.setColor(DARK ? 0x33FFFFFF : 0x1F000000);
            fg.setColor(ACCENT);
        }

        void set(float f, int color) {
            float nf = Math.max(0f, Math.min(1f, f));
            if (nf == fraction && fg.getColor() == color) return;
            fraction = nf;
            fg.setColor(color);
            invalidate();
        }

        void setTrack(int color) { bg.setColor(color); invalidate(); }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, bg);
            if (fraction > 0) {
                rect.set(0, 0, Math.max(h, w * fraction), h);
                cv.drawRoundRect(rect, r, r, fg);
            }
        }
    }

    static LinearLayout.LayoutParams topMargin(Context c, float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, dp);
        return lp;
    }

    /**
     * Protected background thread: an error there is recorded in the error report instead of closing the app
     * (on Android, an unhandled exception in any thread stops the whole application).
     */
    static Thread safeThread(final android.content.Context c, final Runnable r) {
        return new Thread(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    try { CrashLog.save(c, t); } catch (Throwable ignored) {}
                }
            }
        });
    }
}
