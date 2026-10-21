package com.ankineer.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Widget d'écran d'accueil Ankineer Lock (Rings). */
public class WidgetRings extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        WidgetUpdater.update(c.getApplicationContext(), m, ids, WidgetUpdater.typeOf(getClass()));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        WidgetUpdater.update(c.getApplicationContext(), m, new int[]{id}, WidgetUpdater.typeOf(getClass()));
    }
}
