package __PKG__;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * A minimal stand-in for AndroidX FileProvider, so the app can hand files to
 * other apps without pulling in a support library (this build has no Gradle and
 * therefore no dependency resolution — only android.jar).
 *
 * <p>Scope is deliberately tiny: read-only, and only files inside the app's own
 * cache directory. Everything else is a hard 404 rather than a policy decision.
 */
public class ShareProvider extends ContentProvider {

    private static final String ROOT = "cache";

    /**
     * The authority the manifest declares. Derived from the context rather than a
     * constant because the runtime classes live in their own package while the
     * app id — and therefore the authority — differs per project.
     */
    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".share";
    }

    /** content://<pkg>.share/cache/<relative path> for a file under getCacheDir(). */
    public static Uri uriFor(Context ctx, File file) throws IOException {
        String root = ctx.getCacheDir().getCanonicalPath();
        String target = file.getCanonicalPath();
        if (!target.startsWith(root + File.separator)) {
            throw new IOException("キャッシュ領域の外は共有できません: " + target);
        }
        String rel = target.substring(root.length() + 1).replace(File.separatorChar, '/');
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(ctx))
                .appendPath(ROOT)
                .appendEncodedPath(Uri.encode(rel, "/"))
                .build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        java.util.List<String> segs = uri.getPathSegments();
        if (segs.size() < 2 || !ROOT.equals(segs.get(0))) {
            throw new FileNotFoundException("不正な URI: " + uri);
        }
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < segs.size(); i++) {
            if (i > 1) {
                rel.append('/');
            }
            rel.append(segs.get(i));
        }
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("コンテキストがありません");
        }
        try {
            File root = ctx.getCacheDir().getCanonicalFile();
            File target = new File(root, rel.toString()).getCanonicalFile();
            if (!target.getPath().startsWith(root.getPath() + File.separator)) {
                throw new FileNotFoundException("キャッシュ領域の外です: " + uri);
            }
            if (!target.isFile()) {
                throw new FileNotFoundException("ファイルがありません: " + uri);
            }
            return target;
        } catch (IOException e) {
            throw new FileNotFoundException("解決できません: " + uri);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        try {
            return Mime.guess(fileFor(uri).getName());
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new FileNotFoundException("読み取り専用です: " + uri);
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("読み取り専用のプロバイダーです");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("読み取り専用のプロバイダーです");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("読み取り専用のプロバイダーです");
    }
}
