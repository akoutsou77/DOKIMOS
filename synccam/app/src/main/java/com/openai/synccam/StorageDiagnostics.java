package com.openai.synccam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

final class StorageDiagnostics {
    private StorageDiagnostics() { }

    private interface TestBody { String run() throws Exception; }

    static String run(Context context, byte[] jpeg, String projectName, String deviceId) {
        StringBuilder report = new StringBuilder();
        report.append("SyncCam STORAGE DIAGNOSTICS\n");
        report.append("Build: 7.6.3-storage-diagnostics-test\n");
        report.append("Android SDK: ").append(Build.VERSION.SDK_INT)
                .append(" / ").append(Build.VERSION.RELEASE).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        report.append("Target SDK: ").append(context.getApplicationInfo().targetSdkVersion).append('\n');
        report.append("External state: ").append(Environment.getExternalStorageState()).append('\n');
        report.append("JPEG bytes: ").append(jpeg == null ? -1 : jpeg.length).append('\n');
        String treeRaw = context.getSharedPreferences(MediaStoreJpegWriter.PREFS, Context.MODE_PRIVATE)
                .getString(MediaStoreJpegWriter.PREF_SAVE_TREE_URI, "");
        report.append("SAVE ROOT: ").append(treeRaw == null || treeRaw.isEmpty() ? "NOT SELECTED" : treeRaw).append("\n\n");

        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) {
            report.append("FATAL: camera did not return a valid JPEG.\n");
            return report.toString();
        }

        final String stamp = Long.toString(System.currentTimeMillis());
        final String safeProject = safeSegment(projectName, "Session");
        final String safeId = safeSegment(deviceId, "DEVICE");

        test(report, "T01", "internal cache direct FileOutputStream", () -> {
            File dir = new File(context.getCacheDir(), "SyncCamDiag");
            ensureDir(dir);
            File f = new File(dir, "T01_" + stamp + ".jpg");
            writeFile(f, jpeg);
            verifyFile(f);
            return f.getAbsolutePath() + " • " + f.length() + " bytes";
        });

        test(report, "T02", "app-specific external Pictures direct file", () -> {
            File base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (base == null) throw new Exception("getExternalFilesDir(Pictures) returned null");
            File dir = new File(base, "SyncCamDiag/T02");
            ensureDir(dir);
            File f = new File(dir, "T02_" + stamp + ".jpg");
            writeFile(f, jpeg);
            verifyFile(f);
            return f.getAbsolutePath() + " • " + f.length() + " bytes";
        });

        test(report, "T03", "MediaStore EXTERNAL + RELATIVE_PATH, no pending", () ->
                mediaStore(context, jpeg, "T03_" + stamp + ".jpg", "Pictures/SyncCamDiag/T03/",
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false));

