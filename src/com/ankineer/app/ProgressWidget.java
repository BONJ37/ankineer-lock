package com.ankineer.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Petit cercle flottant en haut de l'écran :
 *  - objectif du jour : anneau extérieur = cartes, anneau intérieur = minutes, % au centre ;
 *  - session : un anneau + ce qu'il reste au centre ;
 * toucher = ouvrir Ankineer · glisser = déplacer (position mémorisée).
 */
final class ProgressWidget {
    static final int C_CARDS = 0xFF5B8CFF, C_MINUTES = 0xFFB16CFF, C_SESSION = 0xFFFFB547, C_DONE = 0xFF3DDC97, C_EMERGENCY = 0xFFFF5A5A;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private Ring ring;
    private WindowManager.LayoutParams lp;

    ProgressWidget(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() { return ring != null; }

    void showDaily(float fCards, float fMinutes, String center) {
        if (ring == null && !attach()) return;
        ring.set(fCards, C_CARDS, fMinutes, C_MINUTES, center, null);
    }

    void showSession(float f, int remaining, boolean minutes) {
        if (ring == null && !attach()) return;
        ring.set(f, C_SESSION, -1f, 0, String.valueOf(remaining), minutes ? "min" : "cartes");
    }

    /** Urgence en cours : anneau rouge qui se vide + temps restant (rappel de revenir réviser). */
    void showEmergency(float fractionLeft, String left) {
        if (ring == null && !attach()) return;
        ring.set(fractionLeft, C_EMERGENCY, -1f, 0, left, null);
    }

    void showDone() {
        if (ring == null && !attach()) return;
        ring.set(1f, C_DONE, -1f, 0, "✓", null);
    }

    void hide() {
        if (ring != null) {
            try { wm.removeView(ring); } catch (Exception ignored) {}
            ring = null;
        }
    }

    private boolean attach() {
        final Context c = svc;
        ring = new Ring(c);
        final int size = Ui.dp(c, 52);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = Prefs.widgetX(c);
        int y = Prefs.widgetY(c);
        lp.y = y >= 0 ? y : statusBarHeight(c) + Ui.dp(c, 6);

        ring.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.abs(dx) + Math.abs(dy) > Ui.dp(c, 8)) moved = true;
                        if (moved) {
                            lp.x = ox + Math.round(dx);
                            lp.y = Math.max(0, oy + Math.round(dy));
                            try { wm.updateViewLayout(ring, lp); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) Prefs.setWidgetPos(c, lp.x, lp.y);
                        else openAnkineer();
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(ring, lp);
            return true;
        } catch (Exception e) {
            ring = null;
            return false;
        }
    }

    private void openAnkineer() {
        Intent i = new Intent(svc, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { svc.startActivity(i); } catch (Exception ignored) {}
    }

    private static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : Ui.dp(c, 24);
    }

    /** Anneau(x) de progression dessiné(s) à la main : aucune animation, redessin seulement si ça change. */
    static final class Ring extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
                txt = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float f1, f2 = -1f;
        private int c1, c2;
        private String center = "", unit;
        private final float d;

        Ring(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            disc.setColor(0xF011151C);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(d);
            edge.setColor(0x40FFFFFF);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0x2EFFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            txt.setColor(0xFFFFFFFF);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            sub.setColor(0xB3FFFFFF);
            sub.setTextAlign(Paint.Align.CENTER);
            sub.setTextSize(7.5f * d);
        }

        void set(float a, int ca, float b, int cb, String centerText, String unitText) {
            if (a == f1 && b == f2 && ca == c1 && cb == c2 && centerText.equals(center)
                    && (unitText == null ? unit == null : unitText.equals(unit))) return;
            f1 = a; c1 = ca; f2 = b; c2 = cb; center = centerText; unit = unitText;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f - d;
            cv.drawCircle(cx, cy, R, disc);
            cv.drawCircle(cx, cy, R, edge);
            boolean two = f2 >= 0f;
            float sw = (two ? 3.6f : 4.4f) * d;
            track.setStrokeWidth(sw);
            arc.setStrokeWidth(sw);
            float r1 = R - sw / 2f - 2.2f * d;
            drawArc(cv, cx, cy, r1, f1, c1);
            if (two) drawArc(cv, cx, cy, r1 - sw - 1.6f * d, f2, c2);

            float inner = two ? r1 - 2 * sw - 1.6f * d : r1 - sw;
            txt.setTextSize(Math.min(12.5f * d, inner * (center.length() > 3 ? 0.62f : 0.8f)));
            if (unit == null) {
                cv.drawText(center, cx, cy - (txt.descent() + txt.ascent()) / 2f, txt);
            } else {
                cv.drawText(center, cx, cy + 1.2f * d, txt);
                cv.drawText(unit, cx, cy + 1.2f * d + sub.getTextSize() + 0.6f * d, sub);
            }
        }

        private void drawArc(Canvas cv, float cx, float cy, float rad, float f, int color) {
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            cv.drawArc(r, 0, 360, false, track);
            if (f <= 0f) return;
            arc.setColor(color);
            cv.drawArc(r, -90, Math.max(4f, 360f * Math.min(1f, f)), false, arc);
        }
    }
}
