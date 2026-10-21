package com.ankineer.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Statistiques avancées : tuiles, graphiques 7 j / 30 j / 1 an, profil hebdo, carte de chaleur, discipline, agenda. */
final class StatsPage implements MainActivity.Page {
    private final MainActivity a;
    private final View root;
    private TextView monthTv, dayTitle, dayBody, loadingTv, cardsNote, minutesNote, weekdayNote, heatNote, disciplineTv;
    private final TextView[][] tiles = new TextView[9][];
    private final Button[] periodBtn = new Button[3];
    private Charts.BarChart cardsChart, minutesChart, weekdayChart;
    private Charts.Heatmap heatmap;
    private int periodIdx = 1;
    private static final int[] PERIODS = {7, 30, 365};
    private LinearLayout grid;
    private final Calendar month = Calendar.getInstance();
    private String selected;
    private volatile boolean filling;

    StatsPage(MainActivity act) {
        a = act;
        month.set(Calendar.DAY_OF_MONTH, 1);
        selected = Prefs.today();
        LinearLayout col = Ui.column(a);

        // ---- période
        LinearLayout per = new LinearLayout(a);
        String[] pl = {L.t("7 jours", "7 days"), L.t("30 jours", "30 days"), L.t("1 an", "1 year")};
        for (int i = 0; i < 3; i++) {
            periodBtn[i] = a.chip(pl[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 40), 1f);
            if (i > 0) lp.leftMargin = Ui.dp(a, 8);
            per.addView(periodBtn[i], lp);
            final int k = i;
            periodBtn[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { periodIdx = k; drawSummary(); }
            });
        }
        col.addView(per, Ui.topMargin(a, 6));

        // ---- vue d'ensemble (3 × 3 tuiles)
        LinearLayout[] rows = {new LinearLayout(a), new LinearLayout(a), new LinearLayout(a)};
        for (int i = 0; i < 9; i++) tiles[i] = tile(rows[i / 3], i % 3 != 0);
        for (int i = 0; i < 3; i++) col.addView(rows[i], Ui.topMargin(a, 10));
        loadingTv = Ui.text(a, "", 12, Ui.MUTED, false);
        col.addView(loadingTv, Ui.topMargin(a, 8));

