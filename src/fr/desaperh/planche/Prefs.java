package fr.desaperh.planche;

import android.content.Context;
import android.content.SharedPreferences;

/** Réglages et progression de lecture (stockage privé de l'appli). */
final class Prefs {

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("planche", Context.MODE_PRIVATE);
    }

    static int page(Context c, String key) {
        return sp(c).getInt("p:" + key, 0);
    }

    static int count(Context c, String key) {
        return sp(c).getInt("n:" + key, 0);
    }

    static void saveProgress(Context c, String key, String name, int page, int count) {
        sp(c).edit()
                .putInt("p:" + key, page)
                .putInt("n:" + key, count)
                .putString("last_uri", key)
                .putString("last_name", name)
                .apply();
    }

    /** "" si jamais ouvert, sinon « p. 12 / 180 » ou « terminé ». */
    static String status(Context c, String key) {
        int n = count(c, key);
        if (n <= 0) return "";
        int p = page(c, key);
        return p >= n - 1 ? "terminé" : "p. " + (p + 1) + " / " + n;
    }

    static String lastUri(Context c) {
        return sp(c).getString("last_uri", null);
    }

    static String lastName(Context c) {
        return sp(c).getString("last_name", "");
    }

    static void forgetLast(Context c) {
        sp(c).edit().remove("last_uri").remove("last_name").apply();
    }

    static String tree(Context c) {
        return sp(c).getString("tree", null);
    }

    static void setTree(Context c, String uri) {
        sp(c).edit().putString("tree", uri).apply();
    }

    static boolean rtl(Context c) {
        return sp(c).getBoolean("rtl", false);
    }

    static void setRtl(Context c, boolean v) {
        sp(c).edit().putBoolean("rtl", v).apply();
    }

    static boolean fitWidth(Context c) {
        return sp(c).getBoolean("fit_width", false);
    }

    static void setFitWidth(Context c, boolean v) {
        sp(c).edit().putBoolean("fit_width", v).apply();
    }
}
