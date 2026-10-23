package com.ankineer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Hourly reminder (AlarmManager) + rescheduling after a reboot or an app update. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        String action = intent != null ? intent.getAction() : null;
        if (Reminders.ACTION.equals(action)) {
            if (!(this instanceof ReminderAlarmReceiver)) return;      // reminder coming from another app: ignored
            Reminders.schedule(c);                 // next reminder (single alarm rescheduled every hour)
            final PendingResult pr = goAsync();
            Ui.safeThread(c, new Runnable() {
                @Override public void run() {
                    try { Reminders.run(c, false); } catch (Throwable ignored) {} finally { pr.finish(); }
                }
            }).start();
        } else if (android.content.Intent.ACTION_BOOT_COMPLETED.equals(action) || android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // BOOT_COMPLETED / MY_PACKAGE_REPLACED: reschedule and refresh the widgets
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
