#!/usr/bin/env python3
"""
Check that the Java and Python Lovely engines agree, byte for byte.

    python3 tools/check_lovely_parity.py [--mods DIR --game DIR]

With no arguments: writes the fixture set from tools/test_lovely.py, runs
LovelyCli (the Java engine) over each fixture's inputs, and diffs the result
against the Python engine's expected output. With --mods and --game: runs
BOTH engines over that real mod set and game tree (copies; nothing is
modified) and diffs them, which is how to check a real mod such as
ProperKorean before shipping.

Needs a JDK on PATH (javac/java 17+). No Android SDK is needed: the engine
uses only java.* classes, which is deliberate so it can be tested here.
"""

import filecmp
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import lovely  # noqa: E402
import test_lovely  # noqa: E402

JAVA_SRC = [
    os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "balatro",
                 "dualscreen", "mods", "Toml.java"),
    os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "balatro",
                 "dualscreen", "mods", "LovelyPatcher.java"),
    os.path.join(HERE, "java", "LovelyCli.java"),
]


def compile_java(classes):
    os.makedirs(classes, exist_ok=True)
    subprocess.run(["javac", "-encoding", "utf8", "-d", classes] + JAVA_SRC, check=True)


def run_java(classes, mods, game):
    subprocess.run(["java", "-cp", classes, "LovelyCli", mods, game], check=True)


def _drop_mod_dir(data):
    lines = data.split(b"\n")
    return b"\n".join(l for l in lines if not l.strip().startswith(b"mod_dir ="))


def tree_diff(a, b):
    """Return a list of differences between two directory trees."""
    problems = []

    def walk(da, db, rel):
        cmp = filecmp.dircmp(da, db)
        for n in cmp.left_only:
            problems.append("only in python: %s" % os.path.join(rel, n))
        for n in cmp.right_only:
            problems.append("only in java: %s" % os.path.join(rel, n))
        for n in cmp.common_files:
            with open(os.path.join(da, n), "rb") as fa, open(os.path.join(db, n), "rb") as fb:
                ca, cb = fa.read(), fb.read()
            if n == "manifest.lua":
                # The absolute Mods path differs between the two runs by
                # construction; everything else in the manifest must not.
                ca, cb = _drop_mod_dir(ca), _drop_mod_dir(cb)
            if ca != cb:
                problems.append("differs: %s" % os.path.join(rel, n))
        for n in cmp.common_dirs:
            walk(os.path.join(da, n), os.path.join(db, n), os.path.join(rel, n))
    walk(a, b, "")
    return problems


def main():
    tmp = tempfile.mkdtemp(prefix="lovely-parity-")
    classes = os.path.join(tmp, "classes")
    compile_java(classes)

    if "--mods" in sys.argv:
        mods = sys.argv[sys.argv.index("--mods") + 1]
        game = sys.argv[sys.argv.index("--game") + 1]
        py = os.path.join(tmp, "py")
        jv = os.path.join(tmp, "java")
        shutil.copytree(game, py)
        shutil.copytree(game, jv)
        lovely.patch_tree(py, mods)
        run_java(classes, mods, jv)
        problems = tree_diff(py, jv)
    else:
        fixtures = os.path.join(tmp, "fixtures")
        test_lovely.write_fixtures(fixtures)
        problems = []
        for case in sorted(os.listdir(fixtures)):
            d = os.path.join(fixtures, case)
            work = os.path.join(d, "java-out")
            shutil.copytree(os.path.join(d, "game"), work)
            run_java(classes, os.path.join(d, "Mods"), work)
            for p in tree_diff(os.path.join(d, "expected"), work):
                problems.append("%s: %s" % (case, p))
            print("  %-70s %s" % (case, "ok" if not problems or
                                 not problems[-1].startswith(case) else "DIFF"))

    if problems:
        print()
        for p in problems:
            print("  " + p)
        print("\n%d difference(s) between the Python and Java engines." % len(problems))
        sys.exit(1)
    print("\nPython and Java engines agree.")


if __name__ == "__main__":
    main()
