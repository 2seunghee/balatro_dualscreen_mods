# Balatro Dual Screen

Play [Balatro](https://www.playbalatro.com/) across **both screens** of a
dual-screen Android handheld. Your hand, the Play/Sort/Discard buttons and the
shop live on the lower touchscreen; the table, jokers and score stay on top.

Built and tested on the **AYN Thor**. Nothing in the code is tied to that
device — the second screen is found through Android's display API, not by model
name — but that is the only hardware this has been tested on.

> **This mod is free.** If you paid money for it, demand a refund.

---

|  |  |
| :--: | :--: |
| ![Playing a hand](screenshots/hand.png) | ![The shop](screenshots/shop.png) |
| Your hand and its controls on the lower screen, the table and jokers on top | The shop moves down too, so what you are buying is under your thumbs |
| ![Opening a booster pack](screenshots/buffoon-pack.png) | ![The main menu](screenshots/menu.png) |
| Booster packs take over the panel, with the pack animation on both screens | Menus are laid out for the lower screen rather than letterboxed |

---

## What it does

- **Hand on the lower screen** — cards, Play / Sort / Discard, all touch-driven.
- **Shop, blind select, booster packs and menus** move down too, so whatever
  you are interacting with is under your thumbs.
- **Optional extras**, each a toggle in *Options → Settings → Dual Screen*:
  - hand score and consumables on the lower screen
  - a wider joker row that uses the space freed up top
  - the game's CRT effect applied to the lower screen as well
  - RGB stick lighting that follows the on-screen colours (AYN Thor)
  - a 60 FPS cap to save battery, or 120 if you prefer
  - **swap screens** (experimental), for devices where the hand lands on the
    screen you cannot touch
- **Mods** — Lovely-style mods such as the
  [ProperKorean](https://github.com/dvrp0/ProperKorean-Balatro) translation
  are applied when the game is built on the device. See [Mods](#mods).
- **Save transfer** — import your Steam or other-Android saves, export them
  back. See [Saves](#saves).
- **Controller and touch** both work throughout.
- **Single-screen safe** — with the second screen switched off, or on a device
  that has only one, the game behaves exactly like vanilla.

## Requirements

- A dual-screen Android device (Android 8.0 / API 26 or newer).
- **Your own legally purchased copy of Balatro for PC or Mac.** This app
  contains no part of the game.
- Supported version: **Steam 1.0.1o**. Other PC versions may work but are
  untested.

### Which copies work

| Container | Works |
| --- | --- |
| `Balatro.exe` (Steam, Windows) | yes |
| `Balatro.app` (Steam, macOS) — zipped | yes |
| `Balatro.love` | yes |
| iOS / Mac App Store build | **no** |
| Console builds | **no** |

The mobile build depends on `love.platform`, a launcher module that ships with
Apple's build of the game rather than with LÖVE itself, across 44 call sites.
`love-android` has no such module, so the game cannot start. The app rejects
that build explicitly rather than failing later in a confusing way.

## Install

1. Download the latest APK from the [**Releases**](../../releases) tab.
2. Copy it to your device and install it. Android will ask you to allow
   installing apps from this source — normal for anything outside the Play
   Store.
3. Get your Balatro copy onto the device:
   - **Windows:** `Balatro.exe`, from `Steam\steamapps\common\Balatro\`
   - **macOS:** a **ZIP** of `Balatro.app`, from
     `~/Library/Application Support/Steam/steamapps/common/Balatro/`
     (a `.app` is a folder, so it must be zipped to copy it across)
   - or a `Balatro.love` archive

   Anywhere the device's file picker can reach is fine — `Downloads` is easiest.
4. Open the app. On first launch it asks you to choose that file, checks it,
   and builds your game. This takes a few seconds.
5. It offers to add a home-screen shortcut, then starts the game.

Your Balatro copy is only read during that build. The game is assembled into
the app's private storage; nothing is uploaded anywhere. The app's one use of
the network is the Manager's mod download button, which fetches the zip you
point it at and nothing more.

### Where to find your Balatro copy

The file has to come from a **desktop** Steam install:

- **Windows:** open Steam → Library → right-click Balatro → *Manage → Browse
  local files*. `Balatro.exe` is in that folder. Copy it to the device over
  USB, or through any cloud drive.
- **macOS:** same menu; you get `Balatro.app`. Right-click it → *Compress* and
  copy the resulting zip.
- **Steam Deck / Linux:** `~/.local/share/Steam/steamapps/common/Balatro/Balatro.exe`.

**Already running Balatro on the device** through Winlator, GameNative,
DroidDeck or a similar Windows container or Steam client? Press **Find
Balatro on this device…** on the setup screen. It asks Android for file
access (All files access on Android 11 and newer, used for this search only)
and looks through shared storage, ranking the Steam layout
(`steamapps/common/Balatro/Balatro.exe`) first. What it can reach:

| Where the game is | Found? |
| --- | --- |
| Winlator D: drive (Downloads) or any drive mapped to shared storage | yes |
| A folder you copied from a PC, an SD card | yes |
| Winlator C: drive | no — that is Winlator's private storage, which Android lets no other app read. Move the Balatro folder to D: or copy it out with Winlator's file manager first. |
| GameNative / DroidDeck library kept in the app's own storage | no, for the same reason; export the folder to Downloads or point the library at a shared folder |

### Updating

Install the new APK over the old one and open it. To force a rebuild from
scratch, use the **Balatro DS Manager** (a second icon the app installs) →
*Remove the built game*, which keeps your saves and mods. Clearing the app's
storage from Android Settings also works but removes in-app saves too.

## Mods

Mods made for [Lovely](https://github.com/ethangreen-dev/lovely-injector) —
which is most Balatro mods, including the
[ProperKorean](https://github.com/dvrp0/ProperKorean-Balatro) Korean
translation — can be installed. There is no Lovely for Android, so instead of
patching the game as it loads, this app applies the same patches once, when it
builds the game. That means **after adding or removing a mod, rebuild the
game** from the Manager.

1. Open **Balatro DS Manager** (the second icon), or in the game go to
   *Options → Settings → Dual Screen → Mods & saves*.
2. Get the mod in, one of three ways:
   - **Download mod from the web…** — paste a GitHub repository link (the
     latest release's zip is used) or a direct link to a zip. You are asked
     to confirm the folder name before it is installed. This is the only
     thing in the app that uses the network, and only when you press it.
   - **Install mod from ZIP…** — pick a zip you already downloaded.
   - Drop a mod folder in with a file manager: the app shows up as a storage
     called *Balatro Dual Screen* in the Android file picker and in file
     managers that support it; the folder is `save/balatro-game/Mods`.
3. **Rebuild game with current mods.** A few seconds.
4. **Start Balatro.**

What works: pattern, regex, copy and module patches, `[vars]`, and
priorities — the whole patch format Lovely documents. Mods that ship their
own files (fonts, localisations) find them exactly where they expect, because
the Mods folder sits next to the saves as it does on desktop. What does not:
zipped mods left as zips (unpack them), and anything that re-patches at
runtime. Steamodded has not been tested through this path.

### 한국어 패치 (ProperKorean)

1. [ProperKorean 릴리스](https://github.com/dvrp0/ProperKorean-Balatro/releases)에서
   `ProperKorean.zip`을 받아 기기로 복사합니다.
2. **Balatro DS Manager** 앱(두 번째 아이콘)을 열고 **Install mod from ZIP…**
   으로 그 zip을 선택합니다. `Mods/ProperKorean` 폴더로 풀립니다. 폴더 이름이
   `ProperKorean`이어야 모드가 자기 파일을 찾습니다.
3. **Rebuild game with current mods**를 누르고, 끝나면 **Start Balatro**.
4. 게임 안에서 언어를 한국어로 바꿉니다. 글꼴과 번역이 모드 것으로 바뀝니다.

## Saves

Balatro's save files are the same on every platform, so they move freely
between Steam, other Android builds and this app. In the **Manager**:

- **Import saves from a folder…** — pick the save folder. On Steam for Windows
  that is `%AppData%\Balatro` (copy it to the device first); on macOS
  `~/Library/Application Support/Balatro`; from another Android build,
  `Android/data/<that app>/files/save/Balatro`. The profile folders `1`, `2`,
  `3` are copied. `settings.jkr` is optional and off by default, because a
  desktop copy carries desktop display settings and would reset the Dual
  Screen toggles. Picking a single profile folder asks which slot to put it in.
- **Import saves from a ZIP…** — same, from a zip (such as one exported here).
- **Export saves to a ZIP…** — writes a zip in exactly Steam's layout. Unzip it
  into `%AppData%\Balatro` on a PC and Steam picks it up.

Anything an import overwrites is first copied to `.ds-backup/<time>/` inside
the save folder.

Steam Cloud note: Steam syncs the desktop folder itself; copy your files in
while Steam is closed, then start Steam.

## Swapped screens

Android decides which panel is the "presentation" display, and on some devices
that is the one without touch, or the top one. *Options → Settings → Dual
Screen → Swap screens* puts the hand and buttons on the game's own window and
mirrors the table to the other screen. It is marked experimental because it
was written against the engine rather than tested on such a device; if you
have one, please report what happens.

## Known limitations

- **Stick LEDs (AYN Thor):** the LED hardware is write-only, so the colour you
  had before the game started cannot be read and therefore cannot be restored
  on exit. Turn *AYN Thor: Enable RGB effects* off if that matters to you.
- **Text in other languages** can run past the edge of panels on the lower
  screen; the panel regions are sized for English labels.

## Building from source

You do not need to build anything to play — use the Releases tab. To build it
yourself:

```bash
python3 tools/build.py --balatro /path/to/Balatro.app     # or Balatro.exe
```

That extracts the game, adds the overlay, packages it and produces an APK with
the game already inside, which is convenient for development. To build the
publishable APK instead, which contains no game data:

```bash
python3 tools/build.py --release
```

To bake mods into a developer build, point `--mods` at a Mods folder laid out
as on desktop (one folder per mod):

```bash
python3 tools/build.py --balatro /path/to/Balatro.exe --mods /path/to/Mods
```

To check a copy of the game before building with it:

```bash
python3 tools/check_balatro_source.py /path/to/your/Balatro.app
```

You need the Android SDK/NDK and a JDK; exact versions and rationale are in
[`docs/android-build.md`](docs/android-build.md).

No signing key is needed: without one the build falls back to Android's debug
key and produces a perfectly installable APK. It just cannot install *over* a
release from the Releases tab, because the signatures differ — uninstall the
released version first, which clears its saves.

## How it works

This is not a fork of Balatro. It is an Android app, a Lua overlay and a build
script. Your copy of the game supplies everything else.

```
  this repo (android/, lua/dualscreen/, tools/)  +  your Balatro copy
                              |
              extract -> add overlay -> Game.love -> APK
```

The overlay reassigns a handful of globals when the game loads, so it inherits
upstream behaviour rather than replacing it. Exactly one line is added to the
game's own `main.lua`.

Design decisions and the reasoning behind them are recorded in
[`docs/decisions/`](docs/decisions/).

## Support

If you enjoy this and want to say thank you:

[![Support me on Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20me-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/rosbean)

Entirely optional, and always appreciated.

## Licence and credits

Released under the **GNU General Public License v3.0** — see [`COPYING`](COPYING).

This project stands on work others did first:

- **[BanjoRecomp](https://github.com/BanjoRecomp/BanjoRecomp)** (GPL-3.0) — the
  working dual-screen Android implementation this one learned from. Code is
  lifted and adapted from it, which is why this project is GPL-3.0.
- **[Bifrost](https://github.com/Pollux-MoonBench/Bifrost)** (GPL-3.0) by
  Pollux-MoonBench — an LED controller for the AYN Thor. Its `LedController`
  worked out how to reach the stick LEDs, and the RGB lighting here follows
  that technique.
- **[balatro-portrait-mobile](https://github.com/ShaggyLorean/balatro-portrait-mobile)**
  by ShaggyLorean — prior art that showed Balatro's layout can be reflowed on
  Android, and where the layout logic lives. Read as reference only: it carries
  no licence grant, so **no code from it is used here**.

[`NOTICE.md`](NOTICE.md) records exactly what came from where.

### Built with AI assistance

This project was written with substantial help from an AI coding assistant
(Claude). Design decisions, testing on real hardware and every judgement call
about what to keep were the author's; a great deal of the implementation,
investigation and documentation was produced in collaboration with the model.

Mentioned because you deserve to know what you are installing and reading. It
does not change what the code does or how carefully it was tested — every
feature here was verified on a physical device before it shipped — but if that
matters to you, now you know before you download it.

Balatro is © LocalThunk, published by Playstack. This project is an unofficial
fan modification, not affiliated with or endorsed by either. It contains no
Balatro code or assets, and you must own the game to use it.
