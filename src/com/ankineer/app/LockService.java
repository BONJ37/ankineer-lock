package com.ankineer.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cœur d'Ankineer :
 *  - détecte l'application au premier plan (événements + vérification chaque seconde),
 *  - bloque celles qui ne sont pas autorisées tant que l'objectif n'est pas atteint,
 *  - mesure le temps passé dans AnkiDroid (objectif « minutes »),
 *  - affiche le widget de progression,
 */
public class LockService extends AccessibilityService {
    /** Vrai tant que le service tourne (lu par le diagnostic, même processus). */
    static volatile boolean running = false;
    /** Temps d'étude mesuré mais pas encore enregistré. */
    static volatile long pendingStudyMs = 0;
    /** Dernière application vue au premier plan (diagnostic). */
    static volatile String foreground = null;

    private static final long TICK_MS = 1000;
    private static final long ANKI_IDLE_MS = 120000;  // au-delà, on arrête de compter le temps
    private static final long CHECK_EVERY_MS = 30000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler bg;
    private BlockOverlay overlay;
    private ProgressWidget widget;
    private PowerManager power;

    private volatile Set<String> launchable = Collections.synchronizedSet(new HashSet<String>());
    private volatile Set<String> nonLaunchable = Collections.synchronizedSet(new HashSet<String>());
    private volatile Set<String> always = new HashSet<String>();
    private volatile long listsAt = 0;

    private volatile boolean checking = false;
    private volatile long lastCheckAt = 0;
    private volatile GoalChecker.Result lastResult;

    private String lastBlockPkg;
    private long lastBlockAt;
    private String lastRecordedPkg;
    private long lastRecordedAt;

    private long lastTickAt;
    private long lastAnkiActivity;
    private long lastFlushAt;
    private boolean wasInAnki;
    private boolean wasLocked;
    private SuccessOverlay success;
    private long lastHomeWidgetAt;
    private long doneShownUntil;
    private long lastServerCheck;
    private long sessionDoneUntil;

