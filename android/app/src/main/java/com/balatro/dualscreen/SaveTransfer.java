/*
 * balatro-dualscreen-thor
 * Copyright (C) 2026  balatro-dualscreen-thor contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.balatro.dualscreen;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Moves Balatro saves between this app, Steam and other Android builds.
 *
 * Balatro's saves are portable: every file is a deflate-compressed Lua
 * table (save_manager.lua), identical on every platform. Only the LAYOUT
 * differs by install, and it only differs in where the root sits:
 *
 *   Steam (Windows)   %AppData%\Balatro\settings.jkr, 1\profile.jkr, ...
 *   Steam (macOS)     ~/Library/Application Support/Balatro/
 *   other Android     Android/data/PKG/files/save/Balatro/
 *   this app          Android/data/com.balatro.dualscreen/files/save/balatro-game/
 *
 * Under the root: settings.jkr, and profile folders 1, 2, 3 each holding
 * profile.jkr (the profile), meta.jkr (unlocks, discoveries, high scores),
 * save.jkr (the run in progress) and unlock_notify.jkr. So an import finds
 * that root wherever the user pointed -- the folder itself, a `save/x/`
 * inside it, or a zip of it -- and copies the profile folders across. An
 * export writes the root layout to a zip, which unzips straight into any of
 * the locations above.
 *
 * settings.jkr is OPT-IN on import. It carries display and audio settings,
 * the chosen profile, and this mod's own Dual Screen toggles; a Steam copy
 * would bring a desktop resolution and reset every toggle to its default.
 * Profiles are what people mean by "my save".
 *
 * Everything overwritten is first copied to .ds-backup/TIMESTAMP/ under the
 * save directory, so a wrong import is one folder copy from undone.
 */
public final class SaveTransfer {
    private static final String TAG = "BalatroDS";

    public static final String[] SLOTS = { "1", "2", "3" };
    public static final String SETTINGS = "settings.jkr";
    public static final String BACKUP_DIR = ".ds-backup";
    private static final String[] PROFILE_MARKERS = { "profile.jkr", "meta.jkr", "save.jkr" };

    private SaveTransfer() {
    }

    /** Outcome of an import, for the screen that asked. */
    public static final class Result {
        public final List<String> copied = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public String layout;
        public String backup;
    }

    /** A file or folder in whatever the user picked: a SAF tree or a zip. */
    public abstract static class Node {
        public abstract String name();
        public abstract boolean isDir();
        public abstract List<Node> children() throws IOException;
        public abstract InputStream open() throws IOException;

        public Node child(String name) throws IOException {
            for (Node c : children()) {
                if (c.name().equals(name)) {
                    return c;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    /** A folder picked with ACTION_OPEN_DOCUMENT_TREE. */
    public static Node fromTree(final ContentResolver resolver, final Uri tree) {
        final String rootId = DocumentsContract.getTreeDocumentId(tree);
        return new SafNode(resolver, tree, rootId, "(picked folder)", true);
    }

    private static final class SafNode extends Node {
        private final ContentResolver resolver;
        private final Uri tree;
        private final String docId;
        private final String name;
        private final boolean dir;
        private List<Node> cached;

        SafNode(ContentResolver resolver, Uri tree, String docId, String name, boolean dir) {
            this.resolver = resolver;
            this.tree = tree;
            this.docId = docId;
            this.name = name;
            this.dir = dir;
        }

        @Override public String name() {
            return name;
        }

        @Override public boolean isDir() {
            return dir;
        }

        @Override public List<Node> children() throws IOException {
            if (!dir) {
                return new ArrayList<>();
            }
            if (cached != null) {
                return cached;
            }
            List<Node> out = new ArrayList<>();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            try (Cursor c = resolver.query(childrenUri, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
                if (c == null) {
                    throw new IOException("could not list the picked folder");
                }
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String n = c.getString(1);
                    String mime = c.getString(2);
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    out.add(new SafNode(resolver, tree, id, n, isDir));
                }
            }
            cached = out;
            return out;
        }

        @Override public InputStream open() throws IOException {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            InputStream in = resolver.openInputStream(uri);
            if (in == null) {
                throw new IOException("could not open " + name);
            }
            return in;
        }
    }

    /** A zip on disk (a picked document copied to cache first). */
    public static Node fromZip(File zip) throws IOException {
        final ZipFile zf = new ZipFile(zip);
        ZipNode root = new ZipNode(zf, "", "(zip)", true);
        Map<String, ZipNode> dirs = new HashMap<>();
        dirs.put("", root);
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            String name = e.getName().replace("\\", "/");
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                continue;
            }
            boolean isDir = e.isDirectory();
            String path = isDir ? name.substring(0, name.length() - 1) : name;
            // Ensure every ancestor exists.
            String parentPath = "";
            ZipNode parent = root;
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                parentPath = parentPath.isEmpty() ? parts[i] : parentPath + "/" + parts[i];
                ZipNode d = dirs.get(parentPath);
                if (d == null) {
                    d = new ZipNode(zf, parentPath, parts[i], true);
                    dirs.put(parentPath, d);
                    parent.kids.add(d);
                }
                parent = d;
            }
            String leaf = parts[parts.length - 1];
            if (isDir) {
                if (!dirs.containsKey(path)) {
                    ZipNode d = new ZipNode(zf, path, leaf, true);
                    dirs.put(path, d);
                    parent.kids.add(d);
                }
            } else {
                parent.kids.add(new ZipNode(zf, name, leaf, false));
            }
        }
        return root;
    }

