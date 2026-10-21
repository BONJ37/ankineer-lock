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
 * Test de fumée : lance l'app entière sur la JVM (framework Android simulé) dans plusieurs états,
 * ouvre chaque page, appuie sur chaque bouton / interrupteur, simule le service de blocage,
 * les widgets, les rappels et la mise à jour. Toute exception levée par le code de l'app est rapportée.
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
        if (!seen.add(key)) return;           // même erreur au même endroit : une seule fois
        errors.add("[" + scenario + "] " + where + "\n    " + t + appFrames(t));
    }

    static void step(String where, Step s) {
        steps++;
        try { s.run(); } catch (Throwable t) { err(where, t); }
        drain();
    }

    static void drain() { Hooks.drain(400, Smoke::err); }

    static int baseThreads = -1;

    /** Attend la fin des fils d'arrière-plan lancés par l'app (au plus ~1 s), puis exécute la file du fil principal. */
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
            step(where + " → dialogue " + x[0], () -> l.onClick(null, which));
        }
    }

    /** Appuie sur tout ce qui est cliquable sous {@code root} (et répond « oui » aux dialogues). */
    static void clickAll(String where, Object root, String typed) {
        List<Object> views = tree(root);
        for (Object v : views) if (v instanceof EditText) Hooks.p(v).put("Text", typed);
        for (final Object v : views) {
            final Object l = Hooks.prop(v, "OnClickListener");
            if (l instanceof View.OnClickListener) {
                clicks++;
                step(where + " · clic « " + label(v) + " »", () -> ((View.OnClickListener) l).onClick((View) v));
                confirmDialogs(where + " · « " + label(v) + " »");
            }
            final Object c = Hooks.prop(v, "OnCheckedChangeListener");
            if (c instanceof CompoundButton.OnCheckedChangeListener && v instanceof CompoundButton) {
                clicks++;
                step(where + " · interrupteur « " + label(v) + " » ON", () -> ((CompoundButton.OnCheckedChangeListener) c).onCheckedChanged((CompoundButton) v, true));
                step(where + " · interrupteur « " + label(v) + " » OFF", () -> ((CompoundButton.OnCheckedChangeListener) c).onCheckedChanged((CompoundButton) v, false));
            }
            final Object e = Hooks.prop(v, "OnEditorActionListener");
            if (e instanceof TextView.OnEditorActionListener) {
                step(where + " · clavier OK", () -> ((TextView.OnEditorActionListener) e).onEditorAction((TextView) v, 6, null));
            }
            final Object t = Hooks.prop(v, "OnTouchListener");
            if (t instanceof View.OnTouchListener) {
                step(where + " · toucher", () -> ((View.OnTouchListener) t).onTouch((View) v, android.view.MotionEvent.obtain(0, 0, 0, 10f, 10f, 0)));
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
        String[] names = {"Accueil", "Paramètres", "Urgence", "Statistiques", "Installation", "Mise à jour", "À propos"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            step("ouvrir page " + names[i], () -> a.show(idx));
            settle();
            Object[] pages = (Object[]) field(a, "pages");
            final Object page = pages.length > idx ? pages[idx] : null;
            if (page == null) continue;
            step("tick " + names[i], () -> ((MainActivity.Page) page).tick());
            step("refresh " + names[i], () -> ((MainActivity.Page) page).refresh());
            Object container = field(a, "container");
            clickAll("page " + names[i], container, "75");
            settle();
            step("réouvrir page " + names[i], () -> a.show(idx));
            container = field(a, "container");
            clickAll("page " + names[i] + " (saisie vide)", container, "");
            settle();
        }
        // menu latéral + retour
        step("ouvrir le menu", a::openDrawer);
        clickAll("menu", field(a, "drawer"), "");
        step("retour", a::onBackPressed);
        step("retour 2", a::onBackPressed);
        step("onPause", () -> invoke(a, "onPause", new Class<?>[0]));
        step("onSaveInstanceState", () -> invoke(a, "onSaveInstanceState", new Class<?>[]{android.os.Bundle.class}, new android.os.Bundle()));
        step("onNewIntent page", () -> {
            Intent it = new Intent();
            it.putExtra("page", MainActivity.INSTALL);
            invoke(a, "onNewIntent", new Class<?>[]{Intent.class}, it);
        });
        step("résultat autorisation", () -> a.onRequestPermissionsResult(77, new String[]{"x"}, new int[]{0}));
        settle();
    }

    static void updateFlow(final MainActivity a) throws Throwable {
        step("ouvrir Mise à jour", () -> a.show(MainActivity.UPDATE));
        for (final int vc : new int[]{99, 1}) {
            Hooks.archiveVersionCode = vc;
            step("choisir APK (version " + vc + ")", () -> {
                Intent data = new Intent();
                data.setData(android.net.Uri.parse("content://x/ankineer.apk"));
                invoke(a, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, UpdatePage.REQ_PICK, Activity.RESULT_OK, data);
            });
            settle();
            clickAll("Mise à jour après choix (v" + vc + ")", field(a, "container"), "");
            settle();
        }
        step("choix annulé", () -> invoke(a, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, UpdatePage.REQ_PICK, Activity.RESULT_CANCELED, null));
        for (final int st : new int[]{-1, 0, 1, 3, 4, 5, 6, 7}) {
            step("Updater.onReceive statut " + st, () -> {
                Intent i = new Intent(Updater.ACTION);
                i.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, st);
                i.putExtra(Intent.EXTRA_INTENT, new Intent("confirm"));
                i.putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE, "msg");
                new Updater().onReceive(ctx, i);
            });
        }
        step("refresh Mise à jour", () -> a.show(MainActivity.UPDATE));
    }

    // ------------------------------------------------------------------ service de blocage

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
            step("événement " + e[0], () -> s.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, e[0], e[1])));
            final List<Object> w;
            synchronized (Hooks.class) { w = new ArrayList<>(Hooks.windows); }
            for (Object root : w) clickAll("écran de blocage (" + e[0] + ")", root, "4826");
            drain();
        }
        step("événement sans paquet (fenêtres)", () -> s.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null)));
        // fenêtres visibles : YouTube en image dans l'image, puis Play Store sur la fiche Ankineer
        Hooks.accessibilityWindows.add(window("com.ichi2.anki", true));
        Hooks.accessibilityWindows.add(window("com.google.android.youtube", false));
        for (int i = 0; i < 3; i++) step("tick (PiP)", () -> invoke(s, "onTick", new Class<?>[0]));
        Hooks.accessibilityWindows.clear();
        Hooks.accessibilityWindows.add(window("com.android.vending", true));
        Hooks.foundTexts.add("Ankineer");
        for (int i = 0; i < 3; i++) step("tick (Play Store)", () -> invoke(s, "onTick", new Class<?>[0]));
        step("événement Play Store", () -> s.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.vending", "x")));
        Hooks.foundTexts.clear();
        Hooks.accessibilityWindows.clear();
        Hooks.screenOn = false;
        step("tick écran éteint", () -> invoke(s, "onTick", new Class<?>[0]));
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
        // rendu direct (tous types × styles × états)
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
        step("Reminders.run (horaire)", () -> Reminders.run(ctx, false));
        step("ReminderReceiver REMINDER (autre app : ignoré)", () -> new ReminderReceiver().onReceive(ctx, new Intent(Reminders.ACTION)));
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

    // ------------------------------------------------------------------ scénarios

    static final List<String> allLog = new ArrayList<>();

    static void scenario(String name, Step setup) throws Throwable {
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }
        scenario = name;
        Hooks.reset();
        MainActivity a = openApp(null);       // contexte + valeurs par défaut du premier lancement
        step("préparation", setup);
        a = openApp(null);
        explorePages(a);
        updateFlow(a);
        lockService();
        widgets();
        reminders();
        misc();
        // retour à l'app après tout ça
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

    /** Pannes simulées : l'app doit rester utilisable (mode secours / erreur affichée à la place de la page). */
    static void faults() throws Throwable {
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }
        scenario = "panne de l'écran principal";
        Hooks.reset();
        Hooks.failOn = "android/widget/LinearLayout.setOrientation";
        Hooks.failCount = 1;
        MainActivity a = openApp(null);
        if (!Boolean.TRUE.equals(field(a, "fallback"))) errors.add("[" + scenario + "] le mode secours ne s'est pas activé");
        if (CrashLog.last(a) == null) errors.add("[" + scenario + "] aucun rapport d'erreur enregistré");
        Object root = Hooks.prop(a, "ContentView");
        if (!treeHasText(root, "problème")) errors.add("[" + scenario + "] l'écran de secours n'affiche pas le message");
        clickAll("écran de secours", root, "");
        explorePages(a);
        updateFlow(a);
        step("retour (secours)", a::onBackPressed);

        scenario = "panne d'une page";
        Hooks.reset();
        Hooks.failOn = "android/widget/TextView.setAllCaps";
        Hooks.failCount = 1;
        a = openApp(null);
        if (Boolean.TRUE.equals(field(a, "fallback"))) errors.add("[" + scenario + "] mode secours activé à tort");
        Object container = field(a, "container");
        if (!treeHasText(container, "erreur")) errors.add("[" + scenario + "] la page en panne n'affiche pas l'erreur");
        clickAll("page en erreur", container, "");
        step("rouvrir l'accueil", () -> invokeShow(field(MainActivity.class.cast(ctx), "container") == null ? null : (MainActivity) ctx, MainActivity.HOME));
        container = field(a, "container");
        if (treeHasText(container, "Cette page a rencontré une erreur")) errors.add("[" + scenario + "] l'accueil reste en erreur après réouverture");
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

    /** Vérifie les fonctions : lancement automatique, protection permanente, citations. */
    static void features() throws Throwable {
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }

        scenario = "lancement automatique";
        Hooks.reset();
        MainActivity a = openApp(null);
        Hooks.batteryOk = false;
        final LockService s = new LockService();
        step("service", () -> invoke(s, "onServiceConnected", new Class<?>[0]));
        settle();
        GoalChecker.Result r = GoalChecker.check(a);
        check(r.apiOk, "AnkiDroid simulé illisible (apiOk=false) : " + r.error);
        check(!Prefs.armed(a), "verrou lancé alors que la batterie est optimisée et les apps pas choisies");
        check(InstallPage.missingSteps(a, r).contains("4") && InstallPage.missingSteps(a, r).contains("6"), "étapes manquantes mal détectées : " + InstallPage.missingSteps(a, r));
        Hooks.batteryOk = true;
        Prefs.setAppsChosen(a);
        check(InstallPage.setupComplete(a, r), "installation non reconnue comme terminée : " + InstallPage.missingSteps(a, r));
        step("vérification service", () -> invoke(s, "runCheck", new Class<?>[0]));
        settle();
        check(Prefs.armed(a), "le verrou ne s'est pas lancé automatiquement");
        check(Prefs.lockActive(a), "verrou lancé mais pas actif");
        // arrêt manuel quand libre → pas de relance aujourd'hui
        Prefs.markGoalDone(a);
        Prefs.setArmed(a, false);
        step("vérification après arrêt", () -> invoke(s, "runCheck", new Class<?>[0]));
        check(!Prefs.armed(a), "relancé le jour même malgré l'arrêt manuel");
        Prefs.sp(a).edit().putString("stopDay", "2000-01-01").apply();       // lendemain
        step("vérification le lendemain", () -> invoke(s, "runCheck", new Class<?>[0]));
        check(Prefs.armed(a), "pas de relance le lendemain");
        Prefs.setArmed(a, false);
        Prefs.sp(a).edit().putString("stopDay", "2000-01-01").apply();
        Prefs.setAutoArm(a, false);
        step("option coupée", () -> invoke(s, "runCheck", new Class<?>[0]));
        check(!Prefs.armed(a), "lancé alors que l'option est coupée");
        a = openApp(null);
        explorePages(a);

        scenario = "désinstallation bloquée pour toujours";
        Hooks.reset();
        a = openApp(null);
        Prefs.setProtectForever(a);
        check(Prefs.protectForever(a), "protection non active");
        final LockService s2 = new LockService();
        step("service", () -> invoke(s2, "onServiceConnected", new Class<?>[0]));
        settle();
        // Infos de l'appli d'Ankineer Lock dans Paramètres, téléphone libre (aucun verrou)
        check(!Prefs.lockActive(a), "verrou actif dans ce scénario");
        Hooks.accessibilityWindows.add(window("com.android.settings", true));
        Hooks.foundTexts.add("Ankineer Lock");
        Hooks.foundTexts.add("Désinstaller");
        int before = count("globalAction");
        step("événement Infos de l'appli", () -> s2.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.settings", "com.android.settings.SubSettings")));
        settle();
        step("tick Infos de l'appli", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") > before, "page Infos de l'appli d'Ankineer non bloquée");
        // même page mais sans « Ankineer » : autre app → pas bloquée
        Hooks.foundTexts.remove("Ankineer Lock");
        before = count("globalAction");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        step("tick autre app", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") == before, "Infos d'une autre app bloquées à tort");
        // fenêtre de désinstallation
        Hooks.accessibilityWindows.clear();
        Hooks.accessibilityWindows.add(window("com.google.android.packageinstaller", true));
        Hooks.foundTexts.add("Ankineer");
        step("événement désinstallation", () -> s2.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity")));
        before = count("globalAction");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        step("tick désinstallation", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") > before, "désinstallation d'Ankineer non bloquée");
        // mise à jour d'Ankineer (installateur, pas de mot « désinstaller ») → autorisée
        Hooks.foundTexts.remove("Désinstaller");
        step("événement mise à jour", () -> s2.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.google.android.packageinstaller", "com.android.packageinstaller.PackageInstallerActivity")));
        before = count("globalAction");
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        step("tick mise à jour", () -> invoke(s2, "onTick", new Class<?>[0]));
        check(count("globalAction") == before, "mise à jour d'Ankineer bloquée à tort");
        Hooks.accessibilityWindows.clear();
        Hooks.foundTexts.clear();
        // arrêt : 24 h d'attente
        Prefs.requestProtectOff(a);
        check(Prefs.protectForever(a), "protection arrêtée sans attendre 24 h");
        Prefs.sp(a).edit().putLong("protectOffAt", Clock.now() - 25 * 3600000L).putLong("protectOffUp", Clock.uptime() - 25 * 3600000L).apply();
        check(!Prefs.protectForever(a), "protection toujours active après 24 h");
        a = openApp(null);
        explorePages(a);

        scenario = "fenêtre de succès";
        Hooks.reset();
        a = openApp(null);
        final LockService s3 = new LockService();
        step("service", () -> invoke(s3, "onServiceConnected", new Class<?>[0]));
        settle();
        final SuccessOverlay so = new SuccessOverlay(s3);
        step("succès du jour", () -> {
            so.show(SuccessOverlay.DAILY, GoalChecker.snapshot(ctx));      // (la file n'est pas vidée : pas de fermeture auto)
            check(so.isShowing(), "fenêtre de succès non affichée : " + CrashLog.last(ctx));
            Object win;
            synchronized (Hooks.class) { win = Hooks.windows.isEmpty() ? null : Hooks.windows.get(Hooks.windows.size() - 1); }
            check(treeHasText(win, "Objectif du jour atteint"), "titre de la fenêtre de succès absent");
            check(treeHasText(win, "«"), "citation absente de la fenêtre de succès");
            for (Object v : tree(win)) {
                Object l = Hooks.prop(v, "OnClickListener");
                if (l instanceof View.OnClickListener && "Continuer".equals(String.valueOf(Hooks.prop(v, "Text")))) ((View.OnClickListener) l).onClick((View) v);
            }
            check(!so.isShowing(), "« Continuer » ne ferme pas la fenêtre de succès");
        });
        step("succès de session", () -> so.show(SuccessOverlay.SESSION, GoalChecker.snapshot(ctx)));
        step("fermeture", so::hide);
        // objectif atteint pendant que le service tourne → fenêtre affichée une seule fois
        int w0 = count("window+");
        Prefs.saveStudied(ctx, 80, 40);
        GoalChecker.snapshot(ctx);
        step("tick objectif atteint", () -> invoke(s3, "onTick", new Class<?>[0]));
        step("tick suivant", () -> invoke(s3, "onTick", new Class<?>[0]));
        check(count("window+") - w0 == 1, "fenêtre de succès affichée " + (count("window+") - w0) + " fois (attendu : 1)");

        scenario = "citations";
        for (boolean en : new boolean[]{false, true}) {
            L.en = en;
            for (int i = 0; i < Quotes.count(); i++) {
                String q = Quotes.ofTheHour(i);
                check(q != null && q.length() > 8, "citation vide");
            }
        }
        L.en = false;
        Hooks.reset();
        openApp(null);
        step("rappel avec citation", () -> Reminders.run(ctx, true));
        settle();
    }

    public static void main(String[] args) throws Throwable {
        try { Field f = android.os.Build.VERSION.class.getDeclaredField("SDK_INT"); f.setAccessible(true); f.setInt(null, Integer.parseInt(System.getProperty("sdk", "34"))); }
        catch (Throwable t) { System.out.println("SDK_INT non réglé : " + t); }
        Thread.setDefaultUncaughtExceptionHandler((th, t) -> err("fil " + th.getName(), t));
        baseThreads = Thread.activeCount();

        scenario("premier lancement", () -> {});
        scenario("AnkiDroid absent, service coupé", () -> { Hooks.ankiInstalled = false; Hooks.a11yEnabled = false; });
        scenario("verrouillé", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.saveStudied(ctx, 12, 5);
        });
        scenario("verrouillé + anglais + clair + strict", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.setEnglish(ctx, true);
            Prefs.setDarkMode(ctx, false);
            Prefs.setStrict(ctx, true);
            Prefs.saveStudied(ctx, 30, 10);
        });
        scenario("session en cours", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.startSession(ctx, Prefs.SESSION_MINUTES, 25, 0, 0);
        });
        scenario("urgence par code en cours", () -> {
            Prefs.setArmed(ctx, true);
            Emergency.setPin(ctx, "4826");
            Emergency.setPasswordEnabled(ctx, true);
            Emergency.tryPassword(ctx, "4826");
        });
        scenario("extrême urgence en cours", () -> {
            Prefs.setArmed(ctx, true);
            Emergency.tryExtreme(ctx);
        });
        scenario("objectif atteint", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.saveStudied(ctx, 80, 40);
            Prefs.markGoalDone(ctx);
        });
        scenario("test de blocage", () -> Prefs.startTest(ctx, 60000));
        scenario("ancienne demande d'urgence avec attente", () -> {
            Prefs.setArmed(ctx, true);
            Prefs.sp(ctx).edit().putLong("emReq", Clock.now() - 1000).putLong("emReqWait", 60000).apply();
        });
        scenario("historique 400 jours", () -> { fillStats(ctx); Prefs.setArmed(ctx, true); });
        scenario("Android 6", () -> {
            Field f = android.os.Build.VERSION.class.getDeclaredField("SDK_INT");
            f.setAccessible(true);
            f.setInt(null, 23);
        });

        faults();
        features();
        synchronized (Hooks.class) { for (String l : Hooks.log) allLog.add(l); }
        System.out.println("étapes : " + steps + " · clics : " + clicks);
        Map<String, Integer> kinds = new TreeMap<>();
        for (String l : allLog) { String k = l.split(" ")[0]; kinds.merge(k, 1, Integer::sum); }
        System.out.println("événements simulés : " + kinds);
        if (System.getProperty("verbose") != null) for (String l : allLog) if (l.startsWith("toast") || l.startsWith("notify") || l.startsWith("dialog") || l.startsWith("startActivit")) System.out.println("   " + l);
        if (errors.isEmpty()) {
            System.out.println("AUCUNE ERREUR");
        } else {
            System.out.println(errors.size() + " ERREUR(S) :");
            for (String e : errors) System.out.println("• " + e);
        }
        System.exit(errors.isEmpty() ? 0 : 1);
    }
}
