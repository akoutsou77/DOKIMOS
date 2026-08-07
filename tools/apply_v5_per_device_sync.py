from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
main_path = ROOT / 'synccam/app/src/main/java/com/openai/synccam/MainActivity.java'
transfer_path = ROOT / 'synccam/app/src/main/java/com/openai/synccam/PhotoTransfer.java'
build_path = ROOT / 'synccam/app/build.gradle'
workflow_path = ROOT / '.github/workflows/synccam-build.yml'


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Missing expected block: {label}')
    return text.replace(old, new, 1)

main = main_path.read_text()

main = replace_once(main,
    'import android.widget.FrameLayout;\nimport android.widget.LinearLayout;',
    'import android.widget.FrameLayout;\nimport android.widget.HorizontalScrollView;\nimport android.widget.LinearLayout;',
    'HorizontalScrollView import')

main = replace_once(main,
    '    private TextView syncValue, peerValue, hotspotInfo, photoSync;\n    private EditText code;',
    '    private TextView syncValue, peerValue, hotspotInfo, photoSync;\n    private EditText code;\n    private LinearLayout deviceSyncSection, deviceSyncList;\n    private HorizontalScrollView deviceSyncScroller;\n    private final Map<String, TextView> deviceSyncStatusViews = new HashMap<>();\n    private final Set<String> renderedDeviceIds = new HashSet<>();',
    'device sync UI fields')

main = replace_once(main,
    '            @Override public void onPhotoReceived(String remoteDeviceId, int sequence, String savedUri, int totalReceived) {\n                setStatus("Host saved photo from " + remoteDeviceId + " • capture #" + sequence + " • total received " + totalReceived);\n            }',
    '            @Override public void onPhotoReceived(String remoteDeviceId, int sequence, String savedUri, int totalReceived) {\n                setDeviceSyncState(remoteDeviceId, "SAVED #" + sequence, GREEN);\n                setStatus("Host saved photo from " + remoteDeviceId + " • capture #" + sequence + " • total received " + totalReceived);\n            }',
    'photo receive device status')

anchor = '''        hotspotInfo.setOnClickListener(v -> openWifiSettings());\n        deck.addView(hotspotInfo);\n\n        LinearLayout shutterRow = new LinearLayout(this);'''
insert = '''        hotspotInfo.setOnClickListener(v -> openWifiSettings());\n        deck.addView(hotspotInfo);\n\n        deviceSyncSection = new LinearLayout(this);\n        deviceSyncSection.setOrientation(LinearLayout.VERTICAL);\n        deviceSyncSection.setPadding(0, dp(5), 0, 0);\n        deviceSyncSection.setVisibility(View.GONE);\n\n        TextView deviceSyncHeader = label("PHOTO SYNC BY DEVICE", 9, MUTED, Typeface.BOLD);\n        deviceSyncHeader.setLetterSpacing(0.10f);\n        deviceSyncSection.addView(deviceSyncHeader);\n\n        deviceSyncScroller = new HorizontalScrollView(this);\n        deviceSyncScroller.setHorizontalScrollBarEnabled(false);\n        deviceSyncList = new LinearLayout(this);\n        deviceSyncList.setOrientation(LinearLayout.HORIZONTAL);\n        deviceSyncList.setPadding(0, dp(4), 0, dp(2));\n        deviceSyncScroller.addView(deviceSyncList, new HorizontalScrollView.LayoutParams(-2, dp(62)));\n        deviceSyncSection.addView(deviceSyncScroller, new LinearLayout.LayoutParams(-1, dp(66)));\n        deck.addView(deviceSyncSection);\n\n        LinearLayout shutterRow = new LinearLayout(this);'''
main = replace_once(main, anchor, insert, 'device sync section')

main = main.replace('photoSync = smallButton("SYNC\\nPHOTOS");', 'photoSync = smallButton("SYNC\\nALL");', 1)
main = main.replace('TextView footer = label("CAPTURE ALL synchronizes shutters  •  SYNC PHOTOS copies client JPEGs to the host phone", 9, 0xFF8D96A3, Typeface.NORMAL);',
                    'TextView footer = label("Tap a device to sync only that phone • SYNC ALL pulls new JPEGs from every client", 9, 0xFF8D96A3, Typeface.NORMAL);', 1)

