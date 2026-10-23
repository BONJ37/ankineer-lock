package com.ankineer.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import stubhooks.Hooks;

/**
 * Smoke test: runs the whole app on the JVM (simulated Android framework) in several states,
 * opens every page, taps every button / switch, simulates the blocking service,
 * the widgets, the reminders and the update. Any exception thrown by the app's code is reported.
 */
public class Smoke {
    interface Step { void run() throws Throwable; }

    static final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
    static final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
    static String scenario = "";
    static int steps, clicks;

    static String appFrames(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c != null) {
            for (StackTraceElement e : c.getStackTrace()) {
                if (e.getClassName().startsWith("com.ankineer.app") && !e.getClassName().startsWith("com.ankineer.app.Smoke")) {
                    sb.append("\n      at ").append(e);
                    if (sb.length() > 600) break;
                }
            }
            c = c.getCause() != c ? c.getCause() : null;
            if (c != null) sb.append("\n    caused by ").append(c);
        }
        return sb.toString();
    }

    static void err(String where, Throwable t) {
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
        String key = t.getClass().getName() + appFrames(t);
        if (!seen.add(key)) return;           // same error at the same place: only once
        errors.add("[" + scenario + "] " + where + "\n    " + t + appFrames(t));
    }

    static void step(String where, Step s) {
        steps++;
        try { s.run(); } catch (Throwable t) { err(where, t); }
        drain();
    }

    static void drain() { Hooks.drain(400, Smoke::err); }

    static int baseThreads = -1;

    /** Waits for the background threads started by the app (at most ~1 s), then runs the main-thread queue. */
    static void settle() {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 100 && Thread.activeCount() > baseThreads; i++) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
            if (Hooks.drain(400, Smoke::err) == 0 && Thread.activeCount() <= baseThreads) break;
        }
    }

    static Object field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    static Object invoke(Object o, String name, Class<?>[] types, Object... args) throws Throwable {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(o, args);
            } catch (NoSuchMethodException e) { c = c.getSuperclass(); }
            catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        }
        throw new NoSuchMethodException(name);
    }

    static List<Object> tree(Object root) {
        List<Object> out = new ArrayList<>();
        ArrayDeque<Object> q = new ArrayDeque<>();
        if (root != null) q.add(root);
        Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!q.isEmpty()) {
            Object v = q.poll();
            if (!done.add(v)) continue;
            out.add(v);
            synchronized (Hooks.class) { q.addAll(new ArrayList<>(Hooks.kids(v))); }
        }
        return out;
    }

    static String label(Object v) {
        Object t = Hooks.prop(v, "Text");
        String s = t == null ? v.getClass().getSimpleName() : t.toString().replace('\n', ' ');
        return s.length() > 50 ? s.substring(0, 50) + "…" : s;
    }

    static void confirmDialogs(String where) {
        List<Object[]> b;
        synchronized (Hooks.class) { b = new ArrayList<>(Hooks.dialogButtons); Hooks.dialogButtons.clear(); }
        for (Object[] x : b) {
            final DialogInterface.OnClickListener l = (DialogInterface.OnClickListener) x[1];
            final int which = "setNegativeButton".equals(x[0]) ? DialogInterface.BUTTON_NEGATIVE : DialogInterface.BUTTON_POSITIVE;
            step(where + " → dialog " + x[0], () -> l.onClick(null, which));
        }
    }

    /** Taps everything clickable under {@code root} (and answers "yes" to dialogs). */
    static void clickAll(String where, Object root, String typed) {
        List<Object> views = tree(root);
        for (Object v : views) if (v instanceof EditText) Hooks.p(v).put("Text", typed);
        for (final Object v : views) {
            final Object l = Hooks.prop(v, "OnClickListener");
            if (l instanceof View.OnClickListener) {
                clicks++;
                step(where + " · click “" + label(v) + "”", () -> ((View.OnClickListener) l).onClick((View) v));
                confirmDialogs(where + " · “" + label(v) + "”");
            }
            final Object c = Hooks.prop(v, "OnCheckedChangeListener");
            if (c instanceof CompoundButton.OnCheckedChangeListener && v instanceof CompoundButton) {
                clicks++;
                step(where + " · switch “" + label(v) + "” ON", () -> ((CompoundButton.OnCheckedChangeListener) c).onCheckedChanged((CompoundButton) v, true));
                step(where + " · switch “" + label(v) + "” OFF", () -> ((CompoundButton.OnCheckedChangeListener) c).onCheckedChanged((CompoundButton) v, false));
            }
            final Object e = Hooks.prop(v, "OnEditorActionListener");
            if (e instanceof TextView.OnEditorActionListener) {
                step(where + " · keyboard OK", () -> ((TextView.OnEditorActionListener) e).onEditorAction((TextView) v, 6, null));
            }
            final Object t = Hooks.prop(v, "OnTouchListener");
            if (t instanceof View.OnTouchListener) {
                step(where + " · touch", () -> ((View.OnTouchListener) t).onTouch((View) v, android.view.MotionEvent.obtain(0, 0, 0, 10f, 10f, 0)));
            }
        }
    }

    static Context ctx;

    // ------------------------------------------------------------------ app

    static MainActivity openApp(Intent intent) {
        final MainActivity a = new MainActivity();
        if (intent != null) Hooks.p(a).put("Intent", intent);
        ctx = a;
        step("MainActivity.onCreate", () -> invoke(a, "onCreate", new Class<?>[]{android.os.Bundle.class}, (Object) null));
        step("MainActivity.onResume", () -> invoke(a, "onResume", new Class<?>[0]));
        settle();
        return a;
    }

    static void explorePages(final MainActivity a) throws Exception {
        String[] names = {"Home", "Settings", "Emergency", "Statistics", "Setup", "Update", "About"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            step("open page " + names[i], () -> a.show(idx));
            settle();
            Object[] pages = (Object[]) field(a, "pages");
            final Object page = pages.length > idx ? pages[idx] : null;
            if (page == null) continue;
            step("tick " + names[i], () -> ((MainActivity.Page) page).tick());
            step("refresh " + names[i], () -> ((MainActivity.Page) page).refresh());
            Object container = field(a, "container");
            clickAll("page " + names[i], container, "75");
            settle();
            step("reopen page " + names[i], () -> a.show(idx));
            container = field(a, "container");
            clickAll("page " + names[i] + " (empty input)", container, "");
            settle();
        }
        // side menu + back
        step("open the menu", a::openDrawer);
        clickAll("menu", field(a, "drawer"), "");
        step("back", a::onBackPressed);
        step("back 2", a::onBackPressed);
        step("onPause", () -> invoke(a, "onPause", new Class<?>[0]));
        step("onSaveInstanceState", () -> invoke(a, "onSaveInstanceState", new Class<?>[]{android.os.Bundle.class}, new android.os.Bundle()));
        step("onNewIntent page", () -> {
            Intent it = new Intent();
            it.putExtra("page", MainActivity.INSTALL);
            invoke(a, "onNewIntent", new Class<?>[]{Intent.class}, it);
        });
        step("permission result", () -> a.onRequestPermissionsResult(77, new String[]{"x"}, new int[]{0}));
        settle();
    }

    static void updateFlow(final MainActivity a) throws Throwable {
        step("open Update", () -> a.show(MainActivity.UPDATE));
        for (final int vc : new int[]{99, 1}) {
            Hooks.archiveVersionCode = vc;
            step("choose APK (version " + vc + ")", () -> {
                Intent data = new Intent();
                data.setData(android.net.Uri.parse("content://x/ankineer.apk"));
                invoke(a, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, UpdatePage.REQ_PICK, Activity.RESULT_OK, data);
            });
            settle();
            clickAll("Update after choice (v" + vc + ")", field(a, "container"), "");
            settle();
        }
        step("choice cancelled", () -> invoke(a, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, UpdatePage.REQ_PICK, Activity.RESULT_CANCELED, null));
        for (final int st : new int[]{-1, 0, 1, 3, 4, 5, 6, 7}) {
            step("Updater.onReceive status " + st, () -> {
                Intent i = new Intent(Updater.ACTION);
                i.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, st);
                i.putExtra(Intent.EXTRA_INTENT, new Intent("confirm"));
                i.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE, "msg");
                new Updater().onReceive(ctx, i);
            });
        }
        step("refresh Update", () -> a.show(MainActivity.UPDATE));
    }

    // ------------------------------------------------------------------ blocking service

    static AccessibilityEvent event(int type, String pkg, String cls) {
        AccessibilityEvent e = AccessibilityEvent.obtain(type);
        e.setEventType(type);
        if (pkg != null) e.setPackageName(pkg);
        if (cls != null) e.setClassName(cls);
        return e;
    }

    static AccessibilityWindowInfo window(String pkg, boolean active) {
        AccessibilityWindowInfo w = AccessibilityWindowInfo.obtain();
        Hooks.p(w).put("Type", AccessibilityWindowInfo.TYPE_APPLICATION);
        Hooks.p(w).put("Active", active);
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain();
        n.setPackageName(pkg);
        Hooks.p(w).put("Root", n);
        return w;
    }

    static void lockService() throws Throwable {
        final LockService s = new LockService();
        step("LockService.onServiceConnected", () -> invoke(s, "onServiceConnected", new Class<?>[0]));
        drain();
        String[][] evs = {
                {"com.google.android.youtube", "com.google.android.youtube.app.WatchWhileActivity"},
                {"com.android.settings", "com.android.settings.Settings$AccessibilitySettingsActivity"},
                {"com.android.settings", "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations"},
                {"com.google.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity"},
                {"com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"},
                {"com.android.vending", "com.google.android.finsky.activities.MainActivity"},
                {"com.ichi2.anki", "com.ichi2.anki.Reviewer"},
                {"com.whatsapp", "com.whatsapp.Main"},
        };
        for (final String[] e : evs) {
            step("event " + e[0], () -> s.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, e[0], e[1])));
            final List<Object> w;
            synchronized (Hooks.class) { w = new ArrayList<>(Hooks.windows); }
            for (Object root : w) clickAll("block screen (" + e[0] + ")", root, "4826");
            drain();
        }
        step("event without package (windows)", () -> s.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null)));
        // visible windows: YouTube in picture-in-picture, then the Play Store on Ankineer's page
        Hooks.accessibilityWindows.add(window("com.ichi2.anki", true));
        Hooks.accessibilityWindows.add(window("com.google.android.youtube", false));
        for (int i = 0; i < 3; i++) step("tick (PiP)", () -> invoke(s, "onTick", new Class<?>[0]));
        Hooks.accessibilityWindows.clear();
        Hooks.accessibilityWindows.add(window("com.android.vending", true));
        Hooks.foundTexts.add("Ankineer");
        for (int i = 0; i < 3; i++) step("tick (Play Store)", () -> invoke(s, "onTick", new Class<?>[0]));
        step("Play Store event", () -> s.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.vending", "x")));
        Hooks.foundTexts.clear();
        Hooks.accessibilityWindows.clear();
        Hooks.screenOn = false;
        step("tick screen off", () -> invoke(s, "onTick", new Class<?>[0]));
        Hooks.screenOn = true;
        for (int i = 0; i < 3; i++) step("tick", () -> invoke(s, "onTick", new Class<?>[0]));
        step("onInterrupt", s::onInterrupt);
        step("onUnbind", () -> s.onUnbind(new Intent()));
        step("onDestroy", s::onDestroy);
        settle();
    }

    // ------------------------------------------------------------------ widgets, rappels, divers

    static void widgets() {
        Hooks.widgetIds = new int[]{1, 2};
        for (final int style : new int[]{0, 1, 2, 3}) {
            step("widgets style " + style, () -> { Prefs.setHomeWidgetStyle(ctx, style); WidgetUpdater.updateAll(ctx, true); });
        }
        step("WidgetRings.onUpdate", () -> new WidgetRings().onUpdate(ctx, AppWidgetManager.getInstance(ctx), new int[]{1}));
        step("WidgetMini.onUpdate", () -> new WidgetMini().onUpdate(ctx, AppWidgetManager.getInstance(ctx), new int[]{1}));
        step("WidgetBars.onUpdate", () -> new WidgetBars().onUpdate(ctx, AppWidgetManager.getInstance(ctx), new int[]{1}));
        step("WidgetWeek.onUpdate", () -> new WidgetWeek().onUpdate(ctx, AppWidgetManager.getInstance(ctx), new int[]{1}));
        step("WidgetWeek.onAppWidgetOptionsChanged", () -> new WidgetWeek().onAppWidgetOptionsChanged(ctx, AppWidgetManager.getInstance(ctx), 1, new android.os.Bundle()));
        Hooks.widgetIds = new int[0];
        // direct rendering (all types × styles × states)
        for (int type = 0; type < 4; type++) for (int style = 0; style < 4; style++) for (int st = 0; st <= 5; st++) {
            final int t = type, sy = style, s = st;
            step("WidgetRenderer " + t + "/" + sy + "/" + s, () -> {
                WidgetRenderer.Data d = WidgetUpdater.data(ctx);
                d.state = s;
                WidgetRenderer.render(t, sy, 300, 150, d);
                WidgetRenderer.render(t, sy, 40, 40, d);
            });
        }
    }

    static void reminders() {
        step("Reminders.schedule", () -> Reminders.schedule(ctx));
        step("Reminders.run (test)", () -> Reminders.run(ctx, true));
        step("Reminders.run (hourly)", () -> Reminders.run(ctx, false));
        step("ReminderReceiver REMINDER (other app: ignored)", () -> new ReminderReceiver().onReceive(ctx, new Intent(Reminders.ACTION)));
        step("ReminderAlarmReceiver REMINDER", () -> new ReminderAlarmReceiver().onReceive(ctx, new Intent(Reminders.ACTION)));
        step("ReminderReceiver BOOT", () -> new ReminderReceiver().onReceive(ctx, new Intent(Intent.ACTION_BOOT_COMPLETED)));
        settle();
    }

    static void misc() {
        step("Charts", () -> {
            Charts.BarChart b = new Charts.BarChart(ctx);
            b.set(new int[]{1, 5, 0, 9}, null, 0xFF000000, 5f, new String[]{"a", "b", "c", "d"}, new String[]{"1", "2", "3", "4"});
            invoke(b, "onDraw", new Class<?>[]{Canvas.class}, new Canvas());
            b.onTouchEvent(android.view.MotionEvent.obtain(0, 0, 0, 5f, 5f, 0));
            invoke(b, "onDraw", new Class<?>[]{Canvas.class}, new Canvas());
            b.set(new int[0], null, 0, -1, null, null);
            invoke(b, "onDraw", new Class<?>[]{Canvas.class}, new Canvas());
            Charts.Heatmap h = new Charts.Heatmap(ctx);
            StatsAnalysis s = StatsAnalysis.compute(ctx, 365);
            h.set(s.heat, s.heatWeeks, s.heatLevels());
            invoke(h, "onMeasure", new Class<?>[]{int.class, int.class}, 1000, 1000);
            invoke(h, "onDraw", new Class<?>[]{Canvas.class}, new Canvas());
        });
        step("AppPickerActivity", () -> {
            AppPickerActivity p = new AppPickerActivity();
            invoke(p, "onCreate", new Class<?>[]{android.os.Bundle.class}, (Object) null);
            settle();
            clickAll("AppPicker", Hooks.prop(p, "ContentView"), "");
        });
        step("Stats.backfill", () -> Stats.backfill(ctx));
    }

    // ------------------------------------------------------------------ scenarios

    static final List<String> allLog = new ArrayList<>();

    static void scenario(String name, Step setup) throws Throwable {
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }
        scenario = name;
        Hooks.reset();
        MainActivity a = openApp(null);       // context + first-launch default values
        // the simulated AnkiDroid has no due cards: without this, "unlock as soon as due cards are done" (default)
        // would free every locked state. The default case has its own scenario.
        Prefs.setDueSufficient(ctx, false);
        step("setup", setup);
        a = openApp(null);
        explorePages(a);
        updateFlow(a);
        lockService();
        widgets();
        reminders();
        misc();
        // back to the app after all that
        MainActivity b = openApp(null);
        explorePages(b);
        settle();
    }

    static void fillStats(Context c) throws Exception {
        org.json.JSONObject all = new org.json.JSONObject();
        String today = Prefs.today();
        for (int i = 1; i <= 400; i++) {
            org.json.JSONObject e = new org.json.JSONObject();
            e.put("c", (i * 37) % 120);
            e.put("m", (i * 13) % 60);
            e.put("gc", 50);
            e.put("gm", 15);
            e.put("met", (i % 3) != 0);
            if (i % 7 == 0) e.put("s", 1);
            if (i % 11 == 0) e.put("ePwd", 1);
            if (i % 13 == 0) e.put("eStd", 1);
            if (i > 380) e.put("pre", true);
            all.put(Stats.addDays(today, -i), e);
        }
        Prefs.setStatsJson(c, all.toString());
        Prefs.sp(c).edit().putString("installDay", Stats.addDays(today, -380)).apply();
    }

    static boolean treeHasText(Object root, String needle) {
        for (Object v : tree(root)) {
            Object t = Hooks.prop(v, "Text");
            if (t != null && t.toString().contains(needle)) return true;
        }
        return false;
    }

    /** Simulated failures: the app must stay usable (rescue mode / error shown in place of the page). */
    static void faults() throws Throwable {
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }
        scenario = "main screen failure";
        Hooks.reset();
        Hooks.failOn = "android/widget/LinearLayout.setOrientation";
        Hooks.failCount = 1;
        MainActivity a = openApp(null);
        if (!Boolean.TRUE.equals(field(a, "fallback"))) errors.add("[" + scenario + "] rescue mode didn't activate");
        if (CrashLog.last(a) == null) errors.add("[" + scenario + "] no error report recorded");
        Object root = Hooks.prop(a, "ContentView");
        if (!treeHasText(root, "problème")) errors.add("[" + scenario + "] the rescue screen doesn't show the message");
        clickAll("rescue screen", root, "");
        explorePages(a);
        updateFlow(a);
        step("back (rescue)", a::onBackPressed);

        scenario = "page failure";
        Hooks.reset();
        Hooks.failOn = "android/widget/TextView.setAllCaps";
        Hooks.failCount = 1;
        a = openApp(null);
        if (Boolean.TRUE.equals(field(a, "fallback"))) errors.add("[" + scenario + "] rescue mode wrongly activated");
        Object container = field(a, "container");
        if (!treeHasText(container, "erreur")) errors.add("[" + scenario + "] the failing page doesn't show the error");
        clickAll("page in error", container, "");
        step("reopen Home", () -> invokeShow(field(MainActivity.class.cast(ctx), "container") == null ? null : (MainActivity) ctx, MainActivity.HOME));
        container = field(a, "container");
        if (treeHasText(container, "Cette page a rencontré une erreur")) errors.add("[" + scenario + "] Home still in error after reopening");
        explorePages(a);
        settle();
    }

    static void invokeShow(MainActivity a, int idx) { if (a != null) a.show(idx); }

    static void check(boolean ok, String what) { if (!ok) errors.add("[" + scenario + "] " + what); }

    static int count(String prefix) {
        int n = 0;
        synchronized (Hooks.class) { for (String l : Hooks.log) if (l.startsWith(prefix)) n++; }
        return n;
    }

    /** Checks the features: automatic start, uninstall protection, quotes. */
    static void features() throws Throwable {
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }

        scenario = "default settings";
        Hooks.reset();
        MainActivity a = openApp(null);
        check(Prefs.dueSufficient(a), "“unlock as soon as due cards are done” is not on by default");
        check(Prefs.autoArm(a), "automatic start not on by default");
        check(!Prefs.protectForever(a), "uninstall protection on by default");
        check(Prefs.protectDelayDays(a) == 1, "default protection delay ≠ 1 day");
        Prefs.setDueSufficient(a, false);
        check(!Prefs.dueSufficient(a), "“unlock as soon as due cards are done” can't be turned off");

        scenario = "automatic start";
        Hooks.reset();
        a = openApp(null);
        Prefs.setDueSufficient(a, false);
        Hooks.batteryOk = false;
        final LockService s = new LockService();
        step("service", () -> invoke(s, "onServiceConnected", new Class<?>[0]));
        settle();
        GoalChecker.Result r = GoalChecker.check(a);
        check(r.apiOk, "simulated AnkiDroid unreadable (apiOk=false): " + r.error);
        check(!Prefs.armed(a), "lock started while battery is optimised and apps not chosen");
        check(InstallPage.missingSteps(a, r).contains("4") && InstallPage.missingSteps(a, r).contains("6"), "missing steps wrongly detected: " + InstallPage.missingSteps(a, r));
        Hooks.batteryOk = true;
        Prefs.setAppsChosen(a);
        check(InstallPage.setupComplete(a, r), "setup not recognised as complete: " + InstallPage.missingSteps(a, r));
        step("service check", () -> invoke(s, "runCheck", new Class<?>[0]));
        settle();
        check(Prefs.armed(a), "the lock didn't start automatically");
        check(Prefs.lockActive(a), "lock started but not active");
        // manual stop while free → no restart today
        Prefs.markGoalDone(a);
        Prefs.setArmed(a, false);
        step("check after stop", () -> invoke(s, "runCheck", new Class<?>[0]));
        check(!Prefs.armed(a), "restarted the same day despite the manual stop");
        Prefs.sp(a).edit().putString("stopDay", "2000-01-01").apply();       // lendemain
        step("check the next day", () -> invoke(s, "runCheck", new Class<?>[0]));
        check(Prefs.armed(a), "no restart the next day");
        Prefs.setArmed(a, false);
        Prefs.sp(a).edit().putString("stopDay", "2000-01-01").apply();
        Prefs.setAutoArm(a, false);
        step("option off", () -> invoke(s, "runCheck", new Class<?>[0]));
        check(!Prefs.armed(a), "started while the option is off");
        a = openApp(null);
        explorePages(a);

        scenario = "uninstall protection";
        Hooks.reset();
        a = openApp(null);
        Prefs.setProtectForever(a);
        check(Prefs.protectForever(a), "protection not active");
        final LockService s2 = new LockService();
        step("service", () -> invoke(s2, "onServiceConnected", new Class<?>[0]));
        settle();
        // Ankineer Lock's App info in Settings, phone free (no lock)
        check(!Prefs.lockActive(a), "lock active in this scenario");
        Hooks.accessibilityWindows.add(window("com.android.settings", true));
        Hooks.foundTexts.add("Ankineer Lock");
        Hooks.foundTexts.add("Désinstaller");
        int before = count("globalAction");
        step("App info event", () -> s2.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.settings", "com.android.settings.SubSettings")));
        settle();
        step("tick App info", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") > before, "Ankineer's App info page not blocked");
        // same page but without "Ankineer": another app → not blocked
        Hooks.foundTexts.remove("Ankineer Lock");
        before = count("globalAction");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        step("tick other app", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") == before, "another app's info wrongly blocked");
        // uninstall window
        Hooks.accessibilityWindows.clear();
        Hooks.accessibilityWindows.add(window("com.google.android.packageinstaller", true));
        Hooks.foundTexts.add("Ankineer");
        step("uninstall event", () -> s2.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity")));
        before = count("globalAction");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        step("tick uninstall", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") > before, "Ankineer uninstall not blocked");
        // Ankineer update (installer, no "uninstall" word) → allowed
        Hooks.foundTexts.remove("Désinstaller");
        step("update event", () -> s2.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity")));
        before = count("globalAction");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        step("tick update", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") == before, "Ankineer update wrongly blocked");
        Hooks.accessibilityWindows.clear();
        Hooks.foundTexts.clear();
        // stop: 24 h wait (default delay)
        Prefs.requestProtectOff(a);
        check(Prefs.protectForever(a), "protection stopped without waiting 24 h");
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 25 * 3600000L).putLong("protectOffUp", Clock.uptime() - 25 * 3600000L).apply();
        check(!Prefs.protectForever(a), "protection still on after 24 h");
        // chosen delay: free when off, only longer when on
        check(Prefs.setProtectDelayDays(a, 30) && Prefs.setProtectDelayDays(a, 3), "delay can't be changed while protection is off");
        check(!Prefs.setProtectDelayDays(a, 5), "delay outside the list accepted");
        Prefs.setProtectForever(a);
        check(!Prefs.setProtectDelayDays(a, 1), "delay shortened during the protection");
        check(Prefs.protectDelayDays(a) == 3, "delay changed despite the refusal");
        check(Prefs.setProtectDelayDays(a, 7), "delay can't be lengthened during the protection");
        Prefs.requestProtectOff(a);
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 4 * 86400000L).putLong("protectOffUp", Clock.uptime() - 4 * 86400000L).apply();
        check(Prefs.protectForever(a), "protection stopped before the 7-day delay");
        // manual time moved forward: the clock alone isn't enough, real powered-on time is needed
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 8 * 86400000L).putLong("protectOffUp", Clock.uptime() - 86400000L).apply();
        check(Prefs.protectForever(a) || Clock.autoTime(), "manual time moved forward: protection stopped before the real delay");
        // lengthening during a requested stop pushes the stop back
        Prefs.setProtectForever(a);
        Prefs.requestProtectOff(a);
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 5 * 86400000L).putLong("protectOffUp", Clock.uptime() - 5 * 86400000L).apply();
        check(Prefs.setProtectDelayDays(a, 30), "switch to 30 days refused");
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 8 * 86400000L).putLong("protectOffUp", Clock.uptime() - 8 * 86400000L).apply();
        check(Prefs.protectForever(a), "lengthening the delay didn't push back the stop");
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 31 * 86400000L).putLong("protectOffUp", Clock.uptime() - 31 * 86400000L).apply();
        check(!Prefs.protectForever(a), "protection still on after 30 days");
        check(Prefs.setProtectDelayDays(a, 1), "delay can't be shortened once the protection is off");
        a = openApp(null);
        explorePages(a);

        scenario = "service-off alert";
        Hooks.reset();
        a = openApp(null);
        Reminders.setQuiet(a, 0, 0);
        Prefs.setArmed(a, true);
        Prefs.sp(a).edit().putBoolean("svcSeen", true).apply();
        Hooks.a11yEnabled = false;
        LockService.running = false;
        int n0 = count("notify 43");
        step("hourly reminder, service off", () -> Reminders.run(ctx, false));
        check(count("notify 43") == n0 + 1, "no alert when Android turns the service off");
        step("same hour", () -> Reminders.run(ctx, false));
        check(count("notify 43") == n0 + 1, "alert repeated within the same hour");
        Prefs.sp(a).edit().putLong("remSvcAt", System.currentTimeMillis() - 61 * 60000L).apply();
        step("next hour", () -> Reminders.run(ctx, false));
        check(count("notify 43") == n0 + 2, "no new alert the next hour (expected: at most every hour)");
        Prefs.setArmed(a, false);
        Prefs.sp(a).edit().remove("remSvcAt").apply();
        step("lock stopped", () -> Reminders.run(ctx, false));
        check(count("notify 43") == n0 + 2, "alert while the lock isn't started");
        Hooks.a11yEnabled = true;

        scenario = "success window";
        Hooks.reset();
        a = openApp(null);
        Prefs.setDueSufficient(a, false);
        final LockService s3 = new LockService();
        step("service", () -> invoke(s3, "onServiceConnected", new Class<?>[0]));
        settle();
        final SuccessOverlay so = new SuccessOverlay(s3);
        step("daily success", () -> {
            so.show(SuccessOverlay.DAILY, GoalChecker.snapshot(ctx));      // (the queue isn't drained: no auto close)
            check(so.isShowing(), "success window not shown: " + CrashLog.last(ctx));
            Object win;
            synchronized (Hooks.class) { win = Hooks.windows.isEmpty() ? null : Hooks.windows.get(Hooks.windows.size() - 1); }
            check(treeHasText(win, "Objectif du jour atteint"), "success window title missing");
            check(treeHasText(win, "«"), "quote missing from the success window");
            for (Object v : tree(win)) {
                Object l = Hooks.prop(v, "OnClickListener");
                if (l instanceof View.OnClickListener && "Continuer".equals(String.valueOf(Hooks.prop(v, "Text")))) ((View.OnClickListener) l).onClick((View) v);
            }
            check(!so.isShowing(), "“Continuer” doesn't close the success window");
        });
        step("session success", () -> so.show(SuccessOverlay.SESSION, GoalChecker.snapshot(ctx)));
        step("close", so::hide);
        // goal reached while the service runs → window shown only once
        int w0 = count("window+");
        Prefs.saveStudied(ctx, 80, 40);
        GoalChecker.snapshot(ctx);
        step("tick goal reached", () -> invoke(s3, "onTick", new Class<?>[0]));
        step("next tick", () -> invoke(s3, "onTick", new Class<?>[0]));
        check(count("window+") - w0 == 1, "success window shown " + (count("window+") - w0) + " times (expected: 1)");

        scenario = "quotes";
        for (boolean en : new boolean[]{false, true}) {
            L.en = en;
            for (int i = 0; i < Quotes.count(); i++) {
                String q = Quotes.ofTheHour(i);
                check(q != null && q.length() > 8, "empty quote");
            }
        }
        L.en = false;
        Hooks.reset();
        openApp(null);
        step("reminder with quote", () -> Reminders.run(ctx, true));
        settle();
    }

    public static void main(String[] args) throws Throwable {
        try { Field f = android.os.Build.VERSION.class.getDeclaredField("SDK_INT"); f.setAccessible(true); f.setInt(null, Integer.parseInt(System.getProperty("sdk", "34"))); }
        catch (Throwable t) { System.out.println("SDK_INT not set: " + t); }
        Thread.setDefaultUncaughtExceptionHandler((th, t) -> err("thread " + th.getName(), t));
        baseThreads = Thread.activeCount();

        scenario("first launch", () -> {});
        scenario("AnkiDroid missing, service off", () -> { Hooks.ankiInstalled = false; Hooks.a11yEnabled = false; });
        scenario("locked", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.saveStudied(ctx, 12, 5);
        });
        scenario("locked + English + light + strict", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.setEnglish(ctx, true);
            Prefs.setDarkMode(ctx, false);
            Prefs.setStrict(ctx, true);
            Prefs.saveStudied(ctx, 30, 10);
        });
        scenario("session running", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.startSession(ctx, Prefs.SESSION_MINUTES, 25, 0, 0);
        });
        scenario("code emergency running", () -> {
            Prefs.setArmed(ctx, true);
            Emergency.setPin(ctx, "4826");
            Emergency.setPasswordEnabled(ctx, true);
            Emergency.tryPassword(ctx, "4826");
        });
        scenario("extreme emergency running", () -> {
            Prefs.setArmed(ctx, true);
            Emergency.tryExtreme(ctx);
        });
        scenario("goal reached", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.saveStudied(ctx, 80, 40);
            Prefs.markGoalDone(ctx);
        });
        scenario("block test", () -> Prefs.startTest(ctx, 60000));
        scenario("due cards done (default setting)", () -> {
            Prefs.sp(ctx).edit().remove("dueSufficient").apply();
            Prefs.setArmed(ctx, true);
            Prefs.saveStudied(ctx, 5, 2);
        });
        scenario("old emergency request with a wait", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.sp(ctx).edit().putLong("emReq", Clock.now() - 1000).putLong("emReqWait", 60000).apply();
        });
        scenario("400-day history", () -> { fillStats(ctx); Prefs.setArmed(ctx, true); });
        scenario("Android 6", () -> {
            Field f = android.os.Build.VERSION.class.getDeclaredField("SDK_INT");
            f.setAccessible(true);
            f.setInt(null, 23);
        });

        faults();
        features();
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }
        System.out.println("steps: " + steps + " · clicks: " + clicks);
        Map<String, Integer> kinds = new TreeMap<>();
        for (String l : allLog) { String k = l.split(" ")[0]; kinds.merge(k, 1, Integer::sum); }
        System.out.println("simulated events: " + kinds);
        if (System.getProperty("verbose") != null) for (String l : allLog) if (l.startsWith("toast") || l.startsWith("notify") || l.startsWith("dialog") || l.startsWith("startActivit")) System.out.println("   " + l);
        if (errors.isEmpty()) {
            System.out.println("NO ERRORS");
        } else {
            System.out.println(errors.size() + " ERROR(S):");
            for (String e : errors) System.out.println("• " + e);
        }
        System.exit(errors.isEmpty() ? 0 : 1);
    }
}
