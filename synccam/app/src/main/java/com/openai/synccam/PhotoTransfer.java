package com.openai.synccam;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhotoTransfer {
    static final int PHOTO_PORT = 39394;
    private static final String MAGIC = "SYNCCAM_PHOTO_V1";
    private static final String MANIFEST_PREFS = "synccam_photo_manifest";
    private static final String SENT_KEY = "sent_keys";

    interface Listener {
        boolean acceptIncoming(String groupCode);
        void onPhotoReceived(String remoteDeviceId, int sequence, String savedUri, int totalReceived);
        void onTransferStatus(String message);
    }

    interface SendComplete {
        void done(int requestId, int sentCount);
    }

    private static final class PhotoRecord {
        final int sequence;
        final String name;
        final String uri;
        final String groupCode;

        PhotoRecord(int sequence, String name, String uri, String groupCode) {
            this.sequence = sequence;
            this.name = name;
            this.uri = uri;
            this.groupCode = groupCode == null ? "" : groupCode;
        }
    }

    private static final class RequestContext {
        final String project;
        final int minSequence;

        RequestContext(String project, int minSequence) {
            this.project = project;
            this.minSequence = Math.max(1, minSequence);
        }
    }

    private final Context context;
    private final String deviceId;
    private final Listener listener;
    private final SharedPreferences manifestPrefs;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<String, PhotoRecord> localPhotos = new LinkedHashMap<>();
    private final Set<String> receivedKeys = new HashSet<>();
    private final Set<String> sentKeys = new HashSet<>();
    private final Map<Integer, RequestContext> requestContexts = new HashMap<>();

    private volatile boolean running = true;
    private ServerSocket server;
    private int totalReceived = 0;
    private volatile String hostProjectName = "Session";

    PhotoTransfer(Context context, String deviceId, Listener listener) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
        this.listener = listener;
        this.manifestPrefs = this.context.getSharedPreferences(MANIFEST_PREFS, Context.MODE_PRIVATE);
        loadManifest();
    }

    void setHostProjectName(String projectName) {
        hostProjectName = safeSegment(projectName, "Session");
    }

    synchronized void bindRequestProject(int requestId, String projectName, int minSequence) {
        requestContexts.put(requestId, new RequestContext(safeSegment(projectName, "Session"), minSequence));
    }

    synchronized void releaseRequestProject(int requestId) {
        requestContexts.remove(requestId);
    }

    void start() {
        io.execute(this::serverLoop);
    }

    synchronized void recordLocal(int sequence, String name, String uri, String groupCode) {
        String group = groupCode == null ? "" : groupCode;
        PhotoRecord record = new PhotoRecord(sequence, name, uri, group);
        localPhotos.put(photoKey(group, sequence), record);
        persistPhoto(record);
    }

    synchronized int localCount() {
        return localPhotos.size();
    }

    synchronized int totalReceived() {
        return totalReceived;
    }

    private String photoKey(String group, int sequence) {
        return (group == null ? "" : group) + "#" + sequence;
    }

    private String sentKey(String group, int sequence) {
        return (group == null ? "" : group) + "#" + sequence;
    }

    private String prefPhotoKey(String group, int sequence) {
        String g = group == null || group.isEmpty() ? "NONE" : group.replaceAll("[^A-Za-z0-9_-]", "_");
        return "photo." + g + "." + sequence;
    }

    private synchronized void loadManifest() {
        localPhotos.clear();
        sentKeys.clear();
        Set<String> savedSent = manifestPrefs.getStringSet(SENT_KEY, null);
        if (savedSent != null) sentKeys.addAll(savedSent);
        for (Map.Entry<String, ?> e : manifestPrefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("photo.") || !(e.getValue() instanceof String)) continue;
            try {
                JSONObject j = new JSONObject((String) e.getValue());
                int sequence = j.getInt("sequence");
                String name = j.optString("name", "");
                String uri = j.optString("uri", "");
                String group = j.optString("group", "");
                if (uri.isEmpty()) continue;
                localPhotos.put(photoKey(group, sequence), new PhotoRecord(sequence, name, uri, group));
            } catch (Exception ignored) { }
        }
    }

    private synchronized void persistPhoto(PhotoRecord r) {
        try {
            JSONObject j = new JSONObject();
            j.put("sequence", r.sequence);
            j.put("name", r.name == null ? "" : r.name);
            j.put("uri", r.uri == null ? "" : r.uri);
            j.put("group", r.groupCode);
            manifestPrefs.edit().putString(prefPhotoKey(r.groupCode, r.sequence), j.toString()).apply();
        } catch (Exception ignored) { }
    }

    private synchronized void persistSentKeys() {
        manifestPrefs.edit().putStringSet(SENT_KEY, new HashSet<>(sentKeys)).apply();
    }

    void sendAllAsync(InetAddress hostAddress, String groupCode, int requestId, int minSequence, SendComplete complete) {
        io.execute(() -> {
            int sent = 0;
            int floor = Math.max(1, minSequence);
            List<PhotoRecord> snapshot = new ArrayList<>();
            String group = groupCode == null ? "" : groupCode;
            synchronized (PhotoTransfer.this) {
                for (PhotoRecord r : localPhotos.values()) {
                    if (!group.equals(r.groupCode)) continue;
                    if (r.sequence < floor) continue;
                    if (!sentKeys.contains(sentKey(group, r.sequence))) snapshot.add(r);
                }
            }
            listener.onTransferStatus("Uploading " + snapshot.size() + " photo(s) from this project session to host…");
            for (PhotoRecord r : snapshot) {
                if (!running) break;
                if (sendOne(hostAddress, group, requestId, r)) {
                    sent++;
                    synchronized (PhotoTransfer.this) {
                        sentKeys.add(sentKey(group, r.sequence));
                        persistSentKeys();
                    }
                }
            }
            listener.onTransferStatus("Photo upload complete • " + sent + " sent");
            if (complete != null) complete.done(requestId, sent);
        });
    }

    private boolean sendOne(InetAddress hostAddress, String groupCode, int requestId, PhotoRecord r) {
        Socket s = new Socket();
        InputStream rawIn = null;
        try {
            s.connect(new InetSocketAddress(hostAddress, PHOTO_PORT), 5000);
            s.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            out.writeUTF(MAGIC);
            out.writeUTF(groupCode);
            out.writeUTF(deviceId);
            out.writeInt(requestId);
            out.writeInt(r.sequence);
            out.writeUTF(r.name == null ? "" : r.name);
            out.flush();

            rawIn = context.getContentResolver().openInputStream(Uri.parse(r.uri));
            if (rawIn == null) return false;
            try (InputStream in = new BufferedInputStream(rawIn)) {
                rawIn = null;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
            }
            out.flush();
            try { s.shutdownOutput(); } catch (Exception ignored) { }
            return true;
        } catch (Exception e) {
            listener.onTransferStatus("Photo upload failed • " + e.getMessage());
            return false;
        } finally {
            try { if (rawIn != null) rawIn.close(); } catch (Exception ignored) { }
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private void serverLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PHOTO_PORT));
            while (running) {
                Socket s = server.accept();
                io.execute(() -> receiveOne(s));
            }
        } catch (Exception e) {
            if (running) listener.onTransferStatus("Photo receiver error • " + e.getMessage());
        }
    }

    private void receiveOne(Socket socket) {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()))) {
            s.setSoTimeout(20000);
            String magic = in.readUTF();
            if (!MAGIC.equals(magic)) return;
            String group = in.readUTF();
            String remoteId = in.readUTF();
            int requestId = in.readInt();
            int sequence = in.readInt();
            String originalName = in.readUTF(); // preserve capture-time filename after sanitisation

            if (!listener.acceptIncoming(group)) {
                drain(in);
                return;
            }

            RequestContext request;
            synchronized (this) { request = requestContexts.get(requestId); }
            String project = request == null ? hostProjectName : request.project;
            if (request != null && sequence < request.minSequence) {
                drain(in);
                return;
            }

            String key = project + "|" + remoteId + "#" + sequence;
            synchronized (this) {
                if (receivedKeys.contains(key)) {
                    drain(in);
                    return;
                }
                receivedKeys.add(key);
            }

            String uri = saveIncoming(in, remoteId, sequence, project, originalName);
            if (uri.isEmpty()) {
                synchronized (this) { receivedKeys.remove(key); }
                return;
            }
            int count;
            synchronized (this) {
                totalReceived++;
                count = totalReceived;
            }
            listener.onPhotoReceived(remoteId, sequence, uri, count);
        } catch (Exception e) {
            listener.onTransferStatus("Photo receive failed • " + e.getMessage());
        }
    }

    private String saveIncoming(InputStream in, String remoteId, int sequence, String project, String originalName) {
        Uri u = null;
        try {
            String safeId = StorageLayout.device(remoteId, "REMOTE");
            String safeProject = StorageLayout.project(project);
            String name = StorageLayout.incomingFileName(originalName, safeId, sequence);
            String relative = StorageLayout.relativeDevicePath(safeProject, false, safeId);
            File legacyDir = StorageLayout.legacyDeviceDir(safeProject, false, safeId);

            u = MediaStoreJpegWriter.writePending(context, in, name, relative, legacyDir);
            MediaStoreJpegWriter.publish(context, u);
            return u.toString();
        } catch (Exception e) {
            MediaStoreJpegWriter.abort(context, u);
            String detail = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "no detail" : e.getMessage());
            listener.onTransferStatus("Host save failed • " + detail);
            return "";
        }
    }

    private void drain(InputStream in) {
        try {
            byte[] buf = new byte[32 * 1024];
            while (in.read(buf) >= 0) { }
        } catch (Exception ignored) { }
    }

    private static String safeSegment(String raw, String fallback) {
        return StorageLayout.safeSegment(raw, fallback);
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        io.shutdownNow();
    }
}
