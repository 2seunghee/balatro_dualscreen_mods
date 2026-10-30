# Issue triage (as of 2026-10-30)

What each open GitHub issue needs, and what this tree does about it.

| # | Title | State in this tree |
|---|---|---|
| 1 | Joystick LED colour not restored after quitting | **Cannot be done from the app.** The Thor's LED nodes are write-only (ThorLeds.java): the colour set before the game started cannot be read, so it cannot be put back. The toggle the issue asks for already exists: *Options → Settings → Dual Screen → AYN Thor: Enable RGB effects*. Documented in the README. |
| 2 | Screen swapping | **Implemented, untested on hardware.** *Dual Screen → Swap screens (hand on this screen)*. `lua/dualscreen/swap.lua`. Needs a tester with a device whose touch screen is not the presentation display. |
| 3 | Screen half removed on both (RG DS, GammaOS) | **Needs the reporter.** The log shows both screens are 640×480 and the companion lands on the *top* screen; the photo shows a dark disc eating half of both, which is the CRT shader. Two things to try: *Options → Graphics → CRT* set to 0, and the new Swap screens toggle so the hand is on the touch screen. The overlay now logs window size, DPI scale and canvas size at startup to pin this down. |
| 4 | Mod support | **Implemented.** Lovely-compatible patches applied at assembly; Manager screen; Mods folder exposed through a DocumentsProvider exactly as asked. See `docs/decisions/0003-mod-support.md`. |
| 5 | Where is Balatro.exe (GameNative) | **Implemented + docs.** The setup screen's *Find Balatro on this device* button (`BalatroFinder.java`) searches shared storage, including Winlator's D: drive and any Steam library on a shared folder. A library inside a container app's private storage is unreachable on Android 11+ by design; the README's table says what to do then. |
| 6 | Infinite discards; tarot/planet cards usable mid-round; Spanish text overflow on the lower screen | **Needs reproduction.** The first two are not reproduced and the video is not viewable from here; they may be vanilla behaviour (a Tarot card from a booster pack *can* be used on the hand mid-round). The text overflow is a real limitation: section regions are sized for English labels. Not changed in this tree. |
| 7 | Save game transfer | **Implemented.** Manager → Import saves (folder or zip, from Steam or another Android build) / Export saves (a zip in Steam's layout). `SaveTransfer.java`. |
