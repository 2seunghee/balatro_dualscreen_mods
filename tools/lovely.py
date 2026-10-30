#!/usr/bin/env python3
"""
Apply Lovely-style mod patches to an extracted Balatro tree, at build time.

    python3 tools/lovely.py --mods /path/to/Mods --game-src build/game-src

Lovely (https://github.com/ethangreen-dev/lovely-injector) is the patch
injector most Balatro mods are written for. On desktop it is a DLL that hooks
luaL_loadbuffer and rewrites each game file's source as the engine loads it.
There is no Lovely build for love-android, and this project ships no native
code of its own beyond the display bridge -- so the same rewrites are applied
ONCE, when the game is assembled, and the result is packaged into Game.love.
See docs/decisions/0003-mod-support.md for why that is equivalent for the
patch types involved, and where it is not.

This module is the reference implementation. GameAssembler.java carries the
same engine for the on-device build; tools/test_lovely.py checks the two
agree byte for byte on a fixture set. When one changes, both change.

What is implemented, with the semantics taken from lovely-core's source
(crates/lovely-core/src/patch/*.rs, read October 2026):

  [patches.pattern]  wildcard (* and ?) full-line match on trimmed lines,
                     multi-line patterns, match_indent, times, before/after/at
  [patches.regex]    whole-buffer regex, root_capture, line_prepend, times,
                     the word-boundary spacing rule, $name / ${name} payloads
  [patches.copy]     prepend / append of source files and/or a payload
  [patches.module]   package.preload registration (and load_now), emulated
                     by a shim the overlay loads before main.lua runs
  [vars]             {{lovely:name}} interpolation, merged across files
  [manifest]         priority ordering; version and dump_lua accepted and
                     ignored, exactly as Lovely does

NOT implemented, and refused rather than silently misapplied:

  * zipped mods: Lovely reads patch files out of .zip archives, but LOVE
    cannot read a mod's own assets (fonts, localisations) out of one, so
    every mod needs to be a folder anyway. Zips are skipped with a warning.
  * runtime re-patching (lovely.apply_patches / reload_patches): the shim
    returns false from both.
"""

import os
import re
import sys
import tomllib

# The version the shim reports as lovely.version. Mods that check it only
# ever test for "is Lovely present", and the suffix says what this really is.
SHIM_VERSION = "0.10.0-dualscreen"
LOVELY_REPO = "https://github.com/ethangreen-dev/lovely-injector"

# Where the assembled game carries mod-derived files. Both live OUTSIDE
# dualscreen/ because that directory is copied verbatim from the overlay and
# must contain nothing generated.
MODULES_DIR = "dualscreen-mods/modules"
MANIFEST_PATH = "dualscreen-mods/manifest.lua"

# The line prepended to main.lua when mods are present. It registers the
# `lovely` preload module and every [patches.module], before any game code.
SHIM_REQUIRE = 'require "dualscreen.lovely"'


class LovelyError(Exception):
    """A patch file is malformed or references something that does not exist.

    Lovely itself crashes the game at startup in these cases. Raising here
    makes the build refuse instead, with the mod and file named."""


def warn(msg):
    print("lovely: WARNING: %s" % msg, file=sys.stderr)


# ---------------------------------------------------------------------------
# Wildcard matching -- the `wildmatch` crate: * any run, ? one character.
# ---------------------------------------------------------------------------

def wild_to_regex(pattern):
    out = []
    for ch in pattern:
        if ch == "*":
            out.append(".*")
        elif ch == "?":
            out.append(".")
        else:
            out.append(re.escape(ch))
    return re.compile("^(?s:" + "".join(out) + ")$")


def leading_indent(line):
    i = 0
    while i < len(line) and line[i] in " \t":
        i += 1
    return line[:i]


def split_inclusive(text):
    """Rust's str::split_inclusive('\\n'): pieces keep their newline; no empty
    trailing piece when the text ends in a newline; empty text -> nothing."""
    if text == "":
        return []
    pieces = text.split("\n")
    out = [p + "\n" for p in pieces[:-1]]
    if pieces[-1] != "":
        out.append(pieces[-1])
    return out


