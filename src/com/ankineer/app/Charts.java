package com.ankineer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Graphiques légers dessinés à la main (aucune bibliothèque) : barres et carte de chaleur. */
final class Charts {
    private Charts() {}

    /** Histogramme : une barre par valeur, ligne d'objectif en pointillés, touche = valeur de la barre. */
    static final class BarChart extends View {
        private int[] values = new int[0];
        private int[] colors;
        private String[] labels;           // étiquettes sous l'axe (null = aucune)
        private String[] tips;             // texte affiché au toucher
        private float goal = -1;
        private int color = Ui.ACCENT;
        private int selected = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float dens;

        BarChart(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
            tp.setTextAlign(Paint.Align.CENTER);
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(1.5f * dens);
            gp.setPathEffect(new DashPathEffect(new float[]{5 * dens, 4 * dens}, 0));
        }

        void set(int[] v, int[] perBarColors, int defaultColor, float goalLine, String[] axisLabels, String[] touchTips) {
            values = v;
            colors = perBarColors;
            color = defaultColor;
            goal = goalLine;
            labels = axisLabels;
            tips = touchTips;
            selected = -1;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (values.length == 0) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                int i = (int) (e.getX() / (getWidth() / (float) values.length));
                i = Math.max(0, Math.min(values.length - 1, i));
                if (i != selected) { selected = i; invalidate(); }
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = values.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight();
            float top = 22 * dens, bottom = h - (labels != null ? 18 * dens : 4 * dens);
            float max = Math.max(1, goal);
            for (int v : values) max = Math.max(max, v);
            float colW = w / n, bw = Math.max(2 * dens, Math.min(colW * 0.62f, 22 * dens));
            float rad = Math.min(bw / 2, 5 * dens);
            for (int i = 0; i < n; i++) {
                float cx = colW * i + colW / 2;
                p.setColor(Ui.STROKE);
                r.set(cx - bw / 2, top, cx + bw / 2, bottom);
                if (n <= 31) cv.drawRoundRect(r, rad, rad, p);
                if (values[i] > 0) {
                    float bh = Math.max(2 * dens, (bottom - top) * values[i] / max);
                    int col = colors != null ? colors[i] : color;
                    if (selected >= 0 && i != selected) col = (col & 0x00FFFFFF) | 0x80000000;
                    p.setColor(col);
                    r.set(cx - bw / 2, bottom - bh, cx + bw / 2, bottom);
                    cv.drawRoundRect(r, rad, rad, p);
                }
                if (labels != null && labels[i] != null && labels[i].length() > 0) {
                    tp.setColor(i == selected ? Ui.TEXT : Ui.MUTED);
                    tp.setTextSize(10.5f * dens);
                    cv.drawText(labels[i], cx, h - 4 * dens, tp);
                }
            }
            if (goal > 0) {
                gp.setColor(Ui.MUTED);
                float gy = bottom - (bottom - top) * goal / max;
                cv.drawLine(0, gy, w, gy, gp);
            }
            String tip = selected >= 0 && tips != null ? tips[selected] : null;
            if (tip != null) {
                tp.setColor(Ui.TEXT);
                tp.setTextSize(12.5f * dens);
                tp.setFakeBoldText(true);
                float tx = Math.max(tp.measureText(tip) / 2, Math.min(w - tp.measureText(tip) / 2, colW * selected + colW / 2));
                cv.drawText(tip, tx, 14 * dens, tp);
                tp.setFakeBoldText(false);
            }
        }
    }

    /** Carte de chaleur annuelle : colonnes = semaines, lignes = lundi → dimanche. */
    static final class Heatmap extends View {
        private int[] heat = new int[0];
        private int weeks;
        private int[] levels = {1, 2, 3};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float dens;

        Heatmap(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
        }

        void set(int[] h, int w, int[] lv) {
            heat = h;
            weeks = w;
            levels = lv;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            float cell = weeks > 0 ? w / (float) weeks : 10;
            setMeasuredDimension(w, Math.round(cell * 7));
        }

        static int shade(int base, float alpha) {
            return (Math.round(alpha * 255) << 24) | (base & 0x00FFFFFF);
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (weeks == 0) return;
            float cell = getWidth() / (float) weeks, gap = Math.max(1f, cell * 0.16f), rad = cell * 0.22f;
            for (int col = 0; col < weeks; col++) {
                for (int row = 0; row < 7; row++) {
                    int v = heat[col * 7 + row];
                    if (v < 0) continue;
                    int c;
                    if (v == 0) c = Ui.STROKE;
                    else if (v < levels[0]) c = shade(Ui.GREEN, 0.3f);
                    else if (v < levels[1]) c = shade(Ui.GREEN, 0.5f);
                    else if (v < levels[2]) c = shade(Ui.GREEN, 0.75f);
                    else c = Ui.GREEN;
                    p.setColor(c);
                    r.set(col * cell + gap / 2, row * cell + gap / 2, (col + 1) * cell - gap / 2, (row + 1) * cell - gap / 2);
                    cv.drawRoundRect(r, rad, rad, p);
                }
            }
        }
    }

    /** Anneau de progression (Accueil) : arc coloré, valeur au centre, libellé dessous. */
    static final class Ring extends View {
        private float f;
        private int color = Ui.ACCENT;
        private String value = "", label = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float dens;

        Ring(Context c) {
            super(c);
            dens = c.getResources().getDisplayMetrics().density;
        }

        void set(float fraction, int col, String v, String l) {
            f = Math.max(0f, Math.min(1f, fraction));
            color = col;
            value = v == null ? "" : v;
            label = l == null ? "" : l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h), sw = size * 0.085f;
            float cx = w / 2f, cy = h / 2f, rad = size / 2f - sw;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Ui.STROKE);
            cv.drawArc(r, 0, 360, false, p);
            if (f > 0) {
                p.setColor(color);
                cv.drawArc(r, -90, Math.max(2f, 360f * f), false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.TEXT);
            p.setFakeBoldText(true);
            p.setTextSize(size * (value.length() > 7 ? 0.15f : 0.19f));
            cv.drawText(value, cx, cy + size * 0.03f, p);
            p.setFakeBoldText(false);
            p.setColor(Ui.MUTED);
            p.setTextSize(size * 0.095f);
            cv.drawText(label, cx, cy + size * 0.19f, p);
        }
    }
}
