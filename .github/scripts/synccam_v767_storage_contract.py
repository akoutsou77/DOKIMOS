from pathlib import Path

ROOT = Path('.')
MAIN = ROOT / 'synccam/app/src/main/java/com/openai/synccam/MainActivity.java'
PHOTO = ROOT / 'synccam/app/src/main/java/com/openai/synccam/PhotoTransfer.java'
WRITER = ROOT / 'synccam/app/src/main/java/com/openai/synccam/MediaStoreJpegWriter.java'
LAYOUT = ROOT / 'synccam/app/src/main/java/com/openai/synccam/StorageLayout.java'
GRADLE = ROOT / 'synccam/app/build.gradle'


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)

layout = r'''package com.openai.synccam;

import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Single source of truth for SyncCam public-storage names and paths. */
final class StorageLayout {
    static final String ROOT_FOLDER = "SyncCam";

    private StorageLayout() { }

    static String project(String raw) {
        return safeSegment(raw, "Session");
    }

    static String device(String raw, String fallback) {
        return safeSegment(raw, fallback).replace(' ', '_');
    }

    static String roleFolder(boolean host, String deviceId) {
        return (host ? "HOST_" : "PHONE_") + device(deviceId, host ? "HOST" : "PHONE");
    }

    static String relativeDevicePath(String project, boolean host, String deviceId) {
        return Environment.DIRECTORY_PICTURES + "/" + ROOT_FOLDER + "/" + project(project) + "/" + roleFolder(host, deviceId);
    }

    static File legacyDeviceDir(String project, boolean host, String deviceId) {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ROOT_FOLDER + "/" + project(project) + "/" + roleFolder(host, deviceId));
    }

    static String captureFileName(String stamp, int sequence, String deviceId) {
        String safeStamp = safeFileToken(stamp, "capture");
        String safeId = device(deviceId, "DEVICE");
        return "SyncCam_" + safeStamp + "_S" + Math.max(0, sequence) + "_" + safeId + ".jpg";
    }

    /** Preserve the client's original capture-time filename when it is a valid SyncCam JPEG. */
    static String incomingFileName(String originalName, String remoteDeviceId, int sequence) {
        String clean = safeFileName(originalName);
        String lower = clean.toLowerCase(Locale.US);
        if (clean.startsWith("SyncCam_") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            return clean;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return captureFileName(stamp, sequence, remoteDeviceId);
    }

    static String safeSegment(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') out.append('_');
            else out.append(c);
        }
        String clean = collapseUnderscores(out.toString().trim());
        clean = trimTrailingDotsAndSpaces(clean);
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) clean = fallback;
        if (clean.length() > 64) clean = trimTrailingDotsAndSpaces(clean.substring(0, 64).trim());
        if (clean.isEmpty()) clean = fallback;
        return clean;
    }

    private static String safeFileName(String raw) {
        String s = raw == null ? "" : raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') out.append('_');
            else out.append(c);
        }
        String clean = collapseUnderscores(out.toString().trim());
        clean = trimTrailingDotsAndSpaces(clean);
        if (clean.length() > 160) clean = trimTrailingDotsAndSpaces(clean.substring(0, 160).trim());
        return clean;
    }

    private static String safeFileToken(String raw, String fallback) {
        String s = safeFileName(raw);
        if (s.isEmpty()) return fallback;
        return s.replace('.', '_');
    }

    private static String collapseUnderscores(String value) {
        String out = value;
        while (out.contains("__")) out = out.replace("__", "_");
        return out;
    }

    private static String trimTrailingDotsAndSpaces(String value) {
        String out = value;
        while (!out.isEmpty() && (out.endsWith(".") || out.endsWith(" "))) out = out.substring(0, out.length() - 1);
        return out;
    }
}
'''
LAYOUT.write_text(layout, encoding='utf-8')

main = MAIN.read_text(encoding='utf-8')
main = replace_once(main,
'''        int seq = sequence++;
        long target = SystemClock.elapsedRealtimeNanos() + LEAD_NS;
        net.sendCapture(seq, target);
        String projectSnapshot = projectName;
        String groupSnapshot = net.groupCode;
        scheduleCapture(seq, target, "HOST", projectSnapshot, groupSnapshot);''',
'''        int seq = sequence++;
        long target = SystemClock.elapsedRealtimeNanos() + LEAD_NS;
        String projectSnapshot = projectName;
        String groupSnapshot = net.groupCode;
        net.sendCapture(seq, target, projectSnapshot);
        scheduleCapture(seq, target, "HOST", projectSnapshot, groupSnapshot);''',
'host trigger project propagation')

