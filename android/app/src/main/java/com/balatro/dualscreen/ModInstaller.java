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

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks a mod zip into the Mods folder, as its own folder.
 *
 * Mod zips come in two shapes: a folder at the top ("ProperKorean/lovely.toml")
 * or the files loose at the root ("lovely.toml"). Lovely on desktop can read
 * patches from either, but LOVE cannot read a mod's OWN files (fonts,
 * localisations) out of a zip at all, so here every mod becomes a folder.
 * The folder is named from the zip's top-level folder when it has one, or
 * from the zip's file name otherwise -- which matters for mods that hard-code
 * their own path, as ProperKorean does with "Mods/ProperKorean/".
 *
 * A nested same-named folder ("ProperKorean/ProperKorean/lovely.toml", the
 * mistake ProperKorean's own instructions warn about) is unwrapped: the
 * shallowest folder that holds lovely.toml, a lovely/ folder or any .lua
 * file is taken as the mod root.
 */
public final class ModInstaller {
    private static final String TAG = "BalatroDS";

    private ModInstaller() {
    }

    public static final class Result {
        public String folder;
        public int files;
        public boolean replaced;
        public boolean hasPatches;
    }

    public static Result installZip(Context ctx, File zip, String zipDisplayName)
            throws IOException {
        return installZip(ctx, zip, zipDisplayName, null);
    }

    /** Which folder a zip would be installed as, without installing it. */
    public static String folderNameFor(File zip, String zipDisplayName) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) {
                    names.add(e.getName().replace("\\", "/"));
                }
            }
        }
        if (names.isEmpty()) {
            throw new IOException("the zip is empty");
        }
        String prefix = modRoot(names);
        if (prefix.isEmpty()) {
            return stem(zipDisplayName);
        }
        String p = prefix.substring(0, prefix.length() - 1);
        return p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
    }

    /**
     * Install, optionally under a folder name of the caller's choosing --
     * the downloader offers the user a chance to correct the name, because
     * a GitHub archive's "Repo-main/" is wrong for a mod that hard-codes
     * its own folder.
     */
    public static Result installZip(Context ctx, File zip, String zipDisplayName,
                                    String folderOverride) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) {
                    names.add(e.getName().replace("\\", "/"));
                }
            }
            if (names.isEmpty()) {
                throw new IOException("the zip is empty");
            }

            String prefix = modRoot(names);
            String folder;
            if (folderOverride != null && !folderOverride.trim().isEmpty()) {
                folder = folderOverride.trim();
            } else if (prefix.isEmpty()) {
                folder = stem(zipDisplayName);
            } else {
                String p = prefix.substring(0, prefix.length() - 1);
                folder = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
            }
            if (folder.isEmpty() || folder.equals(".") || folder.equals("..")
                    || folder.contains("/") || folder.contains("\\")) {
                throw new IOException("cannot name a folder for this zip");
            }

            File modsDir = GameDirs.modsDir(ctx);
            //noinspection ResultOfMethodCallIgnored
            modsDir.mkdirs();
            File dest = new File(modsDir, folder);
            Result r = new Result();
            r.folder = folder;
            if (dest.exists()) {
                deleteTree(dest);
                r.replaced = true;
            }
            //noinspection ResultOfMethodCallIgnored
            dest.mkdirs();
            String destCanon = dest.getCanonicalPath();

            for (String name : names) {
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) {
                    continue;
                }
                File out = new File(dest, rel);
                // Zip-slip: a crafted archive must not write outside dest.
                if (!out.getCanonicalPath().startsWith(destCanon + File.separator)) {
                    throw new IOException("zip entry escapes the mod folder: " + name);
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                ZipEntry e = zf.getEntry(name);
                try (InputStream in = zf.getInputStream(e);
                     OutputStream os = new FileOutputStream(out)) {
                    SaveTransfer.pipe(in, os);
                }
                r.files++;
                String lower = rel.toLowerCase(Locale.ROOT);
                if (lower.equals("lovely.toml") || lower.startsWith("lovely/")) {
                    r.hasPatches = true;
                }
            }
            Log.i(TAG, "installed mod " + folder + " (" + r.files + " files, patches="
                    + r.hasPatches + ")");
            return r;
        }
    }

    /**
     * The directory prefix (ending in "/", or "") that is the mod's root:
     * the shallowest one directly holding lovely.toml, a lovely/ folder or
     * a .lua file. Falls back to the single common top-level folder, if any.
     */
    static String modRoot(List<String> names) {
        String best = null;
        for (String n : names) {
            String lower = n.toLowerCase(Locale.ROOT);
            int slash = n.lastIndexOf('/');
            String dir = slash < 0 ? "" : n.substring(0, slash + 1);
            String leaf = slash < 0 ? lower : lower.substring(slash + 1);
            boolean marker = leaf.equals("lovely.toml") || leaf.endsWith(".lua");
            if (!marker && lower.contains("/lovely/")) {
                // a file under some/mod/lovely/x.toml marks some/mod/
                int i = lower.indexOf("/lovely/");
                dir = n.substring(0, i + 1);
                marker = true;
            } else if (!marker && lower.startsWith("lovely/")) {
                dir = "";
                marker = true;
            }
            if (marker && (best == null || depth(dir) < depth(best))) {
                best = dir;
            }
        }
        if (best != null) {
            return best;
        }
        // No markers: a single top-level folder shared by every entry.
        String top = null;
        for (String n : names) {
            int slash = n.indexOf('/');
            String t = slash < 0 ? "" : n.substring(0, slash + 1);
            if (top == null) {
                top = t;
            } else if (!top.equals(t)) {
                return "";
            }
        }
        return top == null ? "" : top;
    }

    private static int depth(String dir) {
        int d = 0;
        for (int i = 0; i < dir.length(); i++) {
            if (dir.charAt(i) == '/') {
                d++;
            }
        }
        return d;
    }

    static String stem(String name) {
        if (name == null) {
            return "mod";
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static void deleteTree(File f) throws IOException {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        if (!f.delete() && f.exists()) {
            throw new IOException("could not delete " + f);
        }
    }

    /** Installed mod folders, with whether each carries Lovely patch files. */
    public static List<String> describeInstalled(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] entries = GameDirs.modsDir(ctx).listFiles();
        if (entries == null) {
            return out;
        }
        java.util.Arrays.sort(entries);
        for (File e : entries) {
            if (e.isDirectory()) {
                boolean patches = new File(e, "lovely.toml").isFile()
                        || new File(e, "lovely").isDirectory();
                out.add(e.getName() + (patches ? "  (Lovely patches)" : "  (no patch files)"));
            } else if (e.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                out.add(e.getName() + "  (zip -- use Install mod to unpack it)");
            }
        }
        return out;
    }
}
