package fr.desaperh.planche;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lecteur plein écran. Zones de tap : gauche / centre (menu) / droite. */
public final class ReaderActivity extends Activity implements PageView.Listener {

    static final String EXTRA_NAME = "fr.desaperh.planche.NAME";
    private static final int RADIUS = 1; // pages gardées en mémoire de part et d'autre

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HashMap<Integer, Bitmap> cache = new HashMap<>();
    private final HashSet<Integer> pending = new HashSet<>();

    private PageView pageView;
    private LinearLayout menu;
    private TextView titleView, pageLabel, dirButton, fitButton;
    private SeekBar seek;

    private Uri uri;
    private String key;
    private String name;
    private volatile ComicArchive archive;
    private int count;
    private int current;
    private int shown = -1;
    private int config;
    private volatile int ioCurrent;
    private volatile int ioConfig;
    private boolean atBottom, rtl, fitWidth, destroyed;
    private int viewW, viewH;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        key = uri.toString();
        name = getIntent().getStringExtra(EXTRA_NAME);
        rtl = Prefs.rtl(this);
        fitWidth = Prefs.fitWidth(this);
        keepPermissionIfOffered();
        buildUi();
        goFullscreen();
        pageView.setMessage("Ouverture…");

        final Context app = getApplicationContext();
        final Uri u = uri;
        final String knownName = name;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String n = knownName != null ? knownName : Ui.queryName(app, u);
                ComicArchive a = null;
                String err = null;
                try {
                    a = ComicArchive.open(app, u);
                } catch (SecurityException e) {
                    err = "Accès refusé.\nRouvrez le fichier depuis la bibliothèque.";
                } catch (Throwable t) {
                    err = Ui.message(t);
                }
                final ComicArchive fa = a;
                final String fe = err, fn = n;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onOpened(fa, fn, fe);
                    }
                });
            }
        });
    }

    private void keepPermissionIfOffered() {
        if ((getIntent().getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // permission temporaire seulement : la reprise ultérieure pourra échouer
        }
    }

    // ------------------------------------------------------------------ interface

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        pageView = new PageView(this);
        pageView.setListener(this);
        pageView.setFitWidth(fitWidth);
        root.addView(pageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int w = r - l, h = b - t;
                if (w > 0 && h > 0 && (w != viewW || h != viewH)) {
                    viewW = w;
                    viewH = h;
                    resetConfig();
                }
            }
        });

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        menu.setPadding(pad, pad, pad, pad + Ui.dp(this, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(Ui.dp(this, 2), Color.BLACK);
        menu.setBackground(bg);
        menu.setClickable(true); // les taps sur le menu ne tournent pas la page

        titleView = Ui.text(this, 18, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        menu.addView(titleView);

        pageLabel = Ui.text(this, 16, false);
        pageLabel.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        menu.addView(pageLabel);

        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(Color.BLACK));
        seek.setThumbTintList(ColorStateList.valueOf(Color.BLACK));
        seek.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) pageLabel.setText("Page " + (p + 1) + " / " + count);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                int p = s.getProgress();
                if (archive != null && p != current) loadPage(p, false);
            }
        });
        menu.addView(seek);

        LinearLayout row1 = row();
        dirButton = Ui.button(this, "");
        dirButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rtl = !rtl;
                Prefs.setRtl(ReaderActivity.this, rtl);
                updateLabels();
            }
        });
        fitButton = Ui.button(this, "");
        fitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fitWidth = !fitWidth;
                Prefs.setFitWidth(ReaderActivity.this, fitWidth);
                pageView.setFitWidth(fitWidth);
                atBottom = false;
                resetConfig();
                updateLabels();
            }
        });
        addWeighted(row1, dirButton);
        addWeighted(row1, fitButton);
        menu.addView(row1);

        LinearLayout row2 = row();
        TextView first = Ui.button(this, "⏮ Début");
        first.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (archive != null) loadPage(0, false);
            }
        });
        TextView close = Ui.button(this, "Bibliothèque");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        addWeighted(row2, first);
        addWeighted(row2, close);
        menu.addView(row2);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(menu, lp);
        menu.setVisibility(View.GONE);

        setContentView(root);
        updateLabels();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 8), 0, 0);
        return r;
    }

    private void addWeighted(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(this, 4);
        lp.setMargins(m, 0, m, 0);
        row.addView(v, lp);
    }

    private void updateLabels() {
        if (titleView == null) return;
        titleView.setText(Ui.stripExtension(name));
        pageLabel.setText(count > 0 ? "Page " + (current + 1) + " / " + count : "");
        seek.setMax(Math.max(0, count - 1));
        seek.setProgress(current);
        dirButton.setText(rtl ? "Sens : manga ←" : "Sens : BD →");
        fitButton.setText(fitWidth ? "Affichage : largeur" : "Affichage : page");
    }

    private boolean menuVisible() {
        return menu != null && menu.getVisibility() == View.VISIBLE;
    }

    private void showMenu() {
        updateLabels();
        menu.setVisibility(View.VISIBLE);
    }

    private void hideMenu() {
        menu.setVisibility(View.GONE);
    }

    @SuppressWarnings("deprecation")
    private void goFullscreen() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    // ------------------------------------------------------------------ chargement

    private void onOpened(ComicArchive a, String n, String err) {
        if (destroyed) {
            closeInBackground(a);
            return;
        }
        name = n != null ? n : "";
        if (a == null) {
            pageView.setMessage("Impossible d'ouvrir ce fichier.\n\n" + err);
            updateLabels();
            return;
        }
        if (a.pageCount() == 0) {
            pageView.setMessage("Aucune image dans cette archive.");
            closeInBackground(a);
            updateLabels();
            return;
        }
        archive = a;
        count = a.pageCount();
        current = Math.max(0, Math.min(count - 1, Prefs.page(this, key)));
        Prefs.saveProgress(this, key, name, current, count);
        History.touch(this, key, name);
        updateLabels();
        if (viewW > 0) loadPage(current, false);
        makeThumbnailIfMissing(); // passe après la page affichée dans la file d'attente
    }

    /** Miniature de la première page pour l'historique, faite une seule fois par album. */
    private void makeThumbnailIfMissing() {
        final File thumb = History.thumbFile(this, key);
        if (thumb.exists() || destroyed) return;
        final ComicArchive a = archive;
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (archive != a) return;
                File tmp = new File(thumb.getPath() + ".tmp");
                try {
                    Bitmap bm = PageDecoder.decode(a.read(0), History.THUMB_W, History.THUMB_H, false);
                    File dir = thumb.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
                    }
                    bm.recycle();
                    if (!tmp.renameTo(thumb)) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                } catch (Throwable t) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete(); // pas de miniature : la bibliothèque affichera le titre à la place
                }
            }
        });
    }

    private void resetConfig() {
        config++;
        ioConfig = config;
        cache.clear();
        pending.clear();
        shown = -1;
        if (archive != null && viewW > 0) loadPage(current, atBottom);
    }

    private void loadPage(int idx, boolean bottom) {
        if (archive == null || viewW <= 0 || destroyed) return;
        current = idx;
        ioCurrent = idx;
        atBottom = bottom;
        updateLabels();
        Bitmap bm = cache.get(idx);
        if (bm != null) {
            pageView.setPage(bm, bottom);
            shown = idx;
            afterShow();
        } else {
            request(idx);
        }
    }

    private void request(final int idx) {
        if (destroyed || idx < 0 || idx >= count || pending.contains(idx) || cache.containsKey(idx)) return;
        pending.add(idx);
        final int cfg = config, w = viewW, h = viewH;
        final boolean fw = fitWidth;
        final ComicArchive a = archive;
        io.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                String err = null;
                boolean skipped = false;
                if (cfg != ioConfig || Math.abs(idx - ioCurrent) > RADIUS || archive != a) {
                    skipped = true; // l'utilisateur est déjà ailleurs
                } else {
                    try {
                        bm = PageDecoder.decode(a.read(idx), w, h, fw);
                    } catch (OutOfMemoryError e) {
                        err = "Mémoire insuffisante pour cette page.";
                    } catch (Throwable t) {
                        err = Ui.message(t);
                    }
                }
                final Bitmap fb = bm;
                final String fe = err;
                final boolean fs = skipped;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onDecoded(idx, cfg, fb, fe, fs);
                    }
                });
            }
        });
    }

    private void onDecoded(int idx, int cfg, Bitmap bm, String err, boolean skipped) {
        if (destroyed || cfg != config) return;
        pending.remove(idx);
        if (skipped) {
            if (idx == current) request(idx);
            return;
        }
        if (bm != null) cache.put(idx, bm);
        if (idx == current) {
            if (bm != null) pageView.setPage(bm, atBottom);
            else pageView.setMessage("Page " + (idx + 1) + " illisible.\n\n" + err);
            shown = idx;
            afterShow();
        }
    }

    private void afterShow() {
        Iterator<Integer> it = cache.keySet().iterator();
        while (it.hasNext()) {
            if (Math.abs(it.next() - current) > RADIUS) it.remove();
        }
        request(current + 1);
        request(current - 1);
    }

    // ------------------------------------------------------------------ navigation

    private void next() {
        if (archive == null) return;
        if (shown == current && pageView.canScroll(1)) {
            pageView.scroll(1);
        } else if (current + 1 < count) {
            loadPage(current + 1, false);
        } else {
            Toast.makeText(this, "Dernière page", Toast.LENGTH_SHORT).show();
        }
    }

    private void prev() {
        if (archive == null) return;
        if (shown == current && pageView.canScroll(-1)) {
            pageView.scroll(-1);
        } else if (current > 0) {
            loadPage(current - 1, fitWidth);
        } else {
            Toast.makeText(this, "Première page", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTap(int zone) {
        if (menuVisible()) {
            hideMenu();
            return;
        }
        if (zone == 0) {
            showMenu();
        } else if (zone < 0) {
            if (rtl) next(); else prev();
        } else {
            if (rtl) prev(); else next();
        }
    }

    @Override
    public void onSwipe(int dir) {
        if (menuVisible()) hideMenu();
        if (dir < 0) {
            if (rtl) prev(); else next();
        } else {
            if (rtl) next(); else prev();
        }
    }

    @Override
    public void onVerticalSwipe(int dir) {
        if (!fitWidth) return;
        if (dir < 0) next(); else prev();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                next();
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
                prev();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (rtl) prev(); else next();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (rtl) next(); else prev();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (menuVisible()) hideMenu(); else showMenu();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (menuVisible()) {
            hideMenu();
            return;
        }
        super.onBackPressed();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onPause() {
        super.onPause();
        if (archive != null && count > 0) Prefs.saveProgress(this, key, name, current, count);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        final ComicArchive a = archive;
        archive = null;
        cache.clear();
        pending.clear();
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (a != null) a.close();
            }
        });
        io.shutdown();
        super.onDestroy();
    }

    private static void closeInBackground(final ComicArchive a) {
        if (a == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                a.close();
            }
        }, "planche-close").start();
    }
}
