package com.ankineer.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Updates the home-screen widgets. An image is redrawn only if its content changes
 * (signature), so there's no cost when nothing moves.
 */
final class WidgetUpdater {
    private WidgetUpdater() {}

    private static final Class<?>[] PROVIDERS = {WidgetRings.class, WidgetMini.class, WidgetBars.class, WidgetWeek.class};
    private static final int[] TYPES = {WidgetRenderer.RINGS, WidgetRenderer.MINI, WidgetRenderer.BARS, WidgetRenderer.WEEK};
    private static String lastSig = "";

    static int typeOf(Class<?> provider) {
        for (int i = 0; i < PROVIDERS.length; i++) if (PROVIDERS[i] == provider) return TYPES[i];
        return WidgetRenderer.RINGS;
    }

    /** Refreshes every placed widget (no work if there are none, or if nothing changed). */
    static void updateAll(Context c, boolean force) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (m == null) return;
            int[][] ids = new int[PROVIDERS.length][];
            int total = 0;
            for (int i = 0; i < PROVIDERS.length; i++) {
                ids[i] = m.getAppWidgetIds(new ComponentName(c, PROVIDERS[i]));
                total += ids[i] == null ? 0 : ids[i].length;
            }
            if (total == 0) return;
            WidgetRenderer.Data d = data(c);
            String sig = signature(c, d);
            synchronized (WidgetUpdater.class) {
                if (!force && sig.equals(lastSig)) return;
                lastSig = sig;
            }
            for (int i = 0; i < PROVIDERS.length; i++) {
                if (ids[i] == null) continue;
                for (int id : ids[i]) draw(c, m, id, TYPES[i], d);
            }
        } catch (Throwable ignored) {}
    }

    /** Refreshes a few specific widgets (added, resized). */
    static void update(Context c, AppWidgetManager m, int[] ids, int type) {
        try {
            WidgetRenderer.Data d = data(c);
            for (int id : ids) draw(c, m, id, type, d);
        } catch (Throwable ignored) {}
    }

    private static void draw(Context c, AppWidgetManager m, int id, int type, WidgetRenderer.Data d) {
        float dens = c.getResources().getDisplayMetrics().density;
        int wDp, hDp;
        Bundle o = m.getAppWidgetOptions(id);
        // portrait : largeur mini / hauteur maxi
        wDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
        hDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) : 0;
        if (wDp <= 0 || hDp <= 0) {
            switch (type) {
                case WidgetRenderer.MINI: wDp = 70; hDp = 70; break;
                case WidgetRenderer.BARS: wDp = 300; hDp = 70; break;
                case WidgetRenderer.WEEK: wDp = 300; hDp = 150; break;
                default: wDp = 150; hDp = 150;
            }
        }
        if (type == WidgetRenderer.MINI || type == WidgetRenderer.RINGS) { int s = Math.min(wDp, hDp); wDp = s; hDp = s; }
        float scale = dens;
        // RemoteViews size limit (transfer to the launcher): ≈ 900 KB max per image
        float maxPx = 230000f;
        if (wDp * hDp * scale * scale > maxPx) scale = (float) Math.sqrt(maxPx / (wDp * (float) hDp));
        Bitmap b = WidgetRenderer.render(type, Prefs.homeWidgetStyle(c), Math.round(wDp * scale), Math.round(hDp * scale), d);
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_image);
        rv.setImageViewBitmap(R.id.w_img, b);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 500, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.w_root, pi);
        m.updateAppWidget(id, rv);
    }

    static WidgetRenderer.Data data(Context c) {
        Clock.init(c);
        L.load(c);
        GoalChecker.Result r = GoalChecker.snapshot(c);
        WidgetRenderer.Data d = new WidgetRenderer.Data();
        d.cards = r.cards; d.goalCards = r.goalCards; d.minutes = r.minutes; d.goalMinutes = r.goalMinutes; d.due = r.due;
        d.fCards = r.fCards; d.fMinutes = r.fMinutes;
        boolean base = Prefs.baseLocked(c);
        if (!GoalChecker.isAnkiInstalled(c) || !GoalChecker.hasPermission(c)) {
            d.state = WidgetRenderer.STATE_NO_DATA;
        } else if (r.session) {
            d.state = WidgetRenderer.STATE_SESSION;
            d.fSession = r.fSession;
            d.sessionLeft = Math.max(0, r.sessionTarget - r.sessionDone);
            d.sessionMinutes = Prefs.SESSION_MINUTES.equals(r.sessionType);
        } else if (base && Prefs.emergencyActive(c)) {
            d.state = WidgetRenderer.STATE_EMERGENCY;
            long until = Prefs.emergencyUntil(c), start = Emergency.activeStart(c);
            long left = Math.max(0, until - Clock.now());
            d.fEmergency = until > start ? left / (float) (until - start) : 0f;
            d.emergencyLeft = ((left + 59999) / 60000) + "m";
        } else if (Prefs.goalDoneToday(c) || r.dailyMet) {
            d.state = WidgetRenderer.STATE_FREE;
        } else if (base) {
            d.state = WidgetRenderer.STATE_LOCKED;
        } else {
            d.state = WidgetRenderer.STATE_NOT_ARMED;
        }
        d.streak = Stats.streak(c);
        String today = Prefs.today();
        SimpleDateFormat dow = new SimpleDateFormat("EEE", L.en ? Locale.ENGLISH : Locale.FRENCH);
        for (int i = 0; i < 7; i++) {
            String day = Stats.addDays(today, i - 6);
            Stats.Day s = Stats.get(c, day);
            d.weekKnown[i] = s.known && !s.pre;
            d.weekCards[i] = i == 6 ? r.cards : s.cards;
            d.weekMet[i] = i == 6 ? (Prefs.goalDoneToday(c) || r.dailyMet) : s.met;
            try {
                String l = dow.format(Stats.FMT.parse(day)).replace(".", "");
                d.weekLabels[i] = l.length() > 0 ? l.substring(0, 1).toUpperCase(Locale.ROOT) + (l.length() > 1 ? l.substring(1, Math.min(3, l.length())) : "") : "";
            } catch (Exception e) { d.weekLabels[i] = ""; }
        }
        d.weekKnown[6] = true;
        return d;
    }

    private static String signature(Context c, WidgetRenderer.Data d) {
        StringBuilder s = new StringBuilder();
        s.append(Prefs.homeWidgetStyle(c)).append(L.en).append('|').append(d.state).append('|').append(d.cards).append('/').append(d.goalCards)
                .append('|').append(d.minutes).append('/').append(d.goalMinutes).append('|').append(d.sessionLeft).append('|')
                .append(d.emergencyLeft).append('|').append(d.streak).append('|').append(Math.round(d.fSession * 100));
        for (int i = 0; i < 7; i++) s.append(',').append(d.weekCards[i]).append(d.weekMet[i] ? '+' : '-');
        return s.toString();
    }
}
