package com.openai.synccam;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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

        PhotoRecord(int sequence, String name, String uri) {
            this.sequence = sequence;
            this.name = name;
            this.uri = uri;
        }
    }

    private final Context context;
    private final String deviceId;
    private final Listener listener;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<Integer, PhotoRecord> localPhotos = new LinkedHashMap<>();
    private final Set<String> receivedKeys = new HashSet<>();
    private final Set<String> sentKeys = new HashSet<>();

    private volatile boolean running = true;
    private ServerSocket server;
    private int totalReceived = 0;

    PhotoTransfer(Context context, String deviceId, Listener listener) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
        this.listener = listener;
    }

    void start() {
        io.execute(this::serverLoop);
    }

    synchronized void recordLocal(int sequence, String name, String uri) {
        localPhotos.put(sequence, new PhotoRecord(sequence, name, uri));
    }

    synchronized int localCount() {
        return localPhotos.size();
    }

    synchronized int totalReceived() {
        return totalReceived;
    }

    void sendAllAsync(InetAddress hostAddress, String groupCode, int requestId, SendComplete complete) {
        io.execute(() -> {
            int sent = 0;
            List<PhotoRecord> snapshot = new ArrayList<>();
            String prefix = groupCode + "|" + hostAddress.getHostAddress() + "|";
            synchronized (PhotoTransfer.this) {
                for (PhotoRecord r : localPhotos.values()) {
                    if (!sentKeys.contains(prefix + r.sequence)) snapshot.add(r);
                }
            }
            listener.onTransferStatus("Uploading " + snapshot.size() + " new photo(s) to host…");
            for (PhotoRecord r : snapshot) {
                if (!running) break;
                if (sendOne(hostAddress, groupCode, requestId, r)) {
                    sent++;
                    synchronized (PhotoTransfer.this) { sentKeys.add(prefix + r.sequence); }
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
            in.readInt(); // requestId, reserved for diagnostics
            int sequence = in.readInt();
            in.readUTF(); // original name; host uses a deterministic safe name

            if (!listener.acceptIncoming(group)) {
                drain(in);
                return;
            }

            String key = remoteId + "#" + sequence;
            synchronized (this) {
                if (receivedKeys.contains(key)) {
                    drain(in);
                    return;
                }
                receivedKeys.add(key);
            }

            String uri = saveIncoming(in, remoteId, sequence);
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

    private String saveIncoming(InputStream in, String remoteId, int sequence) {
        OutputStream rawOut = null;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String safeId = remoteId == null ? "REMOTE" : remoteId.replaceAll("[^A-Za-z0-9_-]", "");
            if (safeId.isEmpty()) safeId = "REMOTE";
            String name = "SyncCam_HOST_" + safeId + "_S" + sequence + "_" + stamp + ".jpg";

            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29)
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SyncCam");
            Uri u = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return "";

            rawOut = context.getContentResolver().openOutputStream(u);
            if (rawOut == null) return "";
            try (OutputStream out = new BufferedOutputStream(rawOut)) {
                rawOut = null;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
                out.flush();
            }
            return u.toString();
        } catch (Exception e) {
            listener.onTransferStatus("Host save failed • " + e.getMessage());
            return "";
        } finally {
            try { if (rawOut != null) rawOut.close(); } catch (Exception ignored) { }
        }
    }

    private void drain(InputStream in) {
        try {
            byte[] buf = new byte[32 * 1024];
            while (in.read(buf) >= 0) { }
        } catch (Exception ignored) { }
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        io.shutdownNow();
    }
}
