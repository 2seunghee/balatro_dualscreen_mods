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
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Looks for a Balatro copy already on the device: inside the Windows
 * containers and Steam clients people run on Android handhelds (Winlator and
 * its forks, GameNative, DroidDeck, ...), or simply wherever a file manager
 * put it.
 *
 * WHAT CAN BE REACHED, and why the search is shaped the way it is:
 *
 *   * Shared storage (/storage/emulated/0 and SD cards) is readable with
 *     "All files access" on Android 11+ (READ_EXTERNAL_STORAGE before that).
 *     That is where Winlator's D: drive points by default (Download), where
 *     custom drive mappings and SteamLibrary folders usually go, and where
 *     anyone who copied Balatro.exe from a PC dropped it. So the bulk of the
 *     search is a bounded walk of shared storage.
 *
 *   * A container app's PRIVATE storage -- Winlator's C: drive (its rootfs
 *     under /data/data), GameNative's default game library -- is not
 *     readable by any other app on Android 11 and later, All-files access
 *     or not. Android 8-10 still let READ_EXTERNAL_STORAGE see other apps'
 *     Android/data folders, so those known folders are probed too, and it
 *     simply finds nothing where the OS says no. The setup screen explains
 *     the private-C:-drive case rather than leaving the user guessing.
 *
 * The walk is bounded by depth, by file count and by a time budget, skips
 * other apps' Android/data and Android/obb (unreadable, and huge), and
 * ranks hits so that the Steam layout (steamapps/common/Balatro/Balatro.exe)
 * comes first. Nothing here reads file contents; the probe that validates a
 * hit is GameAssembler.probe, run by the caller on the few candidates.
 */
public final class BalatroFinder {
    private static final String TAG = "BalatroDS";

    private static final int MAX_DEPTH = 9;
    private static final int MAX_DIRS = 60000;
    private static final int MAX_HITS = 12;

    /**
     * App-specific folders worth a look. Readable only on Android 8-10; on
     * newer systems the directory listing comes back null and that is that.
     * Package names read from each project's build.gradle (October 2026):
     * Winlator com.winlator, GameNative app.gamenative (plus its ".gold"
     * suffix build), DroidDeck com.droiddeck.launcher. The rest are common
     * fork names; a wrong guess costs one failed listing.
     */
    private static final String[] CONTAINER_PACKAGES = {
        "com.winlator", "com.winlator.cmod", "com.winlator.bionic", "com.winlator.frost",
        "app.gamenative", "app.gamenative.gold", "com.pluvia",
        "com.droiddeck.launcher",
    };

    /** Top-level shared-storage folders to look in first, before the rest. */
    private static final String[] HINT_DIRS = {
        "Download", "Downloads", "Games", "Winlator", "GameNative", "DroidDeck", "Steam",
        "SteamLibrary", "Balatro", "Documents",
    };

    public static final class Candidate {
        public final File file;
        public final String kind;      // "Balatro.exe", "Balatro.love", "Balatro.app"
        public final int rank;         // lower is better

        Candidate(File file, String kind, int rank) {
            this.file = file;
            this.kind = kind;
            this.rank = rank;
        }

        public String describe() {
            return file.getAbsolutePath() + "  (" + (file.length() / (1024 * 1024)) + " MB)";
        }
    }

    public interface Progress {
        void update(String message);
    }

    private BalatroFinder() {
    }

    /** Can the walk see shared storage at all, on this Android version? */
    public static boolean hasStorageAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Storage volume roots: primary shared storage plus any SD card. */
    static List<File> volumeRoots(Context ctx) {
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) {
            roots.add(primary);
        }
        // getExternalFilesDirs returns .../Android/data/PKG/files on every
        // mounted volume; four levels up is the volume root.
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) {
            for (File d : dirs) {
                File v = d;
                for (int i = 0; i < 4 && v != null; i++) {
                    v = v.getParentFile();
                }
                if (v != null && v.isDirectory()) {
                    roots.add(v);
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /** Run the search. Blocking; call off the UI thread. */
    public static List<Candidate> scan(Context ctx, long budgetMs, Progress progress) {
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        List<Candidate> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] dirCount = { 0 };

        for (File root : volumeRoots(ctx)) {
            // 1. Known container app folders, best effort.
            for (String pkg : CONTAINER_PACKAGES) {
                File appFiles = new File(root, "Android/data/" + pkg + "/files");
                if (appFiles.isDirectory()) {
                    walk(appFiles, 0, hits, seen, dirCount, deadline, progress);
                }
            }
            // 2. The usual suspects first, so a hit there shows up quickly.
            for (String hint : HINT_DIRS) {
                File d = new File(root, hint);
                if (d.isDirectory()) {
                    walk(d, 1, hits, seen, dirCount, deadline, progress);
                }
            }
            // 3. Everything else on the volume.
            walk(root, 0, hits, seen, dirCount, deadline, progress);
            if (SystemClock.elapsedRealtime() > deadline) {
                break;
            }
        }

        java.util.Collections.sort(hits, new java.util.Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                return Integer.compare(a.rank, b.rank);
            }
        });
        Log.i(TAG, "finder: " + hits.size() + " candidate(s) after " + dirCount[0] + " dirs");
        return hits;
    }

    private static void walk(File dir, int depth, List<Candidate> hits, Set<String> seen,
                             int[] dirCount, long deadline, Progress progress) {
        if (depth > MAX_DEPTH || hits.size() >= MAX_HITS || dirCount[0] >= MAX_DIRS
                || SystemClock.elapsedRealtime() > deadline) {
            return;
        }
        String key;
        try {
            key = dir.getCanonicalPath();
        } catch (Exception e) {
            return;
        }
        if (!seen.add(key)) {
            return;
        }
        dirCount[0]++;
        if (progress != null && dirCount[0] % 200 == 0) {
            progress.update("Searching… " + dir.getAbsolutePath());
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;   // unreadable: another app's private folder, or no permission
        }
        for (File f : entries) {
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                if (lower.equals("balatro.app")) {
                    File inner = new File(f, "Contents/Resources/Balatro.love");
                    if (inner.isFile()) {
                        add(hits, inner, "Balatro.app", rankOf(inner));
                    }
                    continue;
                }
                // Other apps' private folders are unreadable past Android 10
                // and enormous before; the known ones were probed explicitly.
                if (depth == 0 && lower.equals("android")) {
                    continue;
                }
                if (lower.startsWith(".") || lower.equals("lost+found")) {
                    continue;
                }
                walk(f, depth + 1, hits, seen, dirCount, deadline, progress);
            } else if (lower.equals("balatro.exe")) {
                if (f.length() > 10L * 1024 * 1024) {    // the fused one, not a stub
                    add(hits, f, "Balatro.exe", rankOf(f));
                }
            } else if (lower.equals("balatro.love")) {
                add(hits, f, "Balatro.love", rankOf(f) + 1);
            } else if (lower.endsWith(".zip") && lower.contains("balatro")) {
                add(hits, f, "zip", rankOf(f) + 2);   // a zipped .app, probably
            }
        }
    }

    private static void add(List<Candidate> hits, File f, String kind, int rank) {
        for (Candidate c : hits) {
            if (c.file.equals(f)) {
                return;
            }
        }
        hits.add(new Candidate(f, kind, rank));
    }

    /** Steam's own layout first, then anything under a folder called Balatro. */
    private static int rankOf(File f) {
        String p = f.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.contains("/steamapps/common/balatro/")) {
            return 0;
        }
        if (p.contains("/balatro/")) {
            return 2;
        }
        return 4;
    }
}
