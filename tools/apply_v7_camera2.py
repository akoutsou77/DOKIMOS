from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "synccam/app/src/main/java/com/openai/synccam/MainActivity.java"
MANIFEST = ROOT / "synccam/app/src/main/AndroidManifest.xml"
GRADLE = ROOT / "synccam/app/build.gradle"

s = MAIN.read_text(encoding="utf-8")

def must_replace(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f"v7 migration anchor missing: {label}")
    s = s.replace(old, new, 1)

must_replace("import android.hardware.Camera;\n", "", "legacy Camera import")
must_replace("import android.graphics.Paint;\n", "import android.graphics.Paint;\nimport android.graphics.SurfaceTexture;\n", "SurfaceTexture import")
must_replace("import android.view.SurfaceView;\n", "import android.view.SurfaceView;\nimport android.view.TextureView;\n", "TextureView import")
must_replace("public class MainActivity extends Activity implements SurfaceHolder.Callback {", "public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {", "class callback")
must_replace("    private SurfaceView surface;", "    private TextureView surface;", "surface field")
must_replace(
    "    private TextView syncValue, peerValue, hotspotInfo, photoSync, autoCaptureButton, cameraSettingsButton;",
    "    private TextView syncValue, peerValue, hotspotInfo, photoSync, autoCaptureButton, cameraSettingsButton, lensButton;",
    "lens button field")
must_replace(
    "    private Camera camera;\n    private int cameraId = 0;",
    "    private Camera2Controller camera2;\n    private LocationExifHelper locationExif;\n    private volatile boolean storeGpsInJpeg = false;",
    "Camera2 fields")
must_replace("    private final Map<Integer, String> cameraParameterProfiles = new HashMap<>();\n", "", "legacy profiles")
must_replace(
    "        surface = new SurfaceView(this);\n        surface.getHolder().addCallback(this);\n        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));",
    "        surface = new TextureView(this);\n        surface.setSurfaceTextureListener(this);\n        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));",
    "TextureView creation")
must_replace(
    "        TextView flip = smallButton(\"↻\\nCAMERA\");\n        flip.setGravity(Gravity.CENTER);\n        shutterRow.addView(flip, new LinearLayout.LayoutParams(dp(70), dp(60)));",
    "        lensButton = smallButton(\"LENS\\nSELECT\");\n        lensButton.setGravity(Gravity.CENTER);\n        shutterRow.addView(lensButton, new LinearLayout.LayoutParams(dp(70), dp(60)));",
    "lens UI")
must_replace("        flip.setOnClickListener(v -> flip());", "        lensButton.setOnClickListener(v -> showLensSelector());", "lens click")

start = s.index("    private synchronized void showCameraSettings() {")
end = s.index("    private void refreshDeviceSyncList(", start)
new_settings = r'''    private void showCameraSettings() {
        if (camera2 == null) {
            Toast.makeText(this, "Camera2 is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        camera2.showSettingsDialog(this, storeGpsInJpeg, (gpsEnabled, summary) -> {
            storeGpsInJpeg = gpsEnabled;
            if (gpsEnabled && locationExif != null && !locationExif.hasPermission()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ);
            }
            if (locationExif != null) locationExif.start();
            String gps = gpsEnabled ? (locationExif == null ? "GPS pending" : locationExif.status()) : "GPS EXIF off";
            setStatus(summary + " • " + gps);
        });
    }

'''
s = s[:start] + new_settings + s[end:]

must_replace(
    "        if (Build.VERSION.SDK_INT <= 32 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)\n            p.add(Manifest.permission.ACCESS_FINE_LOCATION);",
    "        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)\n            p.add(Manifest.permission.ACCESS_COARSE_LOCATION);\n        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)\n            p.add(Manifest.permission.ACCESS_FINE_LOCATION);",
    "location permissions")

old_start_core = '''    private void startCore() {
        if (net == null) {
            net = new Net();
            net.start();
        }
        if (surfaceReady) openCamera();
    }
'''
new_start_core = '''    private void startCore() {
        if (net == null) {
            net = new Net();
            net.start();
        }
        if (locationExif == null) locationExif = new LocationExifHelper(this);
        locationExif.start();
        if (camera2 == null) {
            camera2 = new Camera2Controller(this, surface, new Camera2Controller.Listener() {
                @Override public void onReady(String lensLabel) {
                    updateLensButton();
                    setStatus("Camera2 ready • " + lensLabel);
                }
                @Override public void onError(String message) { setStatus(message); }
            });
            updateLensButton();
        }
        if (surfaceReady) openCamera();
    }
'''
must_replace(old_start_core, new_start_core, "startCore")