needle = '''    private void setPhotoSyncEnabled(boolean enabled) {\n        ui(() -> {\n            photoSync.setEnabled(enabled);\n            photoSync.setAlpha(enabled ? 1f : 0.42f);\n        });\n    }\n\n    private void openWifiSettings() {'''
replacement = '''    private void setPhotoSyncEnabled(boolean enabled) {\n        ui(() -> {\n            photoSync.setEnabled(enabled);\n            photoSync.setAlpha(enabled ? 1f : 0.42f);\n        });\n    }\n\n    private void refreshDeviceSyncList(Map<String, InetAddress> snapshot) {\n        final Map<String, InetAddress> peersNow = new HashMap<>(snapshot);\n        ui(() -> {\n            if (deviceSyncSection == null || deviceSyncList == null) return;\n            boolean visible = net != null && net.host && !peersNow.isEmpty();\n            deviceSyncSection.setVisibility(visible ? View.VISIBLE : View.GONE);\n            if (!visible) {\n                deviceSyncList.removeAllViews();\n                deviceSyncStatusViews.clear();\n                renderedDeviceIds.clear();\n                return;\n            }\n\n            Set<String> ids = new HashSet<>(peersNow.keySet());\n            if (ids.equals(renderedDeviceIds)) return;\n            renderedDeviceIds.clear();\n            renderedDeviceIds.addAll(ids);\n            deviceSyncList.removeAllViews();\n            deviceSyncStatusViews.clear();\n\n            ArrayList<String> ordered = new ArrayList<>(ids);\n            ordered.sort(String::compareTo);\n            for (String id : ordered) {\n                InetAddress address = peersNow.get(id);\n                LinearLayout card = new LinearLayout(this);\n                card.setOrientation(LinearLayout.VERTICAL);\n                card.setGravity(Gravity.CENTER);\n                card.setPadding(dp(10), dp(6), dp(10), dp(6));\n                card.setBackground(roundRect(BG_CARD, 12, 1, 0xFF454C58));\n\n                TextView device = label(id, 10, Color.WHITE, Typeface.BOLD);\n                device.setGravity(Gravity.CENTER);\n                TextView ip = label(address == null ? "" : address.getHostAddress(), 8, MUTED, Typeface.NORMAL);\n                ip.setGravity(Gravity.CENTER);\n                TextView action = label("SYNC", 10, GREEN, Typeface.BOLD);\n                action.setGravity(Gravity.CENTER);\n                action.setPadding(0, dp(2), 0, 0);\n                card.addView(device);\n                card.addView(ip);\n                card.addView(action);\n                deviceSyncStatusViews.put(id, action);\n\n                card.setOnClickListener(v -> {\n                    setDeviceSyncState(id, "REQUESTED", AMBER);\n                    if (net != null) net.requestPhotoSyncFor(id);\n                });\n\n                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(116), dp(58));\n                cp.rightMargin = dp(6);\n                deviceSyncList.addView(card, cp);\n            }\n        });\n    }\n\n    private void setDeviceSyncState(String id, String state, int color) {\n        ui(() -> {\n            TextView v = deviceSyncStatusViews.get(id);\n            if (v != null) {\n                v.setText(state);\n                v.setTextColor(color);\n            }\n        });\n    }\n\n    private void openWifiSettings() {'''
main = replace_once(main, needle, replacement, 'device sync helper methods')

main = replace_once(main,
    '        setStatus("Host ready • starting local hotspot…");\n        startLocalHotspot();',
    '        refreshDeviceSyncList(new HashMap<>());\n        setStatus("Host ready • starting local hotspot…");\n        startLocalHotspot();',
    'host reset device list')

main = replace_once(main,
    '        setGroupChip(c);\n        InetAddress gw = net.gatewayAddress();',
    '        setGroupChip(c);\n        refreshDeviceSyncList(new HashMap<>());\n        InetAddress gw = net.gatewayAddress();',
    'client hide device list')

