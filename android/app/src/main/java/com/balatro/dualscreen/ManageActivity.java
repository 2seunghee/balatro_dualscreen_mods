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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * The Manager: mods and saves, after the game is set up.
 *
 * Reached three ways: its own launcher entry ("Balatro DS Manager"), the
 * "Mods & saves" button in Options > Settings > Dual Screen (which opens
 * balatrods://manage through love.system.openURL), and the setup screen's
 * link. It runs in its OWN PROCESS (android:process=":manager") so that it
 * survives the game being restarted -- BalatroActivity kills its process on
 * exit, which is right for the engine and would take this screen with it.
 *
 * Plain Activity, programmatic layout, no game assets: the same constraints
 * as SetupActivity, for the same reasons.
 */
public class ManageActivity extends Activity {
    private static final String TAG = "BalatroDS";

    private static final int PICK_MOD_ZIP = 51;
    private static final int PICK_SAVE_TREE = 52;
    private static final int PICK_SAVE_ZIP = 53;
    private static final int CREATE_EXPORT = 54;

    private TextView status;
    private TextView log;
    private ProgressBar progress;
    private LinearLayout buttons;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Arriving through love.system.openURL: SDL starts the target with
        // FLAG_ACTIVITY_NO_HISTORY (SDLActivity.openURL), which finishes an
        // activity as soon as it is stopped -- and the first thing this screen
        // does is open a file picker over itself. Relaunch without the flag and
        // let this instance go.
        Intent in = getIntent();
        if (in != null && (in.getFlags() & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0) {
            Intent clean = new Intent(this, ManageActivity.class);
            clean.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(clean);
            finish();
            return;
        }

        GameDirs.ensure(this);
        buildUi();
        refresh();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 32, 26));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Balatro Dual Screen — Manager");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextSize(13);
        status.setTextColor(Color.rgb(200, 210, 205));
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        root.addView(buttons);

        section("Mods");
        body("Mods go in the Mods folder below, one folder each. Install a mod's "
                + "zip here, or copy its folder in with a file manager (this app "
                + "appears under \"Balatro Dual Screen\" in the system file picker). "
                + "Then rebuild the game: patches are applied when the game is "
                + "assembled, not when it starts.");
        button("Install mod from ZIP…", new Runnable() {
            @Override public void run() {
                pick(PICK_MOD_ZIP, "application/zip", "application/octet-stream", "*/*");
            }
        });
        button("Download mod from the web…", new Runnable() {
            @Override public void run() {
                askDownloadUrl();
            }
        });
        button("Rebuild game with current mods", new Runnable() {
            @Override public void run() {
                rebuild();
            }
        });
        button("Open the Mods folder", new Runnable() {
            @Override public void run() {
                openFolder(GameDirs.modsDir(ManageActivity.this));
            }
        });

        section("Saves");
        body("Saves are the same files on every platform. Import from a Steam "
                + "save folder (%AppData%\\Balatro on Windows), from another "
                + "Android build's save folder, or from a zip exported here; "
                + "export produces a zip that unzips straight into any of them. "
                + "Anything overwritten is kept under .ds-backup first.");
        button("Import saves from a folder…", new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(i, PICK_SAVE_TREE);
            }
        });
        button("Import saves from a ZIP…", new Runnable() {
            @Override public void run() {
                pick(PICK_SAVE_ZIP, "application/zip", "application/octet-stream", "*/*");
            }
        });
        button("Export saves to a ZIP…", new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE, "balatro-saves-"
                        + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date())
                        + ".zip");
                startActivityForResult(i, CREATE_EXPORT);
            }
        });
        button("Open the save folder", new Runnable() {
            @Override public void run() {
                openFolder(GameDirs.saveDir(ManageActivity.this));
            }
        });

        section("Game");
        button("Start Balatro", new Runnable() {
            @Override public void run() {
                restartGame();
            }
        });
        button("Remove the built game (keeps saves and mods)", new Runnable() {
            @Override public void run() {
                confirmRemoveBuild();
            }
        });

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(Color.rgb(170, 190, 180));
        log.setPadding(0, pad, 0, pad);
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(0, dp(18), 0, dp(4));
        buttons.addView(tv);
    }

    private void body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(Color.rgb(220, 226, 222));
        buttons.addView(tv);
    }

    private void button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        buttons.addView(b, lp);
    }

    private void pick(int request, String primary, String... alternatives) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        String[] mimes = new String[alternatives.length + 1];
        mimes[0] = primary;
        System.arraycopy(alternatives, 0, mimes, 1, alternatives.length);
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        startActivityForResult(i, request);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        File game = GameAssembler.assembled(this);
        sb.append("Game:  ").append(game != null
                ? "built (" + (game.length() / (1024 * 1024)) + " MB)"
                : "not built yet -- open the app's main icon to set it up").append('\n');
        sb.append("Copy:  ").append(GameAssembler.source(this) != null
                ? "your Balatro copy is kept for rebuilds"
                : "your Balatro copy is not on the device; rebuilding will ask for it")
          .append("\n\n");
        sb.append("Mods folder:\n  ").append(GameDirs.modsDir(this).getAbsolutePath()).append('\n');
        List<String> mods = ModInstaller.describeInstalled(this);
        if (mods.isEmpty()) {
            sb.append("  (none installed)\n");
        } else {
            for (String m : mods) {
                sb.append("  ").append(m).append('\n');
            }
        }
        sb.append("\nSaves:\n").append(SaveTransfer.describeLocal(this));
        status.setText(sb.toString());
    }

    private void say(String msg) {
        Log.i(TAG, "manager: " + msg);
        log.setText(msg);
        refresh();
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        switch (request) {
            case PICK_MOD_ZIP:
                installMod(uri);
                break;
            case PICK_SAVE_TREE:
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Read access for this call is enough.
                }
                importSaves(SaveTransfer.fromTree(getContentResolver(), uri), null);
                break;
            case PICK_SAVE_ZIP:
                importSavesFromZip(uri);
                break;
            case CREATE_EXPORT:
                exportSaves(uri);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Mods
    // ------------------------------------------------------------------

    private void installMod(final Uri uri) {
        final String name = displayName(uri);
        busy(true, "Installing " + name + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    File zip = SaveTransfer.copyToCache(ManageActivity.this, uri, "mod.zip");
                    ModInstaller.Result r = ModInstaller.installZip(ManageActivity.this, zip, name);
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                    msg = "Installed " + r.folder + " (" + r.files + " files"
                            + (r.replaced ? ", replaced the previous copy" : "") + ").\n"
                            + (r.hasPatches
                               ? "It has Lovely patch files: rebuild the game to apply them."
                               : "No Lovely patch files in it; if it is a Steamodded mod it"
                                 + " needs Steamodded installed as well.");
                } catch (Exception e) {
                    Log.w(TAG, "mod install failed", e);
                    msg = "Install failed: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy(false, null);
                        say(m);
                    }
                });
            }
        }, "mod-install").start();
    }

    // ------------------------------------------------------------------
    // Download. The one place the app touches the network; see
    // ModDownloader for what it accepts and why it is HTTPS-only.
    // ------------------------------------------------------------------

    private void askDownloadUrl() {
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setHint("https://github.com/owner/repo  or a link to a .zip");
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.setSingleLine(true);
        final List<String[]> known = ModDownloader.knownMods();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Download a mod")
                .setMessage("Paste a GitHub repository link or a direct link to a mod"
                        + " zip. The app connects only to fetch that file.")
                .setView(box)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        downloadMod(box.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null);
        if (!known.isEmpty()) {
            final String[] first = known.get(0);
            b.setNeutralButton(first[0].split(" ")[0], new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    downloadMod(first[1]);
                }
            });
        }
        b.show();
    }

    private void downloadMod(final String url) {
        busy(true, "Resolving " + url + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final ModDownloader.Target target = ModDownloader.resolve(url);
                    Log.i(TAG, "manager: download " + target);
                    final File zip = new File(getCacheDir(), "download.zip");
                    ModDownloader.download(target, zip, new ModDownloader.Progress() {
                        @Override public void update(final String m, final int pct) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    log.setText(m);
                                    progress.setProgress(pct);
                                }
                            });
                        }
                    });
                    final String detected = ModInstaller.folderNameFor(zip,
                            ModDownloader.fileNameOf(target.downloadUrl));
                    final String suggested = target.suggestedFolder != null
                            ? target.suggestedFolder : detected;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy(false, null);
                            askFolderNameThenInstall(zip, target, suggested, detected);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "download failed", e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy(false, null);
                            say("Download failed: " + e.getMessage());
                        }
                    });
                }
            }
        }, "mod-download").start();
    }

    /**
     * The folder name is the one thing worth confirming: a mod that
     * hard-codes "Mods/ProperKorean/" needs exactly that name, and a GitHub
     * archive would otherwise land as "ProperKorean-Balatro-main".
     */
    private void askFolderNameThenInstall(final File zip, final ModDownloader.Target target,
                                          String suggested, String detected) {
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setText(suggested);
        box.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Install as which folder?")
                .setMessage("Downloaded: " + target.description + "\nThe zip's own layout"
                        + " suggests \"" + detected + "\". Mods that hard-code their"
                        + " folder name need it spelled exactly as their author says.")
                .setView(box)
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String folder = box.getText().toString().trim();
                        busy(true, "Installing " + folder + "…");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                String msg;
                                try {
                                    ModInstaller.Result r = ModInstaller.installZip(
                                            ManageActivity.this, zip,
                                            ModDownloader.fileNameOf(target.downloadUrl), folder);
                                    msg = "Installed " + r.folder + " (" + r.files + " files"
                                            + (r.replaced ? ", replaced the previous copy" : "")
                                            + ").\n" + (r.hasPatches
                                            ? "It has Lovely patch files: rebuild the game to"
                                              + " apply them."
                                            : "No Lovely patch files in it; if it is a Steamodded"
                                              + " mod it needs Steamodded installed as well.");
                                } catch (Exception e) {
                                    Log.w(TAG, "mod install failed", e);
                                    msg = "Install failed: " + e.getMessage();
                                }
                                //noinspection ResultOfMethodCallIgnored
                                zip.delete();
                                final String m = msg;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        busy(false, null);
                                        say(m);
                                    }
                                });
                            }
                        }, "mod-install").start();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        //noinspection ResultOfMethodCallIgnored
                        zip.delete();
                    }
                })
                .show();
    }

    private void rebuild() {
        if (GameAssembler.source(this) == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Balatro copy needed")
                    .setMessage("The copy of Balatro used to build the game is not on"
                            + " the device any more. The setup screen will ask for"
                            + " the file again and apply the mods during that build.")
                    .setPositiveButton("Open setup", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            File built = GameAssembler.assembled(ManageActivity.this);
                            if (built != null) {
                                //noinspection ResultOfMethodCallIgnored
                                built.delete();
                            }
                            startActivity(new Intent(ManageActivity.this, SetupActivity.class));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        busy(true, "Rebuilding…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    GameAssembler.Report report = new GameAssembler.Report();
                    GameAssembler.rebuild(ManageActivity.this, new GameAssembler.Progress() {
                        @Override public void update(final String m, final int pct) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    log.setText(m);
                                    progress.setProgress(pct);
                                }
                            });
                        }
                    }, report);
                    msg = "Rebuilt." + describe(report)
                            + "\nStart Balatro to play with the new build.";
                } catch (Exception e) {
                    Log.w(TAG, "rebuild failed", e);
                    msg = "Rebuild failed:\n  " + e.getMessage()
                            + "\n\nThe previous build is untouched.";
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy(false, null);
                        say(m);
                    }
                });
            }
        }, "rebuild").start();
    }

    /** One line about mods, appended to a build's result message. */
    static String describe(GameAssembler.Report report) {
        if (report == null || report.mods.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" Mods applied: ");
        sb.append(android.text.TextUtils.join(", ", report.mods)).append('.');
        if (!report.warnings.isEmpty()) {
            sb.append(" ").append(report.warnings.size())
              .append(" patch warning(s) -- see logcat (BalatroDS).");
            sb.append("\n  ").append(report.warnings.get(0));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Saves
    // ------------------------------------------------------------------

    private void importSavesFromZip(final Uri uri) {
        busy(true, "Reading the zip…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File zip = SaveTransfer.copyToCache(ManageActivity.this, uri, "saves.zip");
                    final SaveTransfer.Node root = SaveTransfer.fromZip(zip);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy(false, null);
                            importSaves(root, zip);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy(false, null);
                            say("Could not read that zip: " + e.getMessage());
                        }
                    });
                }
            }
        }, "save-zip").start();
    }

    /** Work out what was picked, ask the one or two questions, then copy. */
    private void importSaves(final SaveTransfer.Node picked, final File cleanup) {
        busy(true, "Looking at the picked saves…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (SaveTransfer.looksLikeProfile(picked)) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                busy(false, null);
                                askSlot(picked, cleanup);
                            }
                        });
                        return;
                    }
                    final SaveTransfer.Node root = SaveTransfer.findSaveRoot(picked);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy(false, null);
                            if (root == null) {
                                say("That does not look like Balatro saves. Pick the folder"
                                        + " that holds settings.jkr and the profile folders"
                                        + " 1, 2, 3 (or one profile folder holding"
                                        + " profile.jkr).");
                                return;
                            }
                            askSettingsThenImport(root, cleanup);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy(false, null);
                            say("Could not read the picked saves: " + e.getMessage());
                        }
                    });
                }
            }
        }, "save-probe").start();
    }

    private void askSlot(final SaveTransfer.Node profile, final File cleanup) {
        final String[] labels = { "Profile 1", "Profile 2", "Profile 3" };
        new AlertDialog.Builder(this)
                .setTitle("Import this profile into which slot?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final String slot = SaveTransfer.SLOTS[which];
                        runImport(new ImportJob() {
                            @Override public SaveTransfer.Result run() throws IOException {
                                return SaveTransfer.importProfile(ManageActivity.this, profile, slot);
                            }
                        }, cleanup);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askSettingsThenImport(final SaveTransfer.Node root, final File cleanup) {
        final boolean[] settings = { false };
        new AlertDialog.Builder(this)
                .setTitle("Import saves")
                .setMultiChoiceItems(new String[] {
                        "Also import settings.jkr (display, audio, and the Dual Screen"
                        + " toggles -- usually not wanted from a Steam copy)" },
                        new boolean[] { false },
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(DialogInterface d, int which,
                                                          boolean checked) {
                                settings[0] = checked;
                            }
                        })
                .setPositiveButton("Import profiles", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        runImport(new ImportJob() {
                            @Override public SaveTransfer.Result run() throws IOException {
                                return SaveTransfer.importRoot(ManageActivity.this, root, settings[0]);
                            }
                        }, cleanup);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface ImportJob {
        SaveTransfer.Result run() throws IOException;
    }

    private void runImport(final ImportJob job, final File cleanup) {
        busy(true, "Importing…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    SaveTransfer.Result r = job.run();
                    StringBuilder sb = new StringBuilder("Imported (" + r.layout + "):\n");
                    for (String c : r.copied) {
                        sb.append("  + ").append(c).append('\n');
                    }
                    for (String s : r.skipped) {
                        sb.append("  - ").append(s).append('\n');
                    }
                    if (r.backup != null) {
                        sb.append("Previous files backed up under ").append(r.backup);
                    }
                    if (r.copied.isEmpty()) {
                        sb.append("Nothing was copied.");
                    }
                    msg = sb.toString();
                } catch (Exception e) {
                    Log.w(TAG, "import failed", e);
                    msg = "Import failed: " + e.getMessage();
                }
                if (cleanup != null) {
                    //noinspection ResultOfMethodCallIgnored
                    cleanup.delete();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy(false, null);
                        say(m);
                    }
                });
            }
        }, "save-import").start();
    }

    private void exportSaves(final Uri uri) {
        busy(true, "Exporting…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new IOException("could not open the destination");
                    }
                    int n = SaveTransfer.exportZip(ManageActivity.this, out);
                    msg = "Exported " + n + " file(s). Unzip it into your Steam save folder"
                            + " (%AppData%\\Balatro on Windows, ~/Library/Application"
                            + " Support/Balatro on macOS) or import it on another device.";
                } catch (Exception e) {
                    Log.w(TAG, "export failed", e);
                    msg = "Export failed: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy(false, null);
                        say(m);
                    }
                });
            }
        }, "save-export").start();
    }

    // ------------------------------------------------------------------
    // Game
    // ------------------------------------------------------------------

    /**
     * Start the game in a fresh process. If it is already running it is
     * killed first: it has the previous build loaded, and the engine cannot
     * restart inside a process anyway (see BalatroActivity.onDestroy).
     */
    private void restartGame() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.processName.equals(getPackageName()) && p.pid != Process.myPid()) {
                        Log.i(TAG, "manager: killing game process " + p.pid);
                        Process.killProcess(p.pid);
                    }
                }
            }
        }
        Intent i = new Intent(this, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void confirmRemoveBuild() {
        new AlertDialog.Builder(this)
                .setTitle("Remove the built game?")
                .setMessage("The assembled game and the kept Balatro copy are deleted."
                        + " Saves and mods stay. The app will ask for your Balatro file"
                        + " again next time it starts.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        File built = GameAssembler.assembled(ManageActivity.this);
                        if (built != null) {
                            //noinspection ResultOfMethodCallIgnored
                            built.delete();
                        }
                        File src = GameAssembler.source(ManageActivity.this);
                        if (src != null) {
                            //noinspection ResultOfMethodCallIgnored
                            src.delete();
                        }
                        say("Removed the built game.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------

    private void openFolder(File dir) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String rel;
        try {
            String base = GameDirs.externalRoot(this).getCanonicalPath();
            String p = dir.getCanonicalPath();
            rel = p.length() > base.length() ? p.substring(base.length() + 1).replace('\\', '/') : "";
        } catch (IOException e) {
            rel = "";
        }
        String docId = "balatro" + (rel.isEmpty() ? "" : "/" + rel);
        Uri doc = DocumentsContract.buildDocumentUri(BalatroDocumentsProvider.AUTHORITY, docId);
        // The system Files app browses a directory document with ACTION_VIEW
        // on its DIR mime type; fall back to telling the user the path.
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            say("No file manager answered. The folder is:\n  " + dir.getAbsolutePath()
                    + "\nIt is also reachable from a computer over USB, and in any"
                    + " file manager that shows \"Balatro Dual Screen\" as a storage.");
        }
    }

    private void busy(boolean on, String msg) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            progress.setProgress(0);
            log.setText(msg);
        }
        for (int i = 0; i < buttons.getChildCount(); i++) {
            View v = buttons.getChildAt(i);
            if (v instanceof Button) {
                v.setEnabled(!on);
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return "mod.zip";
    }
}
