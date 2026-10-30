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

package com.balatro.dualscreen.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies Lovely-style mod patches to game sources at assembly time.
 *
 * This is the Java twin of tools/lovely.py, which is the reference and
 * carries the full explanation. The two are kept in step by
 * tools/check_lovely_parity.py, which runs both engines over the same
 * fixtures and diffs the output byte for byte. Change one, change both.
 *
 * Semantics follow lovely-core (crates/lovely-core/src/patch/*.rs):
 * pattern, regex, copy and module patches, [vars] interpolation, manifest
 * priority ordering. Zipped mods are skipped with a warning -- LOVE cannot
 * read a mod's own assets out of a zip, so every mod is a folder here.
 */
public final class LovelyPatcher {

    public static final String SHIM_VERSION = "0.10.0-dualscreen";
    public static final String LOVELY_REPO =
            "https://github.com/ethangreen-dev/lovely-injector";
    public static final String MODULES_DIR = "dualscreen-mods/modules";
    public static final String MANIFEST_PATH = "dualscreen-mods/manifest.lua";
    public static final String SHIM_REQUIRE = "require \"dualscreen.lovely\"";

    /** A patch file is malformed or refers to something that is not there. */
    public static final class LovelyException extends Exception {
        public LovelyException(String msg) {
            super(msg);
        }
    }

    /** Where warnings go; the assembler shows them to the user. */
    public interface Log {
        void warn(String msg);
    }

    private static final Log STDERR = new Log() {
        @Override public void warn(String msg) {
            System.err.println("lovely: WARNING: " + msg);
        }
    };

    private LovelyPatcher() {
    }

    // ------------------------------------------------------------------
    // Text helpers shared by the patch kinds
    // ------------------------------------------------------------------

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c'
                || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** Lines WITH their terminators, ropey-style. "" -> []. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLineBreak(c)) {
                int end = i + 1;
                if (c == '\r' && end < text.length() && text.charAt(end) == '\n') {
                    end++;
                }
                out.add(text.substring(start, end));
                start = end;
                i = end;
            } else {
                i++;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** Rust's str::lines(). */
    static List<String> rustLines(String text) {
        List<String> parts = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) {
            parts.remove(parts.size() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            out.add(p.endsWith("\r") ? p.substring(0, p.length() - 1) : p);
        }
        return out;
    }

    /** Rust's str::split_inclusive('\n'). */
    static List<String> splitInclusive(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** Unicode-aware trim, like Rust's str::trim and Python's str.strip. */
    static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && Character.isWhitespace(s.charAt(a))) {
            a++;
        }
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    static String leadingIndent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static Pattern wildToRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^(?s:");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append(")$");
        return Pattern.compile(sb.toString());
    }

    static String luaString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // ------------------------------------------------------------------
    // Patches
    // ------------------------------------------------------------------

    abstract static class Patch {
        final int priority;
        final String origin;
        final List<String> targets = new ArrayList<>();

        Patch(Map<String, Object> table, int priority, String origin) {
            this.priority = priority;
            this.origin = origin;
            Object t = table.get("target");
            if (t instanceof String) {
                targets.add((String) t);
            } else if (t instanceof List) {
                for (Object o : (List<?>) t) {
                    targets.add(String.valueOf(o));
                }
            }
        }

        abstract String kind();

        boolean appliesTo(String name) {
            return targets.contains(name);
        }

        Object require(Map<String, Object> table, String key) throws LovelyException {
            if (!table.containsKey(key)) {
                throw new LovelyException(origin + ": [patches." + kind()
                        + "] is missing `" + key + "`");
            }
            return table.get(key);
        }

        abstract String apply(String name, String text, Log log) throws LovelyException;
    }

    static final class PatternPatch extends Patch {
        final String pattern;
        final String position;
        final String payload;
        final boolean matchIndent;
        final Integer times;
        final List<Pattern> lines = new ArrayList<>();

        PatternPatch(Map<String, Object> t, int priority, String origin)
                throws LovelyException {
            super(t, priority, origin);
            require(t, "target");
            pattern = String.valueOf(require(t, "pattern"));
            position = String.valueOf(require(t, "position"));
            if (!position.equals("before") && !position.equals("after")
                    && !position.equals("at")) {
                throw new LovelyException(origin
                        + ": pattern position must be before/after/at, got '"
                        + position + "'");
            }
            payload = String.valueOf(require(t, "payload"));
            Object mi = require(t, "match_indent");
            matchIndent = Boolean.TRUE.equals(mi);
            times = intOrNull(t.get("times"));
            for (String l : rustLines(pattern)) {
                lines.add(wildToRegex(trim(l)));
            }
        }

        @Override String kind() {
            return "pattern";
        }

        @Override String apply(String name, String text, Log log) {
            if (lines.isEmpty()) {
                log.warn(origin + ": empty pattern for " + name + ", skipped");
                return text;
            }
            List<String> src = splitLines(text);
            int n = lines.size();
            List<Integer> matches = new ArrayList<>();
            int i = 0;
            while (i + n <= src.size()) {
                boolean all = true;
                for (int j = 0; j < n; j++) {
                    if (!lines.get(j).matcher(trim(src.get(i + j))).matches()) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    matches.add(i);
                    i += n;
                } else {
                    i++;
                }
            }
            if (matches.isEmpty()) {
                log.warn(origin + ": pattern matched nothing in " + name + ":\n    "
                        + firstLine(pattern));
                return text;
            }
            if (times != null) {
                if (matches.size() < times) {
                    log.warn(origin + ": " + name + ": pattern matched "
                            + matches.size() + " times, times = " + times);
                } else if (matches.size() > times) {
                    log.warn(origin + ": " + name + ": pattern matched "
                            + matches.size() + " times, keeping the first " + times);
                    matches = matches.subList(0, times);
                }
            }

            StringBuilder out = new StringBuilder(text.length() + 256);
            int cursor = 0;
            for (int first : matches) {
                int last = first + n - 1;
                String indent = matchIndent ? leadingIndent(src.get(first)) : "";
                StringBuilder p = new StringBuilder();
                for (String piece : splitInclusive(payload)) {
                    p.append(indent).append(piece);
                }
                if (!payload.endsWith("\n")) {
                    p.append('\n');
                }
                if (position.equals("before")) {
                    append(out, src, cursor, first);
                    out.append(p);
                    append(out, src, first, last + 1);
                } else if (position.equals("after")) {
                    append(out, src, cursor, last + 1);
                    if (!src.get(last).endsWith("\n")) {
                        out.append('\n');
                    }
                    out.append(p);
                } else {
                    append(out, src, cursor, first);
                    out.append(p);
                }
                cursor = last + 1;
            }
            append(out, src, cursor, src.size());
            return out.toString();
        }
    }

    private static void append(StringBuilder out, List<String> lines, int from, int to) {
        for (int i = from; i < to; i++) {
            out.append(lines.get(i));
        }
    }

    private static String firstLine(String s) {
        List<String> l = rustLines(trim(s));
        return l.isEmpty() ? "" : l.get(0);
    }

    private static Integer intOrNull(Object o) throws LovelyException {
        if (o == null) {
            return null;
        }
        if (o instanceof Long) {
            return ((Long) o).intValue();
        }
        throw new LovelyException("`times` must be an integer");
    }

    // --- regex ---------------------------------------------------------------

    private static final Pattern INTERP =
            Pattern.compile("\\$(?:(\\$)|\\{([^}]*)\\}|([0-9A-Za-z_]+))");
    private static final Pattern GROUP_NAME =
            Pattern.compile("\\(\\?P?<([A-Za-z_][A-Za-z0-9_]*)>");

    static final class RegexPatch extends Patch {
        final String pattern;
        final String position;
        final String payload;
        final String rootCapture;
        final String linePrepend;
        final Integer times;
        final boolean verbose;
        final Pattern re;
        /** Rust group name -> Java group name (Java forbids '_' in names). */
        final Map<String, String> names = new HashMap<>();

        RegexPatch(Map<String, Object> t, int priority, String origin)
                throws LovelyException {
            super(t, priority, origin);
            require(t, "target");
            pattern = String.valueOf(require(t, "pattern"));
            position = String.valueOf(require(t, "position"));
            if (!position.equals("before") && !position.equals("after")
                    && !position.equals("at")) {
                throw new LovelyException(origin
                        + ": regex position must be before/after/at, got '"
                        + position + "'");
            }
            payload = String.valueOf(require(t, "payload"));
            rootCapture = t.get("root_capture") == null ? null
                    : String.valueOf(t.get("root_capture"));
            linePrepend = t.get("line_prepend") == null ? ""
                    : String.valueOf(t.get("line_prepend"));
            times = intOrNull(t.get("times"));
            verbose = Boolean.TRUE.equals(t.get("verbose"));

            // Rust accepts (?P<name>) and (?<name>) with underscores; Java
            // accepts only (?<name>) with letters and digits. Rename on the way
            // in and translate references on the way out.
            Matcher m = GROUP_NAME.matcher(pattern);
            StringBuffer sb = new StringBuffer();
            int k = 0;
            while (m.find()) {
                String rust = m.group(1);
                String java = "g" + (k++) + "x";
                names.put(rust, java);
                m.appendReplacement(sb, Matcher.quoteReplacement("(?<" + java + ">"));
            }
            m.appendTail(sb);
            int flags = Pattern.MULTILINE;
            if (verbose) {
                flags |= Pattern.COMMENTS;
            }
            try {
                re = Pattern.compile(sb.toString(), flags);
            } catch (RuntimeException e) {
                throw new LovelyException(origin + ": bad regex '" + pattern + "': "
                        + e.getMessage());
            }
        }

        @Override String kind() {
            return "regex";
        }

        private String groupText(Matcher m, String ref) {
            if (isDigits(ref)) {
                int idx = Integer.parseInt(ref);
                if (idx > m.groupCount()) {
                    return null;
                }
                return m.group(idx);
            }
            String java = names.get(ref);
            if (java == null) {
                return null;
            }
            return m.group(java);
        }

        private String interpolate(String template, Matcher m) {
            Matcher im = INTERP.matcher(template);
            StringBuffer sb = new StringBuffer();
            while (im.find()) {
                String rep;
                if (im.group(1) != null) {
                    rep = "$";
                } else {
                    String ref = im.group(2) != null ? im.group(2) : im.group(3);
                    String g = groupText(m, ref);
                    rep = g == null ? "" : g;
                }
                im.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            im.appendTail(sb);
            return sb.toString();
        }

        private int[] rootSpan(Matcher m) throws LovelyException {
            String root = (rootCapture == null ? "0" : rootCapture).replace("$", "");
            int start;
            int end;
            if (isDigits(root)) {
                int idx = Integer.parseInt(root);
                if (idx > m.groupCount()) {
                    throw new LovelyException(origin + ": root_capture '" + root
                            + "' is not a group");
                }
                start = m.start(idx);
                end = m.end(idx);
            } else {
                String java = names.get(root);
                if (java == null) {
                    throw new LovelyException(origin + ": root_capture '" + root
                            + "' is not a group");
                }
                start = m.start(java);
                end = m.end(java);
            }
            if (start < 0) {
                throw new LovelyException(origin + ": root_capture '" + root
                        + "' did not participate in a match");
            }
            return new int[] { start, end };
        }

        @Override String apply(String name, String text, Log log) throws LovelyException {
            List<int[]> spans = new ArrayList<>();
            List<String> payloads = new ArrayList<>();
            Matcher m = re.matcher(text);
            int count = 0;
            while (m.find()) {
                count++;
                if (times != null && spans.size() >= times) {
                    continue;
                }
                int[] span = rootSpan(m);
                String prepend = interpolate(linePrepend, m);
                StringBuilder combined = new StringBuilder();
                for (String piece : splitInclusive(payload)) {
                    combined.append(prepend).append(piece);
                }
                if (payload.isEmpty()) {
                    combined.setLength(0);
                    combined.append(prepend);
                }
                spans.add(span);
                payloads.add(interpolate(combined.toString(), m));
            }
            if (count == 0) {
                log.warn(origin + ": regex matched nothing in " + name + ":\n    "
                        + firstLine(pattern));
                return text;
            }
            if (times != null) {
                if (count < times) {
                    log.warn(origin + ": " + name + ": regex matched " + count
                            + " times, times = " + times);
                } else if (count > times) {
                    log.warn(origin + ": " + name + ": regex matched " + count
                            + " times, keeping the first " + times);
                }
            }

            StringBuilder out = new StringBuilder(text.length() + 256);
            int cursor = 0;
            for (int i = 0; i < spans.size(); i++) {
                int start = spans.get(i)[0];
                int end = spans.get(i)[1];
                if (start < cursor) {
                    continue;
                }
                String p = payloads.get(i);
                int insertAt = position.equals("after") ? end : start;
                if (!p.isEmpty() && isWordChar(p.charAt(0)) && insertAt > 0
                        && isWordChar(text.charAt(insertAt - 1))) {
                    p = " " + p;
                }
                if (!p.isEmpty() && isWordChar(p.charAt(p.length() - 1))
                        && insertAt < text.length() && isWordChar(text.charAt(insertAt))) {
                    p = p + " ";
                }
                if (position.equals("before")) {
                    out.append(text, cursor, start).append(p).append(text, start, end);
                } else if (position.equals("after")) {
                    out.append(text, cursor, end).append(p);
                } else {
                    out.append(text, cursor, start).append(p);
                }
                cursor = end;
            }
            out.append(text, cursor, text.length());
            return out.toString();
        }
    }

    // --- copy ----------------------------------------------------------------

    static final class CopyPatch extends Patch {
        final String position;
        final List<String> contents = new ArrayList<>();

        CopyPatch(Map<String, Object> t, int priority, String origin, File modDir)
                throws LovelyException, IOException {
            super(t, priority, origin);
            require(t, "target");
            position = String.valueOf(require(t, "position"));
            if (!position.equals("prepend") && !position.equals("append")) {
                throw new LovelyException(origin
                        + ": copy position must be prepend/append, got '" + position + "'");
            }
            Object sources = t.get("sources");
            if (sources instanceof List) {
                for (Object s : (List<?>) sources) {
                    File f = new File(modDir, String.valueOf(s));
                    if (!f.isFile()) {
                        throw new LovelyException(origin + ": copy source not found: " + f);
                    }
                    contents.add(readText(f));
                }
            }
            if (t.containsKey("payload")) {
                contents.add(String.valueOf(t.get("payload")));
            }
        }

        @Override String kind() {
            return "copy";
        }

        @Override String apply(String name, String text, Log log) {
            if (position.equals("prepend")) {
                for (String c : contents) {
                    text = c + "\n" + text;
                }
            } else {
                for (String c : contents) {
                    text = text + "\n" + c;
                }
            }
            return text;
        }
    }

    // --- module --------------------------------------------------------------

    static final class ModulePatch extends Patch {
        final String name;
        final String before;
        final boolean loadNow;
        final String content;
        final String displaySource;
        final String chunkname;
        String packagedPath;

        ModulePatch(Map<String, Object> t, int priority, String origin, File modDir)
                throws LovelyException, IOException {
            super(t, priority, origin);
            String src = String.valueOf(require(t, "source"));
            name = String.valueOf(require(t, "name"));
            before = t.get("before") == null ? null : String.valueOf(t.get("before"));
            loadNow = Boolean.TRUE.equals(t.get("load_now"));
            if (loadNow && (before == null || before.isEmpty())) {
                throw new LovelyException(origin + ": module " + name
                        + " has load_now without before");
            }
            File f = new File(modDir, src);
            if (!f.isFile()) {
                throw new LovelyException(origin + ": module source not found: " + f);
            }
            content = readText(f);
            displaySource = src.replace("\\", "/");
            chunkname = "=[lovely " + name + " \"" + displaySource + "\"]";
        }

        @Override String kind() {
            return "module";
        }

        @Override boolean appliesTo(String name) {
            return false;
        }

        @Override String apply(String name, String text, Log log) {
            return text;
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    static String readText(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<File> patchFiles(File modDir) {
        List<File> files = new ArrayList<>();
        File top = new File(modDir, "lovely.toml");
        if (top.isFile()) {
            files.add(top);
        }
        List<File> found = new ArrayList<>();
        walkToml(new File(modDir, "lovely"), found);
        Collections.sort(found, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().toLowerCase(Locale.ROOT)
                        .compareTo(b.getName().toLowerCase(Locale.ROOT));
            }
        });
        files.addAll(found);
        return files;
    }

    private static void walkToml(File dir, List<File> out) {
        if (!dir.isDirectory()) {
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        List<File> dirs = new ArrayList<>();
        for (File e : entries) {
            if (e.isDirectory()) {
                dirs.add(e);
            } else if (e.getName().toLowerCase(Locale.ROOT).endsWith(".toml")) {
                out.add(e);
            }
        }
        for (File d : dirs) {
            walkToml(d, out);
        }
    }

    /** Mod folders in Lovely's load order: name -> directory. */
    public static LinkedHashMap<String, File> discoverMods(File modsDir, Log log)
            throws IOException {
        LinkedHashMap<String, File> mods = new LinkedHashMap<>();
        if (!modsDir.isDirectory()) {
            return mods;
        }
        Set<String> blacklist = new HashSet<>();
        File bl = new File(new File(modsDir, "lovely"), "blacklist.txt");
        if (bl.isFile()) {
            for (String line : readText(bl).split("\n")) {
                line = trim(line);
                if (!line.isEmpty() && !line.startsWith("#")) {
                    blacklist.add(line);
                }
            }
        }
        File[] entries = modsDir.listFiles();
        if (entries == null) {
            return mods;
        }
        Arrays.sort(entries, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().toLowerCase(Locale.ROOT)
                        .compareTo(b.getName().toLowerCase(Locale.ROOT));
            }
        });
        for (File e : entries) {
            String name = e.getName();
            if (blacklist.contains(name)) {
                continue;
            }
            if (e.isDirectory()) {
                if (new File(e, ".lovelyignore").exists()) {
                    continue;
                }
                mods.put(name, e);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                log.warn(name + " is a zip; unpack it into its own folder under Mods/");
            }
        }
        return mods;
    }

    /** Every patch from every mod, ready to apply per target file. */
    public static final class PatchTable {
        public final String modsDir;
        public final List<String> modNames = new ArrayList<>();
        final List<PatternPatch> patterns = new ArrayList<>();
        final List<RegexPatch> regexes = new ArrayList<>();
        final List<CopyPatch> copies = new ArrayList<>();
        final List<ModulePatch> modules = new ArrayList<>();
        final List<Patch> textual = new ArrayList<>();
        final Map<String, String> vars = new LinkedHashMap<>();
        final Set<String> targets = new HashSet<>();
        private final Log log;

        PatchTable(File dir, Log log) {
            this.log = log;
            this.modsDir = dir.getAbsolutePath().replace("\\", "/");
        }

        public boolean isEmpty() {
            return patterns.isEmpty() && regexes.isEmpty() && copies.isEmpty()
                    && modules.isEmpty();
        }

        public boolean needsPatching(String name) {
            return targets.contains(name);
        }

        public int moduleCount() {
            return modules.size();
        }

        /** `text` with every patch for target `name` applied. */
        public String apply(String name, String text) throws LovelyException {
            if (!targets.contains(name)) {
                return text;
            }
            for (CopyPatch p : copies) {
                if (p.appliesTo(name)) {
                    text = p.apply(name, text, log);
                }
            }
            for (Patch p : textual) {
                if (p.appliesTo(name)) {
                    text = p.apply(name, text, log);
                }
            }
            text = interpolateVars(name, text);
            StringBuilder now = new StringBuilder();
            for (ModulePatch m : modules) {
                if (m.loadNow && name.equals(m.before)) {
                    now.append("require(\"dualscreen.lovely\").load_now(")
                            .append(luaString(m.name)).append(")\n");
                }
            }
            if (now.length() > 0) {
                text = now + text;
            }
            return text;
        }

        private static final Pattern VAR = Pattern.compile("\\{\\{lovely:(\\w+)\\}\\}");

        private String interpolateVars(String name, String text) throws LovelyException {
            Matcher m = VAR.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String key = m.group(1);
                if (!vars.containsKey(key)) {
                    throw new LovelyException(name + ": {{lovely:" + key
                            + "}} is not a registered variable");
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(vars.get(key)));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        /** Files to add to the game: module sources and the manifest. */
        public LinkedHashMap<String, byte[]> extraFiles() {
            LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
            for (ModulePatch m : modules) {
                out.put(m.packagedPath, m.content.getBytes(StandardCharsets.UTF_8));
            }
            out.put(MANIFEST_PATH, manifestLua().getBytes(StandardCharsets.UTF_8));
            return out;
        }

        public String manifestLua() {
            StringBuilder sb = new StringBuilder();
            sb.append("-- Generated when the game was assembled. Do not edit.\n");
            sb.append("return {\n");
            sb.append("    mod_dir = ").append(luaString(modsDir)).append(",\n");
            sb.append("    version = ").append(luaString(SHIM_VERSION)).append(",\n");
            sb.append("    repo = ").append(luaString(LOVELY_REPO)).append(",\n");
            sb.append("    mods = {\n");
            for (String n : modNames) {
                sb.append("        ").append(luaString(n)).append(",\n");
            }
            sb.append("    },\n");
            sb.append("    modules = {\n");
            for (ModulePatch m : modules) {
                sb.append("        { name = ").append(luaString(m.name))
                        .append(", file = ").append(luaString(m.packagedPath))
                        .append(", chunk = ").append(luaString(m.chunkname))
                        .append(", load_now = ").append(m.loadNow ? "true" : "false")
                        .append(" },\n");
            }
            sb.append("    },\n");
            sb.append("}\n");
            return sb.toString();
        }
    }

    /** Load every mod under `modsDir`. Returns null when there is nothing to apply. */
    public static PatchTable load(File modsDir, Log log) throws LovelyException, IOException {
        if (log == null) {
            log = STDERR;
        }
        PatchTable table = new PatchTable(modsDir, log);
        for (Map.Entry<String, File> mod : discoverMods(modsDir, log).entrySet()) {
            List<File> files = patchFiles(mod.getValue());
            if (files.isEmpty()) {
                continue;
            }
            table.modNames.add(mod.getKey());
            for (File pf : files) {
                parsePatchFile(pf, mod.getValue(), mod.getKey(), table);
            }
        }
        if (table.isEmpty()) {
            return null;
        }
        // Stable sorts on priority, lower first; patterns before regexes at
        // equal priority, as Lovely chains the two lists in that order.
        Comparator<Patch> byPriority = new Comparator<Patch>() {
            @Override public int compare(Patch a, Patch b) {
                return Integer.compare(a.priority, b.priority);
            }
        };
        Collections.sort(table.copies, byPriority);
        table.textual.addAll(table.patterns);
        table.textual.addAll(table.regexes);
        Collections.sort(table.textual, byPriority);
        Collections.sort(table.modules, byPriority);
        for (int i = 0; i < table.modules.size(); i++) {
            table.modules.get(i).packagedPath =
                    String.format(Locale.ROOT, "%s/%03d.lua", MODULES_DIR, i);
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    private static void parsePatchFile(File path, File modDir, String modName,
                                       PatchTable table)
            throws LovelyException, IOException {
        String rel = modDir.toPath().relativize(path.toPath()).toString().replace("\\", "/");
        String origin = modName + "/" + rel;
        String raw = readText(path);
        raw = raw.replace("{{lovely_hack:patch_dir}}",
                modDir.getAbsolutePath().replace("\\", "\\\\"));
        Map<String, Object> doc;
        try {
            doc = Toml.parse(raw);
        } catch (Toml.TomlException e) {
            throw new LovelyException(origin + ": TOML parse error: " + e.getMessage());
        }

        Object manifest = doc.get("manifest");
        if (!(manifest instanceof Map)) {
            throw new LovelyException(origin + ": no [manifest] table");
        }
        Map<String, Object> man = (Map<String, Object>) manifest;
        if (!man.containsKey("version")) {
            throw new LovelyException(origin + ": [manifest] has no version");
        }
        int priority = 0;
        if (man.get("priority") != null) {
            if (!(man.get("priority") instanceof Long)) {
                throw new LovelyException(origin + ": manifest priority must be an integer");
            }
            priority = ((Long) man.get("priority")).intValue();
        }

        Object patches = doc.get("patches");
        if (!(patches instanceof List)) {
            throw new LovelyException(origin + ": no [[patches]] entries");
        }
        int i = 0;
        for (Object entry : (List<?>) patches) {
            if (!(entry instanceof Map)) {
                throw new LovelyException(origin + ": patches[" + i + "] is not a table");
            }
            Map<String, Object> e = (Map<String, Object>) entry;
            List<String> kinds = new ArrayList<>();
            for (String k : new String[] { "pattern", "regex", "copy", "module" }) {
                if (e.containsKey(k)) {
                    kinds.add(k);
                }
            }
            if (kinds.size() != 1) {
                throw new LovelyException(origin + ": patches[" + i
                        + "] must contain exactly one of pattern/regex/copy/module");
            }
            String kind = kinds.get(0);
            Object body = e.get(kind);
            if (!(body instanceof Map)) {
                throw new LovelyException(origin + ": patches[" + i + "]." + kind
                        + " is not a table");
            }
            Map<String, Object> t = (Map<String, Object>) body;
            Patch p;
            if (kind.equals("pattern")) {
                p = new PatternPatch(t, priority, origin);
                table.patterns.add((PatternPatch) p);
                table.targets.addAll(p.targets);
            } else if (kind.equals("regex")) {
                p = new RegexPatch(t, priority, origin);
                table.regexes.add((RegexPatch) p);
                table.targets.addAll(p.targets);
            } else if (kind.equals("copy")) {
                p = new CopyPatch(t, priority, origin, modDir);
                table.copies.add((CopyPatch) p);
                table.targets.addAll(p.targets);
            } else {
                ModulePatch m = new ModulePatch(t, priority, origin, modDir);
                table.modules.add(m);
                if (m.before != null && !m.before.isEmpty()) {
                    table.targets.add(m.before);
                }
            }
            i++;
        }

        Object vars = doc.get("vars");
        if (vars != null) {
            if (!(vars instanceof Map)) {
                throw new LovelyException(origin + ": [vars] is not a table");
            }
            for (Map.Entry<String, Object> v : ((Map<String, Object>) vars).entrySet()) {
                table.vars.put(v.getKey(), String.valueOf(v.getValue()));
            }
        }
    }
}
