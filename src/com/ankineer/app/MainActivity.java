package com.ankineer.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/**
 * Ankineer Lock — app shell: top bar, left side menu
 * (Home · Settings · Emergency · Statistics · Setup · Update · About) and pages swapped without changing screen.
 */
public class MainActivity extends Activity {
    static final int HOME = 0, SETTINGS = 1, EMERGENCY = 2, STATS = 3, INSTALL = 4, UPDATE = 5, ABOUT = 6;

    interface Page {
        View view();
        /** Full refresh (opening, back to the app, after reading AnkiDroid). */
        void refresh();
        /** Light refresh, called every second only during a test or a session. */
        void tick();
    }

    volatile GoalChecker.Result lastRes;
    final Handler ui = new Handler();

    /** Menu pages, in the order of the constants above: one list for the menu, the titles and the pages. */
    static final int PAGE_COUNT = 7;
    private final Page[] pages = new Page[PAGE_COUNT];
    private int current = HOME;
    private FrameLayout container;
    private TextView titleTv;
    private View scrim;
    private LinearLayout drawer;
    private final TextView[] menuItems = new TextView[PAGE_COUNT];

    static String[] pageTitles() {
        return new String[]{L.t("Accueil", "Home"), L.t("Paramètres", "Settings"), L.t("Urgence", "Emergency"), L.t("Statistiques", "Statistics"),
                L.t("Installation", "Setup"), L.t("Mise à jour", "Update"), L.t("À propos", "About")};
    }

