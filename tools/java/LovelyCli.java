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

import com.balatro.dualscreen.mods.LovelyPatcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Desktop driver for the Java patch engine, used only by
 * tools/check_lovely_parity.py. Patches a game tree in place exactly as
 * GameAssembler does on device, so the output can be diffed against the
 * Python engine's.
 *
 *     java -cp <classes> LovelyCli <Mods dir> <game dir>
 */
public final class LovelyCli {
    public static void main(String[] args) throws Exception {
        File mods = new File(args[0]);
        File game = new File(args[1]);
        LovelyPatcher.PatchTable table = LovelyPatcher.load(mods, null);
        if (table == null) {
            System.out.println("no patches");
            return;
        }
        // Each target file is patched independently, so the walk order does
        // not matter; the Python engine walks them sorted.
        walk(game, game, table);
        File main = new File(game, "main.lua");
        String body = new String(Files.readAllBytes(main.toPath()), StandardCharsets.UTF_8);
        Files.write(main.toPath(), (LovelyPatcher.SHIM_REQUIRE + "\n" + body)
                .getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : table.extraFiles().entrySet()) {
            File dest = new File(game, e.getKey());
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            Files.write(dest.toPath(), e.getValue());
        }
    }

    private static void walk(File root, File dir, LovelyPatcher.PatchTable table)
            throws Exception {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File f : entries) {
            if (f.isDirectory()) {
                walk(root, f, table);
                continue;
            }
            String rel = root.toPath().relativize(f.toPath()).toString().replace("\\", "/");
            if (!table.needsPatching(rel)) {
                continue;
            }
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String patched = table.apply(rel, text);
            Files.write(f.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        }
    }
}
