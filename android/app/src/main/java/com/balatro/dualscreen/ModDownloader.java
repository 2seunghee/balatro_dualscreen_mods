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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a mod zip from the web, for the Manager's "Download mod" button.
 *
 * THIS IS THE ONLY NETWORK CODE IN THE APP, and the reason the manifest
 * declares INTERNET at all. Everything it does is started by the user
 * typing or choosing a URL; nothing here runs on its own, and nothing is
 * ever sent beyond the request for the file. HTTPS only: the target SDK
 * blocks cleartext, and this class refuses http:// before the system does.
 *
 * Balatro has no Steam Workshop. Mods are handed out as zips from GitHub
 * releases, Nexus and the like, so what this understands is:
 *
 *   a direct link to a .zip               downloaded as is
 *   github.com/OWNER/REPO                 latest release's first .zip asset,
 *                                         or the release's source zipball,
 *                                         or the default branch's archive
 *   github.com/OWNER/REPO/releases/tag/T  that release, same preference
 *
 * GitHub archive zips wrap everything in "REPO-REF/", which is the wrong
 * folder name for a mod that hard-codes its own path (ProperKorean), so the
 * resolver also suggests a folder name and the Manager lets the user edit it
 * before installing.
 *
 * Kept free of Android classes so it can be exercised on a desktop JVM.
 */
public final class ModDownloader {

    /** Refuse anything bigger; the largest mods are tens of MB. */
    public static final long MAX_BYTES = 300L * 1024 * 1024;

    private static final String USER_AGENT = "balatro-dualscreen-manager";

    public interface Progress {
        void update(String message, int percent);
    }

    /** What a URL resolved to. */
    public static final class Target {
        public String downloadUrl;
        public String suggestedFolder;   // null when the zip's own layout decides
        public String description;       // one line for the user

        @Override public String toString() {
            return description + " -> " + downloadUrl;
        }
    }

    private ModDownloader() {
    }

    // ------------------------------------------------------------------
    // Resolving
    // ------------------------------------------------------------------

    private static final Pattern GITHUB = Pattern.compile(
            "^https://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?"
            + "(?:/releases(?:/tag/([^/?#]+)|/latest)?)?/?(?:[?#].*)?$");

    /** Work out what to download for a user-supplied URL. Network for GitHub. */
    public static Target resolve(String input) throws IOException {
        String url = input == null ? "" : input.trim();
        if (url.isEmpty()) {
            throw new IOException("no URL given");
        }
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        if (url.toLowerCase(Locale.ROOT).startsWith("http://")) {
            throw new IOException("only https:// links are accepted");
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("not a web link: " + url);
        }

        Matcher m = GITHUB.matcher(url);
        if (m.matches()) {
            return resolveGitHub(m.group(1), m.group(2), m.group(3));
        }

        Target t = new Target();
        t.downloadUrl = url;
        t.description = "direct download";
        return t;
    }

    private static final Pattern ASSET_URL =
            Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ZIPBALL_URL =
            Pattern.compile("\"zipball_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TAG_NAME =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEFAULT_BRANCH =
            Pattern.compile("\"default_branch\"\\s*:\\s*\"([^\"]+)\"");

    private static Target resolveGitHub(String owner, String repo, String tag)
            throws IOException {
        String api = "https://api.github.com/repos/" + owner + "/" + repo;
        String release = fetchText(api + (tag != null ? "/releases/tags/" + tag
                                                      : "/releases/latest"), true);
        Target t = new Target();
        if (release != null) {
            // First .zip asset wins: that is the file the author packaged.
            Matcher am = ASSET_URL.matcher(release);
            while (am.find()) {
                String u = am.group(1);
                if (u.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    t.downloadUrl = u;
                    t.description = owner + "/" + repo + " release asset";
                    return t;
                }
            }
            Matcher zm = ZIPBALL_URL.matcher(release);
            Matcher tm = TAG_NAME.matcher(release);
            if (zm.find()) {
                t.downloadUrl = zm.group(1);
                t.suggestedFolder = repo;
                t.description = owner + "/" + repo + " release source"
                        + (tm.find() ? " (" + tm.group(1) + ")" : "");
                return t;
            }
        }
        // No release at all: the default branch, as GitHub's "Download ZIP".
        String info = fetchText(api, true);
        if (info == null) {
            throw new IOException("GitHub repository not found: " + owner + "/" + repo);
        }
        Matcher bm = DEFAULT_BRANCH.matcher(info);
        String branch = bm.find() ? bm.group(1) : "main";
        t.downloadUrl = "https://github.com/" + owner + "/" + repo
                + "/archive/refs/heads/" + branch + ".zip";
        t.suggestedFolder = repo;
        t.description = owner + "/" + repo + " branch " + branch + " (no releases)";
        return t;
    }

    /** GET a small text resource; null on 404, exception on other failures. */
    static String fetchText(String url, boolean json) throws IOException {
        HttpURLConnection c = open(url);
        if (json) {
            c.setRequestProperty("Accept", "application/vnd.github+json");
        }
        int code = c.getResponseCode();
        if (code == 404) {
            c.disconnect();
            return null;
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code + " from " + hostOf(url));
        }
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[16 * 1024];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > 4L * 1024 * 1024) {
                    throw new IOException("response too large from " + hostOf(url));
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Downloading
    // ------------------------------------------------------------------

    /** Download to `dest`, following redirects (GitHub assets redirect). */
    public static void download(Target target, File dest, Progress progress)
            throws IOException {
        String url = target.downloadUrl;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 8; hop++) {
            c = open(url);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) {
                    throw new IOException("redirect without a destination");
                }
                if (next.startsWith("/")) {
                    URL base = new URL(url);
                    next = base.getProtocol() + "://" + base.getHost() + next;
                }
                if (!next.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    throw new IOException("refusing a redirect off https: " + next);
                }
                url = next;
                c = null;
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code + " from " + hostOf(url));
            }
            break;
        }
        if (c == null) {
            throw new IOException("too many redirects");
        }

        long length = c.getContentLengthLong();
        if (length > MAX_BYTES) {
            c.disconnect();
            throw new IOException("file is " + (length / (1024 * 1024))
                    + " MB; refusing anything over " + (MAX_BYTES / (1024 * 1024)) + " MB");
        }
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try (InputStream in = c.getInputStream();
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int lastPct = -1;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("download exceeded "
                            + (MAX_BYTES / (1024 * 1024)) + " MB");
                }
                if (progress != null) {
                    int pct = length > 0 ? (int) (total * 100 / length) : -1;
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress.update("Downloading from " + hostOf(url) + " ("
                                + (total / 1024) + " KB)", pct < 0 ? 0 : pct);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!tmp.renameTo(dest)) {
            throw new IOException("could not finish writing " + dest);
        }
        // A zip starts with "PK"; anything else is an HTML error page or the
        // wrong link, and the installer would only fail more confusingly.
        byte[] head = new byte[2];
        try (InputStream in = new java.io.FileInputStream(dest)) {
            if (in.read(head) < 2 || head[0] != 'P' || head[1] != 'K') {
                throw new IOException("that link did not return a zip file");
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        return c;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** The file name a URL implies, for naming the folder when nothing else does. */
    public static String fileNameOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? "mod.zip" : name;
    }

    /** Known mods offered as one-tap choices in the Manager. */
    public static List<String[]> knownMods() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] { "ProperKorean (Korean translation, by dvrp0)",
                "https://github.com/dvrp0/ProperKorean-Balatro" });
        return out;
    }
}
