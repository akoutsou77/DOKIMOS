from pathlib import Path

p = Path('synccam/app/src/main/java/com/openai/synccam/MainActivity.java')
text = p.read_text(encoding='utf-8')
old = '''            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            name = "SyncCam_" + stamp + "_S" + seq + "_" + deviceId + ".jpg";
            String storageProject = safeProjectName(projectSnapshot == null ? projectName : projectSnapshot);
            String safeId = safeProjectName(deviceId).replace(" ", "_");
            String relative = Environment.DIRECTORY_PICTURES + "/SyncCam";
            if (hostCapture) relative += "/" + storageProject + "/HOST_" + safeId;
            File legacyDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    hostCapture ? "SyncCam/" + storageProject + "/HOST_" + safeId : "SyncCam");'''
new = '''            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String fallbackProject = hostCapture ? projectName : "Session_" + (groupSnapshot == null ? "" : groupSnapshot);
            String storageProject = StorageLayout.project(projectSnapshot == null ? fallbackProject : projectSnapshot);
            name = StorageLayout.captureFileName(stamp, seq, deviceId);
            String relative = StorageLayout.relativeDevicePath(storageProject, hostCapture, deviceId);
            File legacyDir = StorageLayout.legacyDeviceDir(storageProject, hostCapture, deviceId);'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'API30 save block: expected exactly one match, found {count}')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')
print('API30/T10 storage block patched')
