package fr.desaperh.planche;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Sélection et tri des images d'une archive. */
final class Pages {

    private static final String[] EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif"
    };

    private Pages() {
    }

    static boolean isImage(String rawName) {
        String n = rawName.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (n.endsWith("/")) return false;
        if (n.startsWith("__macosx/") || n.contains("/__macosx/")) return false;
        String base = n.substring(n.lastIndexOf('/') + 1);
        if (base.startsWith(".")) return false; // ._page.jpg (macOS), .DS_Store…
        for (String e : EXT) {
            if (base.endsWith(e)) return true;
        }
        return false;
    }

    static List<ZipReader.Entry> select(List<ZipReader.Entry> all) {
        List<ZipReader.Entry> out = new ArrayList<>();
        for (ZipReader.Entry e : all) {
            if (isImage(e.name)) out.add(e);
        }
        Collections.sort(out, new Comparator<ZipReader.Entry>() {
            @Override
            public int compare(ZipReader.Entry a, ZipReader.Entry b) {
                return NaturalOrder.INSTANCE.compare(a.name.replace('\\', '/'), b.name.replace('\\', '/'));
            }
        });
        return out;
    }
}
