package com.ankineer.app;

/**
 * Receives the hourly reminder alarm. Not exported: no other app can trigger it
 * (ReminderReceiver must stay exported to receive the phone's boot broadcast).
 */
public class ReminderAlarmReceiver extends ReminderReceiver {}
