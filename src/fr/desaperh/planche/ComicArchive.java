package fr.desaperh.planche;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Archive CBZ ouverte depuis un Uri (sélecteur système ou gestionnaire de fichiers).
 * Lecture directe sur le descripteur fourni par Android ; si le fichier n'est pas
 * « seekable » (fournisseur cloud), copie dans le cache privé de l'appli.
 */
final class ComicArchive implements Closeable {

    private final ZipReader zip;
    private final List<ZipReader.Entry> pages;
    private final File temp;

    private ComicArchive(ZipReader zip, File temp) {
        this.zip = zip;
        this.temp = temp;
        this.pages = Pages.select(zip.entries());
    }

    int pageCount() {
        return pages.size();
    }

    /** À appeler depuis un seul thread à la fois. */
    byte[] read(int index) throws IOException {
        return zip.read(pages.get(index));
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException ignored) {
            // rien à faire
        }
        if (temp != null) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    static ComicArchive open(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) throw new FileNotFoundException("Fichier introuvable.");

        if (pfd.getStatSize() >= 0) {
            ZipReader.Source src = null;
            try {
                FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                src = new ZipReader.ChannelSource(fis.getChannel(), fis, pfd);
                return new ComicArchive(new ZipReader(src), null);
            } catch (ZipReader.UnsupportedFormatException e) {
                closeQuietly(src != null ? src : pfd);
                throw e;
            } catch (IOException | RuntimeException e) {
                closeQuietly(src != null ? src : pfd);
                // on retente via une copie locale
            }
        } else {
            closeQuietly(pfd);
        }

        File tmp = new File(ctx.getCacheDir(), "lecture-" + System.nanoTime() + ".cbz");
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new FileNotFoundException("Fichier introuvable.");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(tmp, "r");
            return new ComicArchive(new ZipReader(new ZipReader.ChannelSource(raf.getChannel(), raf)), tmp);
        } catch (IOException | RuntimeException e) {
            closeQuietly(raf);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    /** Supprime les copies orphelines (appli tuée pendant une lecture). */
    static void cleanCache(Context ctx) {
        File[] files = ctx.getCacheDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith("lecture-")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
            // rien à faire
        }
    }
}
