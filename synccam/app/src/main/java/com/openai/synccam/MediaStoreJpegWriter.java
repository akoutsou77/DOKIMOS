package com.openai.synccam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class MediaStoreJpegWriter {
    static final String PREFS = "synccam_settings";
    static final String PREF_SAVE_TREE_URI = "save_tree_uri";

    private MediaStoreJpegWriter() { }

    static Uri writePending(Context context, byte[] data, String displayName, String relativePath, File legacyDir) throws IOException {
        if (data == null || data.length < 4) throw new IOException("camera returned an empty JPEG");
        if ((data[0] & 0xff) != 0xff || (data[1] & 0xff) != 0xd8)
            throw new IOException("camera returned invalid JPEG data");
        File temp = File.createTempFile("synccam_local_", ".jpg", context.getCacheDir());
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                out.write(data);
                out.flush();
            }
            return writeTempFile(context, temp, displayName, relativePath, legacyDir, data.length);
        } finally {
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    static Uri writePending(Context context, InputStream input, String displayName, String relativePath, File legacyDir) throws IOException {
        if (input == null) throw new IOException("JPEG input stream is null");
        File temp = File.createTempFile("synccam_remote_", ".jpg", context.getCacheDir());
        long copied = 0L;
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    if (n > 0) {
                        out.write(buffer, 0, n);
                        copied += n;
                    }
                }
                out.flush();
            }
            validateJpegFile(temp, copied);
            return writeTempFile(context, temp, displayName, relativePath, legacyDir, copied);
        } finally {
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    private static Uri writeTempFile(Context context, File source, String displayName, String relativePath,
                                     File legacyDir, long expectedBytes) throws IOException {
        validateJpegFile(source, expectedBytes);

        IOException treeFailure = null;
        String tree = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SAVE_TREE_URI, "");
        if (tree != null && !tree.trim().isEmpty()) {
            try {
                return writeToSelectedTree(context, source, displayName, relativePath, expectedBytes, Uri.parse(tree));
            } catch (IOException e) {
                treeFailure = e;
            }
        }

        try {
            return writeViaMediaStore(context, source, displayName, relativePath, legacyDir, expectedBytes);
        } catch (IOException mediaFailure) {
            if (treeFailure != null)
                throw combine("Selected save folder and MediaStore both failed", treeFailure, mediaFailure);
            throw mediaFailure;
        }
    }

    private static Uri writeViaMediaStore(Context context, File source, String displayName, String relativePath,
                                          File legacyDir, long expectedBytes) throws IOException {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state))
            throw new IOException("shared storage is not writable (state=" + state + ")");

        if (Build.VERSION.SDK_INT < 29) {
            if (legacyDir == null) throw new IOException("legacy output directory is missing");
            if (!legacyDir.exists() && !legacyDir.mkdirs())
                throw new IOException("cannot create " + legacyDir.getAbsolutePath());
            return insertAndCopy(context, source, displayName, null, legacyDir,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, expectedBytes);
        }

        String normalizedPath = normalizeRelativePath(relativePath);
        IOException first = null;

        if (Build.VERSION.SDK_INT == 30) {
            // Android 11 OEM MediaStore providers vary. Prefer the long-standing external image
            // collection without a pending transaction, then try the primary-volume URI and
            // finally the canonical pending flow.
            try {
                return insertAndCopy(context, source, displayName, normalizedPath, legacyDir,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, expectedBytes);
            } catch (IOException e) {
                first = e;
            }
            try {
                return insertAndCopy(context, source, displayName, normalizedPath, legacyDir,
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), false, expectedBytes);
            } catch (IOException e) {
                if (first == null) first = e;
            }
            try {
                return insertAndCopy(context, source, displayName, normalizedPath, legacyDir,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, expectedBytes);
            } catch (IOException e) {
                throw combine("Android 11 MediaStore compatibility routes failed", first, e);
            }
        }

        Uri collection = Build.VERSION.SDK_INT == 29
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return insertAndCopy(context, source, displayName, normalizedPath, legacyDir,
                collection, true, expectedBytes);
    }

    private static Uri writeToSelectedTree(Context context, File source, String displayName, String relativePath,
                                           long expectedBytes, Uri treeUri) throws IOException {
        DocumentFile root;
        try {
            root = DocumentFile.fromTreeUri(context, treeUri);
        } catch (Exception e) {
            throw new IOException("cannot open selected save folder: " + safeMessage(e), e);
        }
        if (root == null) throw new IOException("selected save folder is unavailable");
        if (!root.canWrite()) throw new IOException("selected save folder is not writable");

        String subPath = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        while (subPath.startsWith("/")) subPath = subPath.substring(1);
        String picturesPrefix = Environment.DIRECTORY_PICTURES + "/";
        if (subPath.startsWith(picturesPrefix)) subPath = subPath.substring(picturesPrefix.length());
        if (subPath.equals("SyncCam") || subPath.equals("SyncCam/")) subPath = "";
        else if (subPath.startsWith("SyncCam/")) subPath = subPath.substring("SyncCam/".length());
        while (subPath.endsWith("/")) subPath = subPath.substring(0, subPath.length() - 1);

        DocumentFile dir = root;
        if (!subPath.isEmpty()) {
            String[] parts = subPath.split("/");
            for (String raw : parts) {
                String part = safeDocumentSegment(raw, "Folder");
                DocumentFile next = dir.findFile(part);
                if (next != null && !next.isDirectory())
                    throw new IOException("save-folder path component is not a directory: " + part);
                if (next == null) next = dir.createDirectory(part);
                if (next == null) throw new IOException("cannot create save-folder directory: " + part);
                dir = next;
            }
        }

        DocumentFile file = null;
        try {
            DocumentFile existing = dir.findFile(displayName);
            if (existing != null) existing.delete();
            file = dir.createFile("image/jpeg", displayName);
            if (file == null) throw new IOException("system folder provider could not create JPEG");
            Uri uri = file.getUri();
            ContentResolver resolver = context.getContentResolver();

            long written = 0L;
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 OutputStream raw = resolver.openOutputStream(uri, "w")) {
                if (raw == null) throw new IOException("system folder provider returned no output stream");
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n > 0) {
                            out.write(buffer, 0, n);
                            written += n;
                        }
                    }
                    out.flush();
                }
            }
            if (written <= 0) throw new IOException("zero JPEG bytes written to selected folder");
            if (expectedBytes > 0 && written != expectedBytes)
                throw new IOException("short selected-folder JPEG write: " + written + "/" + expectedBytes + " bytes");
            verifyReadableJpeg(resolver, uri);
            return uri;
        } catch (Exception e) {
            if (file != null) {
                try { file.delete(); } catch (Exception ignored) { }
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("selected save folder failed: " + e.getClass().getSimpleName() + ": " + safeMessage(e), e);
        }
    }

    private static Uri insertAndCopy(Context context, File source, String displayName, String relativePath,
                                     File legacyDir, Uri collection, boolean pending, long expectedBytes) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
            if (pending) values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            values.put(MediaStore.Images.Media.DATA, new File(legacyDir, displayName).getAbsolutePath());
        }

        Uri uri = null;
        try {
            uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("MediaStore insert returned null for " + collection);

            long written = 0L;
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 OutputStream raw = resolver.openOutputStream(uri, "w")) {
                if (raw == null) throw new IOException("MediaStore output stream is null for " + uri);
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n > 0) {
                            out.write(buffer, 0, n);
                            written += n;
                        }
                    }
                    out.flush();
                }
            }

            if (written <= 0) throw new IOException("zero JPEG bytes written");
            if (expectedBytes > 0 && written != expectedBytes)
                throw new IOException("short JPEG write: " + written + "/" + expectedBytes + " bytes");

            verifyReadableJpeg(resolver, uri);
            return uri;
        } catch (Exception e) {
            deleteQuietly(resolver, uri);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getClass().getSimpleName() + ": " + safeMessage(e), e);
        }
    }

    static void publish(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("cannot publish null output URI");
        if (!MediaStore.AUTHORITY.equals(uri.getAuthority())) return; // SAF/document URI is already committed.
        if (Build.VERSION.SDK_INT < 29) return;

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        try {
            int changed = resolver.update(uri, values, null, null);
            if (changed > 0) {
                verifyReadableJpeg(resolver, uri);
                return;
            }

            // API 30 compatibility: preferred routes are non-pending and some OEM providers
            // legitimately return 0 for an IS_PENDING=0 no-op update.
            if (Build.VERSION.SDK_INT == 30) {
                int pending = queryPending(resolver, uri);
                if (pending == 0 || pending == -1) {
                    verifyReadableJpeg(resolver, uri);
                    return;
                }
                try {
                    Uri includePending = MediaStore.setIncludePending(uri);
                    changed = resolver.update(includePending, values, null, null);
                    if (changed > 0 || queryPending(resolver, uri) == 0) {
                        verifyReadableJpeg(resolver, uri);
                        return;
                    }
                } catch (Exception ignored) { }
            }
            throw new IOException("MediaStore did not publish the JPEG");
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getClass().getSimpleName() + ": " + safeMessage(e), e);
        }
    }

    static void abort(Context context, Uri uri) {
        if (uri == null) return;
        ContentResolver resolver = context.getContentResolver();
        deleteQuietly(resolver, uri);
        if (Build.VERSION.SDK_INT >= 29 && MediaStore.AUTHORITY.equals(uri.getAuthority())) {
            try { deleteQuietly(resolver, MediaStore.setIncludePending(uri)); } catch (Exception ignored) { }
        }
    }

    static String describe(Context context, Uri uri) {
        if (uri == null) return "no URI";
        long size = statSize(context.getContentResolver(), uri);
        return size > 0 ? uri + " • " + size + " bytes" : uri.toString();
    }

    static String selectedTreeLabel(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_SAVE_TREE_URI, "");
        if (raw == null || raw.trim().isEmpty()) return "AUTO (MediaStore)";
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(raw));
            String name = root == null ? null : root.getName();
            if (name != null && !name.trim().isEmpty()) return name;
        } catch (Exception ignored) { }
        return "SELECTED FOLDER";
    }

    private static String normalizeRelativePath(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) throw new IOException("MediaStore relative path is empty");
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.contains("//")) p = p.replace("//", "/");
        if (!p.endsWith("/")) p += "/";
        return p;
    }

    private static String safeDocumentSegment(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') out.append('_');
            else out.append(c);
        }
        String clean = out.toString().trim();
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) clean = fallback;
        if (clean.length() > 64) clean = clean.substring(0, 64).trim();
        return clean;
    }

    private static void validateJpegFile(File file, long length) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("temporary JPEG is missing");
        if (length < 4 || file.length() < 4) throw new IOException("JPEG is empty");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int a = in.read();
            int b = in.read();
            if (a != 0xff || b != 0xd8) throw new IOException("camera returned invalid JPEG data");
        }
    }

    private static void verifyReadableJpeg(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream in = new BufferedInputStream(resolver.openInputStream(uri))) {
            if (in == null) throw new IOException("cannot reopen saved JPEG");
            int a = in.read();
            int b = in.read();
            if (a != 0xff || b != 0xd8) throw new IOException("saved item is not a readable JPEG");
        } catch (SecurityException e) {
            throw new IOException("cannot reopen saved JPEG: " + safeMessage(e), e);
        }
    }

    private static int queryPending(ContentResolver resolver, Uri uri) {
        if (!MediaStore.AUTHORITY.equals(uri.getAuthority())) return -1;
        Uri queryUri = uri;
        try {
            if (Build.VERSION.SDK_INT >= 29) queryUri = MediaStore.setIncludePending(uri);
            try (Cursor c = resolver.query(queryUri, new String[]{MediaStore.Images.Media.IS_PENDING}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getInt(0);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static long statSize(ContentResolver resolver, Uri uri) {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null && pfd.getStatSize() >= 0) return pfd.getStatSize();
        } catch (Exception ignored) { }
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) { }
        return -1L;
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        if (uri == null) return;
        try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
    }

    private static IOException combine(String prefix, IOException first, IOException last) {
        String a = first == null ? "unknown first failure" : safeMessage(first);
        String b = last == null ? "unknown last failure" : safeMessage(last);
        return new IOException(prefix + " • first=" + a + " • last=" + b, last);
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty() ? "no detail" : m.trim();
    }
}
