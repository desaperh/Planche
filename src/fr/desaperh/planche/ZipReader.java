package fr.desaperh.planche;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lecteur ZIP minimal à accès aléatoire.
 *
 * Il ne lit qu'au moyen de lectures positionnelles sur un descripteur déjà ouvert :
 * aucun chemin de fichier n'est nécessaire, donc aucune autorisation de stockage.
 * Gère : stocké / deflate, ZIP64, noms UTF-8 ou CP437, données préfixées.
 */
public final class ZipReader implements Closeable {

    public static final long MAX_ENTRY = 256L << 20; // 256 Mo par page, garde-fou

    /** Format reconnu mais non pris en charge (RAR, 7z, PDF…) : inutile de réessayer autrement. */
    public static final class UnsupportedFormatException extends IOException {
        private static final long serialVersionUID = 1L;

        public UnsupportedFormatException(String msg) {
            super(msg);
        }
    }

    public interface Source extends Closeable {
        long size() throws IOException;

        void readFully(long pos, byte[] b, int off, int len) throws IOException;
    }

    /** Source basée sur un FileChannel (lectures pread, la position partagée n'est jamais modifiée). */
    public static final class ChannelSource implements Source {
        private final FileChannel ch;
        private final Closeable[] owners;

        public ChannelSource(FileChannel ch, Closeable... owners) {
            this.ch = ch;
            this.owners = owners;
        }

        @Override
        public long size() throws IOException {
            return ch.size();
        }

        @Override
        public void readFully(long pos, byte[] b, int off, int len) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining()) {
                int r = ch.read(buf, pos + (buf.position() - off));
                if (r < 0) throw new EOFException("Fin de fichier inattendue.");
            }
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            try {
                ch.close();
            } catch (IOException e) {
                first = e;
            }
            for (Closeable c : owners) {
                if (c == null) continue;
                try {
                    c.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
        }
    }

    public static final class Entry {
        public final String name;
        final int flags;
        final int method;
        final long compressedSize;
        final long size;
        final long localHeaderOffset;

        Entry(String name, int flags, int method, long compressedSize, long size, long localHeaderOffset) {
            this.name = name;
            this.flags = flags;
            this.method = method;
            this.compressedSize = compressedSize;
            this.size = size;
            this.localHeaderOffset = localHeaderOffset;
        }
    }

    private static final Charset LEGACY = pickLegacyCharset();

    private final Source src;
    private final List<Entry> entries;

    /** En cas d'exception, l'appelant reste responsable de fermer {@code src}. */
    public ZipReader(Source src) throws IOException {
        this.src = src;
        this.entries = Collections.unmodifiableList(readCentralDirectory());
    }

    public List<Entry> entries() {
        return entries;
    }

    @Override
    public void close() throws IOException {
        src.close();
    }

    // ------------------------------------------------------------------ lecture d'une entrée

    public byte[] read(Entry e) throws IOException {
        if ((e.flags & 1) != 0) throw new IOException("Page chiffrée : archive protégée par mot de passe.");
        if (e.compressedSize < 0 || e.compressedSize > MAX_ENTRY || e.size > MAX_ENTRY) {
            throw new IOException("Page trop volumineuse.");
        }
        byte[] lh = new byte[30];
        src.readFully(e.localHeaderOffset, lh, 0, 30);
        if (u32(lh, 0) != 0x04034b50L) throw new IOException("En-tête ZIP local invalide.");
        long dataPos = e.localHeaderOffset + 30 + u16(lh, 26) + u16(lh, 28);
        byte[] comp = new byte[(int) e.compressedSize];
        src.readFully(dataPos, comp, 0, comp.length);
        switch (e.method) {
            case 0:
                return comp;
            case 8:
                return inflate(comp, e.size);
            default:
                throw new IOException("Compression « " + methodName(e.method)
                        + " » non prise en charge. Recréez le CBZ en mode « stocké » ou « deflate ».");
        }
    }

