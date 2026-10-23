package com.ankineer.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App lists: launchable, always allowed, sensitive. */
final class Apps {
    private Apps() {}

    /** Blocked during the lock in strict mode (prevents turning off the service or uninstalling). */
    static final Set<String> SETTINGS = new HashSet<String>(Arrays.asList(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.huawei.systemmanager"));

    static final Set<String> INSTALLERS = new HashSet<String>(Arrays.asList(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller"));

    /** Never blocked: system, calls, AnkiDroid. */
    private static final Set<String> ALWAYS_STATIC = new HashSet<String>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.android.dialer",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.documentsui", "com.google.android.documentsui",   // file picker (update)
            GoalChecker.ANKI_PKG));

    /** Suggested by default in the allow list if installed. */
    static final String[] DEFAULT_WHITELIST = {
            "com.whatsapp", "com.whatsapp.w4b",
            "com.google.android.apps.maps",
            "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock",
            "com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.android.calculator2",
            "com.google.android.apps.messaging", "com.samsung.android.messaging"};

    static Set<String> launchable(Context c) {
        Set<String> out = new HashSet<String>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(i, 0);
            for (ResolveInfo ri : list) out.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {}
        return out;
    }

    static Set<String> homes(Context c) {
        Set<String> out = new HashSet<String>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(i, 0);
            for (ResolveInfo ri : list) out.add(ri.activityInfo.packageName);
        } catch (Exception ignored) {}
        return out;
    }

    static Set<String> alwaysAllowed(Context c) {
        Set<String> out = new HashSet<String>(ALWAYS_STATIC);
        out.add(c.getPackageName());
        out.addAll(homes(c));
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tm.getDefaultDialerPackage() != null) out.add(tm.getDefaultDialerPackage());
        } catch (Exception ignored) {}
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(c);
            if (sms != null) out.add(sms);
        } catch (Exception ignored) {}
        try {
            String imes = Settings.Secure.getString(c.getContentResolver(), "enabled_input_methods");
            if (imes != null) {
                for (String part : imes.split(":")) {
                    int slash = part.indexOf('/');
                    if (slash > 0) out.add(part.substring(0, slash));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isServiceEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.toLowerCase().contains((c.getPackageName() + "/").toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /** "Autostart" screens of Android skins (Xiaomi, Oppo, Vivo, Huawei, Honor, Asus, Letv). */
    private static final String[][] AUTOSTART = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"},
            {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
    };

    private static android.content.Intent autostartIntent(Context c) {
        for (String[] a : AUTOSTART) {
            android.content.Intent i = new android.content.Intent().setClassName(a[0], a[1]).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (c.getPackageManager().resolveActivity(i, 0) != null) return i;
            } catch (Exception ignored) {}
        }
        return null;
    }

    static boolean hasAutostartScreen(Context c) { return autostartIntent(c) != null; }

    /** Opens the manufacturer's "autostart" screen; false if there is none. */
    static boolean openAutostartSettings(Context c) {
        android.content.Intent i = autostartIntent(c);
        if (i == null) return false;
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }
}
