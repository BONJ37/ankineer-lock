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
 * Heart of Ankineer:
 *  - detects the foreground app (events + a check every second),
 *  - blocks the ones that aren't allowed until the goal is reached,
 *  - measures the time spent in AnkiDroid ("minutes" goal),
 *  - shows the progress widget,
 */
public class LockService extends AccessibilityService {
    /** True while the service runs (read by diagnostics, same process). */
    static volatile boolean running = false;
    /** Study time measured but not saved yet. */
    static volatile long pendingStudyMs = 0;
    /** Last app seen in the foreground (diagnostics). */
    static volatile String foreground = null;

    private static final long TICK_MS = 1000;
    private static final long ANKI_IDLE_MS = 120000;  // beyond this, stop counting time
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

    /** Adaptive interval: 1 s only when useful (lock, timer, widget), otherwise 4 s. */
    private long nextDelay = TICK_MS;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { onTick(); } catch (Throwable t) { report(t); }
            main.postDelayed(this, nextDelay);
        }
    };

    private String lastReport;

    /**
     * Error in the service: it is recorded (copyable report) but the service keeps running.
     * An unhandled exception would stop the service and Android would show "This service isn't working".
     */
    private void report(Throwable t) {
        try {
            String k = CrashLog.summary(t);
            if (k.equals(lastReport)) return;
            lastReport = k;
            CrashLog.save(this, t);
        } catch (Throwable ignored) {}
    }

    /** Protected background task (an error in this thread would also stop the whole service). */
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
        Prefs.sp(this).edit().putBoolean("svcSeen", true).apply();    // already active once: warn if it gets turned off
        overlay = new BlockOverlay(this);
        widget = new ProgressWidget(this);
        success = new SuccessOverlay(this);
        // goal already reached when the service starts: no late success window
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

    // ------------------------------------------------------------------ app lists

    private void refreshLists() {
        launchable = Collections.synchronizedSet(Apps.launchable(this));
        nonLaunchable = Collections.synchronizedSet(new HashSet<String>());
        always = Apps.alwaysAllowed(this);
        listsAt = SystemClock.elapsedRealtime();
    }

    /** Last screen (activity class) seen for each app: allows precise exceptions in strict mode. */
    private final java.util.Map<String, String> lastClass = new java.util.HashMap<String, String>();

    /**
     * Settings / install screens left reachable even in strict mode:
     * the "don't optimise battery" request (launched from Ankineer Lock) and APK install / update.
     * Uninstalling, app info (force stop) and accessibility stay blocked.
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
     * During the lock: every app that isn't allowed. During an emergency (apps freed): in strict mode,
     * Settings and uninstall stay blocked while the background lock holds — otherwise a 5-min emergency
     * would be enough to turn the service off.
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

    // ------------------------------------------------------------------ AnkiDroid check

    private void runCheck() {
        try {
            lastResult = GoalChecker.check(this);
            // setup complete → the lock starts on its own (unless stopped by hand today)
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

    // ------------------------------------------------------------------ events

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
        // (these events often have no package name: handle them before the test below)
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            main.removeCallbacks(scanNow);
            main.postDelayed(scanNow, 120);                       // did a floating window just appear?
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
            main.postDelayed(scanNow, 450);               // the screen content shows up a little after opening
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

    /** Takes "audio focus": players (YouTube, Spotify, podcasts…) pause. */
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
     * A blocked app still VISIBLE without being the active app: picture-in-picture (YouTube…), split screen,
     * bubble / floating player. Returns its package name, otherwise null.
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
                // screen allowed even in strict mode (APK update, battery setting): don't block again
                if ((Apps.SETTINGS.contains(pkg) || Apps.INSTALLERS.contains(pkg)) && harmlessProtectedScreen(pkg, lastClass.get(pkg))) continue;
                if (shouldBlock(pkg)) return pkg;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static final String PLAY_STORE = "com.android.vending";

    /** Words on "App info" screens (uninstall / force stop). */
    private static final String[] APP_INFO_WORDS = {"Désinstaller", "Uninstall", "Forcer l'arrêt", "Forcer l’arrêt", "Force stop", "Arrêt forcé"};

    /** Apps that may hold a screen able to remove or turn off Ankineer Lock. */
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
     * Screen to block to protect Ankineer Lock, or null.
     *  - Strict mode + background lock: Ankineer's Play Store page (uninstall without going through the blocked screen).
     *  - Uninstall protection (always, even when the phone is free): Play Store page, "uninstall Ankineer Lock" window,
     *    Ankineer Lock's "App info" page (uninstall / force stop) and the service's accessibility setting.
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

    /** Screen that removes / turns off Ankineer Lock: back to home (locked or not). */
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

    /** Foreground app according to the window list (more reliable than events alone). */
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

    // ------------------------------------------------------------------ one-second loop

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

        // --- study time
        if (inAnki) pendingStudyMs += delta;      // fallback only: minutes come from AnkiDroid's history
        if (wasInAnki && !inAnki) {
            flushStudy(true);
            scheduleCheck(1500);          // just left AnkiDroid: progress up to date
        } else if (now - lastFlushAt > 15000) {
            flushStudy(false);
        }
        wasInAnki = inAnki;

        // --- goals (day + session) recomputed from memory, no disk access
        boolean hadSession = Prefs.sessionActive(this);
        GoalChecker.Result snap = GoalChecker.snapshot(this);
        lastResult = snap;
        if (hadSession && !Prefs.sessionActive(this)) {
            sessionDoneUntil = now + 8000;   // session finished
            if (System.currentTimeMillis() - Prefs.sessionDoneAt(this) < 120000) celebrate(SuccessOverlay.SESSION, snap);
        }
        // daily goal reached (once a day only): big success window
        if (Prefs.goalDoneToday(this) && !Prefs.today().equals(Prefs.sp(this).getString("celebDay", ""))) {
            Prefs.sp(this).edit().putString("celebDay", Prefs.today()).apply();
            celebrate(SuccessOverlay.DAILY, snap);
        }

        // --- blocage
        boolean locked = Prefs.lockActive(this);
        boolean watching = locked || hadSession || (widget != null && widget.isShowing());
        if (watching) {
            // In AnkiDroid: frequent re-read (every answer counts); elsewhere every 30 s.
            scheduleCheck(inAnki ? 8000 : CHECK_EVERY_MS);
        } else if (!Prefs.armed(this) && Prefs.autoArm(this)) {
            scheduleCheck(60000);                 // waiting for the automatic start
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
            lastHomeWidgetAt = now;       // home-screen widgets: state change or every minute (emergency)
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
        // emergency running (background lock still there): red countdown
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

    // ------------------------------------------------------------------ lifecycle

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
