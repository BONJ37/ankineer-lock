package com.ankineer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Rappel horaire (AlarmManager) + reprogrammation après redémarrage ou mise à jour de l'app. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        String action = intent != null ? intent.getAction() : null;
        if (Reminders.ACTION.equals(action)) {
            if (!(this instanceof ReminderAlarmReceiver)) return;      // rappel venu d'une autre app : ignoré
            Reminders.schedule(c);                 // prochain rappel (alarme unique reprogrammée chaque heure)
            final PendingResult pr = goAsync();
            Ui.safeThread(c, new Runnable() {
                @Override public void run() {
                    try { Reminders.run(c, false); } catch (Throwable ignored) {} finally { pr.finish(); }
                }
            }).start();
        } else if (android.content.Intent.ACTION_BOOT_COMPLETED.equals(action) || android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // BOOT_COMPLETED / MY_PACKAGE_REPLACED : on reprogramme et on rafraîchit les widgets
            Reminders.schedule(c);
            final PendingResult pr = goAsync();
            Ui.safeThread(c, new Runnable() {
                @Override public void run() {
                    try { WidgetUpdater.updateAll(c, true); } catch (Throwable ignored) {} finally { pr.finish(); }
                }
            }).start();
        }
    }
}
