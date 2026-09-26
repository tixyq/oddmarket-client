package com.oddmarket;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ApkProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        DownloadRegistry.init(getContext());
        return true;
    }

    private static final String LOG_SEGMENT = "log";

    private File resolveSafeFile(Uri uri) {
        String segment = uri.getLastPathSegment();
        if (segment == null) {
            return null;
        }

        if (LOG_SEGMENT.equals(segment)) {
            return FileLogger.getLogFile();
        }

        if (segment.contains("..") || segment.contains(File.separator) || segment.contains("/")) {
            return null;
        }

        if (!DownloadRegistry.isRegisteredFileName(getContext(), segment)) {
            return null;
        }

        File externalDir = new File(Environment.getExternalStorageDirectory(), "Download");
        File externalFile = safeChildOf(externalDir, segment);
        if (externalFile != null && externalFile.exists()) {
            return externalFile;
        }

        File internalDir = Utils.internalApkDownloadDir(getContext());
        File internalFile = safeChildOf(internalDir, segment);
        if (internalFile != null && internalFile.exists()) {
            return internalFile;
        }

        return null;
    }

    private File safeChildOf(File dir, String segment) {
        File file = new File(dir, segment);
        try {
            String canonicalDir = dir.getCanonicalPath() + File.separator;
            String canonicalFile = file.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalDir)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveSafeFile(uri);
        if (file == null) {
            throw new FileNotFoundException("Invalid or unsafe file path: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (projection == null) {
            projection = new String[] { "_display_name", "_size" };
        }
        MatrixCursor cursor = new MatrixCursor(projection);
        File file = resolveSafeFile(uri);
        if (file == null) {
            return cursor;
        }

        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if ("_display_name".equals(projection[i])) {
                row[i] = file.getName();
            } else if ("_size".equals(projection[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String segment = uri.getLastPathSegment();
        if (LOG_SEGMENT.equals(segment)) {
            return "text/plain";
        }
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