main = replace_once(main,
'''    private String safeProjectName(String raw) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') out.append('_');
            else out.append(c);
        }
        String clean = out.toString().trim();
        while (clean.contains("__")) clean = clean.replace("__", "_");
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) clean = "Session";
        if (clean.length() > 64) clean = clean.substring(0, 64).trim();
        return clean;
    }''',
'''    private String safeProjectName(String raw) {
        return StorageLayout.project(raw);
    }''',
'central project sanitisation')

old_save = '''            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String name = "SyncCam_" + stamp + "_S" + seq + "_" + deviceId + ".jpg";
            String storageProject = safeProjectName(projectSnapshot == null ? projectName : projectSnapshot);
            String safeId = safeProjectName(deviceId).replace(" ", "_");
            String relative = Environment.DIRECTORY_PICTURES + "/SyncCam";
            if (hostCapture) relative += "/" + storageProject + "/HOST_" + safeId;
            File legacyDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    hostCapture ? "SyncCam/" + storageProject + "/HOST_" + safeId : "SyncCam");'''
new_save = '''            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String fallbackProject = hostCapture ? projectName : "Session_" + (groupSnapshot == null ? "" : groupSnapshot);
            String storageProject = StorageLayout.project(projectSnapshot == null ? fallbackProject : projectSnapshot);
            String name = StorageLayout.captureFileName(stamp, seq, deviceId);
            String relative = StorageLayout.relativeDevicePath(storageProject, hostCapture, deviceId);
            File legacyDir = StorageLayout.legacyDeviceDir(storageProject, hostCapture, deviceId);'''
if main.count(old_save) != 2:
    raise SystemExit(f'capture storage block: expected 2 matches, found {main.count(old_save)}')
main = main.replace(old_save, new_save)

main = replace_once(main,
'''        void sendCapture(int seq, long target) {
            String m = "CAPTURE|" + groupCode + "|" + seq + "|" + target;
            sendCaptureBurst(m);
            io.schedule(() -> sendCaptureBurst(m), 70, TimeUnit.MILLISECONDS);
            io.schedule(() -> sendCaptureBurst(m), 160, TimeUnit.MILLISECONDS);
        }''',
'''        void sendCapture(int seq, long target, String projectSnapshot) {
            String captureProject = StorageLayout.project(projectSnapshot);
            String m = "CAPTURE|" + groupCode + "|" + seq + "|" + target + "|" + captureProject;
            sendCaptureBurst(m);
            io.schedule(() -> sendCaptureBurst(m), 70, TimeUnit.MILLISECONDS);
            io.schedule(() -> sendCaptureBurst(m), 160, TimeUnit.MILLISECONDS);
        }''',
'capture packet project field')

main = replace_once(main,
'''                    } else if ("CAPTURE".equals(p[0]) && p.length >= 4) {
                        int seq = Integer.parseInt(p[2]);
                        if (captureSeen.put(seq, true) != null) return;
                        hostAddr = from;
                        long hostTarget = Long.parseLong(p[3]);
                        long localTarget = hostTarget - hostMinusClient;
                        String captureGroup = groupCode;
                        scheduleCapture(seq, localTarget, "CLIENT", null, captureGroup);
                        setStatus("Capture #" + seq + " received • shutter armed");''',
'''                    } else if ("CAPTURE".equals(p[0]) && p.length >= 4) {
                        int seq = Integer.parseInt(p[2]);
                        if (captureSeen.put(seq, true) != null) return;
                        hostAddr = from;
                        long hostTarget = Long.parseLong(p[3]);
                        long localTarget = hostTarget - hostMinusClient;
                        String captureGroup = groupCode;
                        String captureProject = p.length >= 5 ? StorageLayout.project(p[4]) : StorageLayout.project("Session_" + captureGroup);
                        scheduleCapture(seq, localTarget, "CLIENT", captureProject, captureGroup);
                        setStatus("Capture #" + seq + " received • " + captureProject + " • shutter armed");''',
'client project routing')
MAIN.write_text(main, encoding='utf-8')