        test(report, "T04", "MediaStore PRIMARY volume + RELATIVE_PATH, no pending", () -> {
            if (Build.VERSION.SDK_INT < 29) throw new Exception("requires API 29+");
            return mediaStore(context, jpeg, "T04_" + stamp + ".jpg", "Pictures/SyncCamDiag/T04/",
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), false);
        });

        test(report, "T05", "MediaStore EXTERNAL + RELATIVE_PATH + IS_PENDING", () ->
                mediaStore(context, jpeg, "T05_" + stamp + ".jpg", "Pictures/SyncCamDiag/T05/",
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true));

        test(report, "T06", "MediaStore PRIMARY volume + RELATIVE_PATH + IS_PENDING", () -> {
            if (Build.VERSION.SDK_INT < 29) throw new Exception("requires API 29+");
            return mediaStore(context, jpeg, "T06_" + stamp + ".jpg", "Pictures/SyncCamDiag/T06/",
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), true);
        });

        test(report, "T07", "MediaStore EXTERNAL without RELATIVE_PATH", () ->
                mediaStore(context, jpeg, "T07_" + stamp + ".jpg", null,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false));

        test(report, "T08", "selected SAVE ROOT direct JPEG", () -> {
            Uri tree = selectedTree(context);
            return safWrite(context, jpeg, tree, new String[0], "T08_" + stamp + ".jpg");
        });

        test(report, "T09", "selected SAVE ROOT nested Project/HOST folder", () -> {
            Uri tree = selectedTree(context);
            return safWrite(context, jpeg, tree,
                    new String[]{safeProject, "HOST_" + safeId, "DIAGNOSTICS"}, "T09_" + stamp + ".jpg");
        });

        test(report, "T10", "current SyncCam writer", () -> {
            Uri uri = null;
            try {
                String relative = Environment.DIRECTORY_PICTURES + "/SyncCam/" + safeProject + "/HOST_" + safeId;
                File legacy = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "SyncCam/" + safeProject + "/HOST_" + safeId);
                uri = MediaStoreJpegWriter.writePending(context, jpeg, "T10_" + stamp + ".jpg", relative, legacy);
                MediaStoreJpegWriter.publish(context, uri);
                return MediaStoreJpegWriter.describe(context, uri);
            } catch (Exception e) {
                MediaStoreJpegWriter.abort(context, uri);
                throw e;
            }
        });

        test(report, "T11", "raw public Pictures FileOutputStream (expected blocked on scoped storage)", () -> {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SyncCamDiag/T11");
            ensureDir(dir);
            File f = new File(dir, "T11_" + stamp + ".jpg");
            writeFile(f, jpeg);
            verifyFile(f);
            return f.getAbsolutePath() + " • " + f.length() + " bytes";
        });

        report.append("\nINTERPRETATION\n");
        report.append("• T01 fail = camera/JPEG or internal storage problem.\n");
        report.append("• T01 pass + T02 fail = external-volume problem.\n");
        report.append("• T03-T07 fail = MediaStore/provider problem.\n");
        report.append("• T08/T09 pass = SAVE ROOT is the reliable backend for this device.\n");
        report.append("• T08/T09 fail after selecting SAVE ROOT = system document-provider/permission problem.\n");
        report.append("• T10 is the exact current SyncCam path.\n");
        report.append("• T11 failure is normal on Android 11+ with scoped storage.\n");
        return report.toString();
    }

    private static void test(StringBuilder report, String id, String label, TestBody body) {
        try {
            String detail = body.run();
            report.append(id).append(" PASS • ").append(label).append("\n    ")
                    .append(detail == null ? "OK" : detail).append("\n");
        } catch (Throwable t) {
            report.append(id).append(" FAIL • ").append(label).append("\n    ")
                    .append(t.getClass().getSimpleName()).append(": ")
                    .append(message(t)).append("\n");
        }
    }

    private static String mediaStore(Context context, byte[] jpeg, String name, String relativePath,
                                     Uri collection, boolean pending) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29 && relativePath != null) values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
        if (Build.VERSION.SDK_INT >= 29 && pending) values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(collection, values);
            if (uri == null) throw new Exception("insert returned null: " + collection);
            try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
                if (raw == null) throw new Exception("openOutputStream returned null: " + uri);
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    out.write(jpeg);
                    out.flush();
                }
            }
            verifyUri(resolver, uri);
            String extra = "";
            if (Build.VERSION.SDK_INT >= 29 && pending) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                int changed = resolver.update(uri, done, null, null);
                int after = queryPending(resolver, uri);
                if (after == 1) throw new Exception("IS_PENDING remained 1 after publish; update count=" + changed);
                extra = " • publishChanged=" + changed + " • pendingAfter=" + after;
                verifyUri(resolver, uri);
            }
            return uri + " • readable JPEG" + extra;
        } catch (Exception e) {
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
            }
            throw e;
        }
    }

    private static Uri selectedTree(Context context) throws Exception {
        String raw = context.getSharedPreferences(MediaStoreJpegWriter.PREFS, Context.MODE_PRIVATE)
                .getString(MediaStoreJpegWriter.PREF_SAVE_TREE_URI, "");
        if (raw == null || raw.trim().isEmpty()) throw new Exception("SAVE ROOT not selected");
        Uri uri = Uri.parse(raw);
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        if (root == null) throw new Exception("DocumentFile.fromTreeUri returned null");
        if (!root.canWrite()) throw new Exception("selected tree reports canWrite=false");
        return uri;
    }

    private static String safWrite(Context context, byte[] jpeg, Uri treeUri, String[] directories, String name) throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
        if (dir == null) throw new Exception("selected tree is unavailable");
        for (String d : directories) {
            String safe = safeSegment(d, "Folder");
            DocumentFile next = dir.findFile(safe);
            if (next != null && !next.isDirectory()) throw new Exception("path component is a file: " + safe);
            if (next == null) next = dir.createDirectory(safe);
            if (next == null) throw new Exception("createDirectory failed: " + safe);
            dir = next;
        }
        DocumentFile existing = dir.findFile(name);
        if (existing != null) existing.delete();
        DocumentFile f = dir.createFile("image/jpeg", name);
        if (f == null) throw new Exception("createFile returned null");
        try {
            Uri uri = f.getUri();
            try (OutputStream raw = context.getContentResolver().openOutputStream(uri, "w")) {
                if (raw == null) throw new Exception("openOutputStream returned null");
                try (OutputStream out = new BufferedOutputStream(raw)) {
                    out.write(jpeg);
                    out.flush();
                }
            }
            verifyUri(context.getContentResolver(), uri);
            return uri + " • readable JPEG";
        } catch (Exception e) {
            try { f.delete(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static void writeFile(File f, byte[] jpeg) throws Exception {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            out.write(jpeg);
            out.flush();
        }
    }

    private static void verifyFile(File f) throws Exception {
        if (!f.isFile() || f.length() < 4) throw new Exception("file missing or empty");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            int a = in.read();
            int b = in.read();
            if (a != 0xff || b != 0xd8) throw new Exception("saved file is not JPEG");
        }
    }

    private static void verifyUri(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = new BufferedInputStream(resolver.openInputStream(uri))) {
            if (in == null) throw new Exception("openInputStream returned null");
            int a = in.read();
            int b = in.read();
            if (a != 0xff || b != 0xd8) throw new Exception("reopened data is not JPEG");
        }
    }

    private static int queryPending(ContentResolver resolver, Uri uri) {
        if (Build.VERSION.SDK_INT < 29) return -1;
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.Images.Media.IS_PENDING}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) { }
        return -1;
    }

    private static void ensureDir(File dir) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdirs failed: " + dir.getAbsolutePath());
        if (!dir.isDirectory()) throw new Exception("not a directory: " + dir.getAbsolutePath());
    }

    private static String safeSegment(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') out.append('_');
            else out.append(c);
        }
        String clean = out.toString().trim();
        if (clean.isEmpty()) clean = fallback;
        if (clean.length() > 64) clean = clean.substring(0, 64).trim();
        return clean;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? "no detail" : m.trim();
    }
}