    /** Intervalle adaptatif : 1 s seulement quand c'est utile (verrou, chrono, widget), sinon 4 s. */
    private long nextDelay = TICK_MS;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { onTick(); } catch (Throwable t) { report(t); }
            main.postDelayed(this, nextDelay);
        }
    };

    private String lastReport;

    /**
     * Erreur dans le service : on l'enregistre (rapport copiable) mais le service continue de tourner.
     * Une exception non gérée arrêterait le service et Android afficherait « Ce service ne fonctionne pas ».
     */
    private void report(Throwable t) {
        try {
            String k = CrashLog.summary(t);
            if (k.equals(lastReport)) return;
            lastReport = k;
            CrashLog.save(this, t);
        } catch (Throwable ignored) {}
    }

    /** Tâche d'arrière-plan protégée (une erreur dans ce fil arrêterait aussi tout le service). */
    private void bgSafe(final Runnable r) {
        Handler h = bg;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                try { r.run(); } catch (Throwable t) { report(t); }
            }
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        CrashLog.install(this);
        try {
            connect();
        } catch (Throwable t) {
            report(t);
            running = true;
            main.removeCallbacks(tick);
            main.postDelayed(tick, TICK_MS);
        }
    }

    private void connect() {
        Clock.init(this);
        Reminders.schedule(this);
        running = true;
        overlay = new BlockOverlay(this);
        widget = new ProgressWidget(this);
        success = new SuccessOverlay(this);
        // objectif déjà atteint au démarrage du service : pas de fenêtre de succès en retard
        if (Prefs.goalDoneToday(this)) Prefs.sp(this).edit().putString("celebDay", Prefs.today()).apply();
        power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        worker = new HandlerThread("ankineer-worker");
        worker.start();
        bg = new Handler(worker.getLooper());
        listsAt = SystemClock.elapsedRealtime();
        lastTickAt = SystemClock.elapsedRealtime();
        lastFlushAt = lastTickAt;
        wasLocked = Prefs.lockActive(this);
        bgSafe(new Runnable() {
            @Override public void run() {
                refreshLists();
                runCheck();
            }
        });
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    // ------------------------------------------------------------------ listes d'apps

    private void refreshLists() {
        launchable = Collections.synchronizedSet(Apps.launchable(this));
        nonLaunchable = Collections.synchronizedSet(new HashSet<String>());
        always = Apps.alwaysAllowed(this);
        listsAt = SystemClock.elapsedRealtime();
    }

    /** Dernier écran (classe d'activité) vu pour chaque app : permet des exceptions précises en mode strict. */
    private final java.util.Map<String, String> lastClass = new java.util.HashMap<String, String>();

    /**
     * Écrans de Paramètres / d'installation laissés accessibles même en mode strict :
     * la demande « ne pas optimiser la batterie » (lancée depuis Ankineer Lock) et l'installation / mise à jour
     * d'APK. La désinstallation, les infos d'app (arrêt forcé) et l'accessibilité restent bloquées.
     */
    private static boolean harmlessProtectedScreen(String pkg, String cls) {
        if (cls == null) return false;
        if (Apps.SETTINGS.contains(pkg)) return cls.contains("IgnoreBatteryOptimization")
                || cls.contains("ExternalSources") || cls.contains("AutoStart") || cls.contains("StartupAppList") || cls.contains("StartupNormalAppList");
        if (Apps.INSTALLERS.contains(pkg)) return !cls.toLowerCase(java.util.Locale.ROOT).contains("uninstall")
                && !cls.contains("DeleteStagedFile");
        return false;
    }

    /**
     * Pendant le verrou : toutes les apps non autorisées. Pendant une urgence (apps libérées) : en mode strict,
     * Paramètres et désinstallation restent bloqués tant que le verrou de fond tient — sinon une urgence de 5 min
     * suffirait à couper le service.
     */
    private boolean mustBlock(String pkg, boolean locked) {
        if (pkg == null) return false;
        boolean protectedApp = Apps.SETTINGS.contains(pkg) || Apps.INSTALLERS.contains(pkg);
        if (protectedApp && harmlessProtectedScreen(pkg, lastClass.get(pkg))) return false;
        if (locked) return shouldBlock(pkg);
        return protectedApp && Prefs.strict(this) && !Prefs.whitelist(this).contains(pkg)
                && Prefs.emergencyActive(this) && Prefs.baseLocked(this);
    }

    private boolean shouldBlock(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return false;
        if (Prefs.whitelist(this).contains(pkg)) return false;
        if (Apps.SETTINGS.contains(pkg) || Apps.INSTALLERS.contains(pkg)) return Prefs.strict(this);
        if (always.contains(pkg)) return false;
        if (launchable.contains(pkg)) return true;
        if (nonLaunchable.contains(pkg)) return false;
        boolean isApp = getPackageManager().getLaunchIntentForPackage(pkg) != null;
        if (isApp) launchable.add(pkg); else nonLaunchable.add(pkg);
        return isApp;
    }

    // ------------------------------------------------------------------ vérification AnkiDroid

    private void runCheck() {
        try {
            lastResult = GoalChecker.check(this);
            // installation terminée → le verrou se lance tout seul (sauf arrêt manuel aujourd'hui)
            if (!Prefs.armed(this) && Prefs.autoArm(this) && Prefs.autoArmIfReady(this, InstallPage.setupComplete(this, lastResult))) {
                main.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(LockService.this, L.t("🔒 Installation terminée : verrou lancé automatiquement",
                                "🔒 Setup complete: lock started automatically"), Toast.LENGTH_LONG).show();
                    }
                });
            }
        } finally {
            lastCheckAt = SystemClock.elapsedRealtime();
            checking = false;
        }
    }

    private void scheduleCheck(long minIntervalMs) {
        if (bg == null || checking) return;
        if (SystemClock.elapsedRealtime() - lastCheckAt < minIntervalMs) return;
        checking = true;
        bgSafe(new Runnable() {
            @Override public void run() { runCheck(); }
        });
    }

    // ------------------------------------------------------------------ événements

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            handleEvent(e);
        } catch (Throwable t) {
            report(t);
        }
    }

    private void handleEvent(AccessibilityEvent e) {
        if (e == null) return;
        // (ces événements n'ont souvent pas de nom de paquet : à traiter avant le test ci-dessous)
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            main.removeCallbacks(scanNow);
            main.postDelayed(scanNow, 120);                       // une fenêtre flottante vient d'apparaître ?
            return;
        }
        CharSequence cs = e.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        long now = SystemClock.elapsedRealtime();
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = e.getClassName();
        if (cls != null) lastClass.put(pkg, cls.toString());

        if (!pkg.equals(lastRecordedPkg) || now - lastRecordedAt > 5000) {
            lastRecordedPkg = pkg;
            lastRecordedAt = now;
            Prefs.recordEvent(this, pkg);
        }

        if (bg != null && now - listsAt > 120000) {
            listsAt = now;
            bgSafe(new Runnable() {
                @Override public void run() { refreshLists(); }
            });
        }

        if (mustBlock(pkg, Prefs.lockActive(this))) block(pkg);
        else if (isGuardedApp(pkg)) {
            main.removeCallbacks(scanNow);
            main.postDelayed(scanNow, 450);               // le contenu de l'écran s'affiche un peu après l'ouverture
        }
    }

    private void block(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(lastBlockPkg) && now - lastBlockAt < 700) return;
        lastBlockPkg = pkg;
        lastBlockAt = now;
        scheduleCheck(4000);
        silenceMedia();
        performGlobalAction(GLOBAL_ACTION_HOME);
        if (overlay != null) overlay.show(pkg);
    }

    private final android.media.AudioManager.OnAudioFocusChangeListener focusListener =
            new android.media.AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) {}
            };

    /** Prend le « focus audio » : les lecteurs (YouTube, Spotify, podcasts…) se mettent en pause. */
    private void silenceMedia() {
        try {
            final android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null || !am.isMusicActive()) return;
            am.requestAudioFocus(focusListener, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN);
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    try { am.abandonAudioFocus(focusListener); } catch (Exception ignored) {}
                }
            }, 1500);
        } catch (Exception ignored) {}
    }

    private final Runnable scanNow = new Runnable() {
        @Override public void run() {
            try {
                if (overlay != null && overlay.isShowing()) return;
                boolean locked = Prefs.lockActive(LockService.this);
                String p = locked ? visibleBlocked() : null;
                if (p != null) { block(p); return; }
                p = visibleProtected();
                if (p != null) blockProtected(p);
            } catch (Throwable ignored) {}
        }
    };

    /**
     * Une app bloquée encore VISIBLE sans être l'app active : image dans l'image (YouTube…), moitié d'écran partagé,
     * bulle / lecteur flottant. Renvoie son nom de paquet, sinon null.
     */
    private String visibleBlocked() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            for (AccessibilityWindowInfo w : ws) {
                int t = w.getType();
                if (t != AccessibilityWindowInfo.TYPE_APPLICATION && t != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                r.recycle();
                if (p == null) continue;
                String pkg = p.toString();
                if (pkg.equals(getPackageName()) || always.contains(pkg)) continue;
                // écran autorisé même en mode strict (mise à jour d'APK, réglage batterie) : ne pas rebloquer
                if ((Apps.SETTINGS.contains(pkg) || Apps.INSTALLERS.contains(pkg)) && harmlessProtectedScreen(pkg, lastClass.get(pkg))) continue;
                if (shouldBlock(pkg)) return pkg;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static final String PLAY_STORE = "com.android.vending";

    /** Mots des écrans « Infos de l'appli » (désinstaller / forcer l'arrêt). */
    private static final String[] APP_INFO_WORDS = {"Désinstaller", "Uninstall", "Forcer l'arrêt", "Forcer l’arrêt", "Force stop", "Arrêt forcé"};

    /** Applications où peut se trouver un écran permettant de supprimer ou couper Ankineer Lock. */
    private static boolean isGuardedApp(String pkg) {
        return pkg != null && (PLAY_STORE.equals(pkg) || Apps.SETTINGS.contains(pkg) || Apps.INSTALLERS.contains(pkg));
    }

    private static boolean hasText(AccessibilityNodeInfo root, String text) {
        try {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
            boolean hit = found != null && !found.isEmpty();
            if (found != null) for (AccessibilityNodeInfo n : found) if (n != null) n.recycle();
            return hit;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Écran à bloquer pour protéger Ankineer Lock, ou null.
     *  - Mode strict + verrou de fond : fiche Play Store d'Ankineer (désinstallation sans passer par l'écran bloqué).
     *  - Protection permanente (toujours, même téléphone libre) : fiche Play Store, fenêtre « désinstaller Ankineer Lock »,
     *    page « Infos de l'appli » d'Ankineer Lock (désinstaller / forcer l'arrêt) et réglage d'accessibilité du service.
     */
    private String visibleProtected() {
        boolean strictLock = Prefs.strict(this) && Prefs.baseLocked(this);
        boolean forever = Prefs.protectForever(this);
        if (!strictLock && !forever) return null;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            String a11yLabel = getString(R.string.a11y_label);
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence cp = r.getPackageName();
                String pkg = cp == null ? null : cp.toString();
                boolean hit = false;
                if (PLAY_STORE.equals(pkg)) {
                    hit = hasText(r, "Ankineer");
                } else if (forever && Apps.INSTALLERS.contains(pkg)) {
                    String cls = lastClass.get(pkg);
                    boolean uninstall = cls != null && cls.toLowerCase(java.util.Locale.ROOT).contains("uninstall");
                    if (!uninstall) for (String word : APP_INFO_WORDS) if (hasText(r, word)) { uninstall = true; break; }
                    hit = uninstall && hasText(r, "Ankineer");
                } else if (forever && Apps.SETTINGS.contains(pkg) && !harmlessProtectedScreen(pkg, lastClass.get(pkg))) {
                    if (hasText(r, a11yLabel)) hit = true;
                    else if (hasText(r, "Ankineer Lock")) {
                        for (String word : APP_INFO_WORDS) if (hasText(r, word)) { hit = true; break; }
                    }
                }
                r.recycle();
                if (hit) return pkg;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private long lastProtectToastAt;

    /** Écran de suppression / coupure d'Ankineer Lock : retour à l'accueil (verrou ou non). */
    private void blockProtected(String pkg) {
        if (Prefs.lockActive(this)) { block(pkg); return; }
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(lastBlockPkg) && now - lastBlockAt < 700) return;
        lastBlockPkg = pkg;
        lastBlockAt = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_HOME);
        if (now - lastProtectToastAt > 4000) {
            lastProtectToastAt = now;
            Toast.makeText(this, Prefs.protectForever(this)
                    ? L.t("🔒 Ankineer Lock est protégé contre la désinstallation (Paramètres → Verrou)", "🔒 Ankineer Lock is protected against uninstall (Settings → Lock)")
                    : L.t("🔒 Impossible pendant le verrou", "🔒 Not possible while locked"), Toast.LENGTH_LONG).show();
        }
    }

    /** Application au premier plan d'après la liste des fenêtres (plus fiable que les seuls événements). */
    private String detectForeground() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            String top = null;
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                r.recycle();
                if (p == null) continue;
                if (w.isActive() || w.isFocused()) return p.toString();
                if (top == null) top = p.toString();
            }
            return top;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ boucle d'une seconde

    private void onTick() {
        long now = SystemClock.elapsedRealtime();
        long delta = Math.min(2000, Math.max(0, now - lastTickAt));
        lastTickAt = now;

        boolean screenOn = power == null || power.isInteractive();
        if (!screenOn) {
            flushStudy(true);
            wasInAnki = false;
            if (widget != null) widget.hide();
            nextDelay = 5000;
            return;
        }

        String fg = detectForeground();
        if (fg == null) fg = lastRecordedPkg;
        foreground = fg;
        boolean inAnki = GoalChecker.ANKI_PKG.equals(fg);

        // --- temps d'étude
        if (inAnki) pendingStudyMs += delta;      // secours seulement : les minutes viennent de l'historique AnkiDroid
        if (wasInAnki && !inAnki) {
            flushStudy(true);
            scheduleCheck(1500);          // on vient de quitter AnkiDroid : progression à jour
        } else if (now - lastFlushAt > 15000) {
            flushStudy(false);
        }
        wasInAnki = inAnki;

        // --- objectifs (jour + session) recalculés depuis la mémoire, sans accès disque
        boolean hadSession = Prefs.sessionActive(this);
        GoalChecker.Result snap = GoalChecker.snapshot(this);
        lastResult = snap;
        if (hadSession && !Prefs.sessionActive(this)) {
            sessionDoneUntil = now + 8000;   // session terminée
            if (System.currentTimeMillis() - Prefs.sessionDoneAt(this) < 120000) celebrate(SuccessOverlay.SESSION, snap);
        }
        // objectif du jour atteint (une seule fois par jour) : grande fenêtre de succès
        if (Prefs.goalDoneToday(this) && !Prefs.today().equals(Prefs.sp(this).getString("celebDay", ""))) {
            Prefs.sp(this).edit().putString("celebDay", Prefs.today()).apply();
            celebrate(SuccessOverlay.DAILY, snap);
        }

        // --- blocage
        boolean locked = Prefs.lockActive(this);
        boolean watching = locked || hadSession || (widget != null && widget.isShowing());
        if (watching) {
            // Dans AnkiDroid : relecture fréquente (chaque réponse compte) ; ailleurs toutes les 30 s.
            scheduleCheck(inAnki ? 8000 : CHECK_EVERY_MS);
        } else if (!Prefs.armed(this) && Prefs.autoArm(this)) {
            scheduleCheck(60000);                 // en attente du lancement automatique
        }
        String guarded = isGuardedApp(fg) && (overlay == null || !overlay.isShowing()) ? visibleProtected() : null;
        if (guarded != null) blockProtected(guarded);
        else if (fg != null && mustBlock(fg, locked) && (overlay == null || !overlay.isShowing())) block(fg);
        else if (locked && (overlay == null || !overlay.isShowing())) {
            String hidden = visibleBlocked();
            if (hidden != null) block(hidden);
        }
        if (wasLocked && !locked && Prefs.unlockReason(this) == Prefs.REASON_GOAL && !Prefs.sessionActive(this)) {
            doneShownUntil = now + 8000;
        }
        if (locked != wasLocked || now - lastHomeWidgetAt > 60000) {
            lastHomeWidgetAt = now;       // widgets d'écran d'accueil : changement d'état ou chaque minute (urgence)
            if (bg != null) bgSafe(new Runnable() {
                @Override public void run() { WidgetUpdater.updateAll(LockService.this, false); }
            });
        }
        wasLocked = locked;

        // --- widget
        updateWidget(locked, now);

        boolean busyTick = locked || hadSession || inAnki || (widget != null && widget.isShowing())
                || (isGuardedApp(fg) && (Prefs.baseLocked(this) || Prefs.protectForever(this)));
        nextDelay = busyTick ? TICK_MS : 4000;
    }

    private void celebrate(int kind, GoalChecker.Result r) {
        if (success == null || !Prefs.sp(this).getBoolean("celebrate", true)) return;
        if (overlay != null && overlay.isShowing()) overlay.hide();
        success.show(kind, r);
    }

    private void updateWidget(boolean locked, long now) {
        if (widget == null) return;
        boolean session = Prefs.sessionActive(this);
        boolean wanted = Prefs.widgetEnabled(this) && (Prefs.armed(this) || session || Prefs.testActive(this));
        if (!wanted || (overlay != null && overlay.isShowing())) {
            widget.hide();
            return;
        }
        // urgence en cours (verrou de fond toujours là) : compte à rebours rouge
        if (!locked && Prefs.emergencyActive(this) && Prefs.baseLocked(this)) {
            long until = Prefs.emergencyUntil(this), start = Emergency.activeStart(this);
            long leftMs = Math.max(0, until - Clock.now());
            float frac = until > start ? leftMs / (float) (until - start) : 0f;
            long s = (leftMs + 999) / 1000;
            widget.showEmergency(frac, s >= 600 ? (s / 60) + "m" : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
            return;
        }
        boolean showDone = now < doneShownUntil || now < sessionDoneUntil;
        if (!locked && !session && !showDone) {
            widget.hide();
            return;
        }
        GoalChecker.Result r = lastResult;
        if (r == null) r = GoalChecker.snapshot(this);
        if (showDone && !locked && !session) {
            widget.showDone();
        } else if (r.session) {
            widget.showSession(r.fSession, r.sessionTarget - Math.min(r.sessionDone, r.sessionTarget),
                    Prefs.SESSION_MINUTES.equals(r.sessionType));
        } else {
            widget.showDaily(r.fCards, r.fMinutes, r.shortText);
        }
    }

    private void flushStudy(boolean force) {
        long ms = pendingStudyMs;
        if (ms <= 0) return;
        if (!force && ms < 10000) return;
        Prefs.addStudyMs(this, ms);
        pendingStudyMs = 0;
        lastFlushAt = SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        running = false;
        if (success != null) success.hide();
        main.removeCallbacks(tick);
        flushStudy(true);
        if (overlay != null) overlay.hide();
        if (widget != null) widget.hide();
        if (worker != null) worker.quitSafely();
    }
}
