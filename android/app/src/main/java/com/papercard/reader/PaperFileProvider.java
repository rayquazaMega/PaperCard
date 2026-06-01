package com.papercard.reader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

public class PaperFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.papercard.reader.pdf";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (getContext() == null || mode == null || mode.contains("w")) {
            throw new java.io.FileNotFoundException("Unsupported PDF share URI");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) throw new java.io.FileNotFoundException("Missing PDF file name");
        String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File file = new File(new File(getContext().getCacheDir(), "pdfs"), safeName);
        if (!file.exists()) throw new java.io.FileNotFoundException(safeName);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
