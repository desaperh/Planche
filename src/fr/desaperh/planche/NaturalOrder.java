package fr.desaperh.planche;

import java.util.Comparator;

/** Tri « naturel » : page2 avant page10, insensible à la casse. */
final class NaturalOrder implements Comparator<String> {

    static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static char fold(char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }

    @Override
    public int compare(String a, String b) {
        int i = 0, j = 0;
        final int na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (digit(ca) && digit(cb)) {
                int si = i, sj = j;
                while (si < na && a.charAt(si) == '0') si++;
                while (sj < nb && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < na && digit(a.charAt(ei))) ei++;
                while (ej < nb && digit(b.charAt(ej))) ej++;
                int la = ei - si, lb = ej - sj;
                if (la != lb) return la - lb;
                for (int k = 0; k < la; k++) {
                    int d = a.charAt(si + k) - b.charAt(sj + k);
                    if (d != 0) return d;
                }
                int za = si - i, zb = sj - j;
                if (za != zb) return za - zb;
                i = ei;
                j = ej;
            } else {
                char fa = fold(ca), fb = fold(cb);
                if (fa != fb) return fa - fb;
                i++;
                j++;
            }
        }
        int rest = (na - i) - (nb - j);
        if (rest != 0) return rest;
        return a.compareTo(b);
    }
}
