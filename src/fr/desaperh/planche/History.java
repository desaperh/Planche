package fr.desaperh.planche;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Historique des albums ouverts (du plus récent au plus ancien) et leurs miniatures.
 * Tout reste dans le stockage privé de l'appli : aucune autorisation nécessaire.
 */
final class History {

    static final int MAX = 40;
    static final int THUMB_W = 300;
    static final int THUMB_H = 450;

    static final class Book {
        final String uri;
        final String name;
        final long time;

        Book(String uri, String name, long time) {
            this.uri = uri;
            this.name = name;
            this.time = time;
        }
    }

    private History() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("planche", Context.MODE_PRIVATE);
    }

    static List<Book> load(Context c) {
        List<Book> out = new ArrayList<>();
        String raw = sp(c).getString("history", null);
        if (raw == null) {
            // migration depuis la version 1.0 (seul le dernier livre était mémorisé)
            String last = Prefs.lastUri(c);
            if (last != null) out.add(new Book(last, Prefs.lastName(c), System.currentTimeMillis()));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Book(o.getString("u"), o.optString("n", ""), o.optLong("t", 0)));
            }
        } catch (JSONException ignored) {
            // historique illisible : on repart de zéro
        }
        return out;
    }

    private static void save(Context c, List<Book> books) {
        JSONArray a = new JSONArray();
        try {
            for (Book b : books) {
                a.put(new JSONObject().put("u", b.uri).put("n", b.name).put("t", b.time));
            }
        } catch (JSONException ignored) {
            // impossible avec des chaînes et des longs
        }
        sp(c).edit().putString("history", a.toString()).apply();
    }

    /** Place l'album en tête de l'historique. */
    static void touch(Context c, String uri, String name) {
        List<Book> books = load(c);
        for (int i = books.size() - 1; i >= 0; i--) {
            if (books.get(i).uri.equals(uri)) books.remove(i);
        }
        books.add(0, new Book(uri, name != null ? name : "", System.currentTimeMillis()));
        while (books.size() > MAX) {
            forget(c, books.remove(books.size() - 1).uri);
        }
        save(c, books);
    }

    static void remove(Context c, String uri) {
        List<Book> books = load(c);
        for (int i = books.size() - 1; i >= 0; i--) {
            if (books.get(i).uri.equals(uri)) books.remove(i);
        }
        save(c, books);
        forget(c, uri);
    }

    static void clear(Context c) {
        for (Book b : load(c)) forget(c, b.uri);
        save(c, new ArrayList<Book>());
    }

    /** Supprime la miniature et rend l'accès au fichier s'il avait été conservé. */
    private static void forget(Context c, String uri) {
        //noinspection ResultOfMethodCallIgnored
        thumbFile(c, uri).delete();
        try {
            c.getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // accès non conservé (fichier d'un dossier de la bibliothèque, ou déjà rendu)
        }
    }

    static File thumbFile(Context c, String uri) {
        return new File(new File(c.getFilesDir(), "miniatures"), sha1(uri) + ".jpg");
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
