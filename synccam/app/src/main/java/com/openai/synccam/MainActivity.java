package com.openai.synccam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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

    private SurfaceView surface;
    private TextView status, sync, peers;
    private EditText code;
    private Button trigger;
    private Camera camera;
    private int cameraId = 0;
    private boolean surfaceReady;
    private Net net;
    private final String deviceId = UUID.randomUUID().toString().substring(0, 8);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int sequence = 1;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        requestPermissions();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(18, 14, 18, 14);
        panel.setBackgroundColor(0xCC111111);

        TextView title = text("SyncCam — synchronized Wi‑Fi camera", 19);
        panel.addView(title);
        status = text("Starting…", 14); panel.addView(status);
        sync = text("Sync: not connected", 13); panel.addView(sync);
        peers = text("Peers: 0", 13); panel.addView(peers);

        LinearLayout codeRow = new LinearLayout(this);
        code = new EditText(this);
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(0xFFAAAAAA);
        code.setHint("6 digit group code");
        code.setSingleLine(true);
        code.setInputType(2);
        codeRow.addView(code, new LinearLayout.LayoutParams(0, 54, 1f));
        panel.addView(codeRow);

        LinearLayout roles = new LinearLayout(this);
        Button host = button("HOST NEW GROUP");
        Button join = button("JOIN GROUP");
        roles.addView(host, new LinearLayout.LayoutParams(0, 56, 1f));
        roles.addView(join, new LinearLayout.LayoutParams(0, 56, 1f));
        panel.addView(roles);

        LinearLayout actions = new LinearLayout(this);
        trigger = button("TRIGGER ALL"); trigger.setEnabled(false);
        Button flip = button("FRONT / BACK");
        actions.addView(trigger, new LinearLayout.LayoutParams(0, 58, 1f));
        actions.addView(flip, new LinearLayout.LayoutParams(0, 58, 1f));
        panel.addView(actions);

        TextView note = text("Keep SyncCam open on every phone. Photos save to Pictures/SyncCam.", 12);
        panel.addView(note);

        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(panel, p);
        setContentView(root);

        host.setOnClickListener(v -> host());
        join.setOnClickListener(v -> join());
        trigger.setOnClickListener(v -> triggerAll());
        flip.setOnClickListener(v -> flip());
    }

    private TextView text(String s, int size) {
        TextView v = new TextView(this); v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(size); return v;
    }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    private void ui(Runnable r) { runOnUiThread(r); }
    private void setStatus(String s) { ui(() -> status.setText(s)); }
    private void setSync(String s) { ui(() -> sync.setText(s)); }
    private void setPeers(String s) { ui(() -> peers.setText(s)); }

    private void requestPermissions() {
        ArrayList<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (p.isEmpty()) startCore(); else requestPermissions(p.toArray(new String[0]), REQ);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == REQ && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCore();
        else setStatus("Camera permission required");
    }

    private void startCore() {
        if (net == null) { net = new Net(); net.start(); }
        if (surfaceReady) openCamera();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; if (checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) openCamera(); }
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int z) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; closeCamera(); }

    private synchronized void openCamera() {
        closeCamera();
        try {
            camera = Camera.open(cameraId);
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            if (p.getSupportedFlashModes()!=null && p.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_OFF)) p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surface.getHolder());
            camera.startPreview();
            setStatus("Camera ready. Choose HOST or JOIN.");
        } catch (Exception e) { setStatus("Camera error: " + e.getMessage()); }
    }

    private synchronized void closeCamera() {
        if (camera != null) { try { camera.stopPreview(); } catch(Exception ignored){} camera.release(); camera=null; }
    }

    private void flip() {
        int n = Camera.getNumberOfCameras(); if (n < 2) return;
        cameraId = (cameraId + 1) % n; openCamera();
    }

    private void host() {
        if (net == null) return;
        String c = String.format(Locale.US, "%06d", 100000 + new Random().nextInt(900000));
        code.setText(c); net.becomeHost(c); trigger.setEnabled(true); setStatus("HOST group " + c + " — waiting for phones");
    }

    private void join() {
        if (net == null) return;
        String c = code.getText().toString().trim();
        if (c.length()!=6) { Toast.makeText(this,"Enter the 6 digit host code",Toast.LENGTH_SHORT).show(); return; }
        net.becomeClient(c); trigger.setEnabled(false); setStatus("Joining group " + c + "…");
    }

    private void triggerAll() {
        if (net==null || !net.host) return;
        int seq = sequence++;
        long target = SystemClock.elapsedRealtimeNanos() + LEAD_NS;
        net.sendCapture(seq,target);
        scheduleCapture(seq,target,"HOST");
        setStatus("Capture #"+seq+" armed for all phones");
    }

    private void scheduleCapture(int seq,long target,String source) {
        long delay = Math.max(0,target-SystemClock.elapsedRealtimeNanos()-3_000_000L);
        scheduler.schedule(() -> {
            while (SystemClock.elapsedRealtimeNanos() < target) Thread.onSpinWait();
            ui(() -> takePicture(seq,source));
        }, delay, TimeUnit.NANOSECONDS);
    }

    private synchronized void takePicture(int seq,String source) {
        if (camera==null) return;
        final long callNs = SystemClock.elapsedRealtimeNanos();
        try {
            camera.takePicture(null,null,(data,c) -> {
                String uri = saveJpeg(data,seq);
                setStatus("Saved capture #"+seq+" ("+source+")");
                if (net!=null) net.report(seq,callNs,uri);
                try { c.startPreview(); } catch(Exception ignored) {}
            });
        } catch(Exception e) { setStatus("Capture failed: "+e.getMessage()); try { camera.startPreview(); } catch(Exception ignored){} }
    }

    private String saveJpeg(byte[] data,int seq) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());
            String name = "SyncCam_"+stamp+"_S"+seq+".jpg";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,name);
            v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            if (Build.VERSION.SDK_INT>=29) v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES+"/SyncCam");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
            if (u==null) return "";
            try(OutputStream o=getContentResolver().openOutputStream(u)){ o.write(data); }
            return u.toString();
        } catch(Exception e) { setStatus("Save failed: "+e.getMessage()); return ""; }
    }

    @Override protected void onDestroy() {
        super.onDestroy(); closeCamera(); scheduler.shutdownNow(); if (net!=null) net.stop();
    }

    private final class Net {
        final ScheduledExecutorService io = Executors.newScheduledThreadPool(2);
        final Map<String,Long> peerSeen = new HashMap<>();
        final ArrayList<Sample> samples = new ArrayList<>();
        MulticastSocket socket; InetAddress multicast; InetAddress hostAddr;
        WifiManager.MulticastLock lock;
        volatile String groupCode=""; volatile boolean host=false; volatile long hostMinusClient=0; volatile boolean running=true;
        int syncSeq=1; final Map<Integer,Boolean> captureSeen=new HashMap<>();

        void start() {
            io.execute(() -> {
                try {
                    WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    lock=wm.createMulticastLock("SyncCam"); lock.setReferenceCounted(false); lock.acquire();
                    multicast=InetAddress.getByName(GROUP);
                    socket=new MulticastSocket(null); socket.setReuseAddress(true); socket.bind(new InetSocketAddress(PORT)); socket.joinGroup(multicast);
                    setStatus("Network ready. Choose HOST or JOIN.");
                    receive();
                } catch(Exception e){ setStatus("Network error: "+e.getMessage()); }
            });
            io.scheduleAtFixedRate(this::tick,300,500,TimeUnit.MILLISECONDS);
        }

        void becomeHost(String c){ groupCode=c; host=true; hostAddr=null; samples.clear(); peerSeen.clear(); setSync("Sync: HOST clock reference"); setPeers("Peers: 0"); }
        void becomeClient(String c){ groupCode=c; host=false; hostAddr=null; samples.clear(); captureSeen.clear(); setSync("Sync: searching for host…"); setPeers("Peers: client mode"); }

        void tick(){
            if(socket==null||groupCode.isEmpty()) return;
            try {
                if(host){
                    send(multicast,"BEACON|"+groupCode+"|"+deviceId+"|"+SystemClock.elapsedRealtimeNanos());
                    long now=SystemClock.elapsedRealtime(); peerSeen.entrySet().removeIf(e->now-e.getValue()>5000); setPeers("Peers: "+peerSeen.size());
                } else if(hostAddr==null) send(multicast,"DISCOVER|"+groupCode+"|"+deviceId);
                else {
                    long t1=SystemClock.elapsedRealtimeNanos(); send(hostAddr,"SYNC_REQ|"+groupCode+"|"+deviceId+"|"+(syncSeq++)+"|"+t1);
                }
            } catch(Exception ignored){}
        }

        void sendCapture(int seq,long target){ String m="CAPTURE|"+groupCode+"|"+seq+"|"+target; send(multicast,m); io.schedule(()->send(multicast,m),70,TimeUnit.MILLISECONDS); io.schedule(()->send(multicast,m),160,TimeUnit.MILLISECONDS); }
        void report(int seq,long callNs,String uri){ if(!host && hostAddr!=null) send(hostAddr,"CAPTURED|"+groupCode+"|"+deviceId+"|"+seq+"|"+callNs); }

        void receive(){
            byte[] buf=new byte[2048];
            while(running) try {
                DatagramPacket p=new DatagramPacket(buf,buf.length); socket.receive(p); long recv=SystemClock.elapsedRealtimeNanos();
                String m=new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8); handle(m,p.getAddress(),recv);
            } catch(Exception e){ if(running) setStatus("Network receive error: "+e.getMessage()); }
        }

        void handle(String m,InetAddress from,long recv){
            try {
                String[] p=m.split("\\|"); if(p.length<2 || !p[1].equals(groupCode)) return;
                if(host){
                    if("DISCOVER".equals(p[0])){ peerSeen.put(p[2],SystemClock.elapsedRealtime()); send(from,"BEACON|"+groupCode+"|"+deviceId+"|"+SystemClock.elapsedRealtimeNanos()); }
                    else if("SYNC_REQ".equals(p[0]) && p.length>=5){ peerSeen.put(p[2],SystemClock.elapsedRealtime()); long t2=recv; long t3=SystemClock.elapsedRealtimeNanos(); send(from,"SYNC_RESP|"+groupCode+"|"+p[2]+"|"+p[3]+"|"+p[4]+"|"+t2+"|"+t3); }
                    else if("CAPTURED".equals(p[0])) peerSeen.put(p[2],SystemClock.elapsedRealtime());
                } else {
                    if("BEACON".equals(p[0])) hostAddr=from;
                    else if("SYNC_RESP".equals(p[0]) && p.length>=7 && p[2].equals(deviceId)){
                        long t1=Long.parseLong(p[4]), t2=Long.parseLong(p[5]), t3=Long.parseLong(p[6]), t4=recv;
                        long rtt=(t4-t1)-(t3-t2); long off=((t2-t1)+(t3-t4))/2; addSample(rtt,off);
                    } else if("CAPTURE".equals(p[0]) && p.length>=4){
                        int seq=Integer.parseInt(p[2]); if(captureSeen.put(seq,true)!=null) return;
                        long hostTarget=Long.parseLong(p[3]); long localTarget=hostTarget-hostMinusClient; scheduleCapture(seq,localTarget,"CLIENT"); setStatus("Capture #"+seq+" armed from host");
                    }
                }
            } catch(Exception ignored){}
        }

        void addSample(long rtt,long off){
            if(rtt<0 || rtt>500_000_000L) return;
            synchronized(samples){
                samples.add(new Sample(rtt,off)); if(samples.size()>30) samples.remove(0);
                ArrayList<Sample> c=new ArrayList<>(samples); c.sort(Comparator.comparingLong(a->a.rtt)); int n=Math.min(7,c.size()); long sum=0; for(int i=0;i<n;i++) sum+=c.get(i).off; hostMinusClient=sum/n;
                String state=c.size()>=5?"SYNCED":"calibrating"; setSync(String.format(Locale.US,"Sync: %s offset %+.3f ms best RTT %.2f ms",state,hostMinusClient/1e6,c.get(0).rtt/1e6));
            }
        }

        synchronized void send(InetAddress a,String s){ if(socket==null||a==null) return; try { byte[] b=s.getBytes(StandardCharsets.UTF_8); socket.send(new DatagramPacket(b,b.length,a,PORT)); } catch(Exception ignored){} }
        void stop(){ running=false; io.shutdownNow(); try{ if(socket!=null){ try{socket.leaveGroup(multicast);}catch(Exception ignored){} socket.close(); } }catch(Exception ignored){} try{ if(lock!=null&&lock.isHeld()) lock.release(); }catch(Exception ignored){} }
    }

    private static final class Sample { final long rtt,off; Sample(long r,long o){rtt=r;off=o;} }
}
