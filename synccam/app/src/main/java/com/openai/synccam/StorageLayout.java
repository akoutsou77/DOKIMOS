package com.openai.synccam;

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
