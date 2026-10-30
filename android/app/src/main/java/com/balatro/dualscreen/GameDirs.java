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
import java.io.File;

/**
 * Where the game keeps things on the device. One place for these paths,
 * because three features (the Mods folder, save import/export and the files
 * provider) all need the same answer.
 *
 * LOVE's save directory on Android is getExternalFilesDir()/save/IDENTITY
 * (love/src/common/android.cpp, with Balatro's conf asking for external
 * storage), and Balatro does not set an identity of its own, so IDENTITY is
 * the game archive's file name without its extension: "balatro-game" for the
 * assembled game, "game" for a developer build with game.love embedded.
 *
 * That same save directory is what love.filesystem.read("Mods/...") resolves
 * against, which is why the Mods folder lives inside it: a mod written for
 * desktop Lovely, where Mods sits beside the saves in %AppData%/Balatro,
 * finds its own files at exactly the paths it hard-codes.
 */
public final class GameDirs {

    private GameDirs() {
    }

    /** The identity of the game the app will boot. */
    public static String identity(Context ctx) {
        if (GameAssembler.assembled(ctx) != null) {
            return stem(GameAssembler.ASSEMBLED_NAME);
        }
        return "game";
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** getExternalFilesDir(), or the internal files dir if the device has none. */
    public static File externalRoot(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        return ext != null ? ext : ctx.getFilesDir();
    }

    /** .../files/save, the parent of every identity's save directory. */
    public static File saveRoot(Context ctx) {
        return new File(externalRoot(ctx), "save");
    }

    /** .../files/save/IDENTITY -- settings.jkr and the profile folders live here. */
    public static File saveDir(Context ctx) {
        return new File(saveRoot(ctx), identity(ctx));
    }

    /** .../files/save/IDENTITY/Mods -- where mods go, same layout as desktop. */
    public static File modsDir(Context ctx) {
        return new File(saveDir(ctx), "Mods");
    }

    /** Create the save and Mods directories so file managers have somewhere to go. */
    public static void ensure(Context ctx) {
        File mods = modsDir(ctx);
        //noinspection ResultOfMethodCallIgnored
        mods.mkdirs();
    }

    /** The user's Balatro copy, kept so the game can be rebuilt when mods change. */
    public static File keptSource(Context ctx) {
        return new File(ctx.getFilesDir(), "balatro-source.bin");
    }
}