main = replace_once(main,
    '        final Set<Integer> photoSyncSeen = new HashSet<>();\n        final Set<String> photoDonePeers = new HashSet<>();',
    '        final Set<Integer> photoSyncSeen = new HashSet<>();\n        final Set<String> photoDonePeers = new HashSet<>();\n        final Map<Integer, String> individualPhotoRequests = new HashMap<>();',
    'individual photo request map')

old_tick = '''                        for (String id : stale) {\n                            peerSeen.remove(id);\n                            peerAddress.remove(id);\n                        }\n                        setPeers(peerSeen.size() + " connected clients");\n                    }'''
new_tick = '''                        for (String id : stale) {\n                            peerSeen.remove(id);\n                            peerAddress.remove(id);\n                        }\n                        setPeers(peerSeen.size() + " connected clients");\n                        refreshDeviceSyncList(new HashMap<>(peerAddress));\n                    }'''
main = replace_once(main, old_tick, new_tick, 'refresh device list in host tick')

old_request = '''            String message = "PHOTO_SYNC|" + groupCode + "|" + request;\n            for (InetAddress address : targets.values()) send(address, message);\n            io.schedule(() -> {\n                for (InetAddress address : targets.values()) send(address, message);\n            }, 120, TimeUnit.MILLISECONDS);\n            setStatus("Photo sync requested from " + targets.size() + " client phone(s)…");\n        }\n\n        void report(int seq, long callNs, String uri) {'''
new_request = '''            String message = "PHOTO_SYNC|" + groupCode + "|" + request;\n            for (Map.Entry<String, InetAddress> e : targets.entrySet()) {\n                setDeviceSyncState(e.getKey(), "REQUESTED", AMBER);\n                send(e.getValue(), message);\n            }\n            io.schedule(() -> {\n                for (InetAddress address : targets.values()) send(address, message);\n            }, 120, TimeUnit.MILLISECONDS);\n            setStatus("Photo sync requested from " + targets.size() + " client phone(s)…");\n        }\n\n        void requestPhotoSyncFor(String targetId) {\n            if (!host || photoTransfer == null || targetId == null) return;\n            InetAddress address;\n            synchronized (peerSeen) { address = peerAddress.get(targetId); }\n            if (address == null) {\n                setDeviceSyncState(targetId, "OFFLINE", AMBER);\n                setStatus("Photo sync • device " + targetId + " is no longer connected");\n                return;\n            }\n            int request = photoRequestSeq++;\n            synchronized (individualPhotoRequests) { individualPhotoRequests.put(request, targetId); }\n            String message = "PHOTO_SYNC|" + groupCode + "|" + request;\n            send(address, message);\n            io.schedule(() -> send(address, message), 120, TimeUnit.MILLISECONDS);\n            setDeviceSyncState(targetId, "UPLOADING…", AMBER);\n            setStatus("Photo sync requested from device " + targetId + "…");\n        }\n\n        void report(int seq, long callNs, String uri) {'''
main = replace_once(main, old_request, new_request, 'per-device request method')