def is_word_char(ch):
    return ch.isalnum() or ch == "_"


# ropey (Lovely's text buffer) breaks lines on these; so does the Java port.
_BREAKS = "\n\r\x0b\x0c\x85\u2028\u2029"
_LINE_BREAK = re.compile("[^" + _BREAKS + "]*(?:\r\n|[" + _BREAKS + "])"
                         "|[^" + _BREAKS + "]+$")


def split_lines(text):
    """Lines WITH their terminators, ropey-style. '' -> []."""
    return _LINE_BREAK.findall(text)


def rust_lines(text):
    """Rust's str::lines(): split on LF, drop one trailing CR per line, and
    no empty final element when the text ends in a newline."""
    parts = text.split("\n")
    if parts and parts[-1] == "":
        parts.pop()
    return [p[:-1] if p.endswith("\r") else p for p in parts]


# ---------------------------------------------------------------------------
# Patches
# ---------------------------------------------------------------------------

class Patch:
    """Common fields. `priority` comes from the file's manifest."""
    kind = "?"

    def __init__(self, table, priority, origin):
        self.priority = priority
        self.origin = origin          # "ModName/lovely.toml" for messages
        tgt = table.get("target")
        if isinstance(tgt, str):
            self.targets = [tgt]
        elif isinstance(tgt, list):
            self.targets = [str(t) for t in tgt]
        else:
            self.targets = []

    def applies_to(self, name):
        return name in self.targets

    def require(self, table, key):
        if key not in table:
            raise LovelyError("%s: [patches.%s] is missing `%s`"
                              % (self.origin, self.kind, key))
        return table[key]


class PatternPatch(Patch):
    kind = "pattern"

    def __init__(self, table, priority, origin):
        super().__init__(table, priority, origin)
        self.require(table, "target")
        self.pattern = str(self.require(table, "pattern"))
        self.position = str(self.require(table, "position"))
        if self.position not in ("before", "after", "at"):
            raise LovelyError("%s: pattern position must be before/after/at, got %r"
                              % (origin, self.position))
        self.payload = str(self.require(table, "payload"))
        # No default in lovely-core: a pattern patch without match_indent
        # fails to load there, and so it does here.
        self.match_indent = bool(self.require(table, "match_indent"))
        self.times = table.get("times")
        # `overwrite` is accepted and ignored (legacy no-op in Lovely).
        self._lines = [wild_to_regex(l.strip()) for l in rust_lines(self.pattern)]

    def apply(self, name, text):
        if not self._lines:
            warn("%s: empty pattern for %s, skipped" % (self.origin, name))
            return text
        lines = split_lines(text)
        n = len(self._lines)
        matches = []
        i = 0
        while i + n <= len(lines):
            if all(self._lines[j].match(lines[i + j].strip()) for j in range(n)):
                matches.append(i)
                i += n
            else:
                i += 1
        if not matches:
            warn("%s: pattern matched nothing in %s:\n    %s"
                 % (self.origin, name, self.pattern.strip().splitlines()[0]))
            return text
        if self.times is not None:
            if len(matches) < self.times:
                warn("%s: %s: pattern matched %d times, times = %d"
                     % (self.origin, name, len(matches), self.times))
            elif len(matches) > self.times:
                warn("%s: %s: pattern matched %d times, keeping the first %d"
                     % (self.origin, name, len(matches), self.times))
                matches = matches[:self.times]

        out = []
        cursor = 0
        for first in matches:
            last = first + n - 1
            indent = leading_indent(lines[first]) if self.match_indent else ""
            payload = "".join(indent + p for p in split_inclusive(self.payload))
            if not self.payload.endswith("\n"):
                payload += "\n"
            if self.position == "before":
                out.extend(lines[cursor:first])
                out.append(payload)
                out.extend(lines[first:last + 1])
            elif self.position == "after":
                out.extend(lines[cursor:last + 1])
                # Lovely inserts at the byte after the matched line. When that
                # line is the file's last and has no newline, the payload would
                # fuse onto it; one newline keeps the Lua parseable.
                if not lines[last].endswith("\n"):
                    out.append("\n")
                out.append(payload)
            else:  # at
                out.extend(lines[cursor:first])
                out.append(payload)
            cursor = last + 1
        out.extend(lines[cursor:])
        return "".join(out)


