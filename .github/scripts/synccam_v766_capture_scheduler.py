from pathlib import Path

root = Path('synccam')
gradle = root / 'app/build.gradle'
main = root / 'app/src/main/java/com/openai/synccam/MainActivity.java'

g = gradle.read_text()
g = g.replace("versionCode 18", "versionCode 19")
g = g.replace("versionName '7.6.5-api30-direct-t10-capture'", "versionName '7.6.6-direct-camera-scheduler'")
gradle.write_text(g)

s = main.read_text()
old_field = "    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();\n    private final ScheduledExecutorService autoScheduler = Executors.newSingleThreadScheduledExecutor();"
new_field = "    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();\n    private final ScheduledExecutorService captureScheduler = Executors.newSingleThreadScheduledExecutor();\n    private final ScheduledExecutorService autoScheduler = Executors.newSingleThreadScheduledExecutor();"
if old_field not in s:
    raise SystemExit('scheduler field anchor not found')
s = s.replace(old_field, new_field, 1)

start = s.index("    private void scheduleCapture(int seq, long target, String source, String projectSnapshot, String groupSnapshot) {")
end = s.index("    private String saveJpeg(byte[] data, int seq, Camera2Controller.LensInfo lens, String projectSnapshot, boolean hostCapture, String groupSnapshot) {", start)
replacement = r'''    private void scheduleCapture(int seq, long target, String source, String projectSnapshot, String groupSnapshot) {
        activeCountdownSeq = seq;
        showCountdown(seq, target);

        // Capture timing must never share the countdown/UI scheduler. The storage-diagnostics
        // shutter proved that a direct Camera2 capture works on the affected API30 devices.
        // Fire on a dedicated executor and use only a tiny final busy wait for timing accuracy.
        long delay = Math.max(0L, target - SystemClock.elapsedRealtimeNanos() - 3_000_000L);
        captureScheduler.schedule(() -> {
            while (SystemClock.elapsedRealtimeNanos() < target) {
                // Intentionally empty: avoids Thread.onSpinWait compatibility differences.
            }
            attemptScheduledCapture(seq, source, projectSnapshot, groupSnapshot, 0);
        }, delay, TimeUnit.NANOSECONDS);
    }

    private void showCountdown(int seq, long target) {
        scheduler.execute(new Runnable() {
            @Override public void run() {
                if (activeCountdownSeq != seq) return;
                long remain = target - SystemClock.elapsedRealtimeNanos();
                if (remain <= 0) {
                    ui(() -> countdownBadge.setText("CAPTURE"));
                    return;
                }
                double sec = remain / 1_000_000_000.0;
                ui(() -> {
                    countdownBadge.setVisibility(View.VISIBLE);
                    countdownBadge.setText(String.format(Locale.US, "ARMED  #%d   %.1fs", seq, sec));
                });
                scheduler.schedule(this, 100, TimeUnit.MILLISECONDS);
            }
        });
    }

    private void attemptScheduledCapture(int seq, String source, String projectSnapshot, String groupSnapshot, int attempt) {
        Camera2Controller cam = camera2;
        if (cam == null || !cam.isReady()) {
            if (attempt < 20) {
                if (attempt == 0) setStatus("Capture #" + seq + " • waiting for Camera2 ready…");
                captureScheduler.schedule(
                        () -> attemptScheduledCapture(seq, source, projectSnapshot, groupSnapshot, attempt + 1),
                        100, TimeUnit.MILLISECONDS);
            } else {
                activeCountdownSeq = -1;
                setStatus("CAPTURE FAILED #" + seq + " • Camera2 not ready after synchronized trigger");
            }
            return;
        }

        synchronized (this) {
            if (captureInProgress) {
                if (attempt < 20) {
                    captureScheduler.schedule(
                            () -> attemptScheduledCapture(seq, source, projectSnapshot, groupSnapshot, attempt + 1),
                            100, TimeUnit.MILLISECONDS);
                } else {
                    activeCountdownSeq = -1;
                    setStatus("CAPTURE FAILED #" + seq + " • previous capture remained busy");
                }
                return;
            }
            captureInProgress = true;
        }

        activeCountdownSeq = -1;
        ui(() -> {
            flashOverlay.setAlpha(0.85f);
            flashOverlay.animate().alpha(0f).setDuration(260).start();
            countdownBadge.setVisibility(View.GONE);
        });

        final long callNs = SystemClock.elapsedRealtimeNanos();
        setStatus("Capture #" + seq + " • DIRECT CAMERA PATH • shutter requested");

        // This is intentionally the same direct Camera2 call shape used by the working
        // storage-diagnostics button. No UI-thread wrapper or shared countdown executor.
        cam.capture(new Camera2Controller.CaptureCallback() {
            @Override public void onCaptured(byte[] data, long sensorTimestampNs, Camera2Controller.LensInfo lens) {
                try {
                    setStatus("Capture #" + seq + " • JPEG received • " + data.length + " bytes");
                    String uri = saveJpeg(data, seq, lens, projectSnapshot, "HOST".equals(source), groupSnapshot);
                    String lensName = lens == null ? "camera" : lens.label;
                    if (uri == null || uri.isEmpty()) {
                        setStatus("SAVE FAILED #" + seq + " • JPEG captured but storage transaction failed");
                    } else {
                        setStatus("Saved capture #" + seq
                                + (Build.VERSION.SDK_INT == 30 ? " • API30 T10 PATH" : "")
                                + " • DIRECT CAMERA PATH • " + source.toLowerCase(Locale.US)
                                + " • " + lensName + " • " + uri);
                        if (net != null) net.report(seq, callNs, uri);
                    }
                } catch (Throwable t) {
                    setStatus("CAPTURE FAILED #" + seq + " • callback " + t.getClass().getSimpleName()
                            + ": " + (t.getMessage() == null ? "no detail" : t.getMessage()));
                } finally {
                    captureInProgress = false;
                }
            }

            @Override public void onError(String message) {
                captureInProgress = false;
                setStatus("CAPTURE FAILED #" + seq + " • Camera2 • " + message);
            }
        });
    }

'''
s = s[:start] + replacement + s[end:]

old_shutdown = "        scheduler.shutdownNow();\n        autoScheduler.shutdownNow();"
new_shutdown = "        scheduler.shutdownNow();\n        captureScheduler.shutdownNow();\n        autoScheduler.shutdownNow();"
if old_shutdown not in s:
    raise SystemExit('shutdown anchor not found')
s = s.replace(old_shutdown, new_shutdown, 1)

main.write_text(s)
