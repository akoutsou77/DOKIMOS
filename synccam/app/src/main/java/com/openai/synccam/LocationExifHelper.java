package com.openai.synccam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.exifinterface.media.ExifInterface;

import java.util.Locale;

final class LocationExifHelper implements LocationListener {
    private final Activity activity;
    private final LocationManager manager;
    private volatile Location latest;
    private volatile boolean running;

    LocationExifHelper(Activity activity) {
        this.activity = activity;
        this.manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
    }

    boolean hasPermission() {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (!hasPermission() || running || manager == null) return;
        running = true;
        try {
            Location gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            latest = better(gps, net);
        } catch (Exception ignored) { }
        try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this); } catch (Exception ignored) { }
        try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this); } catch (Exception ignored) { }
    }

    void stop() {
        running = false;
        try { if (manager != null) manager.removeUpdates(this); } catch (Exception ignored) { }
    }

    Location getBestLocation() {
        if (!hasPermission()) return null;
        Location best = latest;
        try {
            Location gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            best = better(best, better(gps, net));
        } catch (Exception ignored) { }
        latest = best;
        return best;
    }

    String status() {
        if (!hasPermission()) return "GPS permission not granted";
        Location l = getBestLocation();
        if (l == null) return "GPS enabled • no fix yet";
        long ageMs = Math.max(0L, System.currentTimeMillis() - l.getTime());
        String accuracy = l.hasAccuracy() ? String.format(Locale.US, " ±%.0f m", l.getAccuracy()) : "";
        if (ageMs < 10_000L) return "GPS ready" + accuracy;
        return String.format(Locale.US, "GPS last fix %.0f s ago%s", ageMs / 1000.0, accuracy);
    }

    boolean embed(Uri uri, boolean includeGps, String lensLabel, float focalLengthMm, String deviceId) {
        if (uri == null) return false;
        Location location = includeGps ? getBestLocation() : null;
        try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) return false;
            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
            if (includeGps && location != null) exif.setGpsInfo(location);
            if (focalLengthMm > 0f) {
                long milli = Math.max(1L, Math.round(focalLengthMm * 1000.0));
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, milli + "/1000");
            }
            if (lensLabel != null && !lensLabel.isEmpty()) exif.setAttribute(ExifInterface.TAG_LENS_MODEL, lensLabel);
            String comment = "SyncCam device=" + deviceId + (lensLabel == null ? "" : "; lens=" + lensLabel);
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment);
            exif.saveAttributes();
            return !includeGps || location != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (location != null) latest = better(location, latest);
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private static Location better(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        long dt = a.getTime() - b.getTime();
        if (dt > 10_000L) return a;
        if (dt < -10_000L) return b;
        if (a.hasAccuracy() && b.hasAccuracy()) return a.getAccuracy() <= b.getAccuracy() ? a : b;
        if (a.hasAccuracy()) return a;
        if (b.hasAccuracy()) return b;
        return a.getTime() >= b.getTime() ? a : b;
    }
}
