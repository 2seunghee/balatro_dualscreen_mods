# ADR 0003 — Mod support: Lovely patches applied when the game is assembled

**Status:** Accepted
**Date:** 2026-10-30
**Resolves:** issue #4 (mod support); the specific request was the ProperKorean
translation mod (https://github.com/dvrp0/ProperKorean-Balatro)

## Context

Nearly every Balatro mod is written for **Lovely**
(https://github.com/ethangreen-dev/lovely-injector): a native library that
hooks `luaL_loadbuffer` and rewrites each game file's source text as the
engine loads it, driven by `lovely.toml` patch files. Steamodded itself is a
Lovely mod. The Korean mod the request named is a pure Lovely mod — 28
pattern patches, one copy patch, a font and a localisation file — with no
Steamodded dependency.

This project runs the game on stock love-android with one native module of
its own (the display bridge). There is no Lovely build for love-android.

## Options

**1. Port Lovely natively.** Lovely publishes `liblovely`, a static library
meant to be linked into a LuaJIT build, with the host responsible for hooking
`luaL_loadbufferx`. Lovely Mobile Maker does this. It would give exact
behaviour, including runtime re-patching, at the cost of: a Rust toolchain
targeting `aarch64-linux-android` in the build, a patched LuaJIT inside the
vendored love-android tree (which this project treats as third-party and does
not edit), and a second native module to keep working. None of that is
testable without the device.

**2. Apply the same rewrites once, at assembly time.** Every Lovely patch is
a text transform on a game file, and this project already has a step that
rewrites game files when the game is assembled (the one-line injection into
`main.lua`). Run the patches there and package the result. Lovely's runtime
contributions that are *not* text transforms — the `lovely` metadata module
and `[patches.module]` preloading — are small and are provided by a Lua shim.

**3. Patch at runtime in Lua** by wrapping `require`. Ruled out: the overlay
is loaded at the end of `main.lua`, after the game's own `require` block has
already run, so the files mods care about are loaded before any Lua of ours
can intercept them.

## Decision

Option 2. The patch engine exists twice, deliberately: `tools/lovely.py` is
the reference and is used by `build.py --mods` for developer builds;
`LovelyPatcher.java` is the on-device copy used by `GameAssembler`.
`tools/check_lovely_parity.py` runs both over the same fixtures and over a
real mod and fails on any byte of difference. The semantics were taken from
lovely-core's source, not its README, and the surprising ones are recorded in
the Python module's docstring and tests (copy-prepend reverses order,
`match_indent` has no default, regex `at` checks the byte at the match
*start* for its spacing rule, vars interpolate over original game text too).

When mods are present the assembler makes **two** changes to `main.lua`
instead of one: the existing appended `require "dualscreen.init"` and a
prepended `require "dualscreen.lovely"`, which installs `package.preload.lovely`
and the mods' modules before any game code runs. With no mods installed
nothing is prepended and the archive is identical to the one produced before
mod support existed. That is the null path.

The Mods folder lives in LÖVE's save directory
(`Android/data/com.balatro.dualscreen/files/save/balatro-game/Mods`), the
same place relative to the saves as on desktop, so a mod that hard-codes
`Mods/ProperKorean/...` through `love.filesystem` finds its files. A
`DocumentsProvider` exposes that tree to file managers, since Android 11+
hides `Android/data` from them otherwise.

## What this does not do

- **Runtime re-patching.** `lovely.apply_patches` and `reload_patches` return
  `false`. Nothing shipping uses them for anything but tooling.
- **Zipped mods.** Lovely reads patch files out of zips; LÖVE cannot read a
  mod's own assets out of one, so the Manager unpacks zips into folders
  instead and the engines skip zips with a warning.
- **Patches to `conf.lua` that need a module.** `conf.lua` runs before
  `main.lua`, so a module declared `before = "conf.lua"` is not preloaded in
  time. Text patches to it still apply.
- **A guarantee about Steamodded.** Its patch files use only pattern, regex,
  copy and module patches, all of which are implemented, but it also needs
  NativeFS over `lovely.mod_dir` and has its own mobile patches. It has not
  been run through this path. The Korean mod has been verified to the extent
  possible without the game: every one of its patterns matches, and both
  engines produce the same output.

## Amendment — downloading mods, and the INTERNET permission

The manifest originally declared no `INTERNET` permission, on purpose: an
app that holds the user's copy of the game should provably not talk to the
network. Mod downloading needs it, and the maintainer chose the feature.

The permission is scoped by construction rather than by policy text:
`ModDownloader` is the only class that opens a connection, it runs only from
the Manager's download button, it accepts HTTPS only (and refuses redirects
off HTTPS), it caps downloads at 300 MB, and it sends nothing but the request
for the file. GitHub repository links are resolved through the public API
(latest release's first `.zip` asset, else the release source zipball, else
the default branch archive); anything else is treated as a direct link. The
user confirms the install folder name, because a GitHub archive's
`Repo-main/` is the wrong name for a mod that hard-codes its own path.

## Consequences

Adding or removing a mod means rebuilding the game, which the Manager does
in a few seconds from the kept copy of the user's Balatro. That copy now
lives under `files/` rather than `cache/`, so it survives.

If Lovely's semantics change, both engines change; the parity check and the
fixture tests are the place that shows it. If a mod needs runtime patching,
option 1 is the path, and nothing here precludes it: the Mods folder, the
Manager and the provider carry over unchanged.
