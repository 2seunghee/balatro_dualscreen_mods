#!/usr/bin/env python3
"""
Tests for the Lovely patch engine, and the fixture set the Java port is
checked against.

    python3 tools/test_lovely.py              # run the tests
    python3 tools/test_lovely.py --fixtures DIR   # also write the fixtures

Each fixture is a Mods/ folder plus a fake game tree with the files the
patches target; the expected output is whatever this engine produces. The
Java engine is run on the same inputs by tools/check_lovely_parity.py and
must produce byte-identical files. The game text in the fixtures is made up
for the purpose -- none of it is Balatro's.
"""

import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import lovely  # noqa: E402


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf8", newline="") as f:
        f.write(text)


def read(path):
    with open(path, encoding="utf8", newline="") as f:
        return f.read()


MANIFEST = '[manifest]\nversion = "1.0.0"\ndump_lua = true\npriority = %d\n\n'


class Fixture:
    """A Mods dir and a game tree in a temp directory."""

    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="lovely-test-")
        self.mods = os.path.join(self.root, "Mods")
        self.game = os.path.join(self.root, "game")
        os.makedirs(self.mods)
        os.makedirs(self.game)
        # Every real game has one; patch_tree prepends the shim to it.
        self.game_file("main.lua", "-- main\n")

    def mod(self, name, toml, files=None, priority=0, sub=None):
        d = os.path.join(self.mods, name)
        target = os.path.join(d, "lovely", sub) if sub else os.path.join(d, "lovely.toml")
        write(target, MANIFEST % priority + toml)
        for rel, text in (files or {}).items():
            write(os.path.join(d, rel), text)
        return d

    def game_file(self, rel, text):
        write(os.path.join(self.game, *rel.split("/")), text)

    def apply(self):
        return lovely.patch_tree(self.game, self.mods, log=lambda *_: None)

    def out(self, rel):
        return read(os.path.join(self.game, *rel.split("/")))

    def cleanup(self):
        shutil.rmtree(self.root, ignore_errors=True)


GAME_LUA = (
    "function Game:init()\n"
    "    self.SPEEDFACTOR = 1\n"
    "    init_localization()\n"
    "    if G.F_NO_ACHIEVEMENTS then\n"
    "        return\n"
    "    end\n"
    "end\n"
)