photo = PHOTO.read_text(encoding='utf-8')
photo = replace_once(photo,
'''            int requestId = in.readInt();
            int sequence = in.readInt();
            in.readUTF(); // original name; host uses a deterministic safe name''',
'''            int requestId = in.readInt();
            int sequence = in.readInt();
            String originalName = in.readUTF(); // preserve capture-time filename after sanitisation''',
'preserve original incoming filename')
photo = replace_once(photo,
'''            String uri = saveIncoming(in, remoteId, sequence, project);''',
'''            String uri = saveIncoming(in, remoteId, sequence, project, originalName);''',
'pass incoming filename')
photo = replace_once(photo,
'''    private String saveIncoming(InputStream in, String remoteId, int sequence, String project) {
        Uri u = null;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String safeId = safeSegment(remoteId, "REMOTE").replace(" ", "_");
            String safeProject = safeSegment(project, "Session");
            String name = "SyncCam_" + safeId + "_S" + sequence + "_" + stamp + ".jpg";
            String relative = Environment.DIRECTORY_PICTURES + "/SyncCam/" + safeProject + "/PHONE_" + safeId;
            File legacyDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "SyncCam/" + safeProject + "/PHONE_" + safeId);''',
'''    private String saveIncoming(InputStream in, String remoteId, int sequence, String project, String originalName) {
        Uri u = null;
        try {
            String safeId = StorageLayout.device(remoteId, "REMOTE");
            String safeProject = StorageLayout.project(project);
            String name = StorageLayout.incomingFileName(originalName, safeId, sequence);
            String relative = StorageLayout.relativeDevicePath(safeProject, false, safeId);
            File legacyDir = StorageLayout.legacyDeviceDir(safeProject, false, safeId);''',
'host incoming storage layout')
photo = replace_once(photo,
'''    private static String safeSegment(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') out.append('_');
            else out.append(c);
        }
        String clean = out.toString().trim();
        while (clean.contains("__")) clean = clean.replace("__", "_");
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) clean = fallback;
        if (clean.length() > 64) clean = clean.substring(0, 64).trim();
        return clean;
    }''',
'''    private static String safeSegment(String raw, String fallback) {
        return StorageLayout.safeSegment(raw, fallback);
    }''',
'central transfer sanitisation')
PHOTO.write_text(photo, encoding='utf-8')

writer = WRITER.read_text(encoding='utf-8')
writer = replace_once(writer,
'''        String picturesPrefix = Environment.DIRECTORY_PICTURES + "/";
        if (subPath.startsWith(picturesPrefix)) subPath = subPath.substring(picturesPrefix.length());
        if (subPath.equals("SyncCam") || subPath.equals("SyncCam/")) subPath = "";
        else if (subPath.startsWith("SyncCam/")) subPath = subPath.substring("SyncCam/".length());
        while (subPath.endsWith("/")) subPath = subPath.substring(0, subPath.length() - 1);''',
'''        String picturesPrefix = Environment.DIRECTORY_PICTURES + "/";
        if (subPath.startsWith(picturesPrefix)) subPath = subPath.substring(picturesPrefix.length());

        // If the user selected Pictures/SyncCam, that directory is already the SyncCam root.
        // If the user selected Pictures (or any other parent), retain the SyncCam component so
        // the invariant <selected-parent>/SyncCam/<Project>/<role> is never lost.
        String rootName = root.getName();
        boolean rootIsSyncCam = rootName != null && StorageLayout.ROOT_FOLDER.equalsIgnoreCase(rootName.trim());
        if (rootIsSyncCam) {
            if (subPath.equals(StorageLayout.ROOT_FOLDER) || subPath.equals(StorageLayout.ROOT_FOLDER + "/")) subPath = "";
            else if (subPath.startsWith(StorageLayout.ROOT_FOLDER + "/"))
                subPath = subPath.substring((StorageLayout.ROOT_FOLDER + "/").length());
        }
        while (subPath.endsWith("/")) subPath = subPath.substring(0, subPath.length() - 1);''',
'SAF root awareness')
WRITER.write_text(writer, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = replace_once(gradle, "versionCode 19", "versionCode 20", 'version code')
gradle = replace_once(gradle, "versionName '7.6.6-direct-camera-scheduler'", "versionName '7.6.7-storage-contract'", 'version name')
GRADLE.write_text(gradle, encoding='utf-8')

print('SyncCam v7.6.7 storage contract patch applied')