    private static final String[] PAGE_ICONS = {"🏠", "⚙", "🚨", "📅", "🧰", "⬆", "ℹ"};

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean busy = Prefs.testActive(MainActivity.this) || Prefs.sessionActive(MainActivity.this);
            Page p = pages[current];
            try {
                if (p != null) p.tick();
            } catch (Throwable t) {
                safeRefresh(current);
            }
            if (busy) ui.postDelayed(this, 1000);
            else if (p != null) safeRefresh(current);          // end of the test / session: a full refresh
        }
    };

    void startTickerIfNeeded() {
        ui.removeCallbacks(ticker);
        if (Prefs.testActive(this) || Prefs.sessionActive(this)) ui.postDelayed(ticker, 1000);
    }

    /** Rescue mode: the normal screen could not be built; only the Update page is offered. */
    private boolean fallback;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        try {
            Clock.init(this);
            L.load(this);
            Ui.paintWindow(this);
            Prefs.installDay(this);
            firstRunDefaults();
            Reminders.schedule(this);
            // Android 13+: reminders need the notification permission (asked only once, here)
            if (Reminders.enabled(this) && !Reminders.permitted(this) && !Prefs.sp(this).getBoolean("notifAsked", false)) {
                Prefs.sp(this).edit().putBoolean("notifAsked", true).apply();
                try { requestPermissions(new String[]{Reminders.PERM}, 78); } catch (Exception ignored) {}
            }
            buildUi(b);
        } catch (Throwable t) {
            // never a screen that closes: always keep a way to install a fixed version
            CrashLog.save(this, t);
            showFallback(t);
        }
    }

    private void buildUi(Bundle b) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout main = Ui.column(this);
        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int bp = Ui.dp(this, 10);
        bar.setPadding(bp, bp, Ui.dp(this, 16), bp);
        TextView burger = Ui.text(this, "☰", 22, Ui.TEXT, true);
        burger.setGravity(Gravity.CENTER);
        burger.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(); }
        });
        bar.addView(burger, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        titleTv = Ui.text(this, "", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 6);
        bar.addView(titleTv, tlp);
        TextView lockIc = Ui.text(this, "🔒", 18, Ui.TEXT, false);
        bar.addView(lockIc);
        main.addView(bar);

        container = new FrameLayout(this);
        main.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // side menu
        scrim = new View(this);
        scrim.setBackgroundColor(0x88000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(Ui.dp(this, 280), ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        int start = b != null ? b.getInt("page", HOME) : getIntent().getIntExtra("page", HOME);
        show(Math.max(0, Math.min(ABOUT, start)));
    }

    /** Rescue screen: explanation, error report to copy and Update page (to install the fix). */
    private void showFallback(Throwable t) {
        fallback = true;
        try {
            Ui.paintWindow(this);
            final String report = CrashLog.last(this);
            LinearLayout col = Ui.column(this);
            LinearLayout card = Ui.card(this);
            card.addView(Ui.text(this, "⚠ " + L.t("Ankineer Lock a rencontré un problème", "Ankineer Lock ran into a problem"), 18, Ui.AMBER, true));
            card.addView(Ui.text(this, L.t("L'écran principal n'a pas pu s'ouvrir. Le verrou continue de fonctionner. "
                            + "Installe une version corrigée ci-dessous, ou copie le rapport et envoie-le au développeur.",
                    "The main screen couldn't open. The lock keeps working. "
                            + "Install a fixed version below, or copy the report and send it to the developer."), 14, Ui.TEXT, false), Ui.topMargin(this, 8));
            card.addView(Ui.text(this, CrashLog.summary(t), 12, Ui.MUTED, false), Ui.topMargin(this, 8));
            Button copy = Ui.button(this, L.t("Copier le rapport d'erreur", "Copy the error report"), false);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CrashLog.copy(MainActivity.this, report != null ? report : String.valueOf(t)); }
            });
            card.addView(copy);
            col.addView(card);
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(Ui.BG);
            LinearLayout main = Ui.column(this);
            main.addView(col);
            container = new FrameLayout(this);
            main.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            current = UPDATE;
            pages[UPDATE] = new UpdatePage(this);
            container.addView(pages[UPDATE].view(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pages[UPDATE].refresh();
        } catch (Throwable t2) {
            TextView tv = new TextView(this);
            tv.setPadding(40, 80, 40, 40);
            tv.setText("Ankineer Lock : " + t + "\n\n" + t2);
            setContentView(tv);
        }
    }

    /** A crashing page doesn't take the app down: the error is shown in its place. */
    private View errorView(Throwable t) {
        CrashLog.save(this, t);
        final String report = CrashLog.last(this);
        LinearLayout col = Ui.column(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "⚠ " + L.t("Cette page a rencontré une erreur", "This page ran into an error"), 17, Ui.AMBER, true));
        card.addView(Ui.text(this, CrashLog.summary(t), 12, Ui.MUTED, false), Ui.topMargin(this, 8));
        Button copy = Ui.button(this, L.t("Copier le rapport d'erreur", "Copy the error report"), false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CrashLog.copy(MainActivity.this, report != null ? report : String.valueOf(t)); }
        });
        card.addView(copy);
        Button up = Ui.button(this, L.t("Aller à Mise à jour", "Go to Update"), false);
        up.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { show(UPDATE); }
        });
        card.addView(up);
        col.addView(card);
        return scroll(col);
    }

    /** Refreshes a page while protecting the app against an error in that page. */
    void safeRefresh(int idx) {
        Page p = pages[idx];
        if (p == null) return;
        try {
            p.refresh();
        } catch (Throwable t) {
            pages[idx] = null;
            if (idx == current && container != null) {
                container.removeAllViews();
                container.addView(errorView(t), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", current);
    }

    private LinearLayout buildDrawer() {
        LinearLayout d = Ui.column(this);
        d.setBackgroundColor(Ui.CARD);
        d.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 18));
        d.setClickable(true);

        LinearLayout head = Ui.column(this);
        head.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 18));
        head.addView(Ui.text(this, "Ankineer Lock", 24, Ui.TEXT, true));
        head.addView(Ui.text(this, L.t("Ton téléphone, débloqué par Anki.", "Your phone, unlocked by Anki."), 13, Ui.MUTED, false),
                Ui.topMargin(this, 2));
        d.addView(head);

        String[] titles = pageTitles();
        for (int i = 0; i < PAGE_COUNT; i++) {
            final int idx = i;
            TextView t = Ui.text(this, PAGE_ICONS[i] + "    " + titles[i], 16, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 16), 0);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeDrawer();
                    show(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
            lp.leftMargin = Ui.dp(this, 10);
            lp.rightMargin = Ui.dp(this, 10);
            d.addView(t, lp);
            menuItems[i] = t;
        }
        View spacer = new View(this);
        d.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView brand = Ui.text(this, "Developed by Ankineer", 12, Ui.MUTED, false);
        brand.setGravity(Gravity.CENTER);
        d.addView(brand);
        return d;
    }

    void openDrawer() {
        if (drawer == null || scrim == null) return;
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-Ui.dp(this, 280));
        drawer.animate().translationX(0).setDuration(140).start();
    }

    void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().translationX(-Ui.dp(this, 280)).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() {
                drawer.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
            }
        }).start();
    }

    void show(int idx) {
        if (idx < 0 || idx >= PAGE_COUNT) idx = HOME;
        if (fallback && idx != UPDATE) return;          // rescue mode: only the update is offered
        if (container == null) return;
        current = idx;
        if (titleTv != null) titleTv.setText(pageTitles()[idx]);
        for (int i = 0; i < menuItems.length; i++) {
            if (menuItems[i] == null) continue;
            menuItems[i].setBackground(i == idx ? Ui.round(this, Ui.DARK ? 0x225B8CFF : 0x1A5B8CFF, 12, 0) : null);
            menuItems[i].setTextColor(i == idx ? Ui.ACCENT : Ui.TEXT);
        }
        try {
            if (pages[idx] == null) pages[idx] = create(idx);
            container.removeAllViews();
            container.addView(pages[idx].view(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            pages[idx] = null;
            container.removeAllViews();
            container.addView(errorView(t), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        safeRefresh(idx);
    }

    private Page create(int idx) {
        switch (idx) {
            case SETTINGS: return new SettingsPage(this);
            case EMERGENCY: return new EmergencyPage(this);
            case STATS: return new StatsPage(this);
            case INSTALL: return new InstallPage(this);
            case UPDATE: return new UpdatePage(this);
            case ABOUT: return new AboutPage(this);
            default: return new HomePage(this);
        }
    }

    /** Language or theme changed: rebuild the screen. */
    void rebuild() {
        recreate();      // onSaveInstanceState keeps the page shown
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra("page")) show(Math.max(0, Math.min(ABOUT, intent.getIntExtra("page", HOME))));
    }

    @Override
    public void onBackPressed() {
        if (fallback) { super.onBackPressed(); return; }
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) { closeDrawer(); return; }
        if (current != HOME) { show(HOME); return; }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        safeRefresh(current);
        if (fallback) return;
        runCheck();
        startTickerIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == UpdatePage.REQ_PICK) {
            if (pages[UPDATE] == null) pages[UPDATE] = create(UPDATE);
            ((UpdatePage) pages[UPDATE]).onPicked(res, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        runCheck();
    }

    /** Reads AnkiDroid in the background, then refreshes the page shown. */
    void runCheck() {
        final Context ctx = getApplicationContext();
        Ui.safeThread(MainActivity.this, new Runnable() {
            @Override public void run() {
                lastRes = GoalChecker.check(ctx);
                final boolean started = LockService.running && Prefs.autoArmIfReady(ctx, InstallPage.setupComplete(ctx, lastRes));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (started) Toast.makeText(MainActivity.this, L.t("🔒 Installation terminée : verrou lancé automatiquement",
                                "🔒 Setup complete: lock started automatically"), Toast.LENGTH_LONG).show();
                        safeRefresh(current);
                    }
                });
            }
        }).start();
    }

    private void firstRunDefaults() {
        if (Prefs.initialized(this)) return;
        Set<String> wl = Prefs.whitelist(this);
        for (String pkg : Apps.DEFAULT_WHITELIST) if (Apps.isInstalled(this, pkg)) wl.add(pkg);
        Prefs.setWhitelist(this, wl);
        Prefs.setInitialized(this);
    }

    // ------------------------------------------------------------------ building blocks shared by pages

    ScrollView scroll(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 32));
        sv.addView(content);
        return sv;
    }

    TextView section(LinearLayout parent, String title) {
        TextView t = Ui.text(this, title.toUpperCase(L.en ? java.util.Locale.ENGLISH : java.util.Locale.FRENCH), 12, Ui.MUTED, true);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = Ui.topMargin(this, 14);
        lp.bottomMargin = Ui.dp(this, 8);
        lp.leftMargin = Ui.dp(this, 4);
        parent.addView(t, lp);
        return t;
    }

    Ui.Bar bar(LinearLayout parent, int color) {
        Ui.Bar b = new Ui.Bar(this);
        b.setTrack(Ui.STROKE);
        b.set(0f, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 7));
        lp.topMargin = Ui.dp(this, 6);
        parent.addView(b, lp);
        return b;
    }

    EditText numberField(String hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setBackground(Ui.round(this, Ui.BG, 10, Ui.STROKE));
        int pe = Ui.dp(this, 12);
        e.setPadding(pe, pe, pe, pe);
        return e;
    }

    Switch makeSwitch(String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(15);
        s.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        return s;
    }

    TextView note(LinearLayout parent, String text) {
        TextView t = Ui.text(this, text, 12.5f, Ui.MUTED, false);
        parent.addView(t, Ui.topMargin(this, 4));
        return t;
    }

    Button chip(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        styleChip(b, false);
        return b;
    }

    void styleChip(Button b, boolean on) {
        b.setTextColor(on ? 0xFFFFFFFF : Ui.TEXT);
        b.setBackground(on ? Ui.round(this, Ui.ACCENT, 12, 0) : Ui.round(this, 0x00000000, 12, Ui.STROKE));
    }

    /** Blocking problem before starting a lock; null if everything is fine. */
    String blockingProblem() {
        if (!GoalChecker.isAnkiInstalled(this)) return L.t("AnkiDroid introuvable", "AnkiDroid not found");
        if (!GoalChecker.hasPermission(this)) return L.t("accès à AnkiDroid non autorisé (Installation, étape 1)", "AnkiDroid access not granted (Setup, step 1)");
        GoalChecker.Result r = lastRes;
        if (r == null || !r.apiOk) return L.t("Ankineer n'arrive pas à lire ta progression AnkiDroid", "Ankineer can't read your AnkiDroid progress");
        if (!Apps.isServiceEnabled(this)) return L.t("service d'accessibilité non activé (Installation, étape 3)", "accessibility service not enabled (Setup, step 3)");
        if (!LockService.running) return L.t("service activé mais arrêté : désactive puis réactive-le (Installation)", "service enabled but stopped: turn it off and on again (Setup)");
        return null;
    }
}