    static byte[] inflate(byte[] comp, long expected) throws IOException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(comp);
            int cap = expected > 0 ? (int) expected : Math.max(64 * 1024, comp.length * 4);
            byte[] out = new byte[cap];
            int n = 0;
            boolean dummy = false;
            while (!inf.finished()) {
                if (n == out.length) {
                    if (out.length >= MAX_ENTRY) throw new IOException("Page trop volumineuse.");
                    out = Arrays.copyOf(out, (int) Math.min(MAX_ENTRY, out.length * 2L));
                }
                int r = inf.inflate(out, n, out.length - n);
                n += r;
                if (r == 0) {
                    if (inf.needsInput()) {
                        if (dummy) break; // données tronquées : on rend ce qu'on a
                        inf.setInput(new byte[1]);
                        dummy = true;
                    } else if (inf.needsDictionary()) {
                        throw new IOException("Données compressées invalides.");
                    } else if (n < out.length) {
                        break;
                    }
                }
            }
            return n == out.length ? out : Arrays.copyOf(out, n);
        } catch (DataFormatException ex) {
            throw new IOException("Données compressées corrompues.", ex);
        } finally {
            inf.end();
        }
    }

    static String methodName(int m) {
        switch (m) {
            case 9: return "Deflate64";
            case 12: return "BZIP2";
            case 14: return "LZMA";
            case 93: return "Zstandard";
            case 95: return "XZ";
            case 98: return "PPMd";
            case 99: return "AES";
            default: return "n° " + m;
        }
    }

    // ------------------------------------------------------------------ répertoire central

    private List<Entry> readCentralDirectory() throws IOException {
        final long size = src.size();
        if (size < 22) throw notZip(size);
        int tailLen = (int) Math.min(size, 65557L);
        long tailPos = size - tailLen;
        byte[] tail = new byte[tailLen];
        src.readFully(tailPos, tail, 0, tailLen);

        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tailLen) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) { // tolérance : octets parasites après l'archive
            for (int i = tailLen - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L) {
                    eocd = i;
                    break;
                }
            }
        }
        if (eocd < 0) throw notZip(size);

        long eocdPos = tailPos + eocd;
        long count = u16(tail, eocd + 10);
        long cdSize = u32(tail, eocd + 12);
        long cdOffset = u32(tail, eocd + 16);
        boolean zip64 = false;

        if (eocdPos >= 20) {
            byte[] loc = new byte[20];
            src.readFully(eocdPos - 20, loc, 0, 20);
            if (u32(loc, 0) == 0x07064b50L) {
                long z64Pos = u64(loc, 8);
                if (z64Pos >= 0 && z64Pos + 56 <= size) {
                    byte[] z = new byte[56];
                    src.readFully(z64Pos, z, 0, 56);
                    if (u32(z, 0) == 0x06064b50L) {
                        count = u64(z, 32);
                        cdSize = u64(z, 40);
                        cdOffset = u64(z, 48);
                        zip64 = true;
                    }
                }
            }
        }

        if (cdSize < 0 || cdSize > size || cdSize > Integer.MAX_VALUE - 16) {
            throw new IOException("Répertoire central ZIP invalide.");
        }
        byte[] cd = new byte[(int) cdSize];
        long shift = 0;
        boolean ok = cdOffset >= 0 && cdOffset + cdSize <= size;
        if (ok) {
            src.readFully(cdOffset, cd, 0, cd.length);
            ok = cd.length < 4 || u32(cd, 0) == 0x02014b50L;
        }
        if (!ok && !zip64) {
            // Données ajoutées avant l'archive : le répertoire central précède directement l'EOCD.
            long alt = eocdPos - cdSize;
            if (alt >= 0 && cd.length >= 4) {
                src.readFully(alt, cd, 0, cd.length);
                if (u32(cd, 0) == 0x02014b50L) {
                    shift = alt - cdOffset;
                    ok = true;
                }
            }
        }
        if (!ok) throw new IOException("Répertoire central ZIP introuvable.");

        List<Entry> list = new ArrayList<>((int) Math.max(0, Math.min(count, 100000)));
        int p = 0;
        while (p + 46 <= cd.length && u32(cd, p) == 0x02014b50L) {
            int flags = u16(cd, p + 8);
            int method = u16(cd, p + 10);
            long comp = u32(cd, p + 20);
            long uncomp = u32(cd, p + 24);
            int nl = u16(cd, p + 28);
            int xl = u16(cd, p + 30);
            int cl = u16(cd, p + 32);
            long lho = u32(cd, p + 42);
            if (p + 46 + nl + xl + cl > cd.length) break;
            String name = decodeName(cd, p + 46, nl, (flags & 0x800) != 0);

            int x = p + 46 + nl;
            int xe = x + xl;
            while (x + 4 <= xe) {
                int id = u16(cd, x);
                int len = u16(cd, x + 2);
                int d = x + 4;
                int de = Math.min(d + len, xe);
                if (id == 0x0001) {
                    if (uncomp == 0xFFFFFFFFL && d + 8 <= de) { uncomp = u64(cd, d); d += 8; }
                    if (comp == 0xFFFFFFFFL && d + 8 <= de) { comp = u64(cd, d); d += 8; }
                    if (lho == 0xFFFFFFFFL && d + 8 <= de) { lho = u64(cd, d); }
                }
                x += 4 + len;
            }
            list.add(new Entry(name, flags, method, comp, uncomp, lho + shift));
            p += 46 + nl + xl + cl;
        }
        return list;
    }

    private IOException notZip(long size) throws IOException {
        int n = (int) Math.min(8, size);
        byte[] head = new byte[8];
        if (n > 0) src.readFully(0, head, 0, n);
        return new UnsupportedFormatException(describe(head, n));
    }

    static String describe(byte[] h, int n) {
        if (n >= 4 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!') {
            return "C'est une archive RAR (CBR), pas un CBZ.\nConvertissez-la en CBZ pour la lire ici.";
        }
        if (n >= 4 && (h[0] & 0xff) == 0x37 && (h[1] & 0xff) == 0x7A && (h[2] & 0xff) == 0xBC && (h[3] & 0xff) == 0xAF) {
            return "C'est une archive 7z (CB7), non prise en charge.";
        }
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') {
            return "C'est un PDF, pas un CBZ.";
        }
        return "Ce fichier n'est pas une archive ZIP/CBZ valide.";
    }

    // ------------------------------------------------------------------ utilitaires

    private static String decodeName(byte[] b, int off, int len, boolean utf8) {
        if (utf8) return new String(b, off, len, StandardCharsets.UTF_8);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, len))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, len, LEGACY);
        }
    }

    private static Charset pickLegacyCharset() {
        try {
            if (Charset.isSupported("IBM437")) return Charset.forName("IBM437");
        } catch (RuntimeException ignored) {
            // repli ci-dessous
        }
        return StandardCharsets.ISO_8859_1;
    }

    static int u16(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8;
    }

    static long u32(byte[] b, int i) {
        return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xffffffffL;
    }

    static long u64(byte[] b, int i) {
        return u32(b, i) | u32(b, i + 4) << 32;
    }
}
