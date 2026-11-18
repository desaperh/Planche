package fr.desaperh.planche;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bibliothèque : un dossier choisi via le sélecteur système, parcouru sans autorisation de stockage. */
public final class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_TREE = 2;

    private static final String HELP =
            "Choisissez le dossier qui contient vos CBZ.\n\n"
                    + "Android ne laisse pas choisir la racine du stockage ni « Download » "
                    + "directement : prenez un sous-dossier (ex. Books/BD).\n\n"
                    + "Vous pouvez aussi ouvrir un fichier seul, ou ouvrir un CBZ "
                    + "depuis le gestionnaire de fichiers avec « Ouvrir avec… ».";

    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
    };

    static final class Item {
        final String id;
        final String name;
        final boolean dir;
        final boolean parent;
        final Uri uri;

        Item(String id, String name, boolean dir, boolean parent, Uri uri) {
            this.id = id;
            this.name = name;
            this.dir = dir;
            this.parent = parent;
            this.uri = uri;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<String> stackIds = new ArrayList<>();
    private final ArrayList<String> stackNames = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();

    private Uri tree;
    private int generation;
    private TextView path, empty;
    private LinearLayout recentsBox, recentsRow;
    private int recentsGeneration;
    private final HashMap<String, Bitmap> thumbs = new HashMap<>();
    private ListView list;
    private ItemAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ComicArchive.cleanCache(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        final int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(pad + in.getSystemWindowInsetLeft(), pad + in.getSystemWindowInsetTop(),
                        pad + in.getSystemWindowInsetRight(), pad + in.getSystemWindowInsetBottom());
                return in;
            }
        });

        TextView title = Ui.text(this, 28, true);
        title.setText("Planche");
        root.addView(title);
        TextView sub = Ui.text(this, 15, false);
        sub.setText("Lecteur CBZ · aucune autorisation · hors ligne");
        sub.setTextColor(0xFF444444);
        sub.setPadding(0, 0, 0, Ui.dp(this, 12));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView open = Ui.button(this, "Ouvrir un fichier");
        TextView folder = Ui.button(this, "Choisir un dossier");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, 0, Ui.dp(this, 6), 0);
        row.addView(open, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMargins(Ui.dp(this, 6), 0, 0, 0);
        row.addView(folder, half2);
        root.addView(row);

        root.addView(buildRecents());

        path = Ui.text(this, 16, true);
        path.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.START);
        root.addView(path);

        FrameLayout listFrame = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.BLACK));
        list.setDividerHeight(Math.max(1, Ui.dp(this, 1)));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new ItemAdapter(this);
        list.setAdapter(adapter);
        empty = Ui.text(this, 16, false);
        empty.setText(HELP);
        listFrame.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.setEmptyView(empty);
        root.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        folder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree();
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < items.size()) onItem(items.get(pos));
            }
        });

        String saved = Prefs.tree(this);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            if (hasReadPermission(u)) {
                setTree(u);
            } else {
                Prefs.setTree(this, null);
            }
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecents();
        adapter.notifyDataSetChanged(); // progression mise à jour après lecture
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (stackIds.size() > 1) {
            goUp();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ sélecteurs système

    @SuppressWarnings("deprecation")
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Sélecteur de fichiers indisponible.", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (tree != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, tree);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Sélecteur de dossiers indisponible.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // permission temporaire seulement
        }
        if (req == REQ_FILE) {
            openReader(u, null);
        } else if (req == REQ_TREE) {
            if (tree != null && !tree.equals(u)) {
                try {
                    getContentResolver().releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // déjà libérée
                }
            }
            Prefs.setTree(this, u.toString());
            setTree(u);
            refresh();
        }
    }

    private boolean hasReadPermission(Uri u) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ navigation

    private void setTree(Uri u) {
        tree = u;
        stackIds.clear();
        stackNames.clear();
        stackIds.add(DocumentsContract.getTreeDocumentId(u));
        stackNames.add(null); // nom réel récupéré au premier listage
    }

    private void goUp() {
        stackIds.remove(stackIds.size() - 1);
        stackNames.remove(stackNames.size() - 1);
        refresh();
    }

    private void onItem(Item it) {
        if (it.parent) {
            goUp();
        } else if (it.dir) {
            stackIds.add(it.id);
            stackNames.add(it.name);
            refresh();
        } else {
            openReader(it.uri, it.name);
        }
    }

    @SuppressWarnings("deprecation")
    private void openReader(Uri u, String name) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.setData(u);
        if (name != null && !name.isEmpty()) i.putExtra(ReaderActivity.EXTRA_NAME, name);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ récents (historique + miniatures)

    private View buildRecents() {
        recentsBox = new LinearLayout(this);
        recentsBox.setOrientation(LinearLayout.VERTICAL);
        recentsBox.setPadding(0, Ui.dp(this, 16), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 18, true);
        title.setText("Récents");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = Ui.button(this, "Tout effacer");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clear.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        header.addView(clear);
        recentsBox.addView(header);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recentsRow = new LinearLayout(this);
        recentsRow.setOrientation(LinearLayout.HORIZONTAL);
        recentsRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        scroll.addView(recentsRow);
        recentsBox.addView(scroll);
        recentsBox.setVisibility(View.GONE);
        return recentsBox;
    }

    private void refreshRecents() {
        final int gen = ++recentsGeneration;
        recentsRow.removeAllViews();
        List<History.Book> books = History.load(this);
        recentsBox.setVisibility(books.isEmpty() ? View.GONE : View.VISIBLE);
        for (History.Book b : books) recentsRow.addView(makeCard(b, gen));
    }

    private View makeCard(final History.Book b, int gen) {
        final int w = Ui.dp(this, 120);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 14), 0);
        card.setLayoutParams(lp);

        FrameLayout cover = new FrameLayout(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.WHITE);
        border.setStroke(Math.max(1, Ui.dp(this, 1)), Color.BLACK);
        cover.setBackground(border);
        int bp = Math.max(1, Ui.dp(this, 1));
        cover.setPadding(bp, bp, bp, bp);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView placeholder = Ui.text(this, 13, false);
        placeholder.setText(Ui.stripExtension(b.name));
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        cover.addView(placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(cover, new LinearLayout.LayoutParams(w, Ui.dp(this, 170)));

        TextView name = Ui.text(this, 14, false);
        name.setText(Ui.stripExtension(b.name));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(name);
        String st = Prefs.status(this, b.uri);
        if (!st.isEmpty()) {
            TextView status = Ui.text(this, 12, false);
            status.setTextColor(0xFF444444);
            status.setText(st);
            card.addView(status);
        }

        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openReader(Uri.parse(b.uri), b.name);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmRemove(b);
                return true;
            }
        });
        bindThumbnail(b, img, placeholder, gen);
        return card;
    }

    private void bindThumbnail(History.Book b, final ImageView img, final TextView placeholder, final int gen) {
        final File f = History.thumbFile(this, b.uri);
        if (!f.isFile()) return; // pas encore de miniature : le titre reste affiché
        final String k = f.getName() + ":" + f.lastModified();
        Bitmap cached = thumbs.get(k);
        if (cached != null) {
            img.setImageBitmap(cached);
            placeholder.setVisibility(View.GONE);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565; // moitié moins de mémoire, invisible sur e-ink
                final Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
                if (bm == null) return;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        thumbs.put(k, bm);
                        if (gen != recentsGeneration) return;
                        img.setImageBitmap(bm);
                        placeholder.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private void confirmRemove(final History.Book b) {
        new AlertDialog.Builder(this)
                .setTitle(Ui.stripExtension(b.name))
                .setMessage("Retirer cet album des récents ?\nLe fichier n'est pas supprimé et la page en cours est conservée.")
                .setPositiveButton("Retirer", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        History.remove(MainActivity.this, b.uri);
                        refreshRecents();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Effacer les récents ?")
                .setMessage("La liste et les miniatures seront effacées. Les fichiers ne sont pas supprimés.")
                .setPositiveButton("Effacer", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        History.clear(MainActivity.this);
                        Prefs.forgetLast(MainActivity.this);
                        thumbs.clear();
                        refreshRecents();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showPath() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stackNames.size(); i++) {
            if (i > 0) sb.append(" / ");
            String n = stackNames.get(i);
            sb.append(n != null ? n : "…");
        }
        path.setText(sb.toString());
    }

    private void refresh() {
        if (tree == null) {
            items.clear();
            adapter.notifyDataSetChanged();
            path.setText("");
            empty.setText(HELP);
            return;
        }
        final Context app = getApplicationContext();
        final Uri t = tree;
        final String rootId = stackIds.get(0);
        final String id = stackIds.get(stackIds.size() - 1);
        final boolean needRootName = stackNames.get(0) == null;
        final boolean sub = stackIds.size() > 1;
        final int gen = ++generation;
        showPath();
        items.clear();
        adapter.notifyDataSetChanged();
        empty.setText("Chargement…");

        io.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Item> found = new ArrayList<>();
                String error = null;
                String rootName = null;
                try {
                    if (needRootName) {
                        rootName = Ui.queryName(app, DocumentsContract.buildDocumentUriUsingTree(t, rootId));
                    }
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(t, id);
                    try (Cursor c = app.getContentResolver().query(children, PROJECTION, null, null, null)) {
                        if (c != null) {
                            while (c.moveToNext()) {
                                String did = c.getString(0);
                                String n = c.getString(1);
                                String mime = c.getString(2);
                                if (did == null || n == null || n.startsWith(".")) continue;
                                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                                if (!dir && !isComic(n, mime)) continue;
                                found.add(new Item(did, n, dir, false,
                                        dir ? null : DocumentsContract.buildDocumentUriUsingTree(t, did)));
                            }
                        }
                    }
                    Collections.sort(found, new Comparator<Item>() {
                        @Override
                        public int compare(Item a, Item b) {
                            if (a.dir != b.dir) return a.dir ? -1 : 1;
                            return NaturalOrder.INSTANCE.compare(a.name, b.name);
                        }
                    });
                } catch (SecurityException e) {
                    error = "Accès au dossier perdu.\nChoisissez-le à nouveau.";
                } catch (RuntimeException e) {
                    error = "Lecture du dossier impossible :\n" + Ui.message(e);
                }
                final String fe = error, fr = rootName;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || isDestroyed()) return;
                        if (fr != null && !stackNames.isEmpty()) stackNames.set(0, fr);
                        showPath();
                        items.clear();
                        if (sub) items.add(new Item(null, "Dossier parent", true, true, null));
                        items.addAll(found);
                        adapter.notifyDataSetChanged();
                        empty.setText(fe != null ? fe : "Aucun CBZ dans ce dossier.");
                    }
                });
            }
        });
    }

    static boolean isComic(String name, String mime) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".cbz") || n.endsWith(".zip")
                || "application/vnd.comicbook+zip".equals(mime) || "application/x-cbz".equals(mime);
    }

    // ------------------------------------------------------------------ liste

    private final class ItemAdapter extends BaseAdapter {
        private final Context ctx;

        ItemAdapter(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView name, status;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                name = (TextView) row.getChildAt(0);
                status = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 14));
                name = Ui.text(ctx, 19, false);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                status = Ui.text(ctx, 14, false);
                status.setTextColor(0xFF444444);
                row.addView(name);
                row.addView(status);
            }
            Item it = items.get(pos);
            String st;
            if (it.parent) {
                name.setText("↩  Dossier parent");
                st = "";
            } else if (it.dir) {
                name.setText("▸  " + it.name);
                st = "dossier";
            } else {
                name.setText(Ui.stripExtension(it.name));
                st = Prefs.status(ctx, it.uri.toString());
            }
            status.setText(st);
            status.setVisibility(st.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
