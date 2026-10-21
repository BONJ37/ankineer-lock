package com.ankineer.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Dessine les widgets d'écran d'accueil (image complète : liberté totale de style).
 * Types : anneaux (2×2), mini (1×1), barres (4×1), semaine (4×2).
 * Styles : sombre, clair, verre (transparent), dégradé.
 */
final class WidgetRenderer {
    static final int RINGS = 0, MINI = 1, BARS = 2, WEEK = 3;
    static final int DARK = 0, LIGHT = 1, GLASS = 2, GRADIENT = 3;

    static final int STATE_NOT_ARMED = 0, STATE_LOCKED = 1, STATE_FREE = 2, STATE_SESSION = 3, STATE_EMERGENCY = 4, STATE_NO_DATA = 5;

    /** Données affichées (construites par WidgetUpdater). */
    static final class Data {
        int state;
        int cards, goalCards, minutes, goalMinutes, due = -1;
        float fCards, fMinutes, fSession, fEmergency;
        int sessionLeft;
        boolean sessionMinutes;
        String emergencyLeft = "";
        int streak;
        int[] weekCards = new int[7];
        boolean[] weekMet = new boolean[7];
        String[] weekLabels = new String[7];
        boolean[] weekKnown = new boolean[7];
    }

    private static final class Theme {
        int bg1, bg2, border, text, sub, track, cards, minutes, session, done, emergency, locked;
        boolean gradient;
    }

    private WidgetRenderer() {}

    private static Theme theme(int style) {
        Theme t = new Theme();
        t.session = 0xFFFFB547; t.done = 0xFF3DDC97; t.emergency = 0xFFFF5A5A; t.locked = 0xFFFF6B6B;
        switch (style) {
            case LIGHT:
                t.bg1 = t.bg2 = 0xFFFFFFFF; t.border = 0x14000000; t.text = 0xFF161B26; t.sub = 0xFF6A7283; t.track = 0x16000000;
                t.cards = 0xFF2F6BFF; t.minutes = 0xFF8B3DFF; t.done = 0xFF17955B; t.session = 0xFFE08A00; t.locked = 0xFFD43B3B; t.emergency = 0xFFD43B3B;
                break;
            case GLASS:
                t.bg1 = t.bg2 = 0x66000000; t.border = 0x40FFFFFF; t.text = 0xFFFFFFFF; t.sub = 0xCCFFFFFF; t.track = 0x33FFFFFF;
                t.cards = 0xFF7FA6FF; t.minutes = 0xFFCDA2FF;
                break;
            case GRADIENT:
                t.bg1 = 0xFF3D5AFE; t.bg2 = 0xFF8E54E9; t.gradient = true; t.border = 0x00000000; t.text = 0xFFFFFFFF; t.sub = 0xD9FFFFFF;
                t.track = 0x40FFFFFF; t.cards = 0xFFFFFFFF; t.minutes = 0xFFFFD166; t.done = 0xFF7CFFC4; t.session = 0xFFFFD166;
                t.locked = 0xFFFFC2C2; t.emergency = 0xFFFFC2C2;
                break;
            default:
                t.bg1 = t.bg2 = 0xF011151C; t.border = 0x30FFFFFF; t.text = 0xFFFFFFFF; t.sub = 0xB3FFFFFF; t.track = 0x2EFFFFFF;
                t.cards = 0xFF5B8CFF; t.minutes = 0xFFB16CFF;
        }
        return t;
    }

