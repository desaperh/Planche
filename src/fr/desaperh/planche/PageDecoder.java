package fr.desaperh.planche;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.util.Size;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Décodage d'une page, réduit à la taille de l'écran pour limiter la mémoire. */
final class PageDecoder {

    /** ~48 Mo en ARGB_8888 : au-delà, Android refuse de dessiner le bitmap. */
    private static final double MAX_PIXELS = 12_000_000d;

    private PageDecoder() {
    }

    static Bitmap decode(byte[] data, final int viewW, final int viewH, final boolean fitWidth) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
        return ImageDecoder.decodeBitmap(source, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                Size s = info.getSize();
                int w = Math.max(1, s.getWidth());
                int h = Math.max(1, s.getHeight());
                double scale = fitWidth
                        ? (double) viewW / w
                        : Math.min((double) viewW / w, (double) viewH / h);
                if (scale > 1) scale = 1; // jamais d'agrandissement au décodage
                if ((double) w * h * scale * scale > MAX_PIXELS) {
                    scale = Math.sqrt(MAX_PIXELS / ((double) w * h));
                }
                if (scale < 0.999) {
                    decoder.setTargetSize(
                            Math.max(1, (int) Math.round(w * scale)),
                            Math.max(1, (int) Math.round(h * scale)));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            }
        });
    }
}
