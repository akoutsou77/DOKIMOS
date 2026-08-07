package com.openai.synccam;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int REQ = 42;
    private static final int PORT = 39393;
    private static final String GROUP = "239.255.77.77";
    private static final long LEAD_NS = 2_500_000_000L;

    private static final int BG_PANEL = 0xE6181B22;
    private static final int BG_CARD = 0xD9262B34;
    private static final int ACCENT = 0xFFE95A5A;
    private static final int GREEN = 0xFF55D58A;
    private static final int AMBER = 0xFFFFC65C;
    private static final int MUTED = 0xFFAEB6C2;

    private SurfaceView surface;
    private TextView status, sync, peers, roleChip, groupChip, countdownBadge, trigger;
    private TextView syncValue, peerValue, hotspotInfo, photoSync;
    private EditText code;
    private View flashOverlay;
    private Camera camera;
    private int cameraId = 0;
    private boolean surfaceReady;
    private Net net;
    private PhotoTransfer photoTransfer;
    private final String deviceId = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int sequence = 1;
    private volatile int activeCountdownSeq = -1;

    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private String hotspotSsid = "";
    private String hotspotPassword = "";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        buildUi();
        photoTransfer = new PhotoTransfer(this, deviceId, new PhotoTransfer.Listener() {
            @Override public boolean acceptIncoming(String groupCode) {
                return net != null && net.host && groupCode != null && groupCode.equals(net.groupCode);
            }

            @Override public void onPhotoReceived(String remoteDeviceId, int sequence, String savedUri, int totalReceived) {
                setStatus("Host saved photo from " + remoteDeviceId + " • capture #" + sequence + " • total received " + totalReceived);
            }

            @Override public void onTransferStatus(String message) {
                setStatus(message);
            }
        });
        photoTransfer.start();
        requestPermissions();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        GradientDrawable shadeBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC9000000, 0x18000000, 0x00000000, 0x30000000, 0xC9000000});
        shade.setBackground(shadeBg);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        ReticleView reticle = new ReticleView(this);
        root.addView(reticle, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), dp(12), dp(18), dp(12));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("SYNC", 22, Color.WHITE, Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        TextView subtitle = label("CAM  •  MULTI-CAMERA CONTROL", 10, MUTED, Typeface.BOLD);
        subtitle.setLetterSpacing(0.12f);
        brand.addView(title);
        brand.addView(subtitle);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        roleChip = chip("STANDBY", 0xCC343A45);
        brandRow.addView(roleChip, lpWrap(0, 0, 0, 0));
        top.addView(brandRow);

        LinearLayout identityRow = new LinearLayout(this);
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        identityRow.setPadding(0, dp(8), 0, 0);
        TextView id = label("DEVICE  " + deviceId, 11, 0xFFCDD3DB, Typeface.BOLD);
        identityRow.addView(id, new LinearLayout.LayoutParams(0, -2, 1f));
        groupChip = chip("NO GROUP", 0xA62B3039);
        identityRow.addView(groupChip);
        top.addView(identityRow);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topLp.topMargin = dp(8);
        root.addView(top, topLp);

        countdownBadge = chip("ARMED", 0xE6E95A5A);
        countdownBadge.setTextSize(16);
        countdownBadge.setPadding(dp(18), dp(10), dp(18), dp(10));
        countdownBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        countLp.topMargin = dp(104);
        root.addView(countdownBadge, countLp);

        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);
        deck.setPadding(dp(16), dp(12), dp(16), dp(12));
        deck.setBackground(roundRect(BG_PANEL, 28, 0, 0));

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout stateText = new LinearLayout(this);
        stateText.setOrientation(LinearLayout.VERTICAL);
        status = label("Starting…", 14, Color.WHITE, Typeface.BOLD);
        TextView stateHint = label("Router Wi-Fi or one-phone hotspot are both supported", 10, MUTED, Typeface.NORMAL);
        stateHint.setPadding(0, dp(3), 0, 0);
        stateText.addView(status);
        stateText.addView(stateHint);
        stateRow.addView(stateText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView live = chip("● LIVE", 0x66304B3A);
        live.setTextColor(GREEN);
        stateRow.addView(live);
        deck.addView(stateRow);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setPadding(0, dp(10), 0, 0);
        LinearLayout syncCard = metricCard("CLOCK SYNC", "WAITING");
        syncValue = (TextView) syncCard.getChildAt(1);
        sync = (TextView) syncCard.getChildAt(2);
        metrics.addView(syncCard, weightedCard(1f, 0, dp(5)));
        LinearLayout peerCard = metricCard("DEVICES", "0");
        peerValue = (TextView) peerCard.getChildAt(1);
        peers = (TextView) peerCard.getChildAt(2);
        metrics.addView(peerCard, weightedCard(1f, dp(5), 0));
        deck.addView(metrics);

        TextView groupLabel = label("CAPTURE GROUP", 10, MUTED, Typeface.BOLD);
        groupLabel.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams groupLabelLp = new LinearLayout.LayoutParams(-1, -2);
        groupLabelLp.topMargin = dp(10);
        deck.addView(groupLabel, groupLabelLp);

        LinearLayout codeRow = new LinearLayout(this);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        code = new EditText(this);
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(0xFF7F8793);
        code.setHint("000000");
        code.setTextSize(20);
        code.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        code.setGravity(Gravity.CENTER);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setSelectAllOnFocus(true);
        code.setBackground(roundRect(BG_CARD, 14, 1, 0xFF454C58));
        code.setPadding(dp(12), 0, dp(12), 0);
        codeRow.addView(code, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView copy = smallButton("COPY");
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(dp(72), dp(48));
        copyLp.leftMargin = dp(8);
        codeRow.addView(copy, copyLp);
        deck.addView(codeRow, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout roles = new LinearLayout(this);
        roles.setPadding(0, dp(8), 0, 0);
        TextView host = actionButton("HOST + HOTSPOT", 0xFF343B46, Color.WHITE);
        TextView join = actionButton("JOIN GROUP", 0xFF343B46, Color.WHITE);
        roles.addView(host, weightedButton(1f, 0, dp(5)));
        roles.addView(join, weightedButton(1f, dp(5), 0));
        deck.addView(roles);

        hotspotInfo = label("HOST: creates local Wi-Fi when possible  •  CLIENT: connect to that Wi-Fi, then JOIN", 10, 0xFF9DA7B4, Typeface.NORMAL);
        hotspotInfo.setGravity(Gravity.CENTER);
        hotspotInfo.setPadding(dp(4), dp(7), dp(4), dp(3));
        hotspotInfo.setOnClickListener(v -> openWifiSettings());
        deck.addView(hotspotInfo);

        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setGravity(Gravity.CENTER);
        shutterRow.setPadding(0, dp(8), 0, dp(4));

        TextView flip = smallButton("↻\nCAMERA");
        flip.setGravity(Gravity.CENTER);
        shutterRow.addView(flip, new LinearLayout.LayoutParams(dp(70), dp(60)));

        FrameLayout shutterWrap = new FrameLayout(this);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(104), dp(104));
        swLp.leftMargin = dp(20);
        swLp.rightMargin = dp(20);
        shutterRow.addView(shutterWrap, swLp);

        View ring = new View(this);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(0x1AFFFFFF);
        ringBg.setStroke(dp(3), 0xE6FFFFFF);
        ring.setBackground(ringBg);
        shutterWrap.addView(ring, new FrameLayout.LayoutParams(-1, -1));

        trigger = label("CAPTURE\nALL", 13, Color.WHITE, Typeface.BOLD);
        trigger.setGravity(Gravity.CENTER);
        trigger.setLetterSpacing(0.05f);
        GradientDrawable triggerBg = new GradientDrawable();
        triggerBg.setShape(GradientDrawable.OVAL);
        triggerBg.setColor(ACCENT);
        trigger.setBackground(triggerBg);
        trigger.setEnabled(false);
        trigger.setAlpha(0.42f);
        FrameLayout.LayoutParams trigLp = new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER);
        shutterWrap.addView(trigger, trigLp);

        photoSync = smallButton("SYNC\nPHOTOS");
        photoSync.setGravity(Gravity.CENTER);
        photoSync.setEnabled(false);
        photoSync.setAlpha(0.42f);
        shutterRow.addView(photoSync, new LinearLayout.LayoutParams(dp(70), dp(60)));
        deck.addView(shutterRow);

        TextView footer = label("CAPTURE ALL synchronizes shutters  •  SYNC PHOTOS copies client JPEGs to the host phone", 9, 0xFF8D96A3, Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        deck.addView(footer);

        FrameLayout.LayoutParams deckLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        deckLp.leftMargin = dp(8);
        deckLp.rightMargin = dp(8);
        deckLp.bottomMargin = dp(8);
        root.addView(deck, deckLp);

        flashOverlay = new View(this);
        flashOverlay.setBackgroundColor(Color.WHITE);
        flashOverlay.setAlpha(0f);
        flashOverlay.setClickable(false);
        root.addView(flashOverlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        host.setOnClickListener(v -> hostAndHotspot());
        join.setOnClickListener(v -> join());
        trigger.setOnClickListener(v -> triggerAll());
        flip.setOnClickListener(v -> flip());
        photoSync.setOnClickListener(v -> syncPhotosToHost());
        copy.setOnClickListener(v -> copyConnectionInfo());
    }

    private LinearLayout metricCard(String title, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.setBackground(roundRect(BG_CARD, 14, 1, 0xFF353C47));
        TextView t = label(title, 9, MUTED, Typeface.BOLD);
        t.setLetterSpacing(0.10f);
        TextView v = label(value, 16, Color.WHITE, Typeface.BOLD);
        v.setPadding(0, dp(2), 0, 0);
        TextView d = label("—", 9, 0xFF99A3B0, Typeface.NORMAL);
        d.setPadding(0, dp(1), 0, 0);
        card.addView(t);
        card.addView(v);
        card.addView(d);
        return card;
    }

    private TextView label(String s, int size, int color, int style) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setTypeface(Typeface.create("sans-serif", style));
        return v;
    }

    private TextView chip(String s, int color) {
        TextView v = label(s, 10, Color.WHITE, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        v.setBackground(roundRect(color, 20, 1, 0x334E5662));
        return v;
    }

    private TextView actionButton(String s, int bg, int fg) {
        TextView b = label(s, 11, fg, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.04f);
        b.setBackground(roundRect(bg, 14, 1, 0xFF4B5360));
        return b;
    }

    private TextView smallButton(String s) {
        TextView b = actionButton(s, 0xFF2C323C, 0xFFE2E6EC);
        b.setTextSize(9);
        return b;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams weightedCard(float weight, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(66), weight);
        p.leftMargin = left;
        p.rightMargin = right;
        return p;
    }

    private LinearLayout.LayoutParams weightedButton(float weight, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), weight);
        p.leftMargin = left;
        p.rightMargin = right;
        return p;
    }

    private LinearLayout.LayoutParams lpWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    private void setStatus(String s) { ui(() -> status.setText(s)); }

    private void setSync(String s) {
        ui(() -> {
            sync.setText(s);
            if (s.contains("HOST")) {
                syncValue.setText("REFERENCE");
                syncValue.setTextColor(GREEN);
            } else if (s.contains("SYNCED")) {
                syncValue.setText("LOCKED");
                syncValue.setTextColor(GREEN);
            } else if (s.contains("calibrating")) {
                syncValue.setText("CALIBRATING");
                syncValue.setTextColor(AMBER);
            } else {
                syncValue.setText("SEARCHING");
                syncValue.setTextColor(AMBER);
            }
        });
    }

    private void setPeers(String s) {
        ui(() -> {
            peers.setText(s);
            String digits = s.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) peerValue.setText(digits);
            else if (s.toLowerCase(Locale.US).contains("client")) peerValue.setText("HOST");
            else peerValue.setText("—");
        });
    }

    private void setRole(String role, int color) {
        ui(() -> {
            roleChip.setText(role);
            roleChip.setBackground(roundRect(color, 20, 1, 0x335E6672));
        });
    }

    private void setGroupChip(String group) {
        ui(() -> groupChip.setText(group == null || group.isEmpty() ? "NO GROUP" : "GROUP  " + group));
    }

    private void setTriggerEnabled(boolean enabled) {
        ui(() -> {
            trigger.setEnabled(enabled);
            trigger.setAlpha(enabled ? 1f : 0.42f);
        });
    }

    private void setPhotoSyncEnabled(boolean enabled) {
        ui(() -> {
            photoSync.setEnabled(enabled);
            photoSync.setAlpha(enabled ? 1f : 0.42f);
        });
    }

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 29) startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            else startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private void copyConnectionInfo() {
        String c = code.getText().toString().trim();
        if (c.length() != 6) {
            Toast.makeText(this, "Create or enter a group first", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = "SyncCam group: " + c;
        if (!hotspotSsid.isEmpty()) {
            text += "\nWi-Fi SSID: " + hotspotSsid;
            if (!hotspotPassword.isEmpty()) text += "\nWi-Fi password: " + hotspotPassword;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SyncCam connection", text));
        Toast.makeText(this, "Connection information copied", Toast.LENGTH_SHORT).show();
    }

    private void requestPermissions() {
        ArrayList<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (Build.VERSION.SDK_INT <= 32 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (p.isEmpty()) startCore();
        else requestPermissions(p.toArray(new String[0]), REQ);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == REQ && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCore();
        else setStatus("Camera permission required");
    }

    private void startCore() {
        if (net == null) {
            net = new Net();
            net.start();
        }
        if (surfaceReady) openCamera();
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int z) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        closeCamera();
    }

    private synchronized void openCamera() {
        closeCamera();
        try {
            camera = Camera.open(cameraId);
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            if (p.getSupportedFlashModes() != null && p.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_OFF))
                p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surface.getHolder());
            camera.startPreview();
            setStatus("Camera ready • choose host or join");
        } catch (Exception e) {
            setStatus("Camera error • " + e.getMessage());
        }
    }

    private synchronized void closeCamera() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) { }
            camera.release();
            camera = null;
        }
    }

    private void flip() {
        int n = Camera.getNumberOfCameras();
        if (n < 2) {
            Toast.makeText(this, "Only one camera is available", Toast.LENGTH_SHORT).show();
            return;
        }
        cameraId = (cameraId + 1) % n;
        openCamera();
    }

    private void hostAndHotspot() {
        if (net == null) return;
        String c = String.format(Locale.US, "%06d", 100000 + new Random().nextInt(900000));
        code.setText(c);
        net.becomeHost(c);
        setTriggerEnabled(true);
        setPhotoSyncEnabled(true);
        setRole("HOST", 0xCC3A7255);
        setGroupChip(c);
        setStatus("Host ready • starting local hotspot…");
        startLocalHotspot();
    }

    private void startLocalHotspot() {
        if (hotspotReservation != null) {
            setStatus("Host ready • local hotspot already active");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Nearby Wi-Fi permission required for hotspot");
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ);
            return;
        }
        if (Build.VERSION.SDK_INT <= 32 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Location permission required for hotspot on this Android version");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ);
            return;
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    String ssid = "";
                    String pass = "";
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            SoftApConfiguration cfg = reservation.getSoftApConfiguration();
                            ssid = cfg.getSsid();
                            pass = cfg.getPassphrase();
                        } else {
                            WifiConfiguration cfg = reservation.getWifiConfiguration();
                            if (cfg != null) {
                                ssid = cfg.SSID;
                                pass = cfg.preSharedKey;
                            }
                        }
                    } catch (Exception ignored) { }
                    hotspotSsid = ssid == null ? "" : stripQuotes(ssid);
                    hotspotPassword = pass == null ? "" : stripQuotes(pass);
                    final String shownSsid = hotspotSsid.isEmpty() ? "Android local hotspot" : hotspotSsid;
                    ui(() -> {
                        hotspotInfo.setText("HOTSPOT  " + shownSsid + (hotspotPassword.isEmpty() ? "" : "   •   PASS  " + hotspotPassword) + "   •   tap for Wi-Fi");
                        hotspotInfo.setTextColor(GREEN);
                    });
                    setStatus("Hotspot active • connect the other phone, then JOIN group");
                }

                @Override public void onStopped() {
                    hotspotReservation = null;
                    hotspotSsid = "";
                    hotspotPassword = "";
                    ui(() -> {
                        hotspotInfo.setText("Local hotspot stopped • tap for Wi-Fi settings");
                        hotspotInfo.setTextColor(AMBER);
                    });
                    setStatus("Hotspot stopped • host control still active");
                }

                @Override public void onFailed(int reason) {
                    hotspotReservation = null;
                    ui(() -> {
                        hotspotInfo.setText("Could not create local hotspot • enable phone hotspot manually, then connect client");
                        hotspotInfo.setTextColor(AMBER);
                    });
                    setStatus("Host active • use manual Android hotspot");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            setStatus("Hotspot permission blocked • use manual Android hotspot");
        } catch (Exception e) {
            setStatus("Hotspot unavailable • use manual Android hotspot");
        }
    }

    private String stripQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private void join() {
        if (net == null) return;
        String c = code.getText().toString().trim();
        if (c.length() != 6) {
            Toast.makeText(this, "Enter the 6 digit host code", Toast.LENGTH_SHORT).show();
            return;
        }
        net.becomeClient(c);
        setTriggerEnabled(false);
        setPhotoSyncEnabled(false);
        setRole("CLIENT", 0xCC3C5F86);
        setGroupChip(c);
        InetAddress gw = net.gatewayAddress();
        if (gw == null) setStatus("Client searching • connect to host hotspot/Wi-Fi if needed");
        else setStatus("Client searching host at gateway " + gw.getHostAddress());
    }

    private void triggerAll() {
        if (net == null || !net.host) return;
        int seq = sequence++;
        long target = SystemClock.elapsedRealtimeNanos() + LEAD_NS;
        net.sendCapture(seq, target);
        scheduleCapture(seq, target, "HOST");
        setStatus("Capture #" + seq + " armed on all devices");
    }

    private void syncPhotosToHost() {
        if (net == null || !net.host) {
            Toast.makeText(this, "Photo sync is controlled by the host", Toast.LENGTH_SHORT).show();
            return;
        }
        net.requestPhotoSync();
    }

    private void scheduleCapture(int seq, long target, String source) {
        activeCountdownSeq = seq;
        showCountdown(seq, target);
        long delay = Math.max(0, target - SystemClock.elapsedRealtimeNanos() - 3_000_000L);
        scheduler.schedule(() -> {
            while (SystemClock.elapsedRealtimeNanos() < target) Thread.onSpinWait();
            ui(() -> takePicture(seq, source));
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

    private synchronized void takePicture(int seq, String source) {
        if (camera == null) return;
        activeCountdownSeq = -1;
        flashOverlay.setAlpha(0.85f);
        flashOverlay.animate().alpha(0f).setDuration(260).start();
        countdownBadge.setVisibility(View.GONE);
        final long callNs = SystemClock.elapsedRealtimeNanos();
        try {
            camera.takePicture(null, null, (data, c) -> {
                String uri = saveJpeg(data, seq);
                setStatus("Saved capture #" + seq + " • " + source.toLowerCase(Locale.US));
                if (net != null) net.report(seq, callNs, uri);
                try { c.startPreview(); } catch (Exception ignored) { }
            });
        } catch (Exception e) {
            setStatus("Capture failed • " + e.getMessage());
            try { camera.startPreview(); } catch (Exception ignored) { }
        }
    }

    private String saveJpeg(byte[] data, int seq) {
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
                o.write(data);
            }
            String uri = u.toString();
            if (photoTransfer != null) photoTransfer.recordLocal(seq, name, uri);
            return uri;
        } catch (Exception e) {
            setStatus("Save failed • " + e.getMessage());
            return "";
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        scheduler.shutdownNow();
        if (net != null) net.stop();
        if (photoTransfer != null) photoTransfer.stop();
        try {
            if (hotspotReservation != null) hotspotReservation.close();
        } catch (Exception ignored) { }
        hotspotReservation = null;
    }

    private final class Net {
        final ScheduledExecutorService io = Executors.newScheduledThreadPool(2);
        final Map<String, Long> peerSeen = new HashMap<>();
        final Map<String, InetAddress> peerAddress = new HashMap<>();
        final ArrayList<Sample> samples = new ArrayList<>();
        MulticastSocket socket;
        InetAddress multicast;
        InetAddress limitedBroadcast;
        InetAddress hostAddr;
        WifiManager.MulticastLock lock;
        volatile String groupCode = "";
        volatile boolean host = false;
        volatile long hostMinusClient = 0;
        volatile boolean running = true;
        int syncSeq = 1;
        int photoRequestSeq = 1;
        int activePhotoRequest = 0;
        int expectedPhotoPeers = 0;
        int receivedAtPhotoStart = 0;
        final Map<Integer, Boolean> captureSeen = new HashMap<>();
        final Set<Integer> photoSyncSeen = new HashSet<>();
        final Set<String> photoDonePeers = new HashSet<>();

        void start() {
            io.execute(() -> {
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    try {
                        lock = wm.createMulticastLock("SyncCam");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    } catch (Exception ignored) { }

                    multicast = InetAddress.getByName(GROUP);
                    limitedBroadcast = InetAddress.getByName("255.255.255.255");
                    socket = new MulticastSocket(null);
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    socket.bind(new InetSocketAddress(PORT));
                    try { socket.joinGroup(multicast); } catch (Exception ignored) { }

                    setStatus("Network ready • hotspot/direct mode enabled");
                    receive();
                } catch (Exception e) {
                    setStatus("Network error • " + e.getMessage());
                }
            });
            io.scheduleAtFixedRate(this::tick, 300, 500, TimeUnit.MILLISECONDS);
        }

        void becomeHost(String c) {
            groupCode = c;
            host = true;
            hostAddr = null;
            samples.clear();
            synchronized (peerSeen) {
                peerSeen.clear();
                peerAddress.clear();
            }
            synchronized (photoDonePeers) { photoDonePeers.clear(); }
            setSync("HOST • local clock is reference");
            setPeers("0 connected clients");
        }

        void becomeClient(String c) {
            groupCode = c;
            host = false;
            hostAddr = null;
            samples.clear();
            captureSeen.clear();
            synchronized (photoSyncSeen) { photoSyncSeen.clear(); }
            setSync("Searching for host…");
            setPeers("client mode");
        }

        InetAddress gatewayAddress() {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                DhcpInfo d = wm.getDhcpInfo();
                if (d == null || d.gateway == 0) return null;
                int g = d.gateway;
                byte[] q = new byte[]{
                        (byte) (g & 0xff),
                        (byte) ((g >> 8) & 0xff),
                        (byte) ((g >> 16) & 0xff),
                        (byte) ((g >> 24) & 0xff)
                };
                return InetAddress.getByAddress(q);
            } catch (Exception e) {
                return null;
            }
        }

        InetAddress subnetBroadcast() {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                DhcpInfo d = wm.getDhcpInfo();
                if (d == null || d.ipAddress == 0 || d.netmask == 0) return null;
                int b = (d.ipAddress & d.netmask) | ~d.netmask;
                byte[] q = new byte[]{
                        (byte) (b & 0xff),
                        (byte) ((b >> 8) & 0xff),
                        (byte) ((b >> 16) & 0xff),
                        (byte) ((b >> 24) & 0xff)
                };
                return InetAddress.getByAddress(q);
            } catch (Exception e) {
                return null;
            }
        }

        void tick() {
            if (socket == null || groupCode.isEmpty()) return;
            try {
                if (host) {
                    String beacon = "BEACON|" + groupCode + "|" + deviceId + "|" + SystemClock.elapsedRealtimeNanos();
                    send(multicast, beacon);
                    send(limitedBroadcast, beacon);
                    synchronized (peerSeen) {
                        long now = SystemClock.elapsedRealtime();
                        Set<String> stale = new HashSet<>();
                        for (Map.Entry<String, Long> e : peerSeen.entrySet()) {
                            if (now - e.getValue() > 5000) stale.add(e.getKey());
                        }
                        for (String id : stale) {
                            peerSeen.remove(id);
                            peerAddress.remove(id);
                        }
                        setPeers(peerSeen.size() + " connected clients");
                    }
                } else if (hostAddr == null) {
                    String discover = "DISCOVER|" + groupCode + "|" + deviceId;
                    InetAddress gateway = gatewayAddress();
                    InetAddress subnet = subnetBroadcast();
                    if (gateway != null) send(gateway, discover);
                    if (subnet != null) send(subnet, discover);
                    send(limitedBroadcast, discover);
                    send(multicast, discover);
                } else {
                    long t1 = SystemClock.elapsedRealtimeNanos();
                    send(hostAddr, "SYNC_REQ|" + groupCode + "|" + deviceId + "|" + (syncSeq++) + "|" + t1);
                }
            } catch (Exception ignored) { }
        }

        void sendCapture(int seq, long target) {
            String m = "CAPTURE|" + groupCode + "|" + seq + "|" + target;
            sendCaptureBurst(m);
            io.schedule(() -> sendCaptureBurst(m), 70, TimeUnit.MILLISECONDS);
            io.schedule(() -> sendCaptureBurst(m), 160, TimeUnit.MILLISECONDS);
        }

        void sendCaptureBurst(String m) {
            Set<InetAddress> destinations = new HashSet<>();
            synchronized (peerSeen) {
                destinations.addAll(peerAddress.values());
            }
            for (InetAddress a : destinations) send(a, m);
            send(multicast, m);
            send(limitedBroadcast, m);
        }

        void requestPhotoSync() {
            if (!host || photoTransfer == null) return;
            Map<String, InetAddress> targets = new HashMap<>();
            synchronized (peerSeen) {
                targets.putAll(peerAddress);
            }
            if (targets.isEmpty()) {
                setStatus("Photo sync • no connected client phones");
                return;
            }

            int request = photoRequestSeq++;
            activePhotoRequest = request;
            expectedPhotoPeers = targets.size();
            receivedAtPhotoStart = photoTransfer.totalReceived();
            synchronized (photoDonePeers) { photoDonePeers.clear(); }

            String message = "PHOTO_SYNC|" + groupCode + "|" + request;
            for (InetAddress address : targets.values()) send(address, message);
            io.schedule(() -> {
                for (InetAddress address : targets.values()) send(address, message);
            }, 120, TimeUnit.MILLISECONDS);
            setStatus("Photo sync requested from " + targets.size() + " client phone(s)…");
        }

        void report(int seq, long callNs, String uri) {
            if (!host && hostAddr != null)
                send(hostAddr, "CAPTURED|" + groupCode + "|" + deviceId + "|" + seq + "|" + callNs);
        }

        void receive() {
            byte[] buf = new byte[2048];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    long recv = SystemClock.elapsedRealtimeNanos();
                    String m = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                    handle(m, p.getAddress(), recv);
                } catch (Exception e) {
                    if (running) setStatus("Network receive error • " + e.getMessage());
                }
            }
        }

        void rememberPeer(String id, InetAddress address) {
            synchronized (peerSeen) {
                peerSeen.put(id, SystemClock.elapsedRealtime());
                peerAddress.put(id, address);
            }
        }

        void handle(String m, InetAddress from, long recv) {
            try {
                String[] p = m.split("\\|");
                if (p.length < 2 || !p[1].equals(groupCode)) return;
                if (host) {
                    if ("DISCOVER".equals(p[0]) && p.length >= 3) {
                        rememberPeer(p[2], from);
                        send(from, "BEACON|" + groupCode + "|" + deviceId + "|" + SystemClock.elapsedRealtimeNanos());
                    } else if ("SYNC_REQ".equals(p[0]) && p.length >= 5) {
                        rememberPeer(p[2], from);
                        long t2 = recv;
                        long t3 = SystemClock.elapsedRealtimeNanos();
                        send(from, "SYNC_RESP|" + groupCode + "|" + p[2] + "|" + p[3] + "|" + p[4] + "|" + t2 + "|" + t3);
                    } else if ("CAPTURED".equals(p[0]) && p.length >= 3) {
                        rememberPeer(p[2], from);
                    } else if ("PHOTO_SYNC_DONE".equals(p[0]) && p.length >= 5) {
                        rememberPeer(p[2], from);
                        int request = Integer.parseInt(p[3]);
                        if (request == activePhotoRequest) {
                            int done;
                            synchronized (photoDonePeers) {
                                photoDonePeers.add(p[2]);
                                done = photoDonePeers.size();
                            }
                            if (done >= expectedPhotoPeers) {
                                io.schedule(() -> {
                                    int saved = Math.max(0, photoTransfer.totalReceived() - receivedAtPhotoStart);
                                    setStatus("Photo sync complete • " + saved + " new client photo(s) stored on host");
                                }, 600, TimeUnit.MILLISECONDS);
                            } else {
                                setStatus("Photo sync • " + done + "/" + expectedPhotoPeers + " client phones finished");
                            }
                        }
                    }
                } else {
                    if ("BEACON".equals(p[0])) {
                        if (hostAddr == null || !hostAddr.equals(from)) {
                            hostAddr = from;
                            setStatus("Host found at " + from.getHostAddress() + " • calibrating clocks");
                        }
                    } else if ("SYNC_RESP".equals(p[0]) && p.length >= 7 && p[2].equals(deviceId)) {
                        hostAddr = from;
                        long t1 = Long.parseLong(p[4]);
                        long t2 = Long.parseLong(p[5]);
                        long t3 = Long.parseLong(p[6]);
                        long t4 = recv;
                        long rtt = (t4 - t1) - (t3 - t2);
                        long off = ((t2 - t1) + (t3 - t4)) / 2;
                        addSample(rtt, off);
                    } else if ("CAPTURE".equals(p[0]) && p.length >= 4) {
                        int seq = Integer.parseInt(p[2]);
                        if (captureSeen.put(seq, true) != null) return;
                        hostAddr = from;
                        long hostTarget = Long.parseLong(p[3]);
                        long localTarget = hostTarget - hostMinusClient;
                        scheduleCapture(seq, localTarget, "CLIENT");
                        setStatus("Capture #" + seq + " received • shutter armed");
                    } else if ("PHOTO_SYNC".equals(p[0]) && p.length >= 3) {
                        int request = Integer.parseInt(p[2]);
                        boolean first;
                        synchronized (photoSyncSeen) { first = photoSyncSeen.add(request); }
                        if (first) {
                            hostAddr = from;
                            setStatus("Host requested photos • uploading local captures…");
                            photoTransfer.sendAllAsync(from, groupCode, request, (requestId, sentCount) ->
                                    send(from, "PHOTO_SYNC_DONE|" + groupCode + "|" + deviceId + "|" + requestId + "|" + sentCount));
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        void addSample(long rtt, long off) {
            if (rtt < 0 || rtt > 500_000_000L) return;
            synchronized (samples) {
                samples.add(new Sample(rtt, off));
                if (samples.size() > 30) samples.remove(0);
                ArrayList<Sample> c = new ArrayList<>(samples);
                c.sort(Comparator.comparingLong(a -> a.rtt));
                int n = Math.min(7, c.size());
                long sum = 0;
                for (int i = 0; i < n; i++) sum += c.get(i).off;
                hostMinusClient = sum / n;
                String state = c.size() >= 5 ? "SYNCED" : "calibrating";
                setSync(String.format(Locale.US, "%s • offset %+.3f ms • RTT %.2f ms",
                        state, hostMinusClient / 1e6, c.get(0).rtt / 1e6));
                if (c.size() >= 5) setStatus("Connected • direct host link " + (hostAddr == null ? "" : hostAddr.getHostAddress()));
            }
        }

        synchronized void send(InetAddress a, String s) {
            if (socket == null || a == null) return;
            try {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(b, b.length, a, PORT));
            } catch (Exception ignored) { }
        }

        void stop() {
            running = false;
            io.shutdownNow();
            try {
                if (socket != null) {
                    try { if (multicast != null) socket.leaveGroup(multicast); } catch (Exception ignored) { }
                    socket.close();
                }
            } catch (Exception ignored) { }
            try {
                if (lock != null && lock.isHeld()) lock.release();
            } catch (Exception ignored) { }
        }
    }

    private static final class Sample {
        final long rtt, off;
        Sample(long r, long o) { rtt = r; off = o; }
    }

    private static final class ReticleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ReticleView(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
            p.setColor(0x7AFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.38f;
            float d = 16f * getResources().getDisplayMetrics().density;
            float gap = 6f * getResources().getDisplayMetrics().density;
            c.drawLine(cx - d, cy, cx - gap, cy, p);
            c.drawLine(cx + gap, cy, cx + d, cy, p);
            c.drawLine(cx, cy - d, cx, cy - gap, p);
            c.drawLine(cx, cy + gap, cx, cy + d, p);
            c.drawCircle(cx, cy, 2.2f * getResources().getDisplayMetrics().density, p);
        }
    }
}