class PatternTests(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def tearDown(self):
        self.f.cleanup()

    def test_before_with_indent(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "game.lua"
pattern = "init_localization()"
position = "before"
payload = '''
apply_properkorean()'''
match_indent = true
overwrite = false
""")
        self.f.game_file("game.lua", GAME_LUA)
        self.f.apply()
        self.assertEqual(self.f.out("game.lua"),
            "function Game:init()\n"
            "    self.SPEEDFACTOR = 1\n"
            # TOML trims the newline after an opening ''', so the payload is
            # the one line, indented like the matched line.
            "    apply_properkorean()\n"
            "    init_localization()\n"
            "    if G.F_NO_ACHIEVEMENTS then\n"
            "        return\n"
            "    end\n"
            "end\n")

    def test_after_and_at_wildcards(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "game.lua"
pattern = "self.SPEEDFACTOR = *"
position = "after"
payload = "SMODS.init()"
match_indent = true

[[patches]]
[patches.pattern]
target = "game.lua"
pattern = "if G.F_NO_ACHIEVEMENTS then"
position = "at"
payload = "if false then"
match_indent = false
""")
        self.f.game_file("game.lua", GAME_LUA)
        self.f.apply()
        self.assertEqual(self.f.out("game.lua"),
            "function Game:init()\n"
            "    self.SPEEDFACTOR = 1\n"
            "    SMODS.init()\n"
            "    init_localization()\n"
            "if false then\n"
            "        return\n"
            "    end\n"
            "end\n")

    def test_multiline_pattern_at_replaces_all_lines(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = '''
if a then
  b()
end'''
position = "at"
payload = "c()"
match_indent = true
""")
        self.f.game_file("x.lua", "  if a then\n    b()\n  end\n  d()\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "  c()\n  d()\n")

    def test_times_limits_matches(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "x = 1"
position = "after"
payload = "y = 2"
match_indent = false
times = 2
""")
        self.f.game_file("x.lua", "x = 1\nx = 1\nx = 1\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "x = 1\ny = 2\nx = 1\ny = 2\nx = 1\n")

    def test_no_match_leaves_file_alone(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "nothing here"
position = "after"
payload = "y = 2"
match_indent = false
""")
        self.f.game_file("x.lua", "x = 1\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "x = 1\n")

    def test_missing_match_indent_is_an_error(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "x"
position = "after"
payload = "y"
""")
        self.f.game_file("x.lua", "x\n")
        with self.assertRaises(lovely.LovelyError):
            self.f.apply()

    def test_tab_indent_and_question_mark(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "v = ?"
position = "before"
payload = "w = 0"
match_indent = true
""")
        self.f.game_file("x.lua", "\tv = 1\n\tv = 22\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "\tw = 0\n\tv = 1\n\tv = 22\n")


class RegexTests(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def tearDown(self):
        self.f.cleanup()

    def test_named_capture_line_prepend(self):
        self.f.mod("A", """
[[patches]]
[patches.regex]
target = "x.lua"
pattern = "(?<indent>[\\\\t ]*)local foo = (?<val>\\\\d+)"
position = "after"
line_prepend = "$indent"
payload = '''
local bar = $val
local baz = ${val}0'''
""")
        self.f.game_file("x.lua", "do\n    local foo = 7\nend\n")
        self.f.apply()
        # A regex `after` inserts at the match end with nothing added, and
        # TOML trimmed the payload's leading newline, so the first payload
        # line fuses onto the matched line -- exactly as Lovely does it.
        self.assertEqual(self.f.out("x.lua"),
            "do\n    local foo = 7    local bar = 7\n    local baz = 70\nend\n")

    def test_at_with_root_capture_and_spacing(self):
        self.f.mod("A", """
[[patches]]
[patches.regex]
target = "x.lua"
pattern = "return (?<ret>abc)def"
position = "at"
root_capture = "ret"
payload = "xyz"
""")
        self.f.game_file("x.lua", "return abcdef\n")
        self.f.apply()
        # Payload ends in a word char and the byte at the insert point is 'a'
        # (Lovely checks target_start even for `at`), so a space is appended.
        self.assertEqual(self.f.out("x.lua"), "return xyz def\n")

    def test_before_prepends_space_after_word(self):
        self.f.mod("A", """
[[patches]]
[patches.regex]
target = "x.lua"
pattern = "end$"
position = "before"
payload = "x"
""")
        self.f.game_file("x.lua", "fooend\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "foo x end\n")

    def test_dollar_dollar_and_times(self):
        self.f.mod("A", """
[[patches]]
[patches.regex]
target = "x.lua"
pattern = "(\\\\d)"
position = "at"
payload = "<$1$$>"
times = 2
""")
        self.f.game_file("x.lua", "1 2 3\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "<1$> <2$> 3\n")


class CopyModuleVarTests(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def tearDown(self):
        self.f.cleanup()

    def test_copy_prepend_reverses_and_append_keeps_order(self):
        self.f.mod("A", """
[[patches]]
[patches.copy]
target = "x.lua"
position = "prepend"
sources = ["a.lua", "b.lua"]

[[patches]]
[patches.copy]
target = "x.lua"
position = "append"
sources = ["a.lua"]
payload = "P"
""", files={"a.lua": "A", "b.lua": "B\n"})
        self.f.game_file("x.lua", "X\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "B\n\nA\nX\n\nA\nP")

    def test_module_registration_and_manifest(self):
        self.f.mod("A", """
[[patches]]
[patches.module]
source = "src/m.lua"
name = "Mod.m"

[[patches]]
[patches.module]
source = "src/n.lua"
name = "Mod.n"
before = "x.lua"
load_now = true
""", files={"src/m.lua": "return 1", "src/n.lua": "return 2"})
        self.f.game_file("main.lua", "print('hi')\n")
        self.f.game_file("x.lua", "X\n")
        table = self.f.apply()
        self.assertEqual(self.f.out("main.lua"),
                         'require "dualscreen.lovely"\nprint(\'hi\')\n')
        self.assertEqual(self.f.out("x.lua"),
                         'require("dualscreen.lovely").load_now("Mod.n")\nX\n')
        self.assertEqual(self.f.out(lovely.MODULES_DIR + "/000.lua"), "return 1")
        self.assertEqual(self.f.out(lovely.MODULES_DIR + "/001.lua"), "return 2")
        manifest = self.f.out(lovely.MANIFEST_PATH)
        self.assertIn('name = "Mod.m"', manifest)
        self.assertIn('chunk = "=[lovely Mod.n \\"src/n.lua\\"]"', manifest)
        self.assertIn("load_now = true", manifest)
        self.assertIn('mod_dir = "%s"' % table.mods_dir, manifest)
        self.assertIn('"A",', manifest)

    def test_vars_interpolate_whole_target_and_merge(self):
        self.f.mod("A", """
[vars]
who = "world"

[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "print(*)"
position = "after"
payload = "print('{{lovely:who}}')"
match_indent = false
""")
        self.f.mod("B", """
[vars]
who = "mods"

[[patches]]
[patches.pattern]
target = "y.lua"
pattern = "z"
position = "after"
payload = "zz"
match_indent = false
""")
        self.f.game_file("x.lua", "print('{{lovely:who}}')\n")
        self.f.game_file("y.lua", "z\n")
        self.f.apply()
        # B loads after A (sorted by name) so its value wins everywhere, and
        # the original game text is interpolated too.
        self.assertEqual(self.f.out("x.lua"), "print('mods')\nprint('mods')\n")

    def test_unknown_var_is_an_error(self):
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "z"
position = "after"
payload = "{{lovely:nope}}"
match_indent = false
""")
        self.f.game_file("x.lua", "z\n")
        with self.assertRaises(lovely.LovelyError):
            self.f.apply()

    def test_priority_orders_across_mods_and_files(self):
        # Lower priority runs first. Z has -5 so its "after" lands first,
        # then A's pattern sees the file with Z's line already in it.
        self.f.mod("Z", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "start"
position = "after"
payload = "from_z"
match_indent = false
""", priority=-5)
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "from_z"
position = "after"
payload = "from_a"
match_indent = false
""")
        self.f.mod("A", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "start"
position = "before"
payload = "from_a_sub"
match_indent = false
""", sub="extra.toml")
        self.f.game_file("x.lua", "start\n")
        self.f.apply()
        self.assertEqual(self.f.out("x.lua"), "from_a_sub\nstart\nfrom_z\nfrom_a\n")

    def test_blacklist_and_lovelyignore_and_zip_skipped(self):
        self.f.mod("Skip", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "z"
position = "after"
payload = "bad"
match_indent = false
""")
        self.f.mod("Ignored", """
[[patches]]
[patches.pattern]
target = "x.lua"
pattern = "z"
position = "after"
payload = "bad"
match_indent = false
""", files={".lovelyignore": ""})
        write(os.path.join(self.f.mods, "lovely", "blacklist.txt"), "# c\nSkip\n")
        write(os.path.join(self.f.mods, "Thing.zip"), "PK")
        self.f.game_file("x.lua", "z\n")
        self.assertIsNone(self.f.apply())
        self.assertEqual(self.f.out("x.lua"), "z\n")

    def test_no_mods_means_no_shim(self):
        self.f.game_file("main.lua", "print('hi')\n")
        self.assertIsNone(self.f.apply())
        self.assertEqual(self.f.out("main.lua"), "print('hi')\n")


# ---------------------------------------------------------------------------
# Parity fixtures: everything above, written out for the Java engine.
# ---------------------------------------------------------------------------

def write_fixtures(dest):
    """Materialise every test case's inputs under dest/<case>/{Mods,game,
    expected}. The Java parity check runs its engine on Mods+game and diffs
    against expected."""
    if os.path.isdir(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    cases = []
    for cls in (PatternTests, RegexTests, CopyModuleVarTests):
        for name in sorted(dir(cls)):
            if name.startswith("test_") and "error" not in name:
                cases.append((cls, name))
    for cls, name in cases:
        t = cls(name)
        t.setUp()
        try:
            # Run the test body for its side effects: it builds the fixture
            # and applies it. Snapshot the game tree BEFORE applying by
            # re-running with a copy.
            fixture = t.f
            # Capture inputs: monkeypatch apply to copy the pristine tree.
            orig_apply = fixture.apply
            case_dir = os.path.join(dest, "%s_%s" % (cls.__name__, name))

            def snap_apply(fixture=fixture, case_dir=case_dir, orig=orig_apply):
                shutil.copytree(fixture.mods, os.path.join(case_dir, "Mods"))
                shutil.copytree(fixture.game, os.path.join(case_dir, "game"))
                return orig()
            fixture.apply = snap_apply
            getattr(t, name)()
            shutil.copytree(fixture.game, os.path.join(case_dir, "expected"))
        finally:
            t.tearDown()
    print("wrote %d fixtures to %s" % (len(cases), dest))


if __name__ == "__main__":
    if "--fixtures" in sys.argv:
        i = sys.argv.index("--fixtures")
        write_fixtures(sys.argv[i + 1])
        del sys.argv[i:i + 2]
    unittest.main()
