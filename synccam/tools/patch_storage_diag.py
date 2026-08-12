from pathlib import Path

p = Path('app/src/main/java/com/openai/synccam/MainActivity.java')
s = p.read_text()

if 'storageDiagButton' in s and 'runStorageDiagnostics()' in s:
    print('Storage diagnostics UI already present')
    raise SystemExit(0)

old = 'private TextView syncValue, peerValue, hotspotInfo, photoSync, autoCaptureButton, cameraSettingsButton, lensButton, projectButton, storageButton;'
new = 'private TextView syncValue, peerValue, hotspotInfo, photoSync, autoCaptureButton, cameraSettingsButton, lensButton, projectButton, storageButton, storageDiagButton;'
assert old in s
s = s.replace(old, new, 1)

anchor = '''        deck.addView(storageButton, storageLp);\n\n        TextView footer = label("Per-device photo sync • project/session folders • host interval capture • camera controls adapt to this phone", 9, 0xFF8D96A3, Typeface.NORMAL);'''
replacement = '''        deck.addView(storageButton, storageLp);\n\n        storageDiagButton = actionButton("STORAGE TESTS  •  T01-T11", 0xFF343B46, Color.WHITE);\n        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(-1, dp(44));\n        diagLp.topMargin = dp(2);\n        diagLp.bottomMargin = dp(4);\n        deck.addView(storageDiagButton, diagLp);\n\n        TextView footer = label("Per-device photo sync • project/session folders • host interval capture • camera controls adapt to this phone", 9, 0xFF8D96A3, Typeface.NORMAL);'''
assert anchor in s
s = s.replace(anchor, replacement, 1)

anchor = '''        storageButton.setOnLongClickListener(v -> { clearSaveRoot(); return true; });\n        uiToggleButton.setOnClickListener(v -> toggleUiVisibility());'''
replacement = '''        storageButton.setOnLongClickListener(v -> { clearSaveRoot(); return true; });\n        storageDiagButton.setOnClickListener(v -> runStorageDiagnostics());\n        uiToggleButton.setOnClickListener(v -> toggleUiVisibility());'''
assert anchor in s
s = s.replace(anchor, replacement, 1)

anchor = '    private void toggleUiVisibility() {'
methods = '''    private void runStorageDiagnostics() {
        if (camera2 == null || !camera2.isReady()) {
            Toast.makeText(this, "Camera2 is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (captureInProgress) {
            setStatus("Storage tests blocked • capture already in progress");
            return;
        }
        captureInProgress = true;
        setStatus("STORAGE TESTS • capturing one JPEG for T01-T11…");
        camera2.capture(new Camera2Controller.CaptureCallback() {
            @Override public void onCaptured(byte[] data, long sensorTimestampNs, Camera2Controller.LensInfo lens) {
                scheduler.execute(() -> {
                    String report;
                    try {
                        report = StorageDiagnostics.run(MainActivity.this, data, projectName, deviceId);
                    } catch (Throwable t) {
                        report = "STORAGE DIAGNOSTICS FATAL\\n" + t.getClass().getSimpleName() + ": " +
                                (t.getMessage() == null ? "no detail" : t.getMessage());
                    }
                    captureInProgress = false;
                    final String finalReport = report;
                    ui(() -> showStorageDiagnosticReport(finalReport));
                });
            }

            @Override public void onError(String message) {
                captureInProgress = false;
                setStatus("STORAGE TESTS camera failure • " + message);
            }
        });
    }

    private void showStorageDiagnosticReport(String report) {
        setStatus("STORAGE TESTS complete • copy the T01-T11 report");
        new AlertDialog.Builder(this)
                .setTitle("Storage tests T01-T11")
                .setMessage(report)
                .setNegativeButton("CLOSE", null)
                .setPositiveButton("COPY REPORT", (d, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("SyncCam storage diagnostics", report));
                    Toast.makeText(this, "Storage diagnostic report copied", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

'''
assert anchor in s
s = s.replace(anchor, methods + anchor, 1)
p.write_text(s)
print('Storage diagnostics UI patched')
