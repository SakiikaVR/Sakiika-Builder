package __PKG__;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Filesystem access, clamped to whatever level the APK was built with
 * ({@code Cfg.FILE_ACCESS}). The page sees the same API at every level; what
 * changes is which roots resolve and whether a picker is involved.
 *
 * <dl>
 *   <dt>APP_PRIVATE</dt><dd>{@code files/}, {@code cache/}, {@code external/} only.</dd>
 *   <dt>FOLDER_PICK</dt><dd>one persisted SAF tree; paths are relative to it.</dd>
 *   <dt>DOCUMENTS</dt><dd>as above plus per-call file/folder pickers.</dd>
 *   <dt>MEDIA_ONLY</dt><dd>MediaStore images/video/audio, read-only.</dd>
 *   <dt>FULL_MANAGER</dt><dd>raw java.io.File over all shared storage.</dd>
 * </dl>
 */
public class FsApi extends ApiModule {

    private static final String PREF_TREE = "fs.treeUri";
    private static final int MAX_INLINE_BYTES = 12 * 1024 * 1024;

    private final String level = Cfg.FILE_ACCESS;

    public FsApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "fs";
    }

    @Override
    public String[] methods() {
        return new String[]{"level", "roots", "root", "chooseRoot", "forgetRoot",
                "list", "stat", "exists", "read", "readBase64", "write", "append",
                "mkdir", "delete", "rename", "copy", "move", "du",
                "pickFile", "pickFiles", "pickFolder", "createFile",
                "readUri", "writeUri", "media", "shareFile", "tree", "search"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "level":
                return levelInfo();
            case "roots":
                return roots();
            case "root":
                return rootInfo();
            case "chooseRoot":
                return chooseRoot();
            case "forgetRoot":
                act.prefs().edit().remove(PREF_TREE).apply();
                return true;
            case "list":
                return list(a);
            case "stat":
                return describe(resolve(Jsonx.str(a, "path", "")), true);
            case "exists":
                return resolve(Jsonx.str(a, "path", "")).exists();
            case "read":
                return readText(a);
            case "readBase64":
                return Base64.encodeToString(readBytes(resolve(Jsonx.need(a, "path"))),
                        Base64.NO_WRAP);
            case "write":
                return write(a, false);
            case "append":
                return write(a, true);
            case "mkdir":
                return mkdir(a);
            case "delete":
                return delete(a);
            case "rename":
                return rename(a);
            case "copy":
                return transfer(a, false);
            case "move":
                return transfer(a, true);
            case "du":
                return du(a);
            case "pickFile":
                return pick(a, false, false);
            case "pickFiles":
                return pick(a, true, false);
            case "pickFolder":
                return pick(a, false, true);
            case "createFile":
                return createDocument(a);
            case "readUri":
                return readUri(a);
            case "writeUri":
                return writeUri(a);
            case "media":
                return media(a);
            case "shareFile":
                return shareFile(a);
            case "tree":
                return tree(a);
            case "search":
                return search(a);
            default:
                throw unknown(method);
        }
    }

    // -------------------------------------------------------- level & roots

    private JSONObject levelInfo() {
        boolean saf = "FOLDER_PICK".equals(level) || "DOCUMENTS".equals(level);
        return Jsonx.obj(
                "level", level,
                "canBrowse", !"MEDIA_ONLY".equals(level),
                "canWrite", !"MEDIA_ONLY".equals(level),
                "needsUserGrant", saf,
                "hasRoot", !saf || treeUri() != null,
                "canPick", "DOCUMENTS".equals(level) || saf,
                "allFilesGranted", hasAllFiles(),
                "description", describeLevel());
    }

    private String describeLevel() {
        switch (level) {
            case "APP_PRIVATE":
                return "アプリ専用領域のみ（files/ cache/ external/）";
            case "FOLDER_PICK":
                return "ユーザーが選んだ 1 つのフォルダー配下のみ";
            case "DOCUMENTS":
                return "選んだフォルダー配下＋その場で選んだファイル";
            case "MEDIA_ONLY":
                return "メディア（画像・動画・音声）の読み取りのみ";
            case "FULL_MANAGER":
                return "全ストレージ（ファイルマネージャー相当）";
            default:
                return "無効";
        }
    }

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return act.hasPermission("android.permission.READ_EXTERNAL_STORAGE");
    }

    private JSONArray roots() throws Exception {
        JSONArray out = new JSONArray();
        if ("APP_PRIVATE".equals(level) || "FULL_MANAGER".equals(level)) {
            out.put(Jsonx.obj("id", "files", "path", act.getFilesDir().getAbsolutePath(),
                    "label", "アプリ内部データ", "writable", true));
            out.put(Jsonx.obj("id", "cache", "path", act.getCacheDir().getAbsolutePath(),
                    "label", "キャッシュ", "writable", true));
            File ext = act.getExternalFilesDir(null);
            if (ext != null) {
                out.put(Jsonx.obj("id", "external", "path", ext.getAbsolutePath(),
                        "label", "アプリ外部データ", "writable", true));
            }
        }
        if ("FULL_MANAGER".equals(level)) {
            File shared = Environment.getExternalStorageDirectory();
            if (shared != null) {
                out.put(Jsonx.obj("id", "shared", "path", shared.getAbsolutePath(),
                        "label", "内部共有ストレージ", "writable", hasAllFiles()));
            }
            for (String dir : new String[]{Environment.DIRECTORY_DOWNLOADS,
                    Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_PICTURES,
                    Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES,
                    Environment.DIRECTORY_DCIM}) {
                File f = Environment.getExternalStoragePublicDirectory(dir);
                if (f != null && f.exists()) {
                    out.put(Jsonx.obj("id", dir.toLowerCase(java.util.Locale.ROOT),
                            "path", f.getAbsolutePath(), "label", dir, "writable", hasAllFiles()));
                }
            }
            // Removable volumes show up as siblings of the emulated one.
            File storage = new File("/storage");
            File[] vols = storage.listFiles();
            if (vols != null) {
                for (File v : vols) {
                    String n = v.getName();
                    if ("emulated".equals(n) || "self".equals(n) || !v.isDirectory()) {
                        continue;
                    }
                    out.put(Jsonx.obj("id", "volume:" + n, "path", v.getAbsolutePath(),
                            "label", "外部ボリューム " + n, "writable", false));
                }
            }
        }
        Uri tree = treeUri();
        if (tree != null) {
            out.put(Jsonx.obj("id", "tree", "uri", tree.toString(),
                    "label", "選択したフォルダー", "writable", true,
                    "name", treeDisplayName(tree)));
        }
        return out;
    }

    private JSONObject rootInfo() throws Exception {
        Uri tree = treeUri();
        if (tree != null) {
            return Jsonx.obj("kind", "tree", "uri", tree.toString(),
                    "name", treeDisplayName(tree));
        }
        if ("FULL_MANAGER".equals(level)) {
            File shared = Environment.getExternalStorageDirectory();
            return Jsonx.obj("kind", "file", "path",
                    shared == null ? "/" : shared.getAbsolutePath(),
                    "granted", hasAllFiles());
        }
        if ("APP_PRIVATE".equals(level)) {
            return Jsonx.obj("kind", "file", "path", act.getFilesDir().getAbsolutePath(),
                    "granted", true);
        }
        return Jsonx.obj("kind", "none", "granted", false,
                "hint", "fs.chooseRoot() でフォルダーを選んでください");
    }

    private Uri treeUri() {
        String s = act.prefs().getString(PREF_TREE, null);
        if (s == null) {
            return null;
        }
        Uri u = Uri.parse(s);
        // A grant survives reboots only if it was persisted *and* still held.
        for (android.content.UriPermission p : act.getContentResolver()
                .getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) {
                return u;
            }
        }
        return null;
    }

    private String treeDisplayName(Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = act.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getString(0);
                }
            }
        } catch (Throwable ignored) {
        }
        return tree.getLastPathSegment();
    }

    /** Opens the system folder picker and persists the grant across restarts. */
    private JSONObject chooseRoot() throws Exception {
        requireSaf("chooseRoot");
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        MainActivity.ActResult r = act.startForResultBlocking(i, 600000);
        if (!r.ok() || r.data == null || r.data.getData() == null) {
            return Jsonx.obj("ok", false, "reason", "cancelled");
        }
        Uri tree = r.data.getData();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            act.getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (Throwable t) {
            throw new BridgeError("grant_failed", "フォルダーの権限を保持できませんでした: " + t);
        }
        act.prefs().edit().putString(PREF_TREE, tree.toString()).apply();
        return Jsonx.obj("ok", true, "uri", tree.toString(), "name", treeDisplayName(tree));
    }

    private void requireSaf(String what) throws BridgeError {
        if (!("FOLDER_PICK".equals(level) || "DOCUMENTS".equals(level))) {
            throw new BridgeError("wrong_level", what
                    + " はファイルアクセスが『フォルダー選択』または『都度選択』のときだけ使えます（現在: "
                    + level + "）");
        }
    }

    // ---------------------------------------------------------------- nodes

    /** A file or a SAF document, behind one interface. */
    private interface Node {
        String id();

        String name();

        boolean exists() throws Exception;

        boolean isDir() throws Exception;

        long size() throws Exception;

        long modified() throws Exception;

        String mime() throws Exception;

        List<Node> children() throws Exception;

        Node child(String name) throws Exception;

        InputStream open() throws Exception;

        OutputStream openWrite(boolean append) throws Exception;

        Node createFile(String name, String mime) throws Exception;

        Node createDir(String name) throws Exception;

        boolean delete() throws Exception;

        Node rename(String newName) throws Exception;

        Uri uri();
    }

    private final class FileNode implements Node {
        final File f;

        FileNode(File f) {
            this.f = f;
        }

        @Override
        public String id() {
            return f.getAbsolutePath();
        }

        @Override
        public String name() {
            return f.getName();
        }

        @Override
        public boolean exists() {
            return f.exists();
        }

        @Override
        public boolean isDir() {
            return f.isDirectory();
        }

        @Override
        public long size() {
            return f.length();
        }

        @Override
        public long modified() {
            return f.lastModified();
        }

        @Override
        public String mime() {
            return guessMime(f.getName());
        }

        @Override
        public List<Node> children() {
            File[] kids = f.listFiles();
            if (kids == null) {
                return Collections.emptyList();
            }
            List<Node> out = new ArrayList<>(kids.length);
            for (File k : kids) {
                out.add(new FileNode(k));
            }
            return out;
        }

        @Override
        public Node child(String name) {
            return new FileNode(new File(f, name));
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(f);
        }

        @Override
        public OutputStream openWrite(boolean append) throws IOException {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("親フォルダーを作成できません: " + parent);
            }
            return new FileOutputStream(f, append);
        }

        @Override
        public Node createFile(String name, String mime) throws IOException {
            File target = new File(f, name);
            if (!target.exists() && !target.createNewFile()) {
                throw new IOException("ファイルを作成できません: " + target);
            }
            return new FileNode(target);
        }

        @Override
        public Node createDir(String name) throws IOException {
            File target = new File(f, name);
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("フォルダーを作成できません: " + target);
            }
            return new FileNode(target);
        }

        @Override
        public boolean delete() {
            return deleteRecursive(f);
        }

        @Override
        public Node rename(String newName) throws IOException {
            File target = new File(f.getParentFile(), newName);
            if (!f.renameTo(target)) {
                throw new IOException("名前を変更できません: " + f + " -> " + newName);
            }
            return new FileNode(target);
        }

        @Override
        public Uri uri() {
            return Uri.fromFile(f);
        }
    }

    private final class DocNode implements Node {
        final Uri tree;
        final String docId;
        private String cachedName;
        private String cachedMime;
        private long cachedSize = -1;
        private long cachedModified = -1;
        private boolean loaded;

        DocNode(Uri tree, String docId) {
            this.tree = tree;
            this.docId = docId;
        }

        private Uri self() {
            return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        }

        private void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            try (Cursor c = act.getContentResolver().query(self(), new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    cachedName = c.getString(0);
                    cachedMime = c.getString(1);
                    cachedSize = c.isNull(2) ? -1 : c.getLong(2);
                    cachedModified = c.isNull(3) ? -1 : c.getLong(3);
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public String id() {
            return docId;
        }

        @Override
        public String name() {
            load();
            return cachedName == null ? docId : cachedName;
        }

        @Override
        public boolean exists() {
            load();
            return cachedName != null;
        }

        @Override
        public boolean isDir() {
            load();
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(cachedMime);
        }

        @Override
        public long size() {
            load();
            return cachedSize;
        }

        @Override
        public long modified() {
            load();
            return cachedModified;
        }

        @Override
        public String mime() {
            load();
            return cachedMime == null ? "application/octet-stream" : cachedMime;
        }

        @Override
        public List<Node> children() {
            List<Node> out = new ArrayList<>();
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            try (Cursor c = act.getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    out.add(new DocNode(tree, c.getString(0)));
                }
            } catch (Throwable ignored) {
            }
            return out;
        }

        @Override
        public Node child(String name) {
            for (Node n : children()) {
                if (name.equals(n.name())) {
                    return n;
                }
            }
            // A non-existent child still needs an identity so create/write work.
            return new MissingDoc(this, name);
        }

        @Override
        public InputStream open() throws Exception {
            InputStream in = act.getContentResolver().openInputStream(self());
            if (in == null) {
                throw new IOException("開けません: " + name());
            }
            return in;
        }

        @Override
        public OutputStream openWrite(boolean append) throws Exception {
            OutputStream out = act.getContentResolver()
                    .openOutputStream(self(), append ? "wa" : "wt");
            if (out == null) {
                throw new IOException("書き込めません: " + name());
            }
            return out;
        }

        @Override
        public Node createFile(String name, String mime) throws Exception {
            Uri created = DocumentsContract.createDocument(act.getContentResolver(), self(),
                    mime == null ? guessMime(name) : mime, name);
            if (created == null) {
                throw new IOException("ファイルを作成できません: " + name);
            }
            return new DocNode(tree, DocumentsContract.getDocumentId(created));
        }

        @Override
        public Node createDir(String name) throws Exception {
            Node existing = child(name);
            if (existing.exists() && existing.isDir()) {
                return existing;
            }
            Uri created = DocumentsContract.createDocument(act.getContentResolver(), self(),
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (created == null) {
                throw new IOException("フォルダーを作成できません: " + name);
            }
            return new DocNode(tree, DocumentsContract.getDocumentId(created));
        }

        @Override
        public boolean delete() throws Exception {
            return DocumentsContract.deleteDocument(act.getContentResolver(), self());
        }

        @Override
        public Node rename(String newName) throws Exception {
            Uri renamed = DocumentsContract.renameDocument(act.getContentResolver(), self(),
                    newName);
            if (renamed == null) {
                throw new IOException("名前を変更できません: " + newName);
            }
            return new DocNode(tree, DocumentsContract.getDocumentId(renamed));
        }

        @Override
        public Uri uri() {
            return self();
        }
    }

    /** A path under a SAF tree that does not exist yet. */
    private final class MissingDoc implements Node {
        final DocNode parent;
        final String childName;

        MissingDoc(DocNode parent, String childName) {
            this.parent = parent;
            this.childName = childName;
        }

        @Override
        public String id() {
            return parent.docId + "/" + childName;
        }

        @Override
        public String name() {
            return childName;
        }

        @Override
        public boolean exists() {
            return false;
        }

        @Override
        public boolean isDir() {
            return false;
        }

        @Override
        public long size() {
            return -1;
        }

        @Override
        public long modified() {
            return -1;
        }

        @Override
        public String mime() {
            return guessMime(childName);
        }

        @Override
        public List<Node> children() {
            return Collections.emptyList();
        }

        @Override
        public Node child(String name) throws Exception {
            throw new BridgeError("not_found", "存在しないフォルダーの下は辿れません: " + id());
        }

        @Override
        public InputStream open() throws Exception {
            throw new BridgeError("not_found", "ファイルがありません: " + childName);
        }

        @Override
        public OutputStream openWrite(boolean append) throws Exception {
            return parent.createFile(childName, guessMime(childName)).openWrite(false);
        }

        @Override
        public Node createFile(String name, String mime) throws Exception {
            throw new BridgeError("not_found", "先に " + childName + " を作成してください");
        }

        @Override
        public Node createDir(String name) throws Exception {
            return parent.createDir(childName).createDir(name);
        }

        @Override
        public boolean delete() {
            return false;
        }

        @Override
        public Node rename(String newName) throws Exception {
            throw new BridgeError("not_found", "ファイルがありません: " + childName);
        }

        @Override
        public Uri uri() {
            return null;
        }
    }

    // ----------------------------------------------------------- resolution

    /**
     * Turns a page-supplied path into a Node, refusing anything the current
     * access level must not reach. Accepted forms:
     * {@code files/a.txt}, {@code /storage/emulated/0/x}, {@code content://…},
     * or a plain relative path under the granted tree.
     */
    private Node resolve(String path) throws Exception {
        if (path == null) {
            path = "";
        }
        path = path.replace('\\', '/').trim();

        if (path.startsWith("content://")) {
            // Direct URIs are only honoured if we hold a grant for them.
            Uri u = Uri.parse(path);
            Uri tree = treeUri();
            if (tree != null && DocumentsContract.isTreeUri(tree)) {
                try {
                    return new DocNode(tree, DocumentsContract.getDocumentId(u));
                } catch (Throwable ignored) {
                }
            }
            throw new BridgeError("forbidden",
                    "この content URI にはアクセス権がありません: " + path);
        }

        if ("OFF".equals(level)) {
            throw BridgeError.disabled("ファイルアクセス");
        }

        if ("MEDIA_ONLY".equals(level)) {
            throw new BridgeError("wrong_level",
                    "メディアのみのレベルではパス指定は使えません。fs.media() を使ってください");
        }

        if ("FOLDER_PICK".equals(level) || "DOCUMENTS".equals(level)) {
            Uri tree = treeUri();
            if (tree == null) {
                throw new BridgeError("no_root",
                        "フォルダーが選ばれていません。fs.chooseRoot() を先に呼んでください");
            }
            Node node = new DocNode(tree, DocumentsContract.getTreeDocumentId(tree));
            for (String seg : split(path)) {
                node = node.child(seg);
            }
            return node;
        }

        // File-backed levels.
        File base;
        String rest;
        List<String> segs = split(path);
        String first = segs.isEmpty() ? "" : segs.get(0);
        if (path.startsWith("/")) {
            base = new File("/");
            rest = path;
        } else if ("files".equals(first)) {
            base = act.getFilesDir();
            rest = joinFrom(segs, 1);
        } else if ("cache".equals(first)) {
            base = act.getCacheDir();
            rest = joinFrom(segs, 1);
        } else if ("external".equals(first)) {
            File ext = act.getExternalFilesDir(null);
            if (ext == null) {
                throw new BridgeError("unavailable", "アプリ外部データ領域が使えません");
            }
            base = ext;
            rest = joinFrom(segs, 1);
        } else if ("shared".equals(first)) {
            File shared = Environment.getExternalStorageDirectory();
            if (shared == null) {
                throw new BridgeError("unavailable", "共有ストレージが使えません");
            }
            base = shared;
            rest = joinFrom(segs, 1);
        } else {
            base = act.getFilesDir();
            rest = path;
        }

        File target = rest.isEmpty() ? base : new File(base, rest);
        String canonical;
        try {
            canonical = target.getCanonicalPath();
        } catch (IOException e) {
            canonical = target.getAbsolutePath();
        }
        if (!isAllowedFilePath(canonical)) {
            throw new BridgeError("forbidden",
                    "このアクセスレベルでは触れないパスです: " + canonical
                            + "（レベル: " + level + "）");
        }
        return new FileNode(new File(canonical));
    }

    /** The sandbox check. Symlink escapes are why we compare canonical paths. */
    private boolean isAllowedFilePath(String canonical) {
        if ("FULL_MANAGER".equals(level)) {
            return true;
        }
        if (!"APP_PRIVATE".equals(level)) {
            return false;
        }
        List<String> allowed = new ArrayList<>();
        try {
            allowed.add(act.getFilesDir().getCanonicalPath());
            allowed.add(act.getCacheDir().getCanonicalPath());
            File ext = act.getExternalFilesDir(null);
            if (ext != null) {
                allowed.add(ext.getCanonicalPath());
            }
            File extCache = act.getExternalCacheDir();
            if (extCache != null) {
                allowed.add(extCache.getCanonicalPath());
            }
        } catch (IOException e) {
            return false;
        }
        for (String root : allowed) {
            if (canonical.equals(root) || canonical.startsWith(root + File.separator)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String path) throws BridgeError {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (out.isEmpty()) {
                    throw new BridgeError("forbidden", "'..' でルートの外に出ることはできません");
                }
                out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        return out;
    }

    private static String joinFrom(List<String> segs, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < segs.size(); i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segs.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ operations

    private JSONObject describe(Node n, boolean detailed) throws Exception {
        JSONObject o = Jsonx.obj(
                "name", n.name(),
                "path", n.id(),
                "exists", n.exists(),
                "isDir", n.isDir(),
                "size", n.size(),
                "modified", n.modified());
        if (detailed) {
            o.put("mime", n.mime());
            Uri u = n.uri();
            o.put("uri", u == null ? JSONObject.NULL : u.toString());
        }
        return o;
    }

    private Object list(JSONObject a) throws Exception {
        Node dir = resolve(Jsonx.str(a, "path", ""));
        if (!dir.exists()) {
            throw new BridgeError("not_found", "見つかりません: " + dir.id());
        }
        if (!dir.isDir()) {
            throw new BridgeError("not_a_dir", "フォルダーではありません: " + dir.id());
        }
        boolean showHidden = Jsonx.b(a, "hidden", false);
        JSONArray out = new JSONArray();
        for (Node child : dir.children()) {
            if (!showHidden && child.name().startsWith(".")) {
                continue;
            }
            out.put(describe(child, false));
        }
        return Jsonx.obj("path", dir.id(), "entries", out, "count", out.length());
    }

    private Object tree(JSONObject a) throws Exception {
        int maxDepth = Math.max(1, Math.min(8, Jsonx.i(a, "depth", 2)));
        Node dir = resolve(Jsonx.str(a, "path", ""));
        return walk(dir, maxDepth, new int[]{0}, Jsonx.i(a, "limit", 2000));
    }

    private JSONObject walk(Node n, int depth, int[] counter, int limit) throws Exception {
        JSONObject o = describe(n, false);
        if (n.isDir() && depth > 0 && counter[0] < limit) {
            JSONArray kids = new JSONArray();
            for (Node c : n.children()) {
                if (++counter[0] > limit) {
                    break;
                }
                kids.put(walk(c, depth - 1, counter, limit));
            }
            o.put("children", kids);
        }
        return o;
    }

    private Object search(JSONObject a) throws Exception {
        final String needle = Jsonx.need(a, "name").toLowerCase(java.util.Locale.ROOT);
        int limit = Math.max(1, Math.min(500, Jsonx.i(a, "limit", 100)));
        Node dir = resolve(Jsonx.str(a, "path", ""));
        JSONArray hits = new JSONArray();
        searchInto(dir, needle, hits, limit, Math.max(1, Math.min(10, Jsonx.i(a, "depth", 5))));
        return Jsonx.obj("count", hits.length(), "entries", hits);
    }

    private void searchInto(Node dir, String needle, JSONArray hits, int limit, int depth)
            throws Exception {
        if (hits.length() >= limit || depth <= 0 || !dir.isDir()) {
            return;
        }
        for (Node c : dir.children()) {
            if (hits.length() >= limit) {
                return;
            }
            if (c.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                hits.put(describe(c, false));
            }
            if (c.isDir()) {
                searchInto(c, needle, hits, limit, depth - 1);
            }
        }
    }

    private Object readText(JSONObject a) throws Exception {
        byte[] bytes = readBytes(resolve(Jsonx.need(a, "path")));
        String encoding = Jsonx.str(a, "encoding", "utf8");
        if ("base64".equalsIgnoreCase(encoding)) {
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
        return new String(bytes, charset(encoding));
    }

    private static java.nio.charset.Charset charset(String name) throws BridgeError {
        try {
            if ("utf8".equalsIgnoreCase(name) || "utf-8".equalsIgnoreCase(name)) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
            return java.nio.charset.Charset.forName(name);
        } catch (Throwable t) {
            throw new BridgeError("未知の文字コード: " + name);
        }
    }

    private byte[] readBytes(Node n) throws Exception {
        if (!n.exists()) {
            throw new BridgeError("not_found", "ファイルがありません: " + n.id());
        }
        if (n.isDir()) {
            throw new BridgeError("is_a_dir", "フォルダーは読み込めません: " + n.id());
        }
        long size = n.size();
        if (size > MAX_INLINE_BYTES) {
            throw new BridgeError("too_large", "大きすぎます（" + size + " bytes、上限 "
                    + MAX_INLINE_BYTES + "）。分割して読んでください");
        }
        try (InputStream in = n.open()) {
            return drain(in);
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buf = new byte[16384];
        int read;
        int total = 0;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > MAX_INLINE_BYTES) {
                throw new IOException("読み込みサイズが上限を超えました");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private Object write(JSONObject a, boolean append) throws Exception {
        Node n = resolve(Jsonx.need(a, "path"));
        String encoding = Jsonx.str(a, "encoding", "utf8");
        byte[] payload;
        if ("base64".equalsIgnoreCase(encoding)) {
            payload = Base64.decode(Jsonx.str(a, "data", ""), Base64.DEFAULT);
        } else {
            payload = Jsonx.str(a, "data", "").getBytes(charset(encoding));
        }
        try (OutputStream out = n.openWrite(append)) {
            out.write(payload);
            out.flush();
        }
        return Jsonx.obj("path", n.id(), "bytes", payload.length, "appended", append);
    }

    private Object mkdir(JSONObject a) throws Exception {
        String path = Jsonx.need(a, "path");
        Node n = resolve(path);
        if (n instanceof FileNode) {
            File f = ((FileNode) n).f;
            if (f.isDirectory()) {
                return describe(n, true);
            }
            if (!f.mkdirs()) {
                throw new BridgeError("io", "フォルダーを作成できません: " + f);
            }
            return describe(new FileNode(f), true);
        }
        // SAF has no mkdirs, so walk down creating each missing level.
        Uri tree = treeUri();
        if (tree == null) {
            throw new BridgeError("no_root", "フォルダーが選ばれていません");
        }
        Node node = new DocNode(tree, DocumentsContract.getTreeDocumentId(tree));
        for (String seg : split(path)) {
            Node next = node.child(seg);
            node = (next.exists() && next.isDir()) ? next : node.createDir(seg);
        }
        return describe(node, true);
    }

    private Object delete(JSONObject a) throws Exception {
        Node n = resolve(Jsonx.need(a, "path"));
        if (!n.exists()) {
            return Jsonx.obj("deleted", false, "reason", "not_found");
        }
        if (n.isDir() && !Jsonx.b(a, "recursive", false)) {
            if (!n.children().isEmpty()) {
                throw new BridgeError("not_empty",
                        "空でないフォルダーです。recursive:true を指定してください");
            }
        }
        boolean ok = n.delete();
        return Jsonx.obj("deleted", ok, "path", n.id());
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        return f.delete();
    }

    private Object rename(JSONObject a) throws Exception {
        Node n = resolve(Jsonx.need(a, "path"));
        String newName = Jsonx.need(a, "name");
        if (newName.contains("/") || newName.contains("\\")) {
            throw new BridgeError("name にパス区切りは使えません（移動は fs.move）");
        }
        return describe(n.rename(newName), true);
    }

    private Object transfer(JSONObject a, boolean move) throws Exception {
        Node from = resolve(Jsonx.need(a, "from"));
        if (!from.exists() || from.isDir()) {
            throw new BridgeError("bad_source", "コピー元がファイルとして存在しません: " + from.id());
        }
        Node to = resolve(Jsonx.need(a, "to"));
        long copied = 0;
        try (InputStream in = from.open(); OutputStream out = to.openWrite(false)) {
            byte[] buf = new byte[32768];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                copied += read;
            }
            out.flush();
        }
        boolean removed = false;
        if (move) {
            removed = from.delete();
        }
        return Jsonx.obj("bytes", copied, "from", from.id(), "to", to.id(),
                "sourceRemoved", removed);
    }

    private Object du(JSONObject a) throws Exception {
        Node n = resolve(Jsonx.str(a, "path", ""));
        long[] acc = new long[3];
        sizeOf(n, acc, Math.max(1, Math.min(12, Jsonx.i(a, "depth", 8))));
        return Jsonx.obj("bytes", acc[0], "files", acc[1], "dirs", acc[2], "path", n.id());
    }

    private void sizeOf(Node n, long[] acc, int depth) throws Exception {
        if (n.isDir()) {
            acc[2]++;
            if (depth <= 0) {
                return;
            }
            for (Node c : n.children()) {
                sizeOf(c, acc, depth - 1);
            }
        } else if (n.exists()) {
            acc[1]++;
            long s = n.size();
            if (s > 0) {
                acc[0] += s;
            }
        }
    }

    // -------------------------------------------------------------- pickers

    private Object pick(JSONObject a, boolean multiple, boolean folder) throws Exception {
        if (!("DOCUMENTS".equals(level) || "FOLDER_PICK".equals(level)
                || "FULL_MANAGER".equals(level))) {
            throw new BridgeError("wrong_level",
                    "ピッカーはこのアクセスレベルでは使えません（現在: " + level + "）");
        }
        Intent i;
        if (folder) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mimes = Jsonx.strings(a, "mime");
            i.setType(mimes.length == 1 ? mimes[0] : "*/*");
            if (mimes.length > 1) {
                i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
            }
            if (multiple) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Jsonx.b(a, "writable", false)) {
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        if (Jsonx.b(a, "persist", folder)) {
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }

        MainActivity.ActResult r = act.startForResultBlocking(i, 600000);
        if (!r.ok() || r.data == null) {
            return Jsonx.obj("ok", false, "reason", "cancelled");
        }
        JSONArray picked = new JSONArray();
        if (r.data.getClipData() != null) {
            for (int k = 0; k < r.data.getClipData().getItemCount(); k++) {
                picked.put(uriInfo(r.data.getClipData().getItemAt(k).getUri()));
            }
        } else if (r.data.getData() != null) {
            picked.put(uriInfo(r.data.getData()));
        }
        if (folder && r.data.getData() != null && Jsonx.b(a, "setAsRoot", false)) {
            Uri tree = r.data.getData();
            try {
                act.getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                act.prefs().edit().putString(PREF_TREE, tree.toString()).apply();
            } catch (Throwable ignored) {
            }
        }
        return Jsonx.obj("ok", true, "items", picked,
                "count", picked.length());
    }

    private Object createDocument(JSONObject a) throws Exception {
        String name = Jsonx.need(a, "name");
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Jsonx.str(a, "mime", guessMime(name)));
        i.putExtra(Intent.EXTRA_TITLE, name);
        MainActivity.ActResult r = act.startForResultBlocking(i, 600000);
        if (!r.ok() || r.data == null || r.data.getData() == null) {
            return Jsonx.obj("ok", false, "reason", "cancelled");
        }
        Uri u = r.data.getData();
        String data = Jsonx.str(a, "data", null);
        if (data != null) {
            byte[] payload = "base64".equalsIgnoreCase(Jsonx.str(a, "encoding", "utf8"))
                    ? Base64.decode(data, Base64.DEFAULT)
                    : data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (OutputStream out = act.getContentResolver().openOutputStream(u, "wt")) {
                if (out == null) {
                    throw new BridgeError("io", "書き込めませんでした");
                }
                out.write(payload);
            }
        }
        return Jsonx.obj("ok", true, "item", uriInfo(u));
    }

    private JSONObject uriInfo(Uri u) {
        String name = u.getLastPathSegment();
        long size = -1;
        String mime = act.getContentResolver().getType(u);
        try (Cursor c = act.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) {
                    name = c.getString(ni);
                }
                if (si >= 0 && !c.isNull(si)) {
                    size = c.getLong(si);
                }
            }
        } catch (Throwable ignored) {
        }
        return Jsonx.obj("uri", u.toString(), "name", name, "size", size,
                "mime", mime == null ? "application/octet-stream" : mime);
    }

    /** Reads any URI the user just handed us through a picker. */
    private Object readUri(JSONObject a) throws Exception {
        Uri u = Uri.parse(Jsonx.need(a, "uri"));
        String encoding = Jsonx.str(a, "encoding", "utf8");
        try (InputStream in = act.getContentResolver().openInputStream(u)) {
            if (in == null) {
                throw new BridgeError("io", "開けません: " + u);
            }
            byte[] bytes = drain(in);
            if ("base64".equalsIgnoreCase(encoding)) {
                return Jsonx.obj("bytes", bytes.length,
                        "data", Base64.encodeToString(bytes, Base64.NO_WRAP), "encoding", "base64");
            }
            return Jsonx.obj("bytes", bytes.length,
                    "data", new String(bytes, charset(encoding)), "encoding", encoding);
        }
    }

    private Object writeUri(JSONObject a) throws Exception {
        Uri u = Uri.parse(Jsonx.need(a, "uri"));
        byte[] payload = "base64".equalsIgnoreCase(Jsonx.str(a, "encoding", "utf8"))
                ? Base64.decode(Jsonx.str(a, "data", ""), Base64.DEFAULT)
                : Jsonx.str(a, "data", "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (OutputStream out = act.getContentResolver()
                .openOutputStream(u, Jsonx.b(a, "append", false) ? "wa" : "wt")) {
            if (out == null) {
                throw new BridgeError("io", "書き込めません: " + u);
            }
            out.write(payload);
        }
        return Jsonx.obj("uri", u.toString(), "bytes", payload.length);
    }

    /** MediaStore listing — the only path available at the MEDIA_ONLY level. */
    private Object media(JSONObject a) throws Exception {
        String type = Jsonx.str(a, "type", "images");
        Uri collection;
        String[] cols;
        switch (type) {
            case "images":
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                cols = new String[]{MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE,
                        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT};
                break;
            case "video":
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                cols = new String[]{MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.MIME_TYPE,
                        MediaStore.Video.Media.DURATION};
                break;
            case "audio":
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                cols = new String[]{MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.MIME_TYPE,
                        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ARTIST};
                break;
            case "downloads":
                if (Build.VERSION.SDK_INT < 29) {
                    throw BridgeError.needsApi(29);
                }
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                cols = new String[]{MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.MIME_TYPE};
                break;
            default:
                throw new BridgeError("type は images/video/audio/downloads");
        }
        int limit = Math.max(1, Math.min(1000, Jsonx.i(a, "limit", 100)));
        JSONArray out = new JSONArray();
        ContentResolver cr = act.getContentResolver();
        try (Cursor c = cr.query(collection, cols, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && out.length() < limit) {
                JSONObject row = new JSONObject();
                for (int k = 0; k < c.getColumnCount(); k++) {
                    row.put(c.getColumnName(k), c.isNull(k) ? JSONObject.NULL : c.getString(k));
                }
                long id = c.getLong(0);
                row.put("uri", Uri.withAppendedPath(collection, String.valueOf(id)).toString());
                out.put(row);
            }
        } catch (SecurityException e) {
            throw new BridgeError("permission_denied",
                    "メディアの読み取り権限がありません（READ_MEDIA_* を許可してください）");
        }
        return Jsonx.obj("type", type, "count", out.length(), "items", out);
    }

    /**
     * Hands a file to another app. App-private files need a FileProvider, so we
     * copy into cache and expose that copy through the generated provider.
     */
    private Object shareFile(JSONObject a) throws Exception {
        Node n = resolve(Jsonx.need(a, "path"));
        if (!n.exists() || n.isDir()) {
            throw new BridgeError("not_found", "ファイルがありません: " + n.id());
        }
        Uri shareUri;
        if (n instanceof FileNode) {
            File cacheCopy = new File(act.getCacheDir(), "share");
            if (!cacheCopy.exists() && !cacheCopy.mkdirs()) {
                throw new BridgeError("io", "共有用フォルダーを作れません");
            }
            File dest = new File(cacheCopy, n.name());
            try (InputStream in = n.open(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
            shareUri = androidx_FileProvider_getUriForFile(dest);
        } else {
            shareUri = n.uri();
        }
        final Uri finalUri = shareUri;
        final String mime = n.mime();
        return bridge.onUi(() -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, finalUri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(i, "ファイルを共有"));
            return Jsonx.obj("uri", finalUri.toString(), "mime", mime);
        });
    }

    /** Uses the FileProvider the generator declared in the manifest. */
    private Uri androidx_FileProvider_getUriForFile(File f) throws BridgeError {
        try {
            return ShareProvider.uriFor(act, f);
        } catch (Throwable t) {
            throw new BridgeError("io", "共有 URI を作れませんでした: " + t);
        }
    }

    static String guessMime(String name) {
        return Mime.guess(name);
    }
}