    static Bitmap render(int type, int style, int w, int h, Data d) {
        w = Math.max(40, w);
        h = Math.max(40, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Theme t = theme(style);
        float r = Math.min(w, h) * (type == MINI ? 0.5f : 0.16f);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF box = new RectF(1, 1, w - 1, h - 1);
        if (t.gradient) bg.setShader(new LinearGradient(0, 0, w, h, t.bg1, t.bg2, Shader.TileMode.CLAMP));
        else bg.setColor(t.bg1);
        cv.drawRoundRect(box, r, r, bg);
        if ((t.border >>> 24) != 0) {
            Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
            b.setStyle(Paint.Style.STROKE);
            b.setStrokeWidth(Math.max(1.5f, Math.min(w, h) * 0.008f));
            b.setColor(t.border);
            cv.drawRoundRect(box, r, r, b);
        }
        switch (type) {
            case MINI: mini(cv, w, h, d, t); break;
            case BARS: bars(cv, w, h, d, t); break;
            case WEEK: week(cv, w, h, d, t); break;
            default: rings(cv, w, h, d, t);
        }
        return bmp;
    }

    // ------------------------------------------------------------------ briques

    private static Paint text(int color, float size, boolean bold, Paint.Align align) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(align);
        if (bold) p.setTypeface(Typeface.DEFAULT_BOLD);
        return p;
    }

    private static void arc(Canvas cv, float cx, float cy, float rad, float sw, float f, int color, int track) {
        RectF o = new RectF(cx - rad, cy - rad, cx + rad, cy + rad);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setColor(track);
        cv.drawArc(o, 0, 360, false, p);
        if (f <= 0f) return;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        cv.drawArc(o, -90, Math.max(3f, 360f * Math.min(1f, f)), false, p);
    }

