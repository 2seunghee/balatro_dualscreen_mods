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

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Exposes the game's data folder to the system file picker and to file
 * managers, as a root named after the app.
 *
 * On Android 11 and later nothing outside the app can browse
 * Android/data/com.balatro.dualscreen/files without going through a
 * DocumentsProvider -- which is why issue #4 asked for exactly this. With
 * it, the Files app (and Material Files, Solid Explorer and the rest) can
 * open "Balatro Dual Screen" from their side menu and drop mods into
 * save/balatro-game/Mods, or copy saves out, without a computer.
 *
 * The root is files/, not the Mods folder, so saves and mods are both in
 * reach. Document ids are paths relative to that root.
 */
public class BalatroDocumentsProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.balatro.dualscreen.documents";
    private static final String ROOT_ID = "balatro";

    private static final String[] ROOT_PROJECTION = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES,
    };
    private static final String[] DOC_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private File base;

    @Override
    public boolean onCreate() {
        base = GameDirs.externalRoot(getContext());
        GameDirs.ensure(getContext());
        return true;
    }

    // --- ids <-> files ---------------------------------------------------

    private String idFor(File f) throws FileNotFoundException {
        String rel;
        try {
            String b = base.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.equals(b)) {
                rel = "";
            } else if (p.startsWith(b + File.separator)) {
                rel = p.substring(b.length() + 1).replace(File.separatorChar, '/');
            } else {
                throw new FileNotFoundException("outside the root: " + f);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return ROOT_ID + (rel.isEmpty() ? "" : "/" + rel);
    }

    private File fileFor(String docId) throws FileNotFoundException {
        if (!docId.equals(ROOT_ID) && !docId.startsWith(ROOT_ID + "/")) {
            throw new FileNotFoundException("unknown document " + docId);
        }
        String rel = docId.length() > ROOT_ID.length() ? docId.substring(ROOT_ID.length() + 1) : "";
        File f = rel.isEmpty() ? base : new File(base, rel);
        try {
            if (!f.getCanonicalPath().startsWith(base.getCanonicalPath())) {
                throw new FileNotFoundException("outside the root: " + docId);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!f.exists()) {
            throw new FileNotFoundException("no such document " + docId);
        }
        return f;
    }

    private static String mimeFor(File f) {
        if (f.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private void addRow(MatrixCursor c, File f) throws FileNotFoundException {
        int flags = 0;
        if (f.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (!f.equals(base)) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, idFor(f));
        row.add(Document.COLUMN_DISPLAY_NAME, f.equals(base) ? "Balatro Dual Screen" : f.getName());
        row.add(Document.COLUMN_MIME_TYPE, mimeFor(f));
        row.add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, f.isFile() ? f.length() : 0L);
    }

    // --- DocumentsProvider ---------------------------------------------------

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : ROOT_PROJECTION);
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD
                | Root.FLAG_LOCAL_ONLY);
        row.add(Root.COLUMN_ICON, org.love2d.android.executable.R.mipmap.ds_icon);
        row.add(Root.COLUMN_TITLE, "Balatro Dual Screen");
        row.add(Root.COLUMN_SUMMARY, "saves and mods");
        row.add(Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        row.add(Root.COLUMN_AVAILABLE_BYTES, base.getFreeSpace());
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_PROJECTION);
        addRow(c, fileFor(documentId));
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_PROJECTION);
        File dir = fileFor(parentDocumentId);
        File[] kids = dir.listFiles();
        if (kids != null) {
            java.util.Arrays.sort(kids);
            for (File k : kids) {
                addRow(c, k);
            }
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
                                             CancellationSignal signal)
            throws FileNotFoundException {
        File f = fileFor(documentId);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        File f = new File(parent, displayName);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!f.mkdir() && !f.isDirectory()) {
                    throw new FileNotFoundException("could not create " + f);
                }
            } else if (!f.createNewFile() && !f.isFile()) {
                throw new FileNotFoundException("could not create " + f);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return idFor(f);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File f = fileFor(documentId);
        if (f.equals(base)) {
            throw new FileNotFoundException("cannot delete the root");
        }
        try {
            ModInstaller.deleteTree(f);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName)
            throws FileNotFoundException {
        File f = fileFor(documentId);
        File to = new File(f.getParentFile(), displayName);
        if (!f.renameTo(to)) {
            throw new FileNotFoundException("could not rename " + f);
        }
        return idFor(to);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/") || documentId.equals(parentDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeFor(fileFor(documentId));
    }
}
