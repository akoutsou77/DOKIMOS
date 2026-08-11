from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Missing patch anchor: {label}")
    return text.replace(old, new, 1)

p = Path("synccam/app/src/main/java/com/openai/synccam/Camera2Controller.java")
s = p.read_text(encoding="utf-8")

s = replace_once(s,
'''import android.hardware.camera2.CaptureRequest;\nimport android.hardware.camera2.TotalCaptureResult;''',
'''import android.hardware.camera2.CaptureRequest;\nimport android.hardware.camera2.CaptureResult;\nimport android.hardware.camera2.TotalCaptureResult;''',
"CaptureResult import")

s = replace_once(s,
'''        final float focalLengthMm;\n        final String label;\n        final String key;\n\n        LensInfo(String logicalId, String physicalId, int facing, float focalLengthMm, String label) {\n            this.logicalId = logicalId;\n            this.physicalId = physicalId;\n            this.facing = facing;\n            this.focalLengthMm = focalLengthMm;\n            this.label = label;\n            this.key = logicalId + "|" + (physicalId == null ? "AUTO" : physicalId);\n        }\n\n        boolean isFront() { return facing == CameraCharacteristics.LENS_FACING_FRONT; }''',
'''        final float focalLengthMm;\n        final String label;\n        final String key;\n        final float presetZoomRatio;\n\n        LensInfo(String logicalId, String physicalId, int facing, float focalLengthMm, String label) {\n            this(logicalId, physicalId, facing, focalLengthMm, label, 0f);\n        }\n\n        LensInfo(String logicalId, String physicalId, int facing, float focalLengthMm, String label, float presetZoomRatio) {\n            this.logicalId = logicalId;\n            this.physicalId = physicalId;\n            this.facing = facing;\n            this.focalLengthMm = focalLengthMm;\n            this.label = label;\n            this.presetZoomRatio = presetZoomRatio;\n            this.key = logicalId + "|" + (physicalId == null ? "AUTO" : physicalId)\n                    + String.format(Locale.US, "|Z%.3f", presetZoomRatio);\n        }\n\n        boolean isFront() { return facing == CameraCharacteristics.LENS_FACING_FRONT; }\n        boolean isHalZoomPreset() { return physicalId == null && presetZoomRatio > 0f; }''',
"LensInfo preset zoom")

s = replace_once(s,
'''    private volatile boolean opening = false;\n    private volatile PendingCapture pendingCapture;\n    private Size previewSize;''',
'''    private volatile boolean opening = false;\n    private volatile PendingCapture pendingCapture;\n    private volatile String lastActivePhysicalId = "";\n    private Size previewSize;''',
"active physical field")

s = replace_once(s,
'''        String[] names = new String[lenses.size()];\n        for (int i = 0; i < lenses.size(); i++) names[i] = lenses.get(i).label;''',
'''        String[] names = new String[lenses.size()];\n        for (int i = 0; i < lenses.size(); i++) {\n            LensInfo lens = lenses.get(i);\n            String route;\n            if (lens.physicalId != null) {\n                route = "logical ID " + lens.logicalId + " → physical ID " + lens.physicalId;\n            } else {\n                route = "camera ID " + lens.logicalId;\n            }\n            if (lens.isHalZoomPreset()) route += String.format(Locale.US, " • request %.2f×", lens.presetZoomRatio);\n            names[i] = lens.label + "\\n" + route;\n        }''',
"lens chooser routes")

s = s.replace('out.append("SyncCam v7.1 camera diagnostics\\n");', 'out.append("SyncCam v7.2 camera diagnostics\\n");', 1)

s = replace_once(s,
'''        out.append("SELECTABLE SYNCCAM ROUTES: ").append(lenses.size()).append("\\n");\n        for (int i = 0; i < lenses.size(); i++) {''',
'''        out.append("SELECTABLE SYNCCAM ROUTES: ").append(lenses.size()).append("\\n");\n        out.append("Last active physical ID reported by HAL: ")\n                .append(lastActivePhysicalId.isEmpty() ? "not reported yet" : lastActivePhysicalId).append("\\n");\n        for (int i = 0; i < lenses.size(); i++) {''',
"diagnostic active physical")

s = replace_once(s,
'''            if (lens.physicalId == null) {\n                out.append("     OPEN camera ID ").append(lens.logicalId).append("\\n");\n            } else {\n                out.append("     OPEN logical ").append(lens.logicalId)\n                        .append(" -> TARGET physical ").append(lens.physicalId).append("\\n");\n            }''',
'''            if (lens.physicalId == null) {\n                out.append("     OPEN camera ID ").append(lens.logicalId);\n                if (lens.isHalZoomPreset()) out.append(String.format(Locale.US, " -> CONTROL_ZOOM_RATIO %.2f×", lens.presetZoomRatio));\n                out.append("\\n");\n            } else {\n                out.append("     OPEN logical ").append(lens.logicalId)\n                        .append(" -> TARGET physical ").append(lens.physicalId).append("\\n");\n            }''',
"diagnostic preset route")

s = replace_once(s,
'''                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) { }''',
'''                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {\n                    updateActivePhysicalId(result);\n                }''',
"still active physical")