surface_start = s.index("    @Override public void surfaceCreated(SurfaceHolder h) {")
open_start = s.index("    private synchronized void openCamera() {", surface_start)
new_surface = r'''    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surfaceReady = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (camera2 == null) startCore();
            else openCamera();
        }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (camera2 != null) camera2.onViewSizeChanged();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        surfaceReady = false;
        closeCamera();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

'''
s = s[:surface_start] + new_surface + s[open_start:]

open_start = s.index("    private synchronized void openCamera() {")
host_start = s.index("    private void hostAndHotspot() {", open_start)
new_camera_methods = r'''    private synchronized void openCamera() {
        if (camera2 != null && surfaceReady) camera2.open();
    }

    private synchronized void closeCamera() {
        if (camera2 != null) camera2.close();
    }

    private void showLensSelector() {
        if (camera2 == null) {
            Toast.makeText(this, "Camera2 is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        camera2.showLensDialog(this, () -> {
            updateLensButton();
            setStatus("Selected lens • " + camera2.getSelectedLensLabel());
        });
    }

    private void updateLensButton() {
        if (lensButton == null) return;
        String label = camera2 == null ? "SELECT" : camera2.getSelectedLensLabel();
        int bullet = label.indexOf(" • ");
        if (bullet > 0) label = label.substring(0, bullet);
        final String shown = label.toUpperCase(Locale.US);
        ui(() -> lensButton.setText("LENS\\n" + shown));
    }

'''
s = s[:open_start] + new_camera_methods + s[host_start:]

capture_start = s.index("    private synchronized void takePicture(int seq, String source) {")
save_start = s.index("    private String saveJpeg(", capture_start)
new_capture = r'''    private synchronized void takePicture(int seq, String source) {
        if (camera2 == null || !camera2.isReady() || captureInProgress) return;
        captureInProgress = true;
        activeCountdownSeq = -1;
        flashOverlay.setAlpha(0.85f);
        flashOverlay.animate().alpha(0f).setDuration(260).start();
        countdownBadge.setVisibility(View.GONE);
        final long callNs = SystemClock.elapsedRealtimeNanos();
        camera2.capture(new Camera2Controller.CaptureCallback() {
            @Override public void onCaptured(byte[] data, long sensorTimestampNs, Camera2Controller.LensInfo lens) {
                try {
                    String uri = saveJpeg(data, seq, lens);
                    String lensName = lens == null ? "camera" : lens.label;
                    setStatus("Saved capture #" + seq + " • " + source.toLowerCase(Locale.US) + " • " + lensName);
                    if (net != null) net.report(seq, callNs, uri);
                } finally {
                    captureInProgress = false;
                }
            }

            @Override public void onError(String message) {
                captureInProgress = false;
                setStatus(message);
            }
        });
    }

'''
s = s[:capture_start] + new_capture + s[save_start:]

save_start = s.index("    private String saveJpeg(")
destroy_start = s.index("    @Override protected void onDestroy()", save_start)
new_save = r'''    private String saveJpeg(byte[] data, int seq, Camera2Controller.LensInfo lens) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String name = "SyncCam_" + stamp + "_S" + seq + "_" + deviceId + ".jpg";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29)
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SyncCam");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return "";
            try (OutputStream o = getContentResolver().openOutputStream(u)) {
                if (o == null) return "";
                o.write(data);
            }
            if (locationExif != null) {
                String lensName = lens == null ? "" : lens.label;
                float focal = lens == null ? 0f : lens.focalLengthMm;
                locationExif.embed(u, storeGpsInJpeg, lensName, focal, deviceId);
            }
            String uri = u.toString();
            if (photoTransfer != null) photoTransfer.recordLocal(seq, name, uri);
            return uri;
        } catch (Exception e) {
            setStatus("Save failed • " + e.getMessage());
            return "";
        }
    }

'''
s = s[:save_start] + new_save + s[destroy_start:]

must_replace(
    "        closeCamera();\n        scheduler.shutdownNow();",
    "        closeCamera();\n        if (locationExif != null) locationExif.stop();\n        if (camera2 != null) camera2.shutdown();\n        scheduler.shutdownNow();",
    "destroy camera helpers")

MAIN.write_text(s, encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
manifest = manifest.replace(
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />',
    '    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />')
MANIFEST.write_text(manifest, encoding="utf-8")

gradle = GRADLE.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode 6", "versionCode 7").replace("versionName '6.0.0-auto-camera'", "versionName '7.0.0-camera2-gps'")
if "androidx.exifinterface:exifinterface" not in gradle:
    gradle = gradle.rstrip() + '\n\ndependencies {\n    implementation "androidx.exifinterface:exifinterface:1.4.2"\n}\n'
GRADLE.write_text(gradle, encoding="utf-8")

print("Applied SyncCam v7 Camera2 multi-lens + GPS EXIF migration")