old_done = '''                        if (request == activePhotoRequest) {\n                            int done;\n                            synchronized (photoDonePeers) {\n                                photoDonePeers.add(p[2]);\n                                done = photoDonePeers.size();\n                            }\n                            if (done >= expectedPhotoPeers) {\n                                io.schedule(() -> {\n                                    int saved = Math.max(0, photoTransfer.totalReceived() - receivedAtPhotoStart);\n                                    setStatus("Photo sync complete • " + saved + " new client photo(s) stored on host");\n                                }, 600, TimeUnit.MILLISECONDS);\n                            } else {\n                                setStatus("Photo sync • " + done + "/" + expectedPhotoPeers + " client phones finished");\n                            }\n                        }'''
new_done = '''                        String individualTarget;\n                        synchronized (individualPhotoRequests) { individualTarget = individualPhotoRequests.remove(request); }\n                        int sentCount = Integer.parseInt(p[4]);\n                        if (individualTarget != null) {\n                            setDeviceSyncState(p[2], sentCount == 0 ? "UP TO DATE" : "DONE • " + sentCount, GREEN);\n                            setStatus("Device " + p[2] + " photo sync complete • " + sentCount + " new photo(s)");\n                        } else if (request == activePhotoRequest) {\n                            setDeviceSyncState(p[2], sentCount == 0 ? "UP TO DATE" : "DONE • " + sentCount, GREEN);\n                            int done;\n                            synchronized (photoDonePeers) {\n                                photoDonePeers.add(p[2]);\n                                done = photoDonePeers.size();\n                            }\n                            if (done >= expectedPhotoPeers) {\n                                io.schedule(() -> {\n                                    int saved = Math.max(0, photoTransfer.totalReceived() - receivedAtPhotoStart);\n                                    setStatus("Photo sync complete • " + saved + " new client photo(s) stored on host");\n                                }, 600, TimeUnit.MILLISECONDS);\n                            } else {\n                                setStatus("Photo sync • " + done + "/" + expectedPhotoPeers + " client phones finished");\n                            }\n                        }'''
main = replace_once(main, old_done, new_done, 'per-device completion handling')

main_path.write_text(main)

transfer = transfer_path.read_text()
transfer = replace_once(transfer,
    '    private final Set<String> receivedKeys = new HashSet<>();',
    '    private final Set<String> receivedKeys = new HashSet<>();\n    private final Set<String> sentKeys = new HashSet<>();',
    'sent key set')

old_send = '''            int sent = 0;\n            List<PhotoRecord> snapshot;\n            synchronized (PhotoTransfer.this) {\n                snapshot = new ArrayList<>(localPhotos.values());\n            }\n            listener.onTransferStatus("Uploading " + snapshot.size() + " photo(s) to host…");\n            for (PhotoRecord r : snapshot) {\n                if (!running) break;\n                if (sendOne(hostAddress, groupCode, requestId, r)) sent++;\n            }'''
new_send = '''            int sent = 0;\n            List<PhotoRecord> snapshot = new ArrayList<>();\n            String prefix = groupCode + "|" + hostAddress.getHostAddress() + "|";\n            synchronized (PhotoTransfer.this) {\n                for (PhotoRecord r : localPhotos.values()) {\n                    if (!sentKeys.contains(prefix + r.sequence)) snapshot.add(r);\n                }\n            }\n            listener.onTransferStatus("Uploading " + snapshot.size() + " new photo(s) to host…");\n            for (PhotoRecord r : snapshot) {\n                if (!running) break;\n                if (sendOne(hostAddress, groupCode, requestId, r)) {\n                    sent++;\n                    synchronized (PhotoTransfer.this) { sentKeys.add(prefix + r.sequence); }\n                }\n            }'''
transfer = replace_once(transfer, old_send, new_send, 'incremental transfer')
transfer_path.write_text(transfer)

build = build_path.read_text()
build = build.replace('versionCode 4', 'versionCode 5').replace("versionName '4.0.0-photo-sync'", "versionName '5.0.0-device-sync'")
build_path.write_text(build)

workflow = workflow_path.read_text()
workflow = workflow.replace('SyncCam-v4.0.0-apk', 'SyncCam-v5.0.0-apk')
workflow = workflow.replace('SyncCam v4.0.0', 'SyncCam v5.0.0')
workflow = workflow.replace('v4.0.0', 'v5.0.0')
workflow = workflow.replace(
    'Adds host-side photo collection. After synchronized capture, the HOST can press SYNC PHOTOS to request all client JPEGs. Photos are transferred directly over the local Wi-Fi/hotspot using a TCP photo channel and stored in Pictures/SyncCam on the host phone. Existing capture synchronization and direct hotspot networking are preserved.',
    'Adds per-device host photo sync. The host displays each connected client separately and can sync one phone at a time or use SYNC ALL. Client uploads are incremental within the session, so already transferred photos are not resent. JPEGs are stored in Pictures/SyncCam on the host phone. Existing synchronized capture and hotspot/direct networking are preserved.'
)
workflow_path.write_text(workflow)

print('Applied SyncCam v5 per-device photo sync changes')
