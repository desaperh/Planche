package fr.desaperh.planche;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Petits utilitaires d'interface : contrastes francs, pas d'effets (e-ink). */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String label) {
        TextView b = text(c, 17, false);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(c, 2), Color.BLACK);
        bg.setCornerRadius(dp(c, 6));
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static String stripExtension(String name) {
        if (name == null) return "";
        return name.replaceFirst("(?i)\\.(cbz|zip)$", "");
    }

    static String queryName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String s = cur.getString(0);
                if (s != null) return s;
            }
        } catch (RuntimeException ignored) {
            // repli ci-dessous
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) return "Livre";
        int slash = seg.lastIndexOf('/');
        return slash >= 0 ? seg.substring(slash + 1) : seg;
    }

    static String message(Throwable t) {
        String m = t.getMessage();
        return m != null && !m.isEmpty() ? m : t.getClass().getSimpleName();
    }
}
