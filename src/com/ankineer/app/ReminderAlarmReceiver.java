package com.ankineer.app;

/**
 * Reçoit l'alarme horaire des rappels. Non exporté : aucune autre application ne peut le déclencher
 * (ReminderReceiver, lui, doit rester exporté pour recevoir le redémarrage du téléphone).
 */
public class ReminderAlarmReceiver extends ReminderReceiver {}
