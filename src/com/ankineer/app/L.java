package com.ankineer.app;

import android.content.Context;

/** Langue de l'interface : français (par défaut) ou anglais. Usage : L.t("Cartes", "Cards"). */
final class L {
    static boolean en = false;

    private L() {}

    static void load(Context c) { en = Prefs.english(c); }

    static String t(String fr, String english) { return en ? english : fr; }
}