        // ---- graphiques
        a.section(col, L.t("Cartes par jour", "Cards per day"));
        LinearLayout cc = Ui.card(a);
        cardsChart = new Charts.BarChart(a);
        cc.addView(cardsChart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 170)));
        cardsNote = a.note(cc, "");
        col.addView(cc);

        a.section(col, L.t("Minutes par jour", "Minutes per day"));
        LinearLayout mc = Ui.card(a);
        minutesChart = new Charts.BarChart(a);
        mc.addView(minutesChart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 150)));
        minutesNote = a.note(mc, "");
        col.addView(mc);

        a.section(col, L.t("Jour de la semaine le plus productif", "Most productive weekday"));
        LinearLayout wc = Ui.card(a);
        weekdayChart = new Charts.BarChart(a);
        wc.addView(weekdayChart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 130)));
        weekdayNote = a.note(wc, "");
        col.addView(wc);

        a.section(col, L.t("Activité sur 1 an", "Activity over 1 year"));
        LinearLayout hc = Ui.card(a);
        heatmap = new Charts.Heatmap(a);
        hc.addView(heatmap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout hl = new LinearLayout(a);
        hl.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        hl.addView(Ui.text(a, L.t("Moins ", "Less "), 11, Ui.MUTED, false));
        float[] al = {-1f, 0.3f, 0.5f, 0.75f, 1f};
        for (float f : al) {
            View sq = new View(a);
            sq.setBackground(Ui.round(a, f < 0 ? Ui.STROKE : Charts.Heatmap.shade(Ui.GREEN, f), 3, 0));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(a, 11), Ui.dp(a, 11));
            sl.leftMargin = Ui.dp(a, 3);
            hl.addView(sq, sl);
        }
        hl.addView(Ui.text(a, L.t(" Plus", " More"), 11, Ui.MUTED, false));
        hc.addView(hl, Ui.topMargin(a, 8));
        heatNote = a.note(hc, "");
        col.addView(hc);

        a.section(col, L.t("Discipline", "Discipline"));
        LinearLayout dc = Ui.card(a);
        disciplineTv = Ui.text(a, "", 14, Ui.TEXT, false);
        disciplineTv.setLineSpacing(Ui.dp(a, 3), 1f);
        dc.addView(disciplineTv);
        col.addView(dc);

        // ---- agenda
        a.section(col, L.t("Agenda", "Calendar"));
        LinearLayout cal = Ui.card(a);
        LinearLayout nav = new LinearLayout(a);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = navBtn("‹"), next = navBtn("›");
        monthTv = Ui.text(a, "", 17, Ui.TEXT, true);
        monthTv.setGravity(Gravity.CENTER);
        nav.addView(prev, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 40)));
        nav.addView(monthTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(next, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 40)));
        cal.addView(nav);
        LinearLayout head = new LinearLayout(a);
        String[] wd = L.en ? new String[]{"M", "T", "W", "T", "F", "S", "S"} : new String[]{"L", "M", "M", "J", "V", "S", "D"};
        for (String d : wd) {
            TextView t = Ui.text(a, d, 12, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            head.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(a, 24), 1f));
        }
        cal.addView(head, Ui.topMargin(a, 6));
        grid = Ui.column(a);
        cal.addView(grid);
        LinearLayout legend = new LinearLayout(a);
        legend.setGravity(Gravity.CENTER);
        legend(legend, Ui.GREEN, L.t("Objectif atteint", "Goal reached"));
        legend(legend, Ui.RED, L.t("Non atteint", "Not reached"));
        legend(legend, ProgressWidget.C_SESSION, "Session");
        cal.addView(legend, Ui.topMargin(a, 10));
        col.addView(cal);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { month.add(Calendar.MONTH, -1); drawMonth(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { month.add(Calendar.MONTH, 1); drawMonth(); }
        });

        // ---- détail du jour
        LinearLayout det = Ui.card(a);
        dayTitle = Ui.text(a, "", 16, Ui.TEXT, true);
        det.addView(dayTitle);
        dayBody = Ui.text(a, "", 14, Ui.TEXT, false);
        dayBody.setLineSpacing(Ui.dp(a, 3), 1f);
        det.addView(dayBody, Ui.topMargin(a, 8));
        LinearLayout.LayoutParams dlp = Ui.topMargin(a, 12);
        col.addView(det, dlp);

        root = a.scroll(col);
    }

    /** Tuile : [0] valeur, [1] libellé, [2] détail. */
    private TextView[] tile(LinearLayout row, boolean notFirst) {
        LinearLayout t = Ui.column(a);
        t.setBackground(Ui.round(a, Ui.CARD, 14, Ui.STROKE));
        int p = Ui.dp(a, 11);
        t.setPadding(p, p, p, p);
        TextView v = Ui.text(a, "–", 19, Ui.TEXT, true);
        v.setSingleLine(true);
        t.addView(v);
        TextView l = Ui.text(a, "", 11.5f, Ui.MUTED, false);
        t.addView(l, Ui.topMargin(a, 2));
        TextView d = Ui.text(a, "", 11, Ui.MUTED, false);
        d.setSingleLine(true);
        t.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (notFirst) lp.leftMargin = Ui.dp(a, 8);
        row.addView(t, lp);
        return new TextView[]{v, l, d};
    }

    private void setTile(int i, String value, String label, String detail, int color) {
        tiles[i][0].setText(value);
        tiles[i][0].setTextColor(color);
        tiles[i][1].setText(label);
        tiles[i][2].setText(detail == null ? "" : detail);
        tiles[i][2].setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView navBtn(String s) {
        TextView t = Ui.text(a, s, 24, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(a, 0x00000000, 12, Ui.STROKE));
        return t;
    }

    private void legend(LinearLayout row, int color, String label) {
        View dot = new View(a);
        dot.setBackground(Ui.round(a, color, 5, 0));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10));
        dl.leftMargin = row.getChildCount() == 0 ? 0 : Ui.dp(a, 14);
        row.addView(dot, dl);
        TextView t = Ui.text(a, label, 12, Ui.MUTED, false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Ui.dp(a, 5);
        row.addView(t, tl);
    }

    @Override public View view() { return root; }

    @Override public void tick() {}

    @Override
    public void refresh() {
        drawSummary();
        drawMonth();
        drawDay();
        if (filling) return;
        filling = true;
        loadingTv.setText(L.t("Mise à jour depuis l'historique AnkiDroid…", "Updating from AnkiDroid history…"));
        final Context ctx = a.getApplicationContext();
        Ui.safeThread(a, new Runnable() {
            @Override public void run() {
                Stats.backfill(ctx);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        filling = false;
                        loadingTv.setText(L.t("Historique AnkiDroid depuis le ", "AnkiDroid history since ") + Stats.label(Stats.firstDay(a))
                                + L.t(" · installation le ", " · installed on ") + Stats.label(Prefs.installDay(a)));
                        drawSummary();
                        drawMonth();
                        drawDay();
                    }
                });
            }
        }).start();
    }

    private static String fmt1(float v) {
        return v >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v).replace(".0", "");
    }

    private String shortDay(String d) {
        if (d == null) return "";
        try {
            return new SimpleDateFormat(L.en ? "MMM d" : "d MMM", L.en ? Locale.ENGLISH : Locale.FRENCH).format(Stats.FMT.parse(d)).replace(".", "");
        } catch (Exception e) { return d; }
    }

    /** ‰ → « 91,5 % ». */
    private static String pct(int permille) {
        return permille % 10 == 0 ? (permille / 10) + " %" : String.format(L.en ? Locale.US : Locale.FRANCE, "%.1f %%", permille / 10f);
    }

    private void drawSummary() {
        for (int i = 0; i < 3; i++) a.styleChip(periodBtn[i], i == periodIdx);
        int period = PERIODS[periodIdx];
        StatsAnalysis s = StatsAnalysis.compute(a, period);

        // ---- tuiles
        setTile(0, s.streak + (s.streak > 0 ? " 🔥" : ""), L.t("Série en cours", "Current streak"), L.t("jours d'affilée", "days in a row"), Ui.TEXT);
        setTile(1, String.valueOf(s.bestStreak), L.t("Meilleure série", "Best streak"), L.t("jours", "days"), Ui.TEXT);
        int rt = GoalChecker.retentionToday(a), rg = GoalChecker.retentionGlobal(a);
        String rdet;
        if (rt < 0) rdet = L.t("aucune révision", "no reviews yet");
        else if (rg < 0) rdet = GoalChecker.retentionTodayCount(a) + L.t(" révisions", " reviews");
        else {
            float diff = (rt - rg) / 10f;
            rdet = L.t("globale ", "overall ") + pct(rg) + "  " + (diff >= 0 ? "+" : "") + fmt1(diff) + L.t(" pts", " pts");
        }
        setTile(2, rt < 0 ? "–" : pct(rt), L.t("Rétention du jour", "Today's retention"), rdet,
                rt < 0 || rg < 0 ? Ui.TEXT : rt >= rg ? Ui.GREEN : Ui.RED);
        setTile(3, fmt1(s.avgCards), L.t("Cartes / jour", "Cards / day"), L.t("moyenne", "average"), Ui.TEXT);
        setTile(4, fmt1(s.avgMinutes), L.t("Minutes / jour", "Minutes / day"), L.t("moyenne", "average"), Ui.TEXT);
        setTile(5, s.secPerCard <= 0 ? "–" : fmt1(s.secPerCard) + " s", L.t("Par carte", "Per card"), L.t("temps moyen", "average time"), Ui.TEXT);
        setTile(6, String.valueOf(s.totalCards), L.t("Cartes", "Cards"), s.totalMinutes + " min", Ui.TEXT);
        setTile(7, String.valueOf(s.recordCards), L.t("Record", "Record"), s.recordCards > 0 ? shortDay(s.recordCardsDay) : null, Ui.TEXT);
        Integer tr = s.trend();
        setTile(8, tr == null ? "–" : (tr > 0 ? "▲ +" : tr < 0 ? "▼ " : "= ") + tr + "%", L.t("Tendance", "Trend"),
                L.t("vs période préc.", "vs prev. period"), tr == null ? Ui.TEXT : tr >= 0 ? Ui.GREEN : Ui.RED);

        // ---- graphiques cartes / minutes
        int n = period == 365 ? 52 : period;              // 1 an : regroupé par semaine
        int group = period == 365 ? 7 : 1;
        int off = period - n * group;
        int[] cv = new int[n], mv = new int[n], colors = new int[n], mcol = new int[n];
        String[] lab = new String[n], tipC = new String[n], tipM = new String[n];
        Locale loc = L.en ? Locale.ENGLISH : Locale.FRENCH;
        for (int i = 0; i < n; i++) {
            int metN = 0, trackedN = 0;           // jours au-dessus du seuil de cartes / jours connus
            for (int k = 0; k < group; k++) {
                int j = off + i * group + k;
                cv[i] += s.cards[j];
                mv[i] += s.minutes[j];
                if (s.known[j] || j == period - 1) { trackedN++; if (s.cards[j] >= s.goal[j]) metN++; }
            }
            String first = s.day[off + i * group];
            boolean isToday = i == n - 1 && group == 1;
            // vert = au-dessus du seuil de cartes défini ; aujourd'hui en dessous = bleu (en cours) ; sinon rouge
            colors[i] = trackedN == 0 ? Ui.MUTED : (group == 1 ? (metN > 0 ? Ui.GREEN : isToday ? Ui.ACCENT : Ui.RED)
                    : (metN * 2 >= trackedN ? Ui.GREEN : Ui.RED));
            mcol[i] = ProgressWidget.C_MINUTES;
            String when = shortDay(first);
            if (group == 7) when = L.t("sem. du ", "week of ") + when;
            int rj = group == 1 ? s.ret[off + i] : -1;
            tipC[i] = when + " · " + cv[i] + L.t(" cartes", " cards") + (group == 7 ? " · " + metN + "/" + trackedN + " ✓" : "")
                    + (rj >= 0 ? " · " + pct(rj) : "");
            tipM[i] = when + " · " + mv[i] + " min";
            if (period == 7) {
                try { lab[i] = new SimpleDateFormat("EEE", loc).format(Stats.FMT.parse(first)).replace(".", ""); } catch (Exception e) { lab[i] = ""; }
            } else if (period == 30) {
                lab[i] = (i % 5 == 4 || i == n - 1) ? first.substring(8) : "";
            } else {
                int dom = Integer.parseInt(first.substring(8)), mon = Integer.parseInt(first.substring(5, 7));
                if (dom <= 7 && mon % 2 == 1) {
                    try { lab[i] = new SimpleDateFormat("MMM", loc).format(Stats.FMT.parse(first)).replace(".", ""); } catch (Exception e) { lab[i] = ""; }
                    if (lab[i].length() > 4) lab[i] = lab[i].substring(0, 4);
                } else lab[i] = "";
            }
        }
        float goal = Prefs.dailyCards(a) * group, goalM = Prefs.dailyMinutes(a) * group;
        cardsChart.set(cv, colors, Ui.ACCENT, goal, lab, tipC);
        minutesChart.set(mv, mcol, ProgressWidget.C_MINUTES, goalM, lab, tipM);
        cardsNote.setText(L.t("Vert = au-dessus du seuil de cartes · rouge = en dessous · bleu = aujourd'hui (en cours) · gris = pas de données. Pointillés = seuil",
                "Green = above the card threshold · red = below · blue = today (in progress) · grey = no data. Dashes = threshold")
                + (group == 7 ? L.t(" (× 7 par semaine)", " (× 7 per week)") : "") + L.t(". Touche une barre pour le détail.", ". Touch a bar for details."));
        minutesNote.setText(L.t("Total : ", "Total: ") + s.totalMinutes + " min  ·  " + L.t("record : ", "record: ") + s.recordMinutes + " min"
                + (s.recordMinutes > 0 ? " (" + shortDay(s.recordMinutesDay) + ")" : ""));

        // ---- jour de la semaine (toujours sur 1 an)
        String[] wd = L.en ? new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"} : new String[]{"Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"};
        int[] wv = new int[7], wcol = new int[7];
        String[] wt = new String[7];
        for (int i = 0; i < 7; i++) {
            wv[i] = Math.round(s.weekdayAvg[i]);
            wcol[i] = i == s.bestWeekday ? Ui.GREEN : Ui.ACCENT;
            wt[i] = wd[i] + " · " + fmt1(s.weekdayAvg[i]) + L.t(" cartes en moyenne", " cards on average");
        }
        weekdayChart.set(wv, wcol, Ui.ACCENT, Prefs.dailyCards(a), wd, wt);
        String[] wdl = L.en ? new String[]{"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"}
                : new String[]{"lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"};
        weekdayNote.setText(s.bestWeekday < 0 ? L.t("Pas encore assez de données.", "Not enough data yet.")
                : L.t("Ton meilleur jour : ", "Your best day: ") + wdl[s.bestWeekday] + " (" + fmt1(s.weekdayAvg[s.bestWeekday])
                + L.t(" cartes en moyenne, sur 1 an).", " cards on average, over 1 year)."));

        // ---- carte de chaleur
        heatmap.set(s.heat, s.heatWeeks, s.heatLevels());
        StatsAnalysis y = period == 365 ? s : StatsAnalysis.compute(a, 365);
        heatNote.setText(y.activeDays + L.t(" jours actifs sur 365  ·  ", " active days out of 365  ·  ") + y.totalCards + L.t(" cartes", " cards"));

        // ---- discipline
        int em = s.emStd + s.emPwd + s.emXtr;
        int rgl = GoalChecker.retentionGlobal(a);
        disciplineTv.setText(L.t("Rétention sur la période : ", "Retention over the period: ")
                + (s.periodRet < 0 ? "–" : pct(s.periodRet) + " (" + s.periodRetN + L.t(" révisions)", " reviews)"))
                + "\n" + L.t("Rétention globale (tout l'historique AnkiDroid) : ", "Overall retention (whole AnkiDroid history): ")
                + (rgl < 0 ? "–" : pct(rgl)) + "\n"
                + L.t("Sessions terminées : ", "Sessions completed: ") + s.sessions + "\n"
                + L.t("Urgences : ", "Emergencies: ") + em
                + (em > 0 ? "  (" + s.emStd + L.t(" avec attente · ", " with wait · ") + s.emPwd + L.t(" code · ", " code · ")
                + s.emXtr + L.t(" extrême)", " extreme)") : "") + "\n"
                + L.t("Jours actifs : ", "Active days: ") + s.activeDays + " / " + period
                + "\n" + L.t("Jours sans urgence : ", "Days without emergency: ") + daysWithoutEmergency(s) + " / " + s.trackedDays);
    }

    private static int daysWithoutEmergency(StatsAnalysis s) {
        return Math.max(0, s.trackedDays - emergencyDays(s));
    }

    private static int emergencyDays(StatsAnalysis s) { return s.emDays; }

    private void drawMonth() {
        Locale loc = L.en ? Locale.ENGLISH : Locale.FRENCH;
        String m = new SimpleDateFormat("LLLL yyyy", loc).format(month.getTime());
        monthTv.setText(m.substring(0, 1).toUpperCase(loc) + m.substring(1));
        grid.removeAllViews();
        Calendar c = (Calendar) month.clone();
        int offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;          // lundi = 0
        int daysIn = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        String install = Prefs.installDay(a), today = Prefs.today();
        int cell = 0;
        LinearLayout row = null;
        for (int i = 0; i < offset + daysIn; i++, cell++) {
            if (cell % 7 == 0) {
                row = new LinearLayout(a);
                grid.addView(row, Ui.topMargin(a, 4));
            }
            if (i < offset) {
                row.addView(new View(a), new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1f));
                continue;
            }
            int dayNum = i - offset + 1;
            c.set(Calendar.DAY_OF_MONTH, dayNum);
            final String key = Stats.FMT.format(c.getTime());
            row.addView(dayCell(key, dayNum, install, today), cellLp());
        }
        while (cell % 7 != 0) { row.addView(new View(a), cellLp()); cell++; }
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1f);
        lp.leftMargin = Ui.dp(a, 2);
        lp.rightMargin = Ui.dp(a, 2);
        return lp;
    }

    private View dayCell(final String key, int dayNum, String install, String today) {
        LinearLayout v = Ui.column(a);
        v.setGravity(Gravity.CENTER);
        // historique AnkiDroid compris (jours d'avant l'installation) : objectif évalué après coup
        boolean inRange = key.compareTo(Stats.firstDay(a)) >= 0 && key.compareTo(today) <= 0;
        Stats.Day d = Stats.get(a, key);
        int fill = 0x00000000, txt = inRange ? Ui.TEXT : Ui.MUTED;
        if (inRange && d.known) {
            fill = d.met ? Ui.GREEN : Ui.RED;
            fill = (fill & 0x00FFFFFF) | (Ui.DARK ? 0x40000000 : 0x2E000000);
        }
        boolean sel = key.equals(selected);
        v.setBackground(Ui.round(a, fill, 10, sel ? Ui.ACCENT : (key.equals(today) ? Ui.STROKE : 0)));
        TextView n = Ui.text(a, String.valueOf(dayNum), 14, txt, key.equals(today));
        n.setGravity(Gravity.CENTER);
        v.addView(n);
        if (inRange && d.sessions > 0) {
            View dot = new View(a);
            dot.setBackground(Ui.round(a, ProgressWidget.C_SESSION, 3, 0));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 6), Ui.dp(a, 6));
            dl.topMargin = Ui.dp(a, 2);
            v.addView(dot, dl);
        }
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                selected = key;
                drawMonth();
                drawDay();
            }
        });
        return v;
    }

    private void drawDay() {
        String t = Stats.label(selected);
        dayTitle.setText(t.substring(0, 1).toUpperCase() + t.substring(1));
        Stats.Day d = Stats.get(a, selected);
        if (selected.compareTo(Stats.firstDay(a)) < 0) {
            dayBody.setText(L.t("Plus ancien que l'historique lu (365 jours).", "Older than the history read (365 days)."));
            dayBody.setTextColor(Ui.MUTED);
            return;
        }
        if (selected.compareTo(Prefs.today()) > 0) {
            dayBody.setText(L.t("Ce jour n'est pas encore arrivé.", "This day hasn't come yet."));
            dayBody.setTextColor(Ui.MUTED);
            return;
        }
        if (!d.known) {
            dayBody.setText(L.t("Pas encore de données (ouvre AnkiDroid ou attends la mise à jour).", "No data yet (open AnkiDroid or wait for the update)."));
            dayBody.setTextColor(Ui.MUTED);
            return;
        }
        dayBody.setTextColor(Ui.TEXT);
        dayBody.setText((d.met ? L.t("✓ Objectif atteint", "✓ Goal reached") : L.t("✗ Objectif non atteint", "✗ Goal not reached")) + "\n"
                + L.t("Cartes : ", "Cards: ") + d.cards + " / " + d.goalCards + "\n"
                + "Minutes : " + d.minutes + " / " + d.goalMinutes + "\n"
                + L.t("Rétention : ", "Retention: ") + (d.ret < 0 ? "–" : pct(d.ret) + " (" + d.retN + L.t(" révisions)", " reviews)"))
                + (d.ret >= 0 && GoalChecker.retentionGlobal(a) >= 0
                ? L.t("  ·  globale ", "  ·  overall ") + pct(GoalChecker.retentionGlobal(a)) : "") + "\n"
                + (d.pre ? L.t("Avant l'installation : d'après l'historique AnkiDroid\n", "Before install: from AnkiDroid history\n") : "")
                + L.t("Sessions terminées : ", "Sessions completed: ") + d.sessions
                + (d.emStd + d.emPwd + d.emXtr > 0 ? "\n" + L.t("Urgences : ", "Emergencies: ")
                + (d.emStd > 0 ? d.emStd + L.t(" avec attente  ", " with wait  ") : "")
                + (d.emPwd > 0 ? L.t("code  ", "code  ") : "") + (d.emXtr > 0 ? L.t("extrême", "extreme") : "") : ""));
    }
}