_INTERP = re.compile(r"\$(?:(\$)|\{([^}]*)\}|([0-9A-Za-z_]+))")


def interpolate(template, match):
    """regex-automata's interpolate::string: $$, ${ref}, $ref where ref is a
    group index or name. Unknown or non-participating groups expand to ''."""
    def repl(m):
        if m.group(1):
            return "$"
        ref = m.group(2) if m.group(2) is not None else m.group(3)
        return group_text(match, ref) or ""
    return _INTERP.sub(repl, template)


def group_text(match, ref):
    if ref.isdigit():
        idx = int(ref)
        if idx > match.re.groups:
            return None
        return match.group(idx)
    if ref in match.re.groupindex:
        return match.group(ref)
    return None


def rust_regex_to_python(pattern):
    """The one syntax difference that matters: Rust accepts (?<name>...),
    Python only (?P<name>...). Lookbehind (?<= (?<! is left alone."""
    return re.sub(r"\(\?<(?![=!])", "(?P<", pattern)


class RegexPatch(Patch):
    kind = "regex"

    def __init__(self, table, priority, origin):
        super().__init__(table, priority, origin)
        self.require(table, "target")
        self.pattern = str(self.require(table, "pattern"))
        self.position = str(self.require(table, "position"))
        if self.position not in ("before", "after", "at"):
            raise LovelyError("%s: regex position must be before/after/at, got %r"
                              % (origin, self.position))
        self.payload = str(self.require(table, "payload"))
        self.root_capture = table.get("root_capture")
        self.line_prepend = str(table.get("line_prepend", ""))
        self.times = table.get("times")
        self.verbose = bool(table.get("verbose", False))
        flags = re.M
        if self.verbose:
            flags |= re.X
        try:
            self._re = re.compile(rust_regex_to_python(self.pattern), flags)
        except re.error as e:
            raise LovelyError("%s: bad regex %r: %s" % (origin, self.pattern, e))

    def _root_span(self, m):
        root = (self.root_capture if self.root_capture is not None else "0")
        root = root.replace("$", "")
        if root.isdigit():
            idx = int(root)
            if idx > m.re.groups:
                raise LovelyError("%s: root_capture %r is not a group"
                                  % (self.origin, root))
            span = m.span(idx)
        else:
            if root not in m.re.groupindex:
                raise LovelyError("%s: root_capture %r is not a group"
                                  % (self.origin, root))
            span = m.span(root)
        if span == (-1, -1):
            raise LovelyError("%s: root_capture %r did not participate in a match"
                              % (self.origin, root))
        return span

    def apply(self, name, text):
        matches = list(self._re.finditer(text))
        if not matches:
            warn("%s: regex matched nothing in %s:\n    %s"
                 % (self.origin, name, self.pattern.strip().splitlines()[0]))
            return text
        if self.times is not None:
            if len(matches) < self.times:
                warn("%s: %s: regex matched %d times, times = %d"
                     % (self.origin, name, len(matches), self.times))
            elif len(matches) > self.times:
                warn("%s: %s: regex matched %d times, keeping the first %d"
                     % (self.origin, name, len(matches), self.times))
                matches = matches[:self.times]

        out = []
        cursor = 0
        for m in matches:
            start, end = self._root_span(m)
            if start < cursor:
                # Overlapping root spans cannot be applied in sequence.
                continue
            prepend = interpolate(self.line_prepend, m)
            combined = "".join(prepend + p for p in split_inclusive(self.payload))
            if self.payload == "":
                combined = prepend
            payload = interpolate(combined, m)

            insert_at = end if self.position == "after" else start
            if payload and is_word_char(payload[0]) and insert_at > 0 \
               and is_word_char(text[insert_at - 1]):
                payload = " " + payload
            if payload and is_word_char(payload[-1]) and insert_at < len(text) \
               and is_word_char(text[insert_at]):
                payload = payload + " "

            if self.position == "before":
                out.append(text[cursor:start])
                out.append(payload)
                out.append(text[start:end])
            elif self.position == "after":
                out.append(text[cursor:end])
                out.append(payload)
            else:  # at
                out.append(text[cursor:start])
                out.append(payload)
            cursor = end
        out.append(text[cursor:])
        return "".join(out)


