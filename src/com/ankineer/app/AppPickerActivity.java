package com.ankineer.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Choice of the apps allowed during the lock. */
public class AppPickerActivity extends Activity {
    static final class Item {
        String pkg;
        String label;
        Drawable icon;
    }

    private final List<Item> items = new ArrayList<Item>();
    private Set<String> wl;
    private BaseAdapter adapter;
    private TextView countTv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.isEditable(this)) {
            Toast.makeText(this, L.t("Verrou actif : réglages modifiables après ton objectif.", "Lock active: settings can be changed after your goal."), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        L.load(this);
        Ui.paintWindow(this);
        Prefs.setAppsChosen(this);
        wl = Prefs.whitelist(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 24), p, 0);

        root.addView(Ui.text(this, L.t("Applications autorisées", "Allowed apps"), 24, Ui.TEXT, true));
        TextView sub = Ui.text(this,
                L.t("Utilisables même quand le verrou est actif. Téléphone, SMS, AnkiDroid et l'écran d'accueil le sont toujours.", "Usable even while locked. Phone, SMS, AnkiDroid and the home screen always are."),
                14, Ui.MUTED, false);
        root.addView(sub, Ui.topMargin(this, 6));
        countTv = Ui.text(this, L.t("Chargement des applications…", "Loading apps…"), 13, Ui.ACCENT, true);
        root.addView(countTv, Ui.topMargin(this, 12));

        ListView lv = new ListView(this);
        lv.setDivider(new ColorDrawable(Ui.STROKE));
        lv.setDividerHeight(1);
        lv.setSelector(new ColorDrawable(0x00000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(this, 8);
        root.addView(lv, lp);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row;
                ImageView icon;
                TextView label;
                CheckBox cb;
                if (convert == null) {
                    row = new LinearLayout(AppPickerActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    int v = Ui.dp(AppPickerActivity.this, 10);
                    row.setPadding(0, v, 0, v);
                    icon = new ImageView(AppPickerActivity.this);
                    int s = Ui.dp(AppPickerActivity.this, 36);
                    row.addView(icon, new LinearLayout.LayoutParams(s, s));
                    label = Ui.text(AppPickerActivity.this, "", 15, Ui.TEXT, false);
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    llp.leftMargin = Ui.dp(AppPickerActivity.this, 14);
                    row.addView(label, llp);
                    cb = new CheckBox(AppPickerActivity.this);
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
                    row.addView(cb);
                    row.setTag(new Object[]{icon, label, cb});
                } else {
                    row = (LinearLayout) convert;
                }
                Object[] tag = (Object[]) row.getTag();
                icon = (ImageView) tag[0];
                label = (TextView) tag[1];
                cb = (CheckBox) tag[2];
                Item it = items.get(i);
                icon.setImageDrawable(it.icon);
                label.setText(it.label);
                cb.setChecked(wl.contains(it.pkg));
                return row;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Item it = items.get(pos);
                if (wl.contains(it.pkg)) wl.remove(it.pkg); else wl.add(it.pkg);
                Prefs.setWhitelist(AppPickerActivity.this, wl);
                adapter.notifyDataSetChanged();
                updateCount();
            }
        });

        setContentView(root);
        load();
    }

    private void load() {
        Ui.safeThread(AppPickerActivity.this, new Runnable() {
            @Override public void run() {
                PackageManager pm = getPackageManager();
                Set<String> always = Apps.alwaysAllowed(AppPickerActivity.this);
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                Set<String> seen = new HashSet<String>();
                final List<Item> out = new ArrayList<Item>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (seen.contains(pkg) || always.contains(pkg)) continue;
                    seen.add(pkg);
                    Item it = new Item();
                    it.pkg = pkg;
                    try {
                        it.label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                        it.icon = pm.getApplicationIcon(pkg);
                    } catch (Exception e) {
                        it.label = ri.loadLabel(pm).toString();
                        it.icon = ri.loadIcon(pm);
                    }
                    out.add(it);
                }
                final Collator col = Collator.getInstance(Locale.FRANCE);
                Collections.sort(out, new Comparator<Item>() {
                    @Override public int compare(Item a, Item b) {
                        boolean wa = wl.contains(a.pkg), wb = wl.contains(b.pkg);
                        if (wa != wb) return wa ? -1 : 1;
                        return col.compare(a.label, b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        items.clear();
                        items.addAll(out);
                        adapter.notifyDataSetChanged();
                        updateCount();
                    }
                });
            }
        }).start();
    }

    private void updateCount() {
        int n = 0;
        for (Item it : items) if (wl.contains(it.pkg)) n++;
        countTv.setText(n + L.t(" application(s) autorisée(s)", " allowed app(s)"));
    }
}