    private static void bar(Canvas cv, float x, float y, float w, float h, float f, int color, int track) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(track);
        RectF rr = new RectF(x, y, x + w, y + h);
        cv.drawRoundRect(rr, h / 2, h / 2, p);
        if (f <= 0f) return;
        p.setColor(color);
        rr.set(x, y, x + Math.max(h, w * Math.min(1f, f)), y + h);
        cv.drawRoundRect(rr, h / 2, h / 2, p);
    }

    private static String state(Data d) {
        switch (d.state) {
            case STATE_LOCKED: return L.t("Verrouillé", "Locked");
            case STATE_FREE: return L.t("Libre ✓", "Free ✓");
            case STATE_SESSION: return "Session";
            case STATE_EMERGENCY: return L.t("Urgence ", "Emergency ") + d.emergencyLeft;
            case STATE_NO_DATA: return L.t("AnkiDroid ?", "AnkiDroid?");
            default: return L.t("Non lancé", "Not started");
        }
    }

    private static int stateColor(Data d, Theme t) {
        switch (d.state) {
            case STATE_LOCKED: return t.locked;
            case STATE_FREE: return t.done;
            case STATE_SESSION: return t.session;
            case STATE_EMERGENCY: return t.emergency;
            default: return t.sub;
        }
    }

    private static int pct(Data d) {
        if (d.state == STATE_SESSION) return Math.round(d.fSession * 100);
        if (d.state == STATE_FREE) return 100;
        return Math.round(Math.min(d.fCards, d.fMinutes) * 100);
    }

    // ------------------------------------------------------------------ types

    /** 2×2 : double anneau (cartes / minutes) ou anneau de session, % au centre, chiffres dessous. */
    private static void rings(Canvas cv, int w, int h, Data d, Theme t) {
        float s = Math.min(w, h);
        float pad = s * 0.08f;
        Paint title = text(t.sub, s * 0.07f, true, Paint.Align.LEFT);
        Paint st = text(stateColor(d, t), s * 0.075f, true, Paint.Align.RIGHT);
        String sl = state(d);
        float room = w - pad * 2.4f;
        if (title.measureText("ANKINEER") + st.measureText(sl) + s * 0.05f <= room) {
            cv.drawText("ANKINEER", pad * 1.2f, pad + s * 0.075f, title);
            cv.drawText(sl, w - pad * 1.2f, pad + s * 0.075f, st);
        } else {                                  // pas la place : l'état seul, centré
            st.setTextAlign(Paint.Align.CENTER);
            if (st.measureText(sl) > room) st.setTextSize(st.getTextSize() * room / st.measureText(sl));
            cv.drawText(sl, w / 2f, pad + s * 0.075f, st);
        }

        float footer = s * 0.2f;
        float top = pad + s * 0.12f;
        float avail = h - top - footer;
        float cx = w / 2f, cy = top + avail / 2f;
        float R = Math.min(avail, w - 2 * pad) / 2f;
        float sw = R * 0.16f;
        if (d.state == STATE_SESSION) {
            arc(cv, cx, cy, R - sw / 2, sw * 1.2f, d.fSession, t.session, t.track);
        } else if (d.state == STATE_EMERGENCY) {
            arc(cv, cx, cy, R - sw / 2, sw * 1.2f, d.fEmergency, t.emergency, t.track);
        } else if (d.state == STATE_FREE) {
            arc(cv, cx, cy, R - sw / 2, sw * 1.2f, 1f, t.done, t.track);
        } else {
            arc(cv, cx, cy, R - sw / 2, sw, d.fCards, t.cards, t.track);
            arc(cv, cx, cy, R - sw * 1.75f, sw, d.fMinutes, t.minutes, t.track);
        }
        String center = d.state == STATE_FREE ? "✓" : d.state == STATE_SESSION ? String.valueOf(d.sessionLeft)
                : d.state == STATE_EMERGENCY ? d.emergencyLeft : pct(d) + "%";
        Paint c = text(t.text, R * (center.length() > 4 ? 0.36f : 0.46f), true, Paint.Align.CENTER);
        cv.drawText(center, cx, cy - (c.descent() + c.ascent()) / 2f - (d.state == STATE_SESSION ? R * 0.08f : 0), c);
        if (d.state == STATE_SESSION) {
            Paint u = text(t.sub, R * 0.17f, false, Paint.Align.CENTER);
            cv.drawText(d.sessionMinutes ? "min" : L.t("cartes", "cards"), cx, cy + R * 0.32f, u);
        }
        Paint f = text(t.text, s * 0.085f, true, Paint.Align.CENTER);
        String line = d.cards + "/" + d.goalCards + L.t(" cartes", " cards") + "  ·  " + d.minutes + "/" + d.goalMinutes + " min";
        float tw = f.measureText(line);
        if (tw > w - 2 * pad) f.setTextSize(f.getTextSize() * (w - 2 * pad) / tw);
        cv.drawText(line, cx, h - pad * 1.1f, f);
    }

    /** 1×1 : un anneau (le critère le moins avancé, ou la session) et le % au centre. */
    private static void mini(Canvas cv, int w, int h, Data d, Theme t) {
        float s = Math.min(w, h), cx = w / 2f, cy = h / 2f;
        float R = s / 2f - s * 0.1f, sw = s * 0.085f;
        int col = d.state == STATE_SESSION ? t.session : d.state == STATE_FREE ? t.done : d.state == STATE_EMERGENCY ? t.emergency : t.cards;
        float f = d.state == STATE_SESSION ? d.fSession : d.state == STATE_FREE ? 1f : d.state == STATE_EMERGENCY ? d.fEmergency : Math.min(d.fCards, d.fMinutes);
        arc(cv, cx, cy, R, sw, f, col, t.track);
        String center = d.state == STATE_FREE ? "✓" : d.state == STATE_SESSION ? String.valueOf(d.sessionLeft)
                : d.state == STATE_EMERGENCY ? d.emergencyLeft : pct(d) + "%";
        Paint c = text(t.text, s * (center.length() > 3 ? 0.2f : 0.26f), true, Paint.Align.CENTER);
        cv.drawText(center, cx, cy - (c.descent() + c.ascent()) / 2f, c);
    }

    /** 4×1 : état à gauche, barres cartes / minutes à droite. */
    private static void bars(Canvas cv, int w, int h, Data d, Theme t) {
        float pad = h * 0.18f;
        float left = Math.min(w * 0.3f, h * 2.2f);
        Paint big = text(t.text, h * 0.3f, true, Paint.Align.LEFT);
        cv.drawText(pct(d) + "%", pad, h * 0.5f, big);
        Paint st = text(stateColor(d, t), h * 0.19f, true, Paint.Align.LEFT);
        String sl = state(d);
        float maxW = left - pad;
        if (st.measureText(sl) > maxW) st.setTextSize(st.getTextSize() * maxW / st.measureText(sl));
        cv.drawText(sl, pad, h * 0.78f, st);

        float x = left + pad * 0.6f, bw = w - x - pad;
        float bh = h * 0.1f;
        Paint lab = text(t.sub, h * 0.17f, true, Paint.Align.LEFT);
        Paint val = text(t.text, h * 0.17f, true, Paint.Align.RIGHT);
        if (d.state == STATE_SESSION) {
            cv.drawText(L.t("Session · reste ", "Session · left ") + d.sessionLeft + (d.sessionMinutes ? " min" : L.t(" cartes", " cards")), x, h * 0.36f, lab);
            bar(cv, x, h * 0.5f, bw, bh * 1.4f, d.fSession, t.session, t.track);
            return;
        }
        cv.drawText(L.t("Cartes", "Cards"), x, h * 0.3f, lab);
        cv.drawText(d.cards + "/" + d.goalCards, w - pad, h * 0.3f, val);
        bar(cv, x, h * 0.37f, bw, bh, d.fCards, d.cards >= d.goalCards ? t.done : t.cards, t.track);
        cv.drawText("Minutes", x, h * 0.72f, lab);
        cv.drawText(d.minutes + "/" + d.goalMinutes, w - pad, h * 0.72f, val);
        bar(cv, x, h * 0.79f, bw, bh, d.fMinutes, d.minutes >= d.goalMinutes ? t.done : t.minutes, t.track);
    }

    /** 4×2 : série, 7 derniers jours (barres + objectif), chiffres du jour. */
    private static void week(Canvas cv, int w, int h, Data d, Theme t) {
        float pad = Math.min(w, h) * 0.09f;
        Paint title = text(t.text, h * 0.1f, true, Paint.Align.LEFT);
        cv.drawText("Ankineer Lock", pad, pad + h * 0.09f, title);
        Paint st = text(stateColor(d, t), h * 0.085f, true, Paint.Align.RIGHT);
        cv.drawText(state(d) + (d.streak > 0 ? "   🔥 " + d.streak : ""), w - pad, pad + h * 0.09f, st);

        float chartTop = pad + h * 0.2f, chartBottom = h - pad - h * 0.22f;
        float chartH = chartBottom - chartTop;
        int max = Math.max(1, d.goalCards);
        for (int v : d.weekCards) max = Math.max(max, v);
        float colW = (w - 2 * pad) / 7f, bw = colW * 0.5f;
        Paint lab = text(t.sub, h * 0.075f, true, Paint.Align.CENTER);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 7; i++) {
            float cx = pad + colW * i + colW / 2f;
            float bh = d.weekCards[i] <= 0 ? 0 : Math.max(h * 0.02f, chartH * d.weekCards[i] / (float) max);
            p.setColor(t.track);
            cv.drawRoundRect(new RectF(cx - bw / 2, chartTop, cx + bw / 2, chartBottom), bw / 2, bw / 2, p);
            if (bh > 0) {
                p.setColor(!d.weekKnown[i] ? t.sub : d.weekMet[i] ? t.done : (i == 6 ? t.cards : t.locked));
                cv.drawRoundRect(new RectF(cx - bw / 2, chartBottom - bh, cx + bw / 2, chartBottom), bw / 2, bw / 2, p);
            }
            if (d.weekLabels[i] != null) cv.drawText(d.weekLabels[i], cx, chartBottom + h * 0.1f, lab);
        }
        // ligne d'objectif
        float gy = chartBottom - chartH * d.goalCards / (float) max;
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setColor(t.sub);
        g.setStrokeWidth(Math.max(1f, h * 0.008f));
        g.setPathEffect(new android.graphics.DashPathEffect(new float[]{h * 0.03f, h * 0.02f}, 0));
        cv.drawLine(pad, gy, w - pad, gy, g);

        Paint f = text(t.text, h * 0.085f, true, Paint.Align.LEFT);
        cv.drawText(L.t("Aujourd'hui : ", "Today: ") + d.cards + "/" + d.goalCards + L.t(" cartes · ", " cards · ") + d.minutes + "/" + d.goalMinutes + " min",
                pad, h - pad * 0.7f, f);
    }
}