class CopyPatch(Patch):
    kind = "copy"

    def __init__(self, table, priority, origin, mod_dir):
        super().__init__(table, priority, origin)
        self.require(table, "target")
        self.position = str(self.require(table, "position"))
        if self.position not in ("prepend", "append"):
            raise LovelyError("%s: copy position must be prepend/append, got %r"
                              % (origin, self.position))
        self.contents = []
        for src in table.get("sources", []) or []:
            path = os.path.join(mod_dir, str(src))
            if not os.path.isfile(path):
                raise LovelyError("%s: copy source not found: %s" % (origin, path))
            with open(path, encoding="utf8", errors="surrogateescape") as f:
                self.contents.append(f.read())
        if "payload" in table:
            self.contents.append(str(table["payload"]))

    def apply(self, name, text):
        if self.position == "prepend":
            for c in self.contents:
                text = c + "\n" + text
        else:
            for c in self.contents:
                text = text + "\n" + c
        return text


class ModulePatch(Patch):
    kind = "module"

    def __init__(self, table, priority, origin, mod_dir):
        super().__init__(table, priority, origin)
        src = str(self.require(table, "source"))
        self.name = str(self.require(table, "name"))
        self.before = table.get("before")
        self.load_now = bool(table.get("load_now", False))
        if self.load_now and not self.before:
            raise LovelyError("%s: module %s has load_now without before"
                              % (origin, self.name))
        path = os.path.join(mod_dir, src)
        if not os.path.isfile(path):
            raise LovelyError("%s: module source not found: %s" % (origin, path))
        with open(path, encoding="utf8", errors="surrogateescape") as f:
            self.content = f.read()
        self.display_source = src.replace("\\", "/")
        # Lovely's chunk name, so error messages read the same as on desktop.
        self.chunkname = '=[lovely %s "%s"]' % (self.name, self.display_source)
        # Filled in by PatchTable: where the source is packaged.
        self.packaged_path = None

    def applies_to(self, name):
        return False


# ---------------------------------------------------------------------------
# Patch files and discovery
# ---------------------------------------------------------------------------

def _parse_patch_file(path, mod_dir, mod_name):
    origin = mod_name + "/" + os.path.relpath(path, mod_dir).replace(os.sep, "/")
    with open(path, encoding="utf8", errors="replace") as f:
        raw = f.read()
    # Replaced textually before parsing, backslashes doubled, as Lovely does.
    raw = raw.replace("{{lovely_hack:patch_dir}}",
                      mod_dir.replace("\\", "\\\\"))
    try:
        doc = tomllib.loads(raw)
    except tomllib.TOMLDecodeError as e:
        raise LovelyError("%s: TOML parse error: %s" % (origin, e))

    manifest = doc.get("manifest")
    if not isinstance(manifest, dict):
        raise LovelyError("%s: no [manifest] table" % origin)
    if "version" not in manifest:
        raise LovelyError("%s: [manifest] has no version" % origin)
    priority = manifest.get("priority", 0)
    if not isinstance(priority, int):
        raise LovelyError("%s: manifest priority must be an integer" % origin)

    patches = doc.get("patches")
    if not isinstance(patches, list):
        raise LovelyError("%s: no [[patches]] entries" % origin)

    parsed = []
    for i, entry in enumerate(patches):
        if not isinstance(entry, dict):
            raise LovelyError("%s: patches[%d] is not a table" % (origin, i))
        kinds = [k for k in ("pattern", "regex", "copy", "module") if k in entry]
        if len(kinds) != 1:
            raise LovelyError(
                "%s: patches[%d] must contain exactly one of pattern/regex/"
                "copy/module" % (origin, i))
        kind = kinds[0]
        table = entry[kind]
        if kind == "pattern":
            parsed.append(PatternPatch(table, priority, origin))
        elif kind == "regex":
            parsed.append(RegexPatch(table, priority, origin))
        elif kind == "copy":
            parsed.append(CopyPatch(table, priority, origin, mod_dir))
        else:
            parsed.append(ModulePatch(table, priority, origin, mod_dir))

    variables = doc.get("vars", {}) or {}
    if not isinstance(variables, dict):
        raise LovelyError("%s: [vars] is not a table" % origin)
    return parsed, {str(k): str(v) for k, v in variables.items()}


