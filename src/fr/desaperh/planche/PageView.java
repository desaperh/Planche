package fr.desaperh.planche;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/** Affichage d'une page au Canvas, sans animation (adapté à l'e-ink). */
final class PageView extends View {

    interface Listener {
        /** zone : -1 gauche, 0 centre, 1 droite. */
        void onTap(int zone);

        /** dir : -1 doigt vers la gauche, 1 doigt vers la droite. */
        void onSwipe(int dir);

        /** dir : -1 doigt vers le haut, 1 doigt vers le bas. */
        void onVerticalSwipe(int dir);
    }

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final GestureDetector gestures;
    private final float density;

    private Listener listener;
    private Bitmap bitmap;
    private String message;
    private boolean fitWidth;
    private int scrollY;

    PageView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20,
                ctx.getResources().getDisplayMetrics()));
        textPaint.setTextAlign(Paint.Align.CENTER);
        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (listener == null) return false;
                float w = getWidth();
                float x = e.getX();
                listener.onTap(x < w * 0.3f ? -1 : (x > w * 0.7f ? 1 : 0));
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (listener == null || e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float min = 60 * density;
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > min) {
                    listener.onSwipe(dx < 0 ? -1 : 1);
                    return true;
                }
                if (Math.abs(dy) > min) {
                    listener.onVerticalSwipe(dy < 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setFitWidth(boolean fw) {
        fitWidth = fw;
        scrollY = 0;
        invalidate();
    }

    void setPage(Bitmap bm, boolean atBottom) {
        bitmap = bm;
        message = null;
        scrollY = atBottom ? maxScroll() : 0;
        invalidate();
    }

    void setMessage(String msg) {
        bitmap = null;
        message = msg;
        scrollY = 0;
        invalidate();
    }

    boolean canScroll(int dir) {
        int max = maxScroll();
        if (max <= 0) return false;
        return dir > 0 ? scrollY < max : scrollY > 0;
    }

    void scroll(int dir) {
        int step = Math.max(1, (int) (getHeight() * 0.85f));
        scrollY = Math.max(0, Math.min(maxScroll(), scrollY + dir * step));
        invalidate();
    }

    private float scale() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return 1f;
        float sw = getWidth() / (float) bitmap.getWidth();
        if (fitWidth) return sw;
        return Math.min(sw, getHeight() / (float) bitmap.getHeight());
    }

    private int maxScroll() {
        if (!fitWidth || bitmap == null) return 0;
        int content = Math.round(bitmap.getHeight() * scale());
        return Math.max(0, content - getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        scrollY = Math.min(scrollY, maxScroll());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        final int vw = getWidth(), vh = getHeight();
        if (bitmap != null && !bitmap.isRecycled()) {
            float s = scale();
            float dw = bitmap.getWidth() * s;
            float dh = bitmap.getHeight() * s;
            float left = (vw - dw) / 2f;
            float top = (fitWidth && dh > vh) ? -scrollY : (vh - dh) / 2f;
            dst.set(left, top, left + dw, top + dh);
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint);
        } else if (message != null && !message.isEmpty()) {
            String[] lines = message.split("\n");
            float lh = textPaint.getTextSize() * 1.4f;
            float y = vh / 2f - (lines.length - 1) * lh / 2f;
            for (String line : lines) {
                canvas.drawText(line, vw / 2f, y, textPaint);
                y += lh;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }
}
