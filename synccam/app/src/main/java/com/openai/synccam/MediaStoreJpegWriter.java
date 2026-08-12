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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class MediaStoreJpegWriter {
    private MediaStoreJpegWriter() { }

    static Uri writePending(Context context, byte[] data, String displayName, String relativePath, File legacyDir) throws IOException {
        if (data == null || data.length < 4) throw new IOException("camera returned an empty JPEG");
        if ((data[0] & 0xff) != 0xff || (data[1] & 0xff) != 0xd8)
            throw new IOException("camera returned invalid JPEG data");
        return writePending(context, new java.io.ByteArrayInputStream(data), displayName, relativePath, legacyDir, data.length);
    }

    static Uri writePending(Context context, InputStream input, String displayName, String relativePath, File legacyDir) throws IOException {
        return writePending(context, input, displayName, relativePath, legacyDir, -1L);
    }

    private static Uri writePending(Context context, InputStream input, String displayName, String relativePath, File legacyDir, long expectedBytes) throws IOException {
        if (input == null) throw new IOException("JPEG input stream is null");
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) throw new IOException("shared storage is not writable (state=" + state + ")");

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        Uri collection;
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            if (legacyDir == null) throw new IOException("legacy output directory is missing");
            if (!legacyDir.exists() && !legacyDir.mkdirs()) throw new IOException("cannot create " + legacyDir.getAbsolutePath());
            values.put(MediaStore.Images.Media.DATA, new File(legacyDir, displayName).getAbsolutePath());
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }

        Uri uri = null;
        try {
            uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("MediaStore insert returned null");

            long written = 0L;
            try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
                if (raw == null) throw new IOException("MediaStore output stream is null");
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = input.read(buffer)) >= 0) {
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

            long stored = statSize(resolver, uri);
            if (stored == 0) throw new IOException("MediaStore reports a zero-byte JPEG after write");
            return uri;
        } catch (Exception e) {
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getClass().getSimpleName() + ": " + safeMessage(e), e);
        }
    }

    static void publish(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("cannot publish null MediaStore URI");
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            int changed = context.getContentResolver().update(uri, values, null, null);
            if (changed <= 0) throw new IOException("MediaStore did not publish the JPEG");
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getClass().getSimpleName() + ": " + safeMessage(e), e);
        }
    }

    static void abort(Context context, Uri uri) {
        if (uri == null) return;
        try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
    }

    static String describe(Context context, Uri uri) {
        if (uri == null) return "no URI";
        long size = statSize(context.getContentResolver(), uri);
        return size > 0 ? uri + " • " + size + " bytes" : uri.toString();
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

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? "no detail" : m.trim();
    }
}