def _patch_files(mod_dir):
    """lovely.toml first, then lovely/**/*.toml sorted by lowercase file name."""
    files = []
    top = os.path.join(mod_dir, "lovely.toml")
    if os.path.isfile(top):
        files.append(top)
    sub = os.path.join(mod_dir, "lovely")
    found = []
    if os.path.isdir(sub):
        for root, dirs, names in os.walk(sub):
            dirs.sort()
            for n in sorted(names):
                if n.lower().endswith(".toml"):
                    found.append(os.path.join(root, n))
    found.sort(key=lambda p: os.path.basename(p).lower())
    return files + found


def discover_mods(mods_dir):
    """Mod folders in Lovely's load order. Returns [(name, path)]."""
    if not os.path.isdir(mods_dir):
        return []
    blacklist = set()
    bl = os.path.join(mods_dir, "lovely", "blacklist.txt")
    if os.path.isfile(bl):
        with open(bl, encoding="utf8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    blacklist.add(line)
    mods = []
    for name in sorted(os.listdir(mods_dir), key=str.lower):
        path = os.path.join(mods_dir, name)
        if name in blacklist:
            continue
        if os.path.isdir(path):
            if os.path.exists(os.path.join(path, ".lovelyignore")):
                continue
            mods.append((name, path))
        elif name.lower().endswith(".zip"):
            warn("%s is a zip; unpack it into its own folder under Mods/" % name)
    return mods


class PatchTable:
    """Every patch from every mod, ready to apply per target file."""

    def __init__(self, mods_dir):
        self.mods_dir = os.path.abspath(mods_dir).replace("\\", "/")
        self.mod_names = []
        self.patterns = []
        self.regexes = []
        self.copies = []
        self.modules = []
        self.vars = {}
        self.targets = set()

        for name, path in discover_mods(mods_dir):
            files = _patch_files(path)
            if not files:
                continue   # a plain Steamodded mod, or assets only: nothing to patch
            self.mod_names.append(name)
            for pf in files:
                patches, variables = _parse_patch_file(pf, path, name)
                self.vars.update(variables)
                for p in patches:
                    if isinstance(p, PatternPatch):
                        self.patterns.append(p)
                    elif isinstance(p, RegexPatch):
                        self.regexes.append(p)
                    elif isinstance(p, CopyPatch):
                        self.copies.append(p)
                    else:
                        self.modules.append(p)
                    if isinstance(p, ModulePatch):
                        # Lovely inserts before.unwrap_or_default(): a module
                        # with no `before` registers "" as a target, which no
                        # file is called. Same effect here.
                        if p.before:
                            self.targets.add(p.before)
                    else:
                        self.targets.update(p.targets)

        # Stable sorts on priority, lower first. Patterns before regexes at
        # equal priority, because Lovely chains the two lists in that order.
        self.copies.sort(key=lambda p: p.priority)
        self.textual = sorted(self.patterns + self.regexes, key=lambda p: p.priority)
        self.modules.sort(key=lambda p: p.priority)
        for i, m in enumerate(self.modules):
            m.packaged_path = "%s/%03d.lua" % (MODULES_DIR, i)

    @property
    def empty(self):
        return not (self.patterns or self.regexes or self.copies or self.modules)

    def needs_patching(self, name):
        return name in self.targets

    def apply(self, name, text):
        """Return `text` with every patch for target `name` applied."""
        if name not in self.targets:
            return text
        # load_now modules run before anything else touches this file; the
        # shim line that runs them is prepended LAST so that pattern patches
        # still see the file as Lovely would.
        for p in self.copies:
            if p.applies_to(name):
                text = p.apply(name, text)
        for p in self.textual:
            if p.applies_to(name):
                text = p.apply(name, text)
        text = self._interpolate_vars(name, text)
        now = [m for m in self.modules if m.load_now and m.before == name]
        if now:
            lines = "".join(
                'require("dualscreen.lovely").load_now(%s)\n' % lua_string(m.name)
                for m in now)
            text = lines + text
        return text

    _VAR = re.compile(r"\{\{lovely:(\w+)\}\}")

    def _interpolate_vars(self, name, text):
        def repl(m):
            key = m.group(1)
            if key not in self.vars:
                raise LovelyError("%s: {{lovely:%s}} is not a registered variable"
                                  % (name, key))
            return self.vars[key]
        return self._VAR.sub(repl, text)

    # -- packaging ---------------------------------------------------------

    def extra_files(self):
        """[(path_in_love, bytes)] for the module sources and the manifest."""
        out = []
        for m in self.modules:
            out.append((m.packaged_path,
                        m.content.encode("utf8", errors="surrogateescape")))
        out.append((MANIFEST_PATH, self.manifest_lua().encode("utf8")))
        return out

    def manifest_lua(self):
        """What dualscreen/lovely.lua reads: the mod dir and module list."""
        lines = ["-- Generated when the game was assembled. Do not edit.",
                 "return {",
                 "    mod_dir = %s," % lua_string(self.mods_dir),
                 "    version = %s," % lua_string(SHIM_VERSION),
                 "    repo = %s," % lua_string(LOVELY_REPO),
                 "    mods = {"]
        for n in self.mod_names:
            lines.append("        %s," % lua_string(n))
        lines.append("    },")
        lines.append("    modules = {")
        for m in self.modules:
            lines.append("        { name = %s, file = %s, chunk = %s, load_now = %s },"
                         % (lua_string(m.name), lua_string(m.packaged_path),
                            lua_string(m.chunkname),
                            "true" if m.load_now else "false"))
        lines.append("    },")
        lines.append("}")
        return "\n".join(lines) + "\n"


def lua_string(s):
    return '"' + (s.replace("\\", "\\\\").replace('"', '\\"')
                  .replace("\n", "\\n").replace("\r", "\\r")) + '"'


# ---------------------------------------------------------------------------
# Applying to a tree
# ---------------------------------------------------------------------------

def patch_tree(game_src, mods_dir, log=print):
    """Patch an extracted game in place. Returns the PatchTable, or None when
    there is nothing to do. main.lua gets the shim line prepended; the
    caller still appends the overlay's own require line afterwards."""
    table = PatchTable(mods_dir)
    if table.empty:
        return None

    log("       mods: " + ", ".join(table.mod_names))
    changed = 0
    for name in sorted(table.targets):
        path = os.path.join(game_src, *name.split("/"))
        if not os.path.isfile(path):
            warn("patch target does not exist in the game: %s" % name)
            continue
        with open(path, encoding="utf8", errors="surrogateescape", newline="") as f:
            text = f.read()
        patched = table.apply(name, text)
        if patched != text:
            changed += 1
        with open(path, "w", encoding="utf8", errors="surrogateescape", newline="") as f:
            f.write(patched)

    main_lua = os.path.join(game_src, "main.lua")
    with open(main_lua, encoding="utf8", errors="surrogateescape", newline="") as f:
        body = f.read()
    with open(main_lua, "w", encoding="utf8", errors="surrogateescape", newline="") as f:
        f.write(SHIM_REQUIRE + "\n" + body)

    for rel, data in table.extra_files():
        dest = os.path.join(game_src, *rel.split("/"))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(data)

    log("       %d file(s) patched, %d module(s), %d var(s)"
        % (changed, len(table.modules), len(table.vars)))
    return table


def main():
    import argparse
    p = argparse.ArgumentParser(
        description="Apply Lovely-style mod patches to an extracted Balatro tree.")
    p.add_argument("--mods", required=True, help="the Mods directory")
    p.add_argument("--game-src", required=True, help="extracted game directory")
    args = p.parse_args()
    try:
        table = patch_tree(args.game_src, args.mods)
    except LovelyError as e:
        raise SystemExit("ERROR: %s" % e)
    if table is None:
        print("No Lovely patch files found under %s; nothing to do." % args.mods)


if __name__ == "__main__":
    main()