    private static final class ZipNode extends Node {
        private final ZipFile zf;
        private final String entry;
        private final String name;
        private final boolean dir;
        final List<Node> kids = new ArrayList<>();

        ZipNode(ZipFile zf, String entry, String name, boolean dir) {
            this.zf = zf;
            this.entry = entry;
            this.name = name;
            this.dir = dir;
        }

        @Override public String name() {
            return name;
        }

        @Override public boolean isDir() {
            return dir;
        }

        @Override public List<Node> children() {
            return kids;
        }

        @Override public InputStream open() throws IOException {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) {
                throw new IOException("zip entry vanished: " + entry);
            }
            return zf.getInputStream(e);
        }
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /** Does this folder hold one profile (profile.jkr and friends)? */
    public static boolean looksLikeProfile(Node n) throws IOException {
        if (!n.isDir()) {
            return false;
        }
        for (String m : PROFILE_MARKERS) {
            Node c = n.child(m);
            if (c != null && !c.isDir()) {
                return true;
            }
        }
        return false;
    }

    /** Does this folder hold a save root (settings.jkr or a profile slot)? */
    public static boolean looksLikeSaveRoot(Node n) throws IOException {
        if (!n.isDir()) {
            return false;
        }
        Node s = n.child(SETTINGS);
        if (s != null && !s.isDir()) {
            return true;
        }
        for (String slot : SLOTS) {
            Node d = n.child(slot);
            if (d != null && looksLikeProfile(d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the save root under what was picked: the folder itself, or a
     * descendant up to three levels down (covers a zipped files/ directory,
     * Android/data/.../files/save/Balatro, and an "export" folder wrapping
     * the real one). Returns null if nothing looks like Balatro saves.
     */
    public static Node findSaveRoot(Node picked) throws IOException {
        return findSaveRoot(picked, 0);
    }

    private static Node findSaveRoot(Node n, int depth) throws IOException {
        if (looksLikeSaveRoot(n)) {
            return n;
        }
        if (depth >= 3) {
            return null;
        }
        for (Node c : n.children()) {
            if (c.isDir() && !c.name().equals("Mods") && !c.name().equals(BACKUP_DIR)) {
                Node found = findSaveRoot(c, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Import every profile slot (and optionally settings.jkr) from a save
     * root into this app's save directory.
     */
    public static Result importRoot(Context ctx, Node root, boolean includeSettings)
            throws IOException {
        Result r = new Result();
        r.layout = "save root";
        File dest = GameDirs.saveDir(ctx);
        File backup = newBackupDir(ctx);
        for (String slot : SLOTS) {
            Node d = root.child(slot);
            if (d == null || !d.isDir()) {
                r.skipped.add("slot " + slot + " (not present)");
                continue;
            }
            importProfileInto(d, new File(dest, slot), backup, slot, r);
        }
        Node settings = root.child(SETTINGS);
        if (settings != null && !settings.isDir()) {
            if (includeSettings) {
                copyWithBackup(settings, new File(dest, SETTINGS), backup, SETTINGS, r);
            } else {
                r.skipped.add(SETTINGS + " (not selected)");
            }
        }
        r.backup = backup.isDirectory() ? backup.getAbsolutePath() : null;
        return r;
    }

    /** Import one profile folder into the given slot (1-3). */
    public static Result importProfile(Context ctx, Node profile, String slot)
            throws IOException {
        Result r = new Result();
        r.layout = "single profile -> slot " + slot;
        File backup = newBackupDir(ctx);
        importProfileInto(profile, new File(GameDirs.saveDir(ctx), slot), backup, slot, r);
        r.backup = backup.isDirectory() ? backup.getAbsolutePath() : null;
        return r;
    }

    private static void importProfileInto(Node src, File destDir, File backup,
                                          String slot, Result r) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        destDir.mkdirs();
        int n = 0;
        for (Node f : src.children()) {
            if (f.isDir() || !f.name().toLowerCase(Locale.ROOT).endsWith(".jkr")) {
                continue;
            }
            copyWithBackup(f, new File(destDir, f.name()), backup, slot + "/" + f.name(), r);
            n++;
        }
        if (n == 0) {
            r.skipped.add("slot " + slot + " (no .jkr files)");
        }
    }

    private static void copyWithBackup(Node src, File dest, File backup, String rel,
                                       Result r) throws IOException {
        if (dest.isFile()) {
            File b = new File(backup, rel);
            //noinspection ResultOfMethodCallIgnored
            b.getParentFile().mkdirs();
            copyFile(dest, b);
        }
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (InputStream in = src.open(); OutputStream out = new FileOutputStream(tmp)) {
            pipe(in, out);
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not write " + dest);
        }
        r.copied.add(rel);
    }

    private static File newBackupDir(Context ctx) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        return new File(new File(GameDirs.saveDir(ctx), BACKUP_DIR), stamp);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Zip the save directory in the root layout: settings.jkr and the slot
     * folders at the top level, so the archive unzips straight into
     * %AppData%\Balatro or any other install's save folder. Mods and
     * backups are left out; they are not saves.
     */
    public static int exportZip(Context ctx, OutputStream out) throws IOException {
        File root = GameDirs.saveDir(ctx);
        int n = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            File settings = new File(root, SETTINGS);
            if (settings.isFile()) {
                addFile(zip, settings, SETTINGS);
                n++;
            }
            for (String slot : SLOTS) {
                File d = new File(root, slot);
                File[] files = d.isDirectory() ? d.listFiles() : null;
                if (files == null) {
                    continue;
                }
                for (File f : files) {
                    if (f.isFile()) {
                        addFile(zip, f, slot + "/" + f.getName());
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static void addFile(ZipOutputStream zip, File f, String name) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        zip.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            pipe(in, zip);
        }
        zip.closeEntry();
    }

    // ------------------------------------------------------------------

    /** Human-readable summary of what is on the device now. */
    public static String describeLocal(Context ctx) {
        File root = GameDirs.saveDir(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(root.getAbsolutePath()).append('\n');
        if (!root.isDirectory()) {
            sb.append("  (no saves yet -- start the game once)\n");
            return sb.toString();
        }
        sb.append("  ").append(SETTINGS).append(": ")
          .append(new File(root, SETTINGS).isFile() ? "present" : "absent").append('\n');
        for (String slot : SLOTS) {
            File d = new File(root, slot);
            if (!d.isDirectory()) {
                sb.append("  profile ").append(slot).append(": empty\n");
                continue;
            }
            StringBuilder parts = new StringBuilder();
            for (String m : new String[] { "profile.jkr", "meta.jkr", "save.jkr" }) {
                if (new File(d, m).isFile()) {
                    parts.append(parts.length() == 0 ? "" : ", ").append(m);
                }
            }
            sb.append("  profile ").append(slot).append(": ")
              .append(parts.length() == 0 ? "empty" : parts.toString()).append('\n');
        }
        return sb.toString();
    }

    static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            pipe(in, out);
        }
    }

    static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
    }

    /** Copy a picked document to a cache file, for zip inspection. */
    public static File copyToCache(Context ctx, Uri uri, String name) throws IOException {
        File f = new File(ctx.getCacheDir(), name);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(f)) {
            if (in == null) {
                throw new IOException("could not open the picked file");
            }
            pipe(in, out);
        }
        Log.i(TAG, "copied " + uri + " -> " + f + " (" + f.length() + " bytes)");
        return f;
    }
}
