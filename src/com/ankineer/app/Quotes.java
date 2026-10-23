package com.ankineer.app;

import java.util.Calendar;

/** Motivational quotes for the hourly reminders (a new one every hour, in French or English). */
final class Quotes {
    private Quotes() {}

    // {French, English, author}  — empty author = Ankineer's own line
    private static final String[][] Q = {
            {"La vie est courte, l'art est long.", "Life is short, the art is long.", "Hippocrate"},
            {"Le hasard ne favorise que les esprits préparés.", "Chance favours only the prepared mind.", "Louis Pasteur"},
            {"Un voyage de mille lieues commence toujours par un premier pas.", "A journey of a thousand miles begins with a single step.", "Lao-Tseu"},
            {"Ce n'est pas parce que les choses sont difficiles que nous n'osons pas, c'est parce que nous n'osons pas qu'elles sont difficiles.",
                    "It is not because things are difficult that we do not dare; it is because we do not dare that they are difficult.", "Sénèque"},
            {"Pendant qu'on remet à plus tard, la vie passe.", "While we postpone, life speeds by.", "Sénèque"},
            {"Le bon médecin soigne la maladie ; le grand médecin soigne le patient qui a la maladie.",
                    "The good physician treats the disease; the great physician treats the patient who has the disease.", "William Osler"},
            {"Fais chaque chose comme si c'était la dernière de ta vie.", "Do every act of your life as though it were your last.", "Marc Aurèle"},
            {"Tu as du pouvoir sur ton esprit, pas sur les événements. Comprends cela, et tu trouveras la force.",
                    "You have power over your mind, not outside events. Realise this, and you will find strength.", "Marc Aurèle"},
            {"Il n'est pas de vent favorable pour celui qui ne sait où il va.", "No wind is favourable to the one who doesn't know where he is going.", "Sénèque"},
            {"La patience est amère, mais son fruit est doux.", "Patience is bitter, but its fruit is sweet.", "Jean-Jacques Rousseau"},
            {"Dix cartes maintenant valent mieux que cent « plus tard ».", "Ten cards now beat a hundred “later”.", ""},
            {"Chaque carte révisée aujourd'hui, c'est un patient mieux soigné demain.", "Every card you review today is a patient better treated tomorrow.", ""},
            {"La régularité bat l'intensité : un peu, mais tous les jours.", "Consistency beats intensity: a little, but every day.", ""},
            {"Ton futur toi te remerciera pour les révisions d'aujourd'hui.", "Future you will thank you for today's reviews.", ""},
            {"Le téléphone peut attendre. Ta mémoire, elle, oublie.", "Your phone can wait. Your memory won't.", ""},
            {"Les cartes dues ne disparaissent pas : elles s'accumulent. Commence par cinq.", "Due cards don't vanish, they pile up. Start with five.", ""},
            {"La motivation te fait commencer, l'habitude te fait continuer.", "Motivation gets you started, habit keeps you going.", ""},
            {"Une minute d'Anki maintenant vaut dix minutes de relecture la veille de l'examen.", "One minute of Anki now is worth ten minutes of rereading the night before the exam.", ""},
            {"Pas besoin d'avoir envie : ouvre AnkiDroid, l'envie suivra.", "You don't need to feel like it: open AnkiDroid, the feeling will follow.", ""},
            {"La répétition espacée transforme l'effort d'aujourd'hui en savoir pour toujours.", "Spaced repetition turns today's effort into lasting knowledge.", ""},
            {"Ce que tu révises chaque jour devient ce que tu sais vraiment.", "What you review every day becomes what you truly know.", ""},
            {"Termine tes cartes, puis profite de ton téléphone sans culpabilité.", "Finish your cards, then enjoy your phone guilt-free.", ""},
            {"Les grands résultats sont faits de petites séances.", "Great results are made of small sessions.", ""},
            {"Une série ne se casse pas en un jour : protège-la aujourd'hui.", "A streak isn't built in a day — protect it today.", ""},
            {"Concentre-toi sur la carte devant toi, pas sur la pile entière.", "Focus on the card in front of you, not the whole pile.", ""},
            {"Le savoir est la seule chose qui grandit quand on le révise.", "Knowledge is the only thing that grows when you review it.", ""},
            {"Commence maintenant, perfectionne plus tard.", "Start now, refine later.", ""},
            {"Chaque « Bien » est une victoire. Va en chercher quelques-unes.", "Every “Good” is a win. Go get a few.", ""},
            {"La discipline, c'est choisir ce que tu veux le plus plutôt que ce que tu veux maintenant.",
                    "Discipline is choosing what you want most over what you want now.", ""},
            {"Encore quelques cartes et ton téléphone est à toi.", "A few more cards and your phone is yours.", ""},
    };

    /** Quote of the current hour (changes every hour). {@code offset} gives another quote for the 2nd reminder. */
    static String ofTheHour(int offset) {
        Calendar cal = Calendar.getInstance();
        int i = (cal.get(Calendar.DAY_OF_YEAR) * 24 + cal.get(Calendar.HOUR_OF_DAY) + offset * 7) % Q.length;
        String[] q = Q[i];
        String text = L.en ? q[1] : q[0];
        return "« " + text + " »" + (q[2].isEmpty() ? "" : " — " + q[2]);
    }

    static int count() { return Q.length; }
}
