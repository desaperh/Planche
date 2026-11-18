import fr.desaperh.planche.ZipReader;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.List;

/** Banc de test JVM : liste les pages sélectionnées (ordre de lecture) avec leur SHA-256. */
public final class ZipReaderCli {
    public static void main(String[] args) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(args[0], "r");
        ZipReader.Source src = new ZipReader.ChannelSource(raf.getChannel(), raf);
        ZipReader z;
        try {
            z = new ZipReader(src);
        } catch (ZipReader.UnsupportedFormatException e) {
            System.out.println("UNSUPPORTED\t" + e.getMessage().replace('\n', ' '));
            src.close();
            return;
        } catch (Exception e) {
            System.out.println("ERROR\t" + e.getMessage());
            src.close();
            return;
        }
        Class<?> pages = Class.forName("fr.desaperh.planche.Pages");
        Method select = pages.getDeclaredMethod("select", List.class);
        select.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ZipReader.Entry> sel = (List<ZipReader.Entry>) select.invoke(null, z.entries());
        System.out.println("ENTRIES\t" + z.entries().size() + "\tPAGES\t" + sel.size());
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        for (int i = 0; i < sel.size() && i < limit; i++) {
            ZipReader.Entry e = sel.get(i);
            try {
                byte[] data = z.read(e);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
                System.out.println("PAGE\t" + e.name + "\t" + sb);
            } catch (Exception ex) {
                System.out.println("PAGEERR\t" + e.name + "\t" + ex.getMessage());
            }
        }
        z.close();
    }
}
