package com.ankineer.app;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** À propos : fonctionnement détaillé de l'app. */
final class AboutPage implements MainActivity.Page {
    private final View root;

    AboutPage(MainActivity a) {
        LinearLayout col = Ui.column(a);

        LinearLayout hero = Ui.card(a);
        hero.addView(Ui.text(a, "Ankineer Lock", 26, Ui.TEXT, true));
        hero.addView(Ui.text(a, L.t("Ton téléphone se libère quand ton travail Anki est fait.",
                "Your phone unlocks once your Anki work is done."), 14, Ui.MUTED, false), Ui.topMargin(a, 4));
        hero.addView(Ui.text(a, "Version 1.0", 12, Ui.MUTED, false), Ui.topMargin(a, 8));
        col.addView(hero);

        block(a, col, L.t("Le principe", "The idea"),
                L.t("Tant que ton objectif Anki du jour n'est pas atteint, les applications qui distraient (réseaux sociaux, vidéos, jeux…) sont bloquées. "
                                + "AnkiDroid, le téléphone, les SMS et les apps que tu autorises restent toujours accessibles. "
                                + "Dès que l'objectif est atteint, tout se débloque jusqu'au changement de jour d'AnkiDroid (4h par défaut, lu dans tes réglages AnkiDroid).",
                        "Until your daily Anki goal is reached, distracting apps (social media, videos, games…) are blocked. "
                                + "AnkiDroid, phone, SMS and the apps you allow always stay available. "
                                + "Once the goal is reached, everything unlocks until AnkiDroid's next day change (4 am by default, read from your AnkiDroid settings)."));

        block(a, col, L.t("L'objectif du jour", "The daily goal"),
                L.t("Il combine deux seuils, tous deux obligatoires : un nombre de cartes étudiées (au moins 50) et un temps d'étude (au moins 15 minutes). "
                                + "Les chiffres sont exactement ceux qu'affiche AnkiDroid (« Étudié X cartes en Y minutes aujourd'hui ») : "
                                + "ils viennent de l'historique des révisions d'AnkiDroid, donc tes révisions sur PC comptent dès qu'AnkiDroid est synchronisé. "
                                + "Tu peux augmenter les seuils à tout moment ; tu ne peux les baisser que lorsque le téléphone est libre.",
                        "It combines two thresholds, both required: a number of cards studied (at least 50) and study time (at least 15 minutes). "
                                + "The numbers are exactly those AnkiDroid shows (“Studied X cards in Y minutes today”): "
                                + "they come from AnkiDroid's review history, so your PC reviews count as soon as AnkiDroid is synced. "
                                + "You can raise the thresholds anytime; you can only lower them while your phone is free."));

        block(a, col, L.t("Les cartes dues", "Due cards"),
                L.t("Deux options dans Paramètres :\n"
                                + "• « Débloquer dès que toutes les cartes dues sont faites » : s'il ne reste rien à réviser, les seuils ne s'appliquent pas.\n"
                                + "• « Exiger aussi 0 carte due » : il faut en plus avoir terminé toutes les cartes dues du jour.",
                        "Two options in Settings:\n"
                                + "• “Unlock as soon as all due cards are done”: if there's nothing left to review, the thresholds don't apply.\n"
                                + "• “Also require 0 due cards”: you must also finish all of today's due cards."));

        block(a, col, L.t("Les sessions", "Sessions"),
                L.t("Une session est un objectif ponctuel que tu te fixes (par exemple 40 cartes ou 25 minutes). "
                                + "Pendant la session, tout est bloqué — même si l'objectif du jour est déjà atteint — jusqu'à ce que la cible soit atteinte. "
                                + "Le compte part de zéro au lancement et la session s'arrête toute seule à la fin.",
                        "A session is a one-off target you set yourself (for example 40 cards or 25 minutes). "
                                + "During the session everything is locked — even if the daily goal is already reached — until you hit the target. "
                                + "The count starts at zero and the session ends on its own."));

        block(a, col, L.t("Le cercle de progression", "The progress circle"),
                L.t("Un petit cercle reste en haut de l'écran pendant le verrou : anneau bleu = cartes, anneau violet = minutes, pourcentage au centre. "
                                + "Pendant une session, un anneau orange montre ce qu'il reste. Touche-le pour ouvrir Ankineer Lock, glisse-le pour le déplacer.",
                        "A small circle stays at the top of the screen while locked: blue ring = cards, purple ring = minutes, percentage in the middle. "
                                + "During a session, an orange ring shows what's left. Tap it to open Ankineer Lock, drag it to move it."));

        block(a, col, L.t("Les rappels", "Reminders"),
                L.t("Chaque heure, deux notifications distinctes : « Encore N cartes dues » s'il reste des cartes à réviser, "
                                + "et « Objectif minimum pas encore atteint » avec ce qu'il te manque, chacune avec une citation de motivation différente. "
                                + "Rien pendant les heures calmes (23h → 8h par défaut). "
                                + "Tout se règle dans Paramètres → Rappels.",
                        "Every hour, two separate notifications: “Still N due cards” if cards are left to review, "
                                + "and “Minimum goal not reached yet” with what's missing, each with a different motivational quote. "
                                + "Nothing during quiet hours (11 pm → 8 am by default). "
                                + "Everything is in Settings → Reminders."));

        block(a, col, L.t("Les widgets d'écran d'accueil", "Home screen widgets"),
                L.t("Quatre widgets : anneaux (2×2), mini anneau (1×1), barres cartes · minutes (4×1) et semaine + série (4×2). "
                                + "Quatre styles au choix dans Paramètres : sombre, clair, verre, dégradé. Ils changent de couleur selon l'état : "
                                + "verrouillé, session, urgence, libre.",
                        "Four widgets: rings (2×2), mini ring (1×1), cards · minutes bars (4×1) and week + streak (4×2). "
                                + "Four styles in Settings: dark, light, glass, gradient. Their colour follows the state: locked, session, emergency, free."));

        block(a, col, L.t("Les statistiques", "Statistics"),
                L.t("Sur 7 jours, 30 jours ou 1 an : série et meilleure série, taux de réussite, moyennes, temps par carte, record, tendance, "
                                + "graphiques jour par jour, ton jour de la semaine le plus productif, carte d'activité sur 1 an, sessions et urgences.",
                        "Over 7 days, 30 days or 1 year: streak and best streak, success rate, averages, time per card, record, trend, "
                                + "day-by-day charts, your most productive weekday, a 1-year activity map, sessions and emergencies."));

        block(a, col, L.t("L'écran de blocage", "The block screen"),
                L.t("Quand tu ouvres une app bloquée, Ankineer Lock te ramène à l'accueil et affiche ta progression, avec un bouton pour ouvrir AnkiDroid "
                                + "et les urgences disponibles.",
                        "When you open a blocked app, Ankineer Lock sends you back to the home screen and shows your progress, with a button to open AnkiDroid "
                                + "and the emergencies available."));

        block(a, col, L.t("Les urgences", "Emergencies"),
                L.t("Deux moyens de déblocage, réglables dans la section Urgence :\n"
                                + "• Par code : libère tout de suite (15 min par défaut), une seule fois par jour, 5 essais maximum. "
                                + "Idéal si quelqu'un de confiance choisit le code.\n"
                                + "• Extrême urgence : 5 minutes tout de suite, une seule fois par jour.\n"
                                + "Avant le code et l'extrême urgence, un grand avertissement rappelle que c'est une autorisation exceptionnelle "
                                + "et qu'il faut revenir à la révision rapidement. Pendant l'urgence, le cercle devient un compte à rebours rouge. "
                                + "Une urgence libère tes apps, mais ne permet jamais d'arrêter le verrou ni de baisser l'objectif.",
                        "Two ways to unlock, set in the Emergency section:\n"
                                + "• With a code: frees right away (15 min by default), once a day only, 5 attempts max. "
                                + "Best if someone you trust picks the code.\n"
                                + "• Extreme emergency: 5 minutes right away, once a day only.\n"
                                + "Before the code and the extreme emergency, a big warning reminds you this is an exceptional permission "
                                + "and that you must get back to your reviews quickly. During the emergency, the circle becomes a red countdown. "
                                + "An emergency frees your apps but never lets you stop the lock or lower the goal."));

        block(a, col, L.t("Lancement automatique", "Automatic start"),
                L.t("Dès que l'installation est terminée (AnkiDroid connecté, service actif, batterie non optimisée, applications autorisées choisies), "
                                + "le verrou se lance tout seul. Si tu l'arrêtes à la main quand le téléphone est libre, il se relance le lendemain.",
                        "As soon as setup is complete (AnkiDroid connected, service running, battery not optimised, allowed apps chosen), "
                                + "the lock starts on its own. If you stop it by hand while your phone is free, it starts again the next day."));

        block(a, col, L.t("Désinstallation bloquée pour toujours", "Uninstall blocked forever"),
                L.t("Option dans Paramètres → Verrou. Même téléphone libre, Ankineer Lock ramène à l'accueil si tu ouvres : la fenêtre de désinstallation "
                                + "d'Ankineer Lock, sa fiche Play Store, ses « Infos de l'appli » (désinstaller, forcer l'arrêt) ou le réglage d'accessibilité qui coupe le service. "
                                + "Pour l'arrêter : téléphone libre, puis 24 heures d'attente. Limite : le mode sans échec d'Android désactive toutes les apps installées.",
                        "Option in Settings → Lock. Even when your phone is free, Ankineer Lock goes back to the home screen if you open: the uninstall window "
                                + "of Ankineer Lock, its Play Store page, its “App info” (uninstall, force stop) or the accessibility setting that turns the service off. "
                                + "To stop it: phone free, then a 24-hour wait. Limit: Android safe mode disables all installed apps."));

        block(a, col, L.t("Les mises à jour", "Updates"),
                L.t("Section Mise à jour : choisis le nouvel APK, Ankineer Lock vérifie que c'est bien lui (même signature, version plus récente) "
                                + "et lance la mise à jour par Android. Pas de désinstallation : réglages, statistiques et service sont gardés.",
                        "Update section: pick the new APK, Ankineer Lock checks it's really itself (same signature, newer version) "
                                + "and starts the update through Android. No uninstall: settings, stats and the service are kept."));

        block(a, col, L.t("Protection contre la triche", "Anti-cheat protection"),
                L.t("Pendant le verrou, les réglages qui assouplissent l'objectif sont figés, et l'option « Bloquer Paramètres et désinstallation » "
                                + "empêche de couper le service ou de supprimer l'app. Ce n'est pas une prison (un redémarrage en mode sans échec reste possible) : "
                                + "c'est une barrière forte qui t'aide à tenir ton engagement.",
                        "While locked, settings that would relax the goal are frozen, and “Block Settings and uninstall” "
                                + "prevents turning off the service or removing the app. It isn't a prison (a safe-mode reboot is still possible): "
                                + "it's a strong barrier that helps you keep your commitment."));

        block(a, col, L.t("Statistiques", "Statistics"),
                L.t("L'agenda montre les 365 derniers jours d'après l'historique d'AnkiDroid, même avant l'installation : vert = objectif atteint "
                                + "(seuils cartes et minutes de l'app), rouge = non atteint, point orange = session terminée. Les séries sont calculées sur tout cet historique. "
                                + "La rétention du jour (réponses autres que « À revoir » sur les cartes en révision) est comparée à ta rétention globale.",
                        "The calendar shows the last 365 days from AnkiDroid's history, even before install: green = goal reached "
                                + "(the app's card and minute thresholds), red = not reached, orange dot = session completed. Streaks are computed over this whole history. "
                                + "Today's retention (answers other than “Again” on review cards) is compared with your overall retention."));

        block(a, col, L.t("Vie privée", "Privacy"),
                L.t("Tout reste sur ton téléphone. Ankineer Lock n'a pas accès à Internet, ne lit pas le contenu de tes écrans et n'envoie aucune donnée. "
                                + "Il lit seulement, dans AnkiDroid, ton nombre de cartes, ton temps d'étude et tes cartes dues.",
                        "Everything stays on your phone. Ankineer Lock has no Internet access, doesn't read your screen content and sends no data. "
                                + "It only reads your card count, study time and due cards from AnkiDroid."));

        TextView brand = Ui.text(a, "Developed by Ankineer", 14, Ui.TEXT, true);
        brand.setGravity(Gravity.CENTER);
        col.addView(brand, Ui.topMargin(a, 26));
        TextView sub = Ui.text(a, L.t("Gratuit et open source (GPL-3.0)", "Free and open source (GPL-3.0)") + "\ngithub.com/BONJ37/ankineer-lock", 12, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, Ui.topMargin(a, 2));

        root = a.scroll(col);
    }

    private static void block(MainActivity a, LinearLayout col, String title, String body) {
        LinearLayout c = Ui.card(a);
        c.addView(Ui.text(a, title, 16, Ui.TEXT, true));
        TextView b = Ui.text(a, body, 14, Ui.MUTED, false);
        b.setLineSpacing(Ui.dp(a, 3), 1f);
        c.addView(b, Ui.topMargin(a, 6));
        col.addView(c);
    }

    @Override public View view() { return root; }
    @Override public void refresh() {}
    @Override public void tick() {}
}
