package com.openai.synccam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Camera2Controller {
    interface Listener {
        void onReady(String lensLabel);
        void onError(String message);
    }

    interface CaptureCallback {
        void onCaptured(byte[] jpeg, long sensorTimestampNs, LensInfo lens);
        void onError(String message);
    }

    interface SettingsCallback {
        void onApplied(boolean gpsEnabled, String summary);
    }

    static final class LensInfo {
        final String logicalId;
        final String physicalId;
        final int facing;
        final float focalLengthMm;
        final String label;
        final String key;

        LensInfo(String logicalId, String physicalId, int facing, float focalLengthMm, String label) {
            this.logicalId = logicalId;
            this.physicalId = physicalId;
            this.facing = facing;
            this.focalLengthMm = focalLengthMm;
            this.label = label;
            this.key = logicalId + "|" + (physicalId == null ? "AUTO" : physicalId);
        }

        boolean isFront() { return facing == CameraCharacteristics.LENS_FACING_FRONT; }
    }

    static final class Settings {
        Size pictureSize;
        int afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        int awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        String flash = "OFF";
        int exposureComp = 0;
        float zoomRatio = 1.0f;
        int jpegQuality = 95;
        boolean aeLock = false;
        boolean awbLock = false;
        boolean manualExposure = false;
        int iso = 100;
        long exposureTimeNs = 10_000_000L;
        boolean manualFocus = false;
        float focusDistance = 0f;
    }

    private final Activity activity;
    private final TextureView textureView;
    private final CameraManager manager;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("SyncCam-Camera2");
    private Handler handler;
    private final ArrayList<LensInfo> lenses = new ArrayList<>();
    private final Map<String, Settings> profiles = new HashMap<>();

    private volatile int selectedIndex = 0;
    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private volatile ImageReader imageReader;
    private volatile Surface previewSurface;
    private volatile CaptureRequest.Builder previewBuilder;
    private volatile boolean opening = false;
    private volatile PendingCapture pendingCapture;
    private Size previewSize;

    private static final class PendingCapture {
        final CaptureCallback callback;
        PendingCapture(CaptureCallback callback) { this.callback = callback; }
    }

    Camera2Controller(Activity activity, TextureView textureView, Listener listener) {
        this.activity = activity;
        this.textureView = textureView;
        this.listener = listener;
        this.manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        thread.start();
        handler = new Handler(thread.getLooper());
        enumerateLenses();
    }

    List<LensInfo> getLenses() { return Collections.unmodifiableList(new ArrayList<>(lenses)); }

    LensInfo getSelectedLens() {
        if (lenses.isEmpty()) return null;
        int i = Math.max(0, Math.min(selectedIndex, lenses.size() - 1));
        return lenses.get(i);
    }

    String getSelectedLensLabel() {
        LensInfo l = getSelectedLens();
        return l == null ? "NO LENS" : l.label;
    }

    boolean isReady() { return session != null && device != null && imageReader != null; }

    void open() {
        handler.post(this::openInternal);
    }

    void close() {
        handler.post(this::closeInternal);
    }

    void shutdown() {
        handler.post(() -> {
            closeInternal();
            thread.quitSafely();
        });
    }

    void onViewSizeChanged() {
        LensInfo lens = getSelectedLens();
        if (lens != null && previewSize != null) configureTransform(lens, previewSize);
    }

    void showLensDialog(Activity host, Runnable onChanged) {
        if (lenses.isEmpty()) {
            listener.onError("No Camera2 lenses were reported by this phone");
            return;
        }
        String[] names = new String[lenses.size()];
        for (int i = 0; i < lenses.size(); i++) names[i] = lenses.get(i).label;
        new AlertDialog.Builder(host)
                .setTitle("Select camera lens")
                .setSingleChoiceItems(names, selectedIndex, (dialog, which) -> {
                    selectedIndex = which;
                    dialog.dismiss();
                    restart();
                    if (onChanged != null) onChanged.run();
                })
                .setNeutralButton("Diagnostics", (dialog, which) -> showDiagnosticsDialog(host))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showDiagnosticsDialog(Activity host) {
        final String report = buildDiagnosticsReport();
        ScrollView scroll = new ScrollView(host);
        TextView text = new TextView(host);
        int pad = dp(host, 16);
        text.setPadding(pad, pad, pad, pad);
        text.setText(report);
        text.setTextSize(11);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(text);

        new AlertDialog.Builder(host)
                .setTitle("Camera2 diagnostics")
                .setView(scroll)
                .setNeutralButton("Copy report", (dialog, which) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("SyncCam camera diagnostics", report));
                        android.widget.Toast.makeText(host, "Camera diagnostics copied", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("Close", null)
                .show();
    }

    private String buildDiagnosticsReport() {
        StringBuilder out = new StringBuilder();
        out.append("SyncCam v7.1 camera diagnostics\n");
        out.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

        out.append("SELECTABLE SYNCCAM ROUTES: ").append(lenses.size()).append("\n");
        for (int i = 0; i < lenses.size(); i++) {
            LensInfo lens = lenses.get(i);
            out.append(i == selectedIndex ? "* " : "  ")
                    .append(i + 1).append(". ").append(lens.label).append("\n");
            if (lens.physicalId == null) {
                out.append("     OPEN camera ID ").append(lens.logicalId).append("\n");
            } else {
                out.append("     OPEN logical ").append(lens.logicalId)
                        .append(" -> TARGET physical ").append(lens.physicalId).append("\n");
            }
        }

        out.append("\nANDROID CAMERA2 REPORT\n");
        try {
            String[] ids = manager.getCameraIdList();
            Set<String> directIds = new HashSet<>(Arrays.asList(ids));
            out.append("Direct camera IDs reported by CameraManager: ").append(Arrays.toString(ids)).append("\n");

            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Integer hw = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Range<Long> shutter = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size maxJpeg = map == null ? null : chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG));
                Set<String> physical = Build.VERSION.SDK_INT >= 28 ? c.getPhysicalCameraIds() : Collections.emptySet();

                out.append("\nCAMERA ID ").append(id).append("  [DIRECT OPEN]\n");
                out.append("  facing: ").append(facingName(facing == null ? CameraCharacteristics.LENS_FACING_BACK : facing)).append("\n");
                out.append("  hardware: ").append(hardwareLevelName(hw)).append("\n");
                out.append("  focal lengths: ").append(focals == null ? "[]" : Arrays.toString(focals)).append(" mm\n");
                out.append("  sensor size: ").append(sensor == null ? "unknown" : sensor.toString()).append(" mm\n");
                out.append("  active array: ").append(active == null ? "unknown" : active.toShortString()).append("\n");
                out.append("  max JPEG: ").append(maxJpeg == null ? "unknown" : maxJpeg.getWidth() + "x" + maxJpeg.getHeight()).append("\n");
                if (Build.VERSION.SDK_INT >= 30) {
                    Range<Float> zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    out.append("  zoom ratio range: ").append(zoom == null ? "not reported" : zoom.toString()).append("\n");
                } else {
                    Float zoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                    out.append("  max digital zoom: ").append(zoom == null ? "not reported" : String.format(Locale.US, "%.2fx", zoom)).append("\n");
                }
                out.append("  flash: ").append(Boolean.TRUE.equals(flash) ? "YES" : "NO").append("\n");
                out.append("  manual sensor: ").append(hasCapability(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) ? "YES" : "NO").append("\n");
                out.append("  RAW: ").append(hasCapability(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ? "YES" : "NO").append("\n");
                if (Build.VERSION.SDK_INT >= 28) {
                    out.append("  logical multi-camera: ").append(hasCapability(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) ? "YES" : "NO").append("\n");
                }
                out.append("  ISO range: ").append(iso == null ? "not reported" : iso.toString()).append("\n");
                out.append("  shutter ns range: ").append(shutter == null ? "not reported" : shutter.toString()).append("\n");
                out.append("  min focus distance: ").append(minFocus == null ? "not reported" : String.format(Locale.US, "%.3f diopters", minFocus)).append("\n");
                out.append("  physical IDs: ").append(physical.isEmpty() ? "[]" : physical.toString()).append("\n");

                for (String pid : physical) {
                    boolean routed = false;
                    for (LensInfo lens : lenses) {
                        if (id.equals(lens.logicalId) && pid.equals(lens.physicalId)) {
                            routed = true;
                            break;
                        }
                    }
                    out.append("    PHYSICAL ").append(pid)
                            .append(" • SyncCam routed: ").append(routed ? "YES" : "NO")
                            .append(" • direct ID: ").append(directIds.contains(pid) ? "YES" : "NO");
                    try {
                        CameraCharacteristics pc = manager.getCameraCharacteristics(pid);
                        float[] pf = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        SizeF ps = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                        out.append(" • focal ").append(pf == null ? "[]" : Arrays.toString(pf)).append(" mm");
                        if (ps != null) out.append(" • sensor ").append(ps.toString()).append(" mm");
                    } catch (Exception e) {
                        out.append(" • characteristics hidden");
                    }
                    out.append("\n");
                }
            }
        } catch (Exception e) {
            out.append("\nDIAGNOSTIC ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        out.append("\nNOTE: If the stock camera app has a lens that is absent from both the direct and physical ID lists above, the phone manufacturer is not exposing that lens through the public Camera2 HAL to third-party apps.\n");
        return out.toString();
    }

    void showSettingsDialog(Activity host, boolean gpsEnabled, SettingsCallback callback) {
        LensInfo lens = getSelectedLens();
        if (lens == null) {
            listener.onError("Camera is not ready");
            return;
        }
        CameraCharacteristics c = characteristicsFor(lens);
        if (c == null) {
            listener.onError("Cannot read lens capabilities");
            return;
        }
        Settings s = settingsFor(lens, c);

        ScrollView scroll = new ScrollView(host);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(host, 20);
        box.setPadding(pad, dp(host, 8), pad, dp(host, 16));
        scroll.addView(box);

        TextView lensInfo = new TextView(host);
        lensInfo.setText(lens.label + "\nCamera2 " + lens.logicalId + (lens.physicalId == null ? "" : " • physical " + lens.physicalId));
        lensInfo.setTextSize(12);
        box.addView(lensInfo);

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        List<Size> jpegSizes = map == null ? Collections.emptyList() : sortedSizes(map.getOutputSizes(ImageFormat.JPEG));
        Spinner resolution = null;
        if (!jpegSizes.isEmpty()) {
            box.addView(label(host, "PHOTO RESOLUTION"));
            ArrayList<String> labels = new ArrayList<>();
            for (Size z : jpegSizes) labels.add(z.getWidth() + " × " + z.getHeight());
            resolution = spinner(host, labels, sizeIndex(jpegSizes, s.pictureSize));
            box.addView(resolution);
        }

        int[] afModes = safeInts(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        Spinner af = null;
        if (afModes.length > 0) {
            box.addView(label(host, "FOCUS MODE"));
            ArrayList<String> names = new ArrayList<>();
            for (int v : afModes) names.add(afName(v));
            af = spinner(host, names, indexOf(afModes, s.afMode));
            box.addView(af);
        }

        int[] awbModes = safeInts(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES));
        Spinner awb = null;
        if (awbModes.length > 0) {
            box.addView(label(host, "WHITE BALANCE"));
            ArrayList<String> names = new ArrayList<>();
            for (int v : awbModes) names.add(awbName(v));
            awb = spinner(host, names, indexOf(awbModes, s.awbMode));
            box.addView(awb);
        }

        Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Spinner flash = null;
        if (Boolean.TRUE.equals(flashAvailable)) {
            box.addView(label(host, "FLASH"));
            flash = spinner(host, Arrays.asList("OFF", "AUTO", "ON"), Arrays.asList("OFF", "AUTO", "ON").indexOf(s.flash));
            box.addView(flash);
        }

        Range<Integer> evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        SeekBar exposure = null;
        TextView exposureValue = null;
        if (evRange != null && evRange.getLower() < evRange.getUpper()) {
            box.addView(label(host, "EXPOSURE COMPENSATION"));
            exposureValue = new TextView(host);
            box.addView(exposureValue);
            exposure = new SeekBar(host);
            exposure.setMax(evRange.getUpper() - evRange.getLower());
            exposure.setProgress(clamp(s.exposureComp, evRange.getLower(), evRange.getUpper()) - evRange.getLower());
            TextView finalExposureValue = exposureValue;
            SeekBar finalExposure = exposure;
            Runnable update = () -> {
                int idx = finalExposure.getProgress() + evRange.getLower();
                double step = evStep == null ? 0.0 : evStep.doubleValue();
                finalExposureValue.setText(String.format(Locale.US, "%+.2f EV", idx * step));
            };
            update.run();
            exposure.setOnSeekBarChangeListener(new SimpleSeek(update));
            box.addView(exposure);
        }

        float maxZoom = maxZoom(c);
        SeekBar zoom = null;
        TextView zoomValue = null;
        if (maxZoom > 1.01f) {
            box.addView(label(host, "DIGITAL ZOOM"));
            zoomValue = new TextView(host);
            box.addView(zoomValue);
            zoom = new SeekBar(host);
            zoom.setMax(1000);
            zoom.setProgress(Math.round((clamp(s.zoomRatio, 1f, maxZoom) - 1f) / (maxZoom - 1f) * 1000f));
            SeekBar finalZoom = zoom;
            TextView finalZoomValue = zoomValue;
            Runnable update = () -> finalZoomValue.setText(String.format(Locale.US, "%.2f×", 1f + (maxZoom - 1f) * finalZoom.getProgress() / 1000f));
            update.run();
            zoom.setOnSeekBarChangeListener(new SimpleSeek(update));
            box.addView(zoom);
        }

        box.addView(label(host, "JPEG QUALITY"));
        TextView qualityValue = new TextView(host);
        box.addView(qualityValue);
        SeekBar quality = new SeekBar(host);
        quality.setMax(99);
        quality.setProgress(clamp(s.jpegQuality, 1, 100) - 1);
        Runnable updateQuality = () -> qualityValue.setText((quality.getProgress() + 1) + "%");
        updateQuality.run();
        quality.setOnSeekBarChangeListener(new SimpleSeek(updateQuality));
        box.addView(quality);

        Boolean aeLockAvailable = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        CheckBox aeLock = new CheckBox(host);
        aeLock.setText("Lock auto exposure (AE)");
        aeLock.setChecked(s.aeLock);
        if (Boolean.TRUE.equals(aeLockAvailable)) box.addView(aeLock);

        Boolean awbLockAvailable = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
        CheckBox awbLock = new CheckBox(host);
        awbLock.setText("Lock auto white balance (AWB)");
        awbLock.setChecked(s.awbLock);
        if (Boolean.TRUE.equals(awbLockAvailable)) box.addView(awbLock);

        boolean manualSensor = hasCapability(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        CheckBox manualExposure = new CheckBox(host);
        EditText iso = new EditText(host);
        EditText shutter = new EditText(host);
        Range<Integer> isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (manualSensor && isoRange != null && exposureRange != null) {
            box.addView(label(host, "MANUAL EXPOSURE"));
            manualExposure.setText("Use manual ISO and shutter");
            manualExposure.setChecked(s.manualExposure);
            box.addView(manualExposure);
            iso.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            iso.setHint("ISO " + isoRange.getLower() + "–" + isoRange.getUpper());
            iso.setText(String.valueOf(clamp(s.iso, isoRange.getLower(), isoRange.getUpper())));
            box.addView(iso);
            shutter.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            shutter.setHint("Exposure milliseconds");
            shutter.setText(String.format(Locale.US, "%.3f", s.exposureTimeNs / 1_000_000.0));
            box.addView(shutter);
        }

        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        CheckBox manualFocus = new CheckBox(host);
        EditText focusDistance = new EditText(host);
        if (minFocus != null && minFocus > 0f) {
            box.addView(label(host, "MANUAL FOCUS"));
            manualFocus.setText("Use manual focus distance");
            manualFocus.setChecked(s.manualFocus);
            box.addView(manualFocus);
            focusDistance.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            focusDistance.setHint("0 (infinity) to " + String.format(Locale.US, "%.2f", minFocus) + " diopters");
            focusDistance.setText(String.format(Locale.US, "%.3f", s.focusDistance));
            box.addView(focusDistance);
        }

        CheckBox gps = new CheckBox(host);
        gps.setText("Store this phone's GPS location in JPEG EXIF");
        gps.setChecked(gpsEnabled);
        box.addView(label(host, "PHOTO METADATA"));
        box.addView(gps);

        TextView note = new TextView(host);
        note.setText("Controls are queried from the selected Camera2 lens. Physical lens availability depends on the phone manufacturer and Android camera HAL.");
        note.setTextSize(11);
        note.setPadding(0, dp(host, 12), 0, 0);
        box.addView(note);

        Spinner finalResolution = resolution;
        Spinner finalAf = af;
        Spinner finalAwb = awb;
        Spinner finalFlash = flash;
        SeekBar finalExposure = exposure;
        SeekBar finalZoom = zoom;
        new AlertDialog.Builder(host)
                .setTitle("Camera settings • " + lens.label)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Defaults", (d, which) -> {
                    profiles.remove(lens.key);
                    restart();
                    if (callback != null) callback.onApplied(gps.isChecked(), "Camera defaults restored for " + lens.label);
                })
                .setPositiveButton("Apply", (d, which) -> {
                    try {
                        if (finalResolution != null && !jpegSizes.isEmpty()) s.pictureSize = jpegSizes.get(finalResolution.getSelectedItemPosition());
                        if (finalAf != null) s.afMode = afModes[finalAf.getSelectedItemPosition()];
                        if (finalAwb != null) s.awbMode = awbModes[finalAwb.getSelectedItemPosition()];
                        if (finalFlash != null) s.flash = (String) finalFlash.getSelectedItem();
                        if (finalExposure != null && evRange != null) s.exposureComp = finalExposure.getProgress() + evRange.getLower();
                        if (finalZoom != null) s.zoomRatio = 1f + (maxZoom - 1f) * finalZoom.getProgress() / 1000f;
                        s.jpegQuality = quality.getProgress() + 1;
                        if (Boolean.TRUE.equals(aeLockAvailable)) s.aeLock = aeLock.isChecked();
                        if (Boolean.TRUE.equals(awbLockAvailable)) s.awbLock = awbLock.isChecked();
                        if (manualSensor && isoRange != null && exposureRange != null) {
                            s.manualExposure = manualExposure.isChecked();
                            s.iso = clamp(parseInt(iso.getText().toString(), s.iso), isoRange.getLower(), isoRange.getUpper());
                            double ms = parseDouble(shutter.getText().toString(), s.exposureTimeNs / 1_000_000.0);
                            s.exposureTimeNs = clamp((long) (ms * 1_000_000.0), exposureRange.getLower(), exposureRange.getUpper());
                        }
                        if (minFocus != null && minFocus > 0f) {
                            s.manualFocus = manualFocus.isChecked();
                            s.focusDistance = clamp((float) parseDouble(focusDistance.getText().toString(), s.focusDistance), 0f, minFocus);
                        }
                        profiles.put(lens.key, s);
                        restart();
                        if (callback != null) callback.onApplied(gps.isChecked(), "Camera settings applied to " + lens.label);
                    } catch (Exception e) {
                        listener.onError("Camera setting rejected: " + e.getMessage());
                    }
                })
                .show();
    }

    void capture(CaptureCallback callback) {
        handler.post(() -> captureInternal(callback));
    }

    private void captureInternal(CaptureCallback callback) {
        if (device == null || session == null || imageReader == null) {
            callback.onError("Camera2 session is not ready");
            return;
        }
        if (pendingCapture != null) {
            callback.onError("Previous Camera2 capture is still processing");
            return;
        }
        LensInfo lens = getSelectedLens();
        CameraCharacteristics c = characteristicsFor(lens);
        if (lens == null || c == null) {
            callback.onError("Selected lens is unavailable");
            return;
        }
        try {
            CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(imageReader.getSurface());
            applySettings(still, lens, c, settingsFor(lens, c), true);
            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(c, lens));
            pendingCapture = new PendingCapture(callback);
            session.capture(still.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) { }
                @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    PendingCapture p = pendingCapture;
                    pendingCapture = null;
                    if (p != null) p.callback.onError("Camera2 capture failed: " + failure.getReason());
                }
            }, handler);
        } catch (Exception e) {
            pendingCapture = null;
            callback.onError("Camera2 capture error: " + e.getMessage());
        }
    }

    private void enumerateLenses() {
        lenses.clear();
        try {
            String[] ids = manager.getCameraIdList();
            Set<String> topLevelIds = new HashSet<>(Arrays.asList(ids));
            Set<String> ownedPhysical = new HashSet<>();
            if (Build.VERSION.SDK_INT >= 28) {
                for (String id : ids) {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    ownedPhysical.addAll(c.getPhysicalCameraIds());
                }
            }
            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                int f = facing == null ? CameraCharacteristics.LENS_FACING_BACK : facing;
                Set<String> physical = Build.VERSION.SDK_INT >= 28 ? c.getPhysicalCameraIds() : Collections.emptySet();
                if (!physical.isEmpty()) {
                    float[] logicalFocals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    float logicalFocal = logicalFocals == null || logicalFocals.length == 0 ? 0f : logicalFocals[0];
                    lenses.add(new LensInfo(id, null, f, logicalFocal, facingName(f) + " Auto" + focalSuffix(logicalFocal)));
                    for (String pid : physical) {
                        CameraCharacteristics pc;
                        try { pc = manager.getCameraCharacteristics(pid); }
                        catch (Exception e) { continue; }
                        Integer pfacing = pc.get(CameraCharacteristics.LENS_FACING);
                        int actualFacing = pfacing == null ? f : pfacing;
                        float focal = firstFocal(pc);
                        String physicalLabel = classifyLens(pc, actualFacing, focal);
                        if (topLevelIds.contains(pid)) physicalLabel += " • routed";
                        lenses.add(new LensInfo(id, pid, actualFacing, focal, physicalLabel));
                    }
                } else {
                    float focal = firstFocal(c);
                    String directLabel = classifyLens(c, f, focal);
                    if (ownedPhysical.contains(id)) directLabel += " • direct";
                    lenses.add(new LensInfo(id, null, f, focal, directLabel));
                }
            }
            lenses.sort(Comparator
                    .comparingInt((LensInfo l) -> l.isFront() ? 1 : 0)
                    .thenComparingDouble(l -> l.physicalId == null && l.label.contains("Auto") ? 9999.0 : l.focalLengthMm));
            int preferred = 0;
            for (int i = 0; i < lenses.size(); i++) {
                String n = lenses.get(i).label.toLowerCase(Locale.US);
                if (!lenses.get(i).isFront() && n.contains("wide") && !n.contains("ultra")) { preferred = i; break; }
            }
            selectedIndex = preferred;
        } catch (Exception e) {
            listener.onError("Camera2 enumeration failed: " + e.getMessage());
        }
    }

    private void restart() {
        handler.post(() -> {
            closeInternal();
            openInternal();
        });
    }

    private void openInternal() {
        if (opening || device != null || !textureView.isAvailable()) return;
        LensInfo lens = getSelectedLens();
        if (lens == null) {
            listener.onError("No camera lenses available");
            return;
        }
        opening = true;
        try {
            manager.openCamera(lens.logicalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    opening = false;
                    device = camera;
                    createSession(lens);
                }

                @Override public void onDisconnected(CameraDevice camera) {
                    opening = false;
                    camera.close();
                    if (device == camera) device = null;
                    listener.onError("Camera disconnected");
                }

                @Override public void onError(CameraDevice camera, int error) {
                    opening = false;
                    camera.close();
                    if (device == camera) device = null;
                    listener.onError("Camera2 open error " + error);
                }
            }, handler);
        } catch (SecurityException e) {
            opening = false;
            listener.onError("Camera permission required");
        } catch (CameraAccessException e) {
            opening = false;
            listener.onError("Camera2 access error: " + e.getMessage());
        }
    }

    private void createSession(LensInfo lens) {
        CameraCharacteristics c = characteristicsFor(lens);
        StreamConfigurationMap map = c == null ? null : c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || device == null || !textureView.isAvailable()) {
            listener.onError("No stream configuration for " + lens.label);
            return;
        }
        Settings settings = settingsFor(lens, c);
        Size[] jpegChoices = map.getOutputSizes(ImageFormat.JPEG);
        if (settings.pictureSize == null || !containsSize(jpegChoices, settings.pictureSize)) settings.pictureSize = chooseJpegSize(jpegChoices);
        Size[] previewChoices = map.getOutputSizes(SurfaceTexture.class);
        previewSize = choosePreviewSize(previewChoices, textureView.getWidth(), textureView.getHeight());
        if (settings.pictureSize == null || previewSize == null) {
            listener.onError("Camera does not expose compatible preview/JPEG sizes");
            return;
        }
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null) return;
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);
        imageReader = ImageReader.newInstance(settings.pictureSize.getWidth(), settings.pictureSize.getHeight(), ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireNextImage();
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                long ts = image.getTimestamp();
                PendingCapture p = pendingCapture;
                pendingCapture = null;
                if (p != null) p.callback.onCaptured(data, ts, getSelectedLens());
            } catch (Exception e) {
                PendingCapture p = pendingCapture;
                pendingCapture = null;
                if (p != null) p.callback.onError("JPEG read failed: " + e.getMessage());
            } finally {
                if (image != null) image.close();
            }
        }, handler);

        try {
            OutputConfiguration previewOut = new OutputConfiguration(previewSurface);
            OutputConfiguration jpegOut = new OutputConfiguration(imageReader.getSurface());
            if (Build.VERSION.SDK_INT >= 28 && lens.physicalId != null) {
                previewOut.setPhysicalCameraId(lens.physicalId);
                jpegOut.setPhysicalCameraId(lens.physicalId);
            }
            device.createCaptureSessionByOutputConfigurations(Arrays.asList(previewOut, jpegOut), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    if (device == null) return;
                    session = cameraCaptureSession;
                    try {
                        previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        previewBuilder.addTarget(previewSurface);
                        applySettings(previewBuilder, lens, c, settingsFor(lens, c), false);
                        session.setRepeatingRequest(previewBuilder.build(), null, handler);
                        configureTransform(lens, previewSize);
                        listener.onReady(lens.label);
                    } catch (Exception e) {
                        listener.onError("Preview request failed: " + e.getMessage());
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    listener.onError("Camera session configuration failed for " + lens.label);
                }
            }, handler);
        } catch (Exception e) {
            listener.onError("Camera session error: " + e.getMessage());
        }
    }

    private void applySettings(CaptureRequest.Builder b, LensInfo lens, CameraCharacteristics c, Settings s, boolean still) {
        set(b, lens, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        int[] afAvailable = safeInts(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        int af = contains(afAvailable, s.afMode) ? s.afMode : preferredAf(afAvailable);
        if (s.manualFocus) {
            set(b, lens, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            set(b, lens, CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDistance);
        } else {
            set(b, lens, CaptureRequest.CONTROL_AF_MODE, af);
        }

        int[] awbAvailable = safeInts(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES));
        int awb = contains(awbAvailable, s.awbMode) ? s.awbMode : CaptureRequest.CONTROL_AWB_MODE_AUTO;
        set(b, lens, CaptureRequest.CONTROL_AWB_MODE, awb);
        set(b, lens, CaptureRequest.CONTROL_AWB_LOCK, s.awbLock);

        if (s.manualExposure && hasCapability(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            set(b, lens, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            set(b, lens, CaptureRequest.SENSOR_SENSITIVITY, s.iso);
            set(b, lens, CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureTimeNs);
        } else {
            int aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
            if ("AUTO".equals(s.flash)) aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
            else if ("ON".equals(s.flash)) aeMode = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            set(b, lens, CaptureRequest.CONTROL_AE_MODE, aeMode);
            Range<Integer> evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (evRange != null) set(b, lens, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamp(s.exposureComp, evRange.getLower(), evRange.getUpper()));
            set(b, lens, CaptureRequest.CONTROL_AE_LOCK, s.aeLock);
        }
        applyZoom(b, lens, c, s.zoomRatio);
        if (still) b.set(CaptureRequest.JPEG_QUALITY, (byte) clamp(s.jpegQuality, 1, 100));
    }

    private <T> void set(CaptureRequest.Builder b, LensInfo lens, CaptureRequest.Key<T> key, T value) {
        if (value == null) return;
        try { b.set(key, value); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 28 && lens != null && lens.physicalId != null) {
            try { b.setPhysicalCameraKey(key, value, lens.physicalId); } catch (Exception ignored) { }
        }
    }

    private void applyZoom(CaptureRequest.Builder b, LensInfo lens, CameraCharacteristics c, float requested) {
        float max = maxZoom(c);
        float zoom = clamp(requested, 1f, max);
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null) {
                set(b, lens, CaptureRequest.CONTROL_ZOOM_RATIO, clamp(zoom, range.getLower(), range.getUpper()));
                return;
            }
        }
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active != null && zoom > 1.001f) {
            int cropW = Math.max(2, Math.round(active.width() / zoom));
            int cropH = Math.max(2, Math.round(active.height() / zoom));
            int left = active.centerX() - cropW / 2;
            int top = active.centerY() - cropH / 2;
            set(b, lens, CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + cropW, top + cropH));
        }
    }

    private Settings settingsFor(LensInfo lens, CameraCharacteristics c) {
        Settings s = profiles.get(lens.key);
        if (s != null) return s;
        s = new Settings();
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) s.pictureSize = chooseJpegSize(map.getOutputSizes(ImageFormat.JPEG));
        int[] af = safeInts(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        s.afMode = preferredAf(af);
        int[] awb = safeInts(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES));
        s.awbMode = contains(awb, CaptureRequest.CONTROL_AWB_MODE_AUTO) ? CaptureRequest.CONTROL_AWB_MODE_AUTO : (awb.length == 0 ? CaptureRequest.CONTROL_AWB_MODE_OFF : awb[0]);
        Range<Integer> isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) s.iso = clamp(100, isoRange.getLower(), isoRange.getUpper());
        Range<Long> exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exposureRange != null) s.exposureTimeNs = clamp(10_000_000L, exposureRange.getLower(), exposureRange.getUpper());
        profiles.put(lens.key, s);
        return s;
    }

    private CameraCharacteristics characteristicsFor(LensInfo lens) {
        if (lens == null) return null;
        try { return manager.getCameraCharacteristics(lens.physicalId == null ? lens.logicalId : lens.physicalId); }
        catch (Exception e) {
            try { return manager.getCameraCharacteristics(lens.logicalId); }
            catch (Exception ignored) { return null; }
        }
    }

    private void configureTransform(LensInfo lens, Size size) {
        if (lens == null || size == null || textureView.getWidth() == 0 || textureView.getHeight() == 0) return;
        textureView.post(() -> {
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, size.getHeight(), size.getWidth());
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                float scale = Math.max((float) viewHeight / size.getHeight(), (float) viewWidth / size.getWidth());
                matrix.postScale(scale, scale, centerX, centerY);
                matrix.postRotate(90 * (rotation - 2), centerX, centerY);
            } else if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, centerX, centerY);
            }
            if (lens.isFront()) matrix.postScale(-1f, 1f, centerX, centerY);
            textureView.setTransform(matrix);
        });
    }

    private int jpegOrientation(CameraCharacteristics c, LensInfo lens) {
        Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensorOrientation = sensor == null ? 90 : sensor;
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int displayDegrees;
        if (rotation == Surface.ROTATION_90) displayDegrees = 90;
        else if (rotation == Surface.ROTATION_180) displayDegrees = 180;
        else if (rotation == Surface.ROTATION_270) displayDegrees = 270;
        else displayDegrees = 0;
        if (lens != null && lens.isFront()) return (sensorOrientation + displayDegrees) % 360;
        return (sensorOrientation - displayDegrees + 360) % 360;
    }

    private void closeInternal() {
        pendingCapture = null;
        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        if (device != null) {
            try { device.close(); } catch (Exception ignored) { }
            device = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) { }
            imageReader = null;
        }
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Exception ignored) { }
            previewSurface = null;
        }
        previewBuilder = null;
        opening = false;
    }

    private static String hardwareLevelName(Integer level) {
        if (level == null) return "UNKNOWN";
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "LEVEL " + level;
        }
    }

    private static String classifyLens(CameraCharacteristics c, int facing, float focal) {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "Front" + focalSuffix(focal);
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "External" + focalSuffix(focal);
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        String kind = "Rear";
        if (sensor != null && focal > 0f) {
            double fov = 2.0 * Math.toDegrees(Math.atan(sensor.getWidth() / (2.0 * focal)));
            if (fov >= 80) kind = "Ultra-wide";
            else if (fov >= 55) kind = "Wide";
            else if (fov >= 38) kind = "Standard";
            else kind = "Telephoto";
        }
        return kind + focalSuffix(focal);
    }

    private static String facingName(int facing) {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "Front";
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "External";
        return "Rear";
    }

    private static String focalSuffix(float focal) {
        return focal <= 0 ? "" : String.format(Locale.US, " • %.2f mm", focal);
    }

    private static float firstFocal(CameraCharacteristics c) {
        float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return f == null || f.length == 0 ? 0f : f[0];
    }

    private static Size chooseJpegSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Collections.max(Arrays.asList(sizes), Comparator.comparingLong(Camera2Controller::area));
    }

    private static Size choosePreviewSize(Size[] sizes, int viewW, int viewH) {
        if (sizes == null || sizes.length == 0) return null;
        ArrayList<Size> candidates = new ArrayList<>(Arrays.asList(sizes));
        candidates.sort(Comparator.comparingLong(Camera2Controller::area));
        Size best = candidates.get(0);
        long target = Math.max(1, (long) Math.max(viewW, viewH) * Math.max(viewW, viewH));
        for (Size s : candidates) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1920) best = s;
            if (area(s) >= target && s.getWidth() <= 1920 && s.getHeight() <= 1920) break;
        }
        return best;
    }

    private static List<Size> sortedSizes(Size[] sizes) {
        if (sizes == null) return Collections.emptyList();
        ArrayList<Size> r = new ArrayList<>(Arrays.asList(sizes));
        r.sort((a, b) -> Long.compare(area(b), area(a)));
        return r;
    }

    private static long area(Size s) { return (long) s.getWidth() * s.getHeight(); }

    private static boolean containsSize(Size[] sizes, Size value) {
        if (sizes == null || value == null) return false;
        for (Size s : sizes) if (s.equals(value)) return true;
        return false;
    }

    private static int sizeIndex(List<Size> sizes, Size selected) {
        if (selected == null) return 0;
        int i = sizes.indexOf(selected);
        return i < 0 ? 0 : i;
    }

    private static float maxZoom(CameraCharacteristics c) {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return Math.max(1f, r.getUpper());
        }
        Float z = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return z == null ? 1f : Math.max(1f, z);
    }

    private static boolean hasCapability(CameraCharacteristics c, int capability) {
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return contains(safeInts(caps), capability);
    }

    private static int preferredAf(int[] modes) {
        if (contains(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        if (contains(modes, CaptureRequest.CONTROL_AF_MODE_AUTO)) return CaptureRequest.CONTROL_AF_MODE_AUTO;
        return modes.length == 0 ? CaptureRequest.CONTROL_AF_MODE_OFF : modes[0];
    }

    private static String afName(int v) {
        switch (v) {
            case CaptureRequest.CONTROL_AF_MODE_OFF: return "OFF / manual";
            case CaptureRequest.CONTROL_AF_MODE_AUTO: return "AUTO";
            case CaptureRequest.CONTROL_AF_MODE_MACRO: return "MACRO";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO: return "CONTINUOUS VIDEO";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE: return "CONTINUOUS PICTURE";
            case CaptureRequest.CONTROL_AF_MODE_EDOF: return "EDOF";
            default: return "AF " + v;
        }
    }

    private static String awbName(int v) {
        switch (v) {
            case CaptureRequest.CONTROL_AWB_MODE_OFF: return "OFF";
            case CaptureRequest.CONTROL_AWB_MODE_AUTO: return "AUTO";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT: return "INCANDESCENT";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT: return "FLUORESCENT";
            case CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT: return "WARM FLUORESCENT";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT: return "DAYLIGHT";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "CLOUDY";
            case CaptureRequest.CONTROL_AWB_MODE_TWILIGHT: return "TWILIGHT";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE: return "SHADE";
            default: return "AWB " + v;
        }
    }

    private static int[] safeInts(int[] values) { return values == null ? new int[0] : values; }
    private static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }
    private static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return 0; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int parseInt(String s, int fallback) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; } }
    private static double parseDouble(String s, double fallback) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return fallback; } }

    private static TextView label(Activity a, String s) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(11);
        v.setPadding(0, dp(a, 10), 0, dp(a, 3));
        return v;
    }

    private static Spinner spinner(Activity a, List<String> values, int selected) {
        Spinner s = new Spinner(a);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        if (!values.isEmpty()) s.setSelection(Math.max(0, Math.min(selected, values.size() - 1)));
        return s;
    }

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    private static final class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final Runnable update;
        SimpleSeek(Runnable update) { this.update = update; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { update.run(); }
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