s = replace_once(s,
'''                } else {\n                    float focal = firstFocal(c);\n                    String directLabel = classifyLens(c, f, focal);\n                    if (ownedPhysical.contains(id)) directLabel += " • direct";\n                    lenses.add(new LensInfo(id, null, f, focal, directLabel));\n                }\n            }\n            lenses.sort(Comparator''',
'''                } else {\n                    float focal = firstFocal(c);\n                    String directLabel = classifyLens(c, f, focal);\n                    if (ownedPhysical.contains(id)) directLabel += " • direct";\n                    lenses.add(new LensInfo(id, null, f, focal, directLabel));\n                }\n                if (!ownedPhysical.contains(id)) addHalZoomPresets(id, c, f);\n            }\n            lenses.sort(Comparator''',
"add HAL presets")

s = replace_once(s,
'''    private void restart() {\n        handler.post(() -> {''',
'''    private void addHalZoomPresets(String cameraId, CameraCharacteristics c, int facing) {\n        if (Build.VERSION.SDK_INT < 30 || facing == CameraCharacteristics.LENS_FACING_FRONT) return;\n        Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n        if (range == null) return;\n        float lo = range.getLower();\n        float hi = range.getUpper();\n        if (hi <= lo + 0.01f) return;\n\n        ArrayList<Float> ratios = new ArrayList<>();\n        if (lo < 0.95f) ratios.add(lo);\n        if (lo <= 1f && hi >= 1f) ratios.add(1f);\n        float[] common = new float[]{2f, 3f, 5f, 10f};\n        for (float candidate : common) {\n            if (candidate >= lo - 0.001f && candidate <= hi + 0.001f) ratios.add(candidate);\n        }\n        boolean hasTele = false;\n        for (float ratio : ratios) if (ratio > 1.05f) hasTele = true;\n        if (!hasTele && hi > 1.05f) ratios.add(hi);\n\n        ArrayList<Float> unique = new ArrayList<>();\n        for (float ratio : ratios) {\n            boolean duplicate = false;\n            for (float existing : unique) if (Math.abs(existing - ratio) < 0.03f) duplicate = true;\n            if (!duplicate) unique.add(ratio);\n        }\n\n        float baseFocal = firstFocal(c);\n        for (float ratio : unique) {\n            String kind;\n            if (ratio < 0.95f) kind = "Ultra-wide";\n            else if (ratio <= 1.05f) kind = "Main";\n            else kind = "Tele";\n            String label = String.format(Locale.US, "%s %.2f× • HAL", kind, ratio);\n            float estimatedFocal = baseFocal > 0f ? baseFocal * ratio : 0f;\n            lenses.add(new LensInfo(cameraId, null, facing, estimatedFocal, label, ratio));\n        }\n    }\n\n    private void restart() {\n        handler.post(() -> {''',
"HAL preset method")

s = replace_once(s,
'''                        applySettings(previewBuilder, lens, c, settingsFor(lens, c), false);\n                        session.setRepeatingRequest(previewBuilder.build(), null, handler);\n                        configureTransform(lens, previewSize);''',
'''                        applySettings(previewBuilder, lens, c, settingsFor(lens, c), false);\n                        session.setRepeatingRequest(previewBuilder.build(), new CameraCaptureSession.CaptureCallback() {\n                            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {\n                                updateActivePhysicalId(result);\n                            }\n                        }, handler);\n                        configureTransform(lens, previewSize);''',
"preview active physical")

s = replace_once(s,
'''        applyZoom(b, lens, c, s.zoomRatio);''',
'''        float requestedZoom = lens != null && lens.isHalZoomPreset() ? lens.presetZoomRatio : s.zoomRatio;\n        applyZoom(b, lens, c, requestedZoom);''',
"preset zoom application")

s = replace_once(s,
'''    private void applyZoom(CaptureRequest.Builder b, LensInfo lens, CameraCharacteristics c, float requested) {\n        float max = maxZoom(c);\n        float zoom = clamp(requested, 1f, max);\n        if (Build.VERSION.SDK_INT >= 30) {\n            Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n            if (range != null) {\n                set(b, lens, CaptureRequest.CONTROL_ZOOM_RATIO, clamp(zoom, range.getLower(), range.getUpper()));\n                return;\n            }\n        }\n        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);''',
'''    private void applyZoom(CaptureRequest.Builder b, LensInfo lens, CameraCharacteristics c, float requested) {\n        if (Build.VERSION.SDK_INT >= 30) {\n            Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);\n            if (range != null) {\n                float zoom = clamp(requested, range.getLower(), range.getUpper());\n                set(b, lens, CaptureRequest.CONTROL_ZOOM_RATIO, zoom);\n                return;\n            }\n        }\n        float max = maxZoom(c);\n        float zoom = clamp(requested, 1f, max);\n        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);''',
"ultrawide zoom clamp bug")

s = replace_once(s,
'''    private Settings settingsFor(LensInfo lens, CameraCharacteristics c) {''',
'''    private void updateActivePhysicalId(TotalCaptureResult result) {\n        if (Build.VERSION.SDK_INT < 29 || result == null) return;\n        try {\n            String active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);\n            if (active != null && !active.isEmpty()) lastActivePhysicalId = active;\n        } catch (Exception ignored) { }\n    }\n\n    private Settings settingsFor(LensInfo lens, CameraCharacteristics c) {''',
"active physical helper")

p.write_text(s, encoding="utf-8")

bpath = Path("synccam/app/build.gradle")
b = bpath.read_text(encoding="utf-8")
b = replace_once(b, "        versionCode 8", "        versionCode 9", "version code")
b = replace_once(b, "        versionName '7.1.0-camera-diagnostics'", "        versionName '7.2.0-lens-selection'", "version name")
bpath.write_text(b, encoding="utf-8")

print("Applied SyncCam v7.2 lens selection fix")
