package com.example.piliplus.official;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.example.piliplus.BuildConfig;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Debug-only same-APK IPC/Surface test, independent of Flutter's ARM translation compatibility. */
public final class OfficialPlaybackTestActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Runnable> replies = new HashMap<>();
    private final Messenger events = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger service;
    private OfficialBridgeProbe bridge;
    private SurfaceView video;
    private TextureView textureView;
    private int textureFrames;
    private final org.json.JSONArray textureTrace=new org.json.JSONArray();
    private boolean traceTexture,traceSaved;
    private void saveTextureTrace(boolean complete) {
        if(!traceTexture || traceSaved)return;traceSaved=true;
        try(FileWriter out=new FileWriter(new File(getFilesDir(),"official-texture-trace.json"))) {
            out.write(new JSONObject().put("sample",sample).put("complete",complete).put("frames",textureTrace)
                .put("clock","callback System.nanoTime/CLOCK_MONOTONIC, SurfaceTexture timestamp nanos").toString());
            record("texture_trace_saved",null);
        }catch(Exception e){record("failure_texture_trace",null);}
    }
    private TextView label;
    private long session = 1;
    private int request, rounds, count;
    private boolean bound, opened, destroyed;
    private String sample;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder); maybeOpen();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; record("service_died", null); }
        @Override public void onBindingDied(ComponentName name) { service = null; record("binding_died", null); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BuildConfig.OFFICIAL_AUDIO) { finish(); return; }
        sample = getIntent().getStringExtra("sample");
        if (sample == null) sample = "stereo-48000-16.flac";
        if (!Arrays.asList("stereo-44100-16.flac", "stereo-96000-24.flac", "surround-soft.flac", "stereo-48000-16.flac", "dash-muxed.mp4", "frame-id.mp4", "sync-fragmented", "surround-soft.eac3", "surround-quiet.eac3", "loudness-stereo.m4a", "sync-loudness").contains(sample))
            throw new IllegalArgumentException("Owned fixtures only");
        rounds = getIntent().getBooleanExtra("cycles", false) ? 5 : 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        // A transparent video target must not turn the test window background into a flash marker.
        layout.setBackgroundColor(android.graphics.Color.BLACK);
        label = new TextView(this); label.setTextColor(android.graphics.Color.WHITE); label.setText("官方进程 IPC/Surface 集成验证；不代表整体听感验收"); layout.addView(label);
        boolean textureTarget=getIntent().getBooleanExtra("textureViewTarget",false) && !getIntent().getBooleanExtra("productionBridge",false);
        traceTexture=getIntent().getBooleanExtra("traceTexture",false);
        if(traceTexture)new File(getFilesDir(),"official-texture-trace.json").delete();
        video = new SurfaceView(this); layout.addView(video, textureTarget?new LinearLayout.LayoutParams(1,1):new LinearLayout.LayoutParams(-1, 0, 1));
        if(textureTarget){
            textureView=new TextureView(this);layout.addView(textureView,new LinearLayout.LayoutParams(-1,0,1));
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface,int w,int h){maybeOpen();}
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface,int w,int h){}
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface){return true;}
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface){if(traceTexture)textureTrace.put(new org.json.JSONArray().put(System.nanoTime()).put(surface.getTimestamp()));if(++textureFrames==1||textureFrames%60==0){Bundle data=new Bundle();data.putInt("frames",textureFrames);record("texture_presented",data);}}
            });
        }
        video.getHolder().addCallback(this); setContentView(layout);
        record("test_created", null);
        if (getIntent().getBooleanExtra("productionBridge", false)) {
            try { bridge=new OfficialBridgeProbe(this,video,this::record,this::acceptEvent); }
            catch(Exception error){record("failure_bridge_init",null);}
            return;
        }
        bound = bindService(new Intent(this, OfficialPlaybackService.class), connection, BIND_AUTO_CREATE);
        if (!bound) record("failure_bind", null);
    }
    private void record(String event, Bundle data) {
        try {
            SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
            date.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            JSONObject row = new JSONObject().put("time", date.format(new Date()))
                .put("event", event).put("session", session).put("client_pid", android.os.Process.myPid());
            if (data != null) {
                JSONObject fields = new JSONObject(); for (String key : data.keySet()) {
                    Object value = data.get(key);
                    fields.put(key, value instanceof int[] ? new org.json.JSONArray(value) : value);
                }
                row.put("data", fields);
            }
            try (FileWriter file = new FileWriter(new File(getFilesDir(), "official-integration-events.jsonl"), true)) {
                file.write(row + "\n");
            }
            label.setText(event + " / session " + session);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void send(String op, Bundle data, Runnable reply) {
        if (!destroyed && bridge != null) { bridge.send(op,data,reply); return; }
        if (destroyed || service == null) return;
        data.putString("op", op); data.putLong("session", session);
        int id = ++request; if (reply != null) replies.put(id, reply);
        Message m = Message.obtain(null, OfficialPlaybackService.COMMAND, id, 0); m.setData(data); m.replyTo = events;
        try { service.send(m); } catch (RemoteException e) { record("failure_send", null); }
    }
    private boolean receive(Message message) {
        Bundle data = message.getData();
        if (destroyed || data.getLong("session") != session) return true;
        if (message.what == OfficialPlaybackService.REPLY) {
            record(data.getBoolean("ok") ? "command_ok" : "failure_command", data);
            Runnable next = replies.remove(message.arg1);
            if (next != null && data.getBoolean("ok")) next.run();
        } else if (message.what == OfficialPlaybackService.EVENT) {
            acceptEvent(data);
        }
        return true;
    }
    private void acceptEvent(Bundle data) {
        String event=data.getString("event","unknown");record(event,data);
        if(event.equals("completed"))ui.postDelayed(()->{saveTextureTrace(true);if(getIntent().getBooleanExtra("videoTrace",false))send("close",new Bundle(),()->record("probe_released",null));},500);
        if(event.equals("prepared")){
            send("play",new Bundle(),null);if(rounds>0)cycle();
            if(bridge==null && getIntent().getBooleanExtra("repeatSurfaceAfterStart",false)){
                long token=session;
                ui.postDelayed(()->{
                    if(token!=session)return;
                    Bundle surface=new Bundle();surface.putParcelable("surface",video.getHolder().getSurface());
                    send("surface",surface,()->record("repeated_surface",null));
                },250);
            }
        }
    }
    private void maybeOpen() {
        if(textureView!=null&&!textureView.isAvailable())return;
        if (opened || (service == null && bridge == null) || !video.getHolder().getSurface().isValid()) return;
        opened = true;
        if(bridge!=null){send("create",new Bundle(),this::open);return;}
        send("create", new Bundle(), () -> {
            Bundle surface = new Bundle(); surface.putParcelable("surface", video.getHolder().getSurface());
            send("surface", surface, this::open);
        });
    }
    private String asset(String name) throws Exception {
        JSONObject hashes;
        try (InputStream in = getAssets().open("official_fixture/manifest.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            hashes = new JSONObject(out.toString("UTF-8"));
        }
        String expected = hashes.getString(name);
        File directory = new File(getFilesDir(), "official-fixtures"); directory.mkdirs();
        File file = new File(directory, expected + "-" + name);
        if (!file.exists()) try (InputStream in = getAssets().open("official_fixture/" + name); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder actual = new StringBuilder(); for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!expected.equals(actual.toString())) throw new SecurityException("Fixture hash mismatch");
        return file.getPath();
    }
    private void open() {
        try {
            Bundle source = new Bundle();
            source.putLong("position", getIntent().getLongExtra("position", 0));
            source.putBoolean("audioOnly", !sample.endsWith(".mp4") && !sample.startsWith("sync-"));
            source.putBoolean("clockDiagnostics", getIntent().getBooleanExtra("clockDiagnostics", false));
            source.putBoolean("startupPrefill", getIntent().getBooleanExtra("startupPrefill", false));
            source.putBoolean("videoTrace",getIntent().getBooleanExtra("videoTrace",false));
            source.putString("source", asset((sample.equals("sync-fragmented") || sample.equals("sync-loudness")) ? "fragmented-dash-video.mp4" : sample));
            if (sample.equals("sync-fragmented")) source.putString("audio", asset("fragmented-dash-audio.m4a"));
            if (sample.equals("sync-loudness")) source.putString("audio", asset("loudness-fragmented-audio.m4a"));
            source.putInt("endpoint", getIntent().getIntExtra("endpoint", 0));
            source.putFloat("speed", getIntent().getFloatExtra("speed", 1)); source.putFloat("volume", 1); source.putInt("virtualizer", getIntent().getIntExtra("virtualizer", 1));
            String mode = getIntent().getStringExtra("loudnessMode");
            boolean transitions = getIntent().getBooleanExtra("loudnessTransitions", false);
            if (transitions) mode = count == 4 ? "off" : count == 3 ? "high_dynamic" : "normal";
            source.putString("loudnessMode", mode == null ? "off" : mode);
            if (sample.equals("stereo-48000-16.flac") || sample.equals("loudness-stereo.m4a") || sample.equals("sync-loudness")) {
                String metadata = getIntent().getStringExtra("metadataCase");
                if (transitions) metadata = count == 1 ? "missing" : count == 2 ? "zero" : "valid";
                if (!"missing".equals(metadata)) {
                    JSONObject manifest = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(asset("loudness-manifest.json")).toPath()), "UTF-8"));
                    org.json.JSONArray array = manifest.getJSONObject("fixtures").getJSONObject(sample.equals("sync-loudness") ? "loudness-stereo.m4a" : sample).getJSONArray("values");
                    double[] values = new double[7]; for (int i=0;i<7;i++) values[i]=array.getDouble(i);
                    if ("zero".equals(metadata)) values[1]=0;
                    if ("quiet".equals(metadata)) values[0]=-40;
                    source.putDoubleArray("loudnessValues", values);
                    Bundle scenes = new Bundle(); JSONObject targets=manifest.getJSONObject("scene_targets");
                    for (java.util.Iterator<String> names=targets.keys();names.hasNext();) { String key=names.next();scenes.putString(key,targets.getString(key)); }
                    source.putBundle("loudnessScenes",scenes);
                }
            }
            String testValues = getIntent().getStringExtra("testLoudnessValues");
            if (testValues != null) {
                org.json.JSONArray array = new org.json.JSONArray(testValues);
                if (array.length() != 7) throw new IllegalArgumentException("Seven test metadata values required");
                double[] values = new double[7]; for (int i=0;i<7;i++) values[i]=array.getDouble(i);
                source.putDoubleArray("loudnessValues", values); source.remove("loudnessScenes");
                Bundle audit = new Bundle(); audit.putString("values", array.toString()); record("synthetic_test_metadata", audit);
            }
            send("open", source, null);
        } catch (Exception error) { record("failure_fixture", null); }
    }
    private void cycle() {
        long token = session;
        ui.postDelayed(() -> { if (token == session) send("pause", new Bundle(), () -> record("paused", null)); }, 2000);
        ui.postDelayed(() -> {
            if (token != session) return;
            Bundle seek = new Bundle(); seek.putLong("position", getIntent().getLongExtra("seekTarget", 1000));
            send("seek", seek, () -> send("play", new Bundle(), () -> record("resumed", null)));
        }, 3000);
        ui.postDelayed(() -> {
            if (token != session) return;
            send("close", new Bundle(), () -> {
                record("released", null);
                if (++count == rounds) { record("cycles_complete", null); return; }
                ++session; opened = false; maybeOpen();
            });
        }, 6000);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { maybeOpen(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { if(bridge==null)send("surface", new Bundle(), null); }
    @Override protected void onStop() {
        if (service != null || bridge != null) send("close", new Bundle(), null);
        super.onStop();
    }
    @Override protected void onDestroy() {
        saveTextureTrace(false);
        destroyed = true; ui.removeCallbacksAndMessages(null); replies.clear();
        if(bridge!=null)bridge.destroy();
        if (bound) { unbindService(connection); bound = false; }
        super.onDestroy();
    }
}
