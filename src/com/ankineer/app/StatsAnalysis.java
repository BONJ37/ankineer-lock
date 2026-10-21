package com.ankineer.app;

import android.content.Context;

import java.util.Calendar;

/** Calculs des statistiques avancées (aucun accès AnkiDroid : uniquement l'historique en mémoire). */
final class StatsAnalysis {
    int period;
    // séries jour par jour sur la période (dernier = aujourd'hui)
    String[] day;
    int[] cards, minutes, goal;
    boolean[] met, tracked, known;
    // résumé
    int trackedDays, metDays, activeDays, totalCards, totalMinutes;
    float avgCards, avgMinutes, secPerCard;
    int prevTotalCards;                      // même durée, juste avant (tendance)
    boolean prevKnown;
    int recordCards, recordMinutes;
    String recordCardsDay, recordMinutesDay;
    int sessions, emStd, emPwd, emXtr, emDays;
    int[] ret;                  // rétention de chaque jour (‰, -1 = aucune révision)
    int periodRet = -1, periodRetN;   // rétention de la période (pondérée par le nombre de révisions)
    int streak, bestStreak;
    // profil hebdomadaire sur 1 an (lundi = 0)
    float[] weekdayAvg = new float[7];
    int bestWeekday = -1;
    // carte de chaleur : 53 semaines × 7 jours, lundi en haut, -1 = hors période
    int[] heat;
    int heatWeeks;

    private StatsAnalysis() {}

    static int rate(int met, int days) { return days <= 0 ? 0 : Math.round(met * 100f / days); }

    static StatsAnalysis compute(Context c, int period) {
        StatsAnalysis s = new StatsAnalysis();
        s.period = period;
        String today = Prefs.today(), install = Prefs.installDay(c);
        s.day = new String[period];
        s.cards = new int[period];
        s.minutes = new int[period];
        s.goal = new int[period];
        s.met = new boolean[period];
        s.tracked = new boolean[period];
        s.known = new boolean[period];
        s.ret = new int[period];
        long retSum = 0;
        for (int i = 0; i < period; i++) {
            String d = Stats.addDays(today, i - (period - 1));
            Stats.Day x = Stats.get(c, d);
            s.day[i] = d;
            s.known[i] = x.known;
            s.cards[i] = x.cards;
            s.minutes[i] = x.minutes;
            s.goal[i] = x.known ? x.goalCards : Prefs.dailyCards(c);
            s.tracked[i] = d.compareTo(install) >= 0;
            s.met[i] = s.tracked[i] && x.met;
            if (s.tracked[i]) { s.trackedDays++; if (x.met) s.metDays++; }
            if (x.cards > 0) s.activeDays++;
            s.totalCards += x.cards;
            s.totalMinutes += x.minutes;
            if (x.cards > s.recordCards) { s.recordCards = x.cards; s.recordCardsDay = d; }
            if (x.minutes > s.recordMinutes) { s.recordMinutes = x.minutes; s.recordMinutesDay = d; }
            s.sessions += x.sessions;
            s.ret[i] = x.ret;
            if (x.ret >= 0 && x.retN > 0) { retSum += (long) x.ret * x.retN; s.periodRetN += x.retN; }
            s.emStd += x.emStd;
            s.emPwd += x.emPwd;
            s.emXtr += x.emXtr;
            if (s.tracked[i] && x.emStd + x.emPwd + x.emXtr > 0) s.emDays++;
        }
        if (s.periodRetN > 0) s.periodRet = (int) Math.round(retSum / (double) s.periodRetN);
        int knownDays = 0;
        for (boolean k : s.known) if (k) knownDays++;
        s.avgCards = knownDays == 0 ? 0 : s.totalCards / (float) knownDays;
        s.avgMinutes = knownDays == 0 ? 0 : s.totalMinutes / (float) knownDays;
        s.secPerCard = s.totalCards == 0 ? 0 : s.totalMinutes * 60f / s.totalCards;
        for (int i = 1; i <= period; i++) {
            Stats.Day x = Stats.get(c, Stats.addDays(today, -(period - 1) - i));
            if (x.known) s.prevKnown = true;
            s.prevTotalCards += x.cards;
        }
        s.streak = Stats.streak(c);
        s.bestStreak = Math.max(s.streak, Stats.bestStreak(c));

        // profil hebdomadaire + carte de chaleur sur 365 jours
        float[] sum = new float[7];
        int[] cnt = new int[7];
        Calendar cal = Calendar.getInstance();
        int todayDow;
        try { cal.setTime(Stats.FMT.parse(today)); } catch (Exception ignored) {}
        todayDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;      // lundi = 0
        int span = Stats.HISTORY_DAYS;
        s.heatWeeks = (span + todayDow + 6) / 7;
        s.heat = new int[s.heatWeeks * 7];
        java.util.Arrays.fill(s.heat, -1);
        int lastCol = s.heatWeeks - 1;
        for (int i = 0; i < span; i++) {
            String d = Stats.addDays(today, -i);
            Stats.Day x = Stats.get(c, d);
            int dow = ((todayDow - i) % 7 + 7) % 7;
            int col = lastCol - (i + (6 - todayDow)) / 7;
            if (col < 0) break;
            s.heat[col * 7 + dow] = x.known ? x.cards : 0;
            if (x.known) { sum[dow] += x.cards; cnt[dow]++; }
        }
        float best = -1;
        for (int i = 0; i < 7; i++) {
            s.weekdayAvg[i] = cnt[i] == 0 ? 0 : sum[i] / cnt[i];
            if (cnt[i] > 0 && s.weekdayAvg[i] > best) { best = s.weekdayAvg[i]; s.bestWeekday = i; }
        }
        if (best <= 0) s.bestWeekday = -1;
        return s;
    }

    /** Évolution (%) du total de cartes par rapport à la période précédente, ou null si inconnue. */
    Integer trend() {
        if (!prevKnown || prevTotalCards <= 0) return null;
        return Math.round((totalCards - prevTotalCards) * 100f / prevTotalCards);
    }

    /** Seuils de la carte de chaleur (4 niveaux) à partir des jours actifs. */
    int[] heatLevels() {
        java.util.ArrayList<Integer> v = new java.util.ArrayList<>();
        for (int x : heat) if (x > 0) v.add(x);
        if (v.isEmpty()) return new int[]{1, 2, 3};
        java.util.Collections.sort(v);
        return new int[]{v.get(v.size() / 4), v.get(v.size() / 2), v.get(v.size() * 3 / 4)};
    }
}
