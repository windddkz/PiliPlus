package com.example.piliplus.official;

import android.content.*;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.*;
import android.view.Surface;
import androidx.annotation.NonNull;
import com.example.piliplus.BuildConfig;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.*;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.view.View;
import io.flutter.plugin.platform.*;
import java.io.InputStream;
import java.util.*;

/** Flutter sends controls and a Surface; the dedicated service owns all media clocks and PCM. */
public final class OfficialPlayerPlugin
        implements FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Messenger receiver =
            new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Context context;
    private SurfaceView hostView;
    private Surface surface;
    private Bundle windowData(Surface output) {
        Bundle data = new Bundle();
        data.putParcelable("surface", output);
        if (Build.VERSION.SDK_INT >= 33 && output != null && hostView != null
                && hostView.getSurfaceControl().isValid()) {
            data.putParcelable("parent", hostView.getSurfaceControl());
            data.putInt("windowWidth", hostView.getWidth());
            data.putInt("windowHeight", hostView.getHeight());
        }
        return data;
    }
    private int surfaceGeneration;
    private boolean closing;
    private WaitingOpen waitingOpen;
    private final class WaitingOpen {
        final Bundle data;
        final MethodChannel.Result result;
        final long token = session;
        boolean attaching;
        final Runnable timeout = () -> {
            if (waitingOpen == this)
                cancelWaitingOpen("surface_timeout");
        };
        WaitingOpen(Bundle data, MethodChannel.Result result) {
            this.data = data;
            this.result = result;
        }
    }
    private void cancelWaitingOpen(String reason) {
        cancelWaitingOpen(reason, null);
    }
    private void cancelWaitingOpen(String reason, String diagnostic) {
        WaitingOpen opening = waitingOpen;
        waitingOpen = null;
        if (opening != null) {
            ui.removeCallbacks(opening.timeout);
            opening.result.error(reason, "Video output was not ready", diagnostic);
        }
    }
    private void flushWaitingOpen() {
        WaitingOpen opening = waitingOpen;
        if (opening == null || opening.attaching || closing || remote == null || surface == null
                || !surface.isValid())
            return;
        int generation = surfaceGeneration;
        opening.attaching = true;
        Bundle output = windowData(surface);
        send("surface", output, new MethodChannel.Result() {
            public void success(Object ignored) {
                if (waitingOpen != opening || session != opening.token || closing)
                    return;
                opening.attaching = false;
                if (generation != surfaceGeneration || surface == null || !surface.isValid()) {
                    flushWaitingOpen();
                    return;
                }
                waitingOpen = null;
                ui.removeCallbacks(opening.timeout);
                send("open", opening.data, opening.result, null);
            }
            public void error(String code, String message, Object details) {
                if (waitingOpen == opening)
                    cancelWaitingOpen(code);
            }
            public void notImplemented() {
                if (waitingOpen == opening)
                    cancelWaitingOpen("surface_unsupported");
            }
        }, null);
    }
    private MethodChannel channel;
    private EventChannel eventChannel;
    private EventChannel.EventSink sink;
    private Messenger remote;
    private IBinder binder;
    private boolean bound;
    private long session;
    private int request;
    private int nativePid;
    private long sessionStarted;
    private String lastStage = "bind", terminationReason;
    private boolean processReclaimed;
    private MethodChannel.Result creating;
    private boolean retiringIdle;
    private final Runnable retireIdle = () -> {
        if (session != 0 || remote == null)
            return;
        retiringIdle = true;
        Message message = Message.obtain(null, OfficialPlaybackService.TERMINATE_IDLE);
        message.replyTo = receiver;
        try {
            remote.send(message);
        } catch (RemoteException error) {
            died("native_process_died");
        }
    };
    private IBinder.DeathRecipient death;

    private static final class Pending {
        final String op;
        final MethodChannel.Result result;
        final Runnable success;
        final Runnable timeout;
        Pending(String op, MethodChannel.Result result, Runnable success, Runnable timeout) {
            this.op = op;
            this.result = result;
            this.success = success;
            this.timeout = timeout;
        }
    }
    private ServiceConnection connection;
    private ServiceConnection connectionFor(long token) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (!bound || token != session)
                    return;
                binder = service;
                remote = new Messenger(service);
                death = () -> ui.post(() -> {
                    if (binder == service)
                        died("native_process_died");
                });
                try {
                    binder.linkToDeath(death, 0);
                } catch (RemoteException e) {
                    died("native_process_died");
                    return;
                }
                send("create", new Bundle(), null, () -> {
                    if (creating != null) {
                        MethodChannel.Result result = creating;
                        creating = null;
                        result.success(values("session", session));
                    }
                });
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (bound && connection == this)
                    died("native_process_died");
            }
            @Override
            public void onBindingDied(ComponentName name) {
                if (bound && connection == this)
                    died("binding_died");
            }
            @Override
            public void onNullBinding(ComponentName name) {
                if (bound && connection == this)
                    died("binding_rejected");
            }
        };
    }

    private void bind(long token) {
        connection = connectionFor(token);
        bound = context.bindService(new Intent(context, OfficialPlaybackService.class), connection,
                Context.BIND_AUTO_CREATE);
        if (!bound)
            died("bind_failed");
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        binding.getPlatformViewRegistry().registerViewFactory("piliplus/official_surface",
                new PlatformViewFactory(StandardMessageCodec.INSTANCE) {
                    @Override
                    public PlatformView create(Context viewContext, int id, Object args) {
                        if (!(args instanceof Map<?, ?>)
                                || !(((Map<?, ?>) args).get("session") instanceof Number))
                            throw new IllegalArgumentException("Missing session");
                        long token = ((Number) ((Map<?, ?>) args).get("session")).longValue();
                        SurfaceView view = new SurfaceView(viewContext);
                        // A delayed Flutter mount must never attach to a newer playback session.
                        boolean current = token != 0 && token == session && !closing;
                        if (current) {
                            hostView = view;
                            surface = null;
                            surfaceGeneration++;
                        }
                        SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
                            private boolean owns() {
                                return current && token == session && hostView == view && !closing;
                            }
                            public void surfaceCreated(SurfaceHolder holder) {
                                if (!owns())
                                    return;
                                surface = holder.getSurface();
                                surfaceGeneration++;
                                emit(values("session", session, "event", "platform_surface_ready"));
                                if (waitingOpen != null)
                                    flushWaitingOpen();
                                else if (remote != null) {
                                    send("surface", windowData(surface), null, null);
                                }
                            }
                            public void surfaceChanged(
                                    SurfaceHolder holder, int format, int width, int height) {
                                if (owns() && remote != null) {
                                    Bundle data = windowData(surface);
                                    data.remove("surface");
                                    send("resize", data, null, null);
                                }
                            }
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                if (!owns())
                                    return;
                                surface = null;
                                surfaceGeneration++;
                                if (remote != null) {
                                    Bundle data = new Bundle();
                                    data.putParcelable("surface", null);
                                    send("surface", data, null, null);
                                }
                            }
                        };
                        view.getHolder().addCallback(callback);
                        return new PlatformView() {
                            public View getView() {
                                return view;
                            }
                            public void dispose() {
                                view.getHolder().removeCallback(callback);
                                if (token == session && hostView == view) {
                                    hostView = null;
                                    surface = null;
                                    surfaceGeneration++;
                                    if (remote != null && !closing) {
                                        Bundle data = new Bundle();
                                        data.putParcelable("surface", null);
                                        send("surface", data, null, null);
                                    }
                                }
                            }
                        };
                    }
                });
        channel = new MethodChannel(binding.getBinaryMessenger(), "piliplus/official_player");
        channel.setMethodCallHandler(this);
        eventChannel =
                new EventChannel(binding.getBinaryMessenger(), "piliplus/official_player/events");
        eventChannel.setStreamHandler(this);
    }
    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        retiringIdle = false;
        terminate();
        died("engine_detached");
        channel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
        sink = null;
    }
    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        sink = events;
    }
    @Override
    public void onCancel(Object arguments) {
        sink = null;
    }

    private String unavailable() {
        if (Build.VERSION.SDK_INT < 26)
            return "android_26_required";
        if (!BuildConfig.OFFICIAL_AUDIO)
            return "runtime_not_in_build";
        if (!android.os.Process.is64Bit() || !System.getProperty("os.arch", "").equals("aarch64"))
            return "arm64_required";
        try (InputStream ignored =
                        context.getAssets().open("official_runtime/runtime-manifest.json")) {
            return null;
        } catch (Exception ignored) {
            return "runtime_missing";
        }
    }
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        if (call.method.equals("availability")) {
            String reason = unavailable();
            result.success(
                    values("available", reason == null, "reason", reason == null ? "" : reason));
            return;
        }
        if (call.method.equals("create")) {
            String reason = unavailable();
            if (reason != null) {
                result.error(reason, "Official player unavailable", null);
                return;
            }
            if (session != 0) {
                result.error("active_session", "Close the previous player first", null);
                return;
            }
            ui.removeCallbacks(retireIdle);
            session = Math.max(1, SystemClock.elapsedRealtimeNanos());
            creating = result;
            closing = false;
            nativePid = 0;
            lastStage = "bind";
            terminationReason = null;
            sessionStarted = System.currentTimeMillis();
            long token = session;
            if (!retiringIdle) {
                if (remote != null)
                    send("create", new Bundle(), null, () -> {
                        if (creating != null) {
                            MethodChannel.Result ready = creating;
                            creating = null;
                            ready.success(values("session", session));
                        }
                    });
                else
                    bind(token);
            }
            ui.postDelayed(() -> {
                if (token == session && creating != null) {
                    terminate();
                    died("bind_timeout");
                }
            }, 15000);
            return;
        }
        Number token = call.argument("session");
        if (session == 0 || token == null || token.longValue() != session || remote == null) {
            result.error("stale_session", "No matching official player", null);
            return;
        }
        Bundle data = new Bundle();
        switch (call.method) {
            case "preload":
                if (closing) {
                    result.error("closing", "Player session is closing", null);
                    return;
                }
                putSources(call, data);
                data.putString("preloadToken", call.argument("preloadToken"));
                data.putLong("position", number(call, "position", 0).longValue());
                break;
            case "open":
                if (closing || waitingOpen != null) {
                    result.error("open_pending", "Another video open is pending", null);
                    return;
                }
                if (BuildConfig.DEBUG)
                    data.putBoolean("videoTrace", Boolean.TRUE.equals(call.argument("videoTrace")));
                data.putBoolean("audioOnly", Boolean.TRUE.equals(call.argument("audioOnly")));
                data.putBoolean("isLive", Boolean.TRUE.equals(call.argument("isLive")));
                data.putBoolean("flipX", Boolean.TRUE.equals(call.argument("flipX")));
                data.putBoolean("flipY", Boolean.TRUE.equals(call.argument("flipY")));
                putSources(call, data);
                data.putString("preloadToken", call.argument("preloadToken"));
                data.putLong("position", number(call, "position", 0).longValue());
                data.putLong("load", number(call, "load", 1).longValue());
                data.putFloat("speed", number(call, "speed", 1).floatValue());
                data.putFloat("volume", number(call, "volume", 1).floatValue());
                data.putInt("endpoint", number(call, "endpoint", 0).intValue());
                data.putInt("virtualizer", number(call, "virtualizer", 1).intValue());
                String loudnessMode = call.argument("loudnessMode");
                data.putString("loudnessMode", loudnessMode == null ? "off" : loudnessMode);
                Object loudnessValues = call.argument("loudnessValues");
                if (loudnessValues instanceof java.util.List<?>
                        && ((java.util.List<?>) loudnessValues).size() == 7) {
                    double[] values = new double[7];
                    for (int i = 0; i < values.length; i++) {
                        Object value = ((java.util.List<?>) loudnessValues).get(i);
                        values[i] = value instanceof Number ? ((Number) value).doubleValue()
                                                            : Double.NaN;
                    }
                    data.putDoubleArray("loudnessValues", values);
                }
                Object loudnessScenes = call.argument("loudnessScenes");
                if (loudnessScenes instanceof Map<?, ?>
                        && !((Map<?, ?>) loudnessScenes).isEmpty()) {
                    Bundle scenes = new Bundle();
                    for (String key : new String[] {"normal_target_i", "high_dynamic_target_i",
                                 "undersized_target_i"}) {
                        Object value = ((Map<?, ?>) loudnessScenes).get(key);
                        scenes.putString(key,
                                value instanceof String && ((String) value).length() <= 128
                                        ? (String) value
                                        : "invalid");
                    }
                    data.putBundle("loudnessScenes", scenes);
                }
                data.putBoolean("play", Boolean.TRUE.equals(call.argument("play")));
                break;
            case "resourceUpdate":
                data.putLong("load", number(call, "load", -1).longValue());
                data.putLong("requestId", number(call, "requestId", -1).longValue());
                data.putBoolean("available", Boolean.TRUE.equals(call.argument("available")));
                if (data.getBoolean("available"))
                    putSources(call, data);
                break;
            case "videoControl":
                if (!(call.argument("automatic") instanceof Boolean)
                        || !(call.argument("quality") instanceof Integer)
                        || !(call.argument("requestId") instanceof Integer
                                || call.argument("requestId") instanceof Long)) {
                    result.error("invalid_video_control", "Invalid quality control", null);
                    return;
                }
                data.putLong("load", number(call, "load", -1).longValue());
                data.putLong("requestId", number(call, "requestId", -1).longValue());
                data.putInt("quality", (Integer) call.argument("quality"));
                data.putBoolean("automatic", (Boolean) call.argument("automatic"));
                break;
            case "screenshot":
                data.putLong("load", number(call, "load", -1).longValue());
                break;
            case "seek":
                data.putLong("position", number(call, "position", -1).longValue());
                break;
            case "speed":
                data.putFloat("speed", number(call, "speed", Float.NaN).floatValue());
                break;
            case "volume":
                data.putFloat("volume", number(call, "volume", Float.NaN).floatValue());
                break;
            case "viewport":
                data.putLong("load", number(call, "load", -1).longValue());
                for (String key : new String[] {"scaleX", "scaleY", "offsetX", "offsetY"}) {
                    float value = number(call, key, Float.NaN).floatValue();
                    if (!Float.isFinite(value) || (key.startsWith("scale") && value <= 0)
                            || Math.abs(value) > 1000000) {
                        result.error("invalid_viewport", "Invalid display transform", null);
                        return;
                    }
                    data.putFloat(key, value);
                }
                break;
            case "mirror":
                if (!(call.argument("flipX") instanceof Boolean)
                        || !(call.argument("flipY") instanceof Boolean)) {
                    result.error("invalid_mirror", "Both mirror axes are required", null);
                    return;
                }
                data.putLong("load", number(call, "load", -1).longValue());
                data.putBoolean("flipX", (Boolean) call.argument("flipX"));
                data.putBoolean("flipY", (Boolean) call.argument("flipY"));
                break;
            case "play":
            case "pause":
            case "warmUp":
                break;
            case "dispose":
                closing = true;
                cancelWaitingOpen("disposed");
                send("close", data, result, () -> cleanup(true));
                return;
            default:
                result.notImplemented();
                return;
        }
        if (call.method.equals("open") && !data.getBoolean("audioOnly")
                && !OfficialRuntime.canPrepareWithoutSurface(data)) {
            waitingOpen = new WaitingOpen(data, result);
            ui.postDelayed(waitingOpen.timeout, 20000);
            flushWaitingOpen();
            return;
        }
        if (call.method.equals("open"))
            data.putBoolean("awaitOutput", surface == null || !surface.isValid());
        send(call.method, data, result, null);
    }
    private static Bundle streamBundle(OfficialStream track) {
        Bundle info = new Bundle();
        info.putString("source", track.source);
        info.putInt("id", track.id);
        info.putString("codecs", track.codecs);
        info.putInt("bandwidth", track.bandwidth);
        info.putStringArrayList("backups", new ArrayList<>(track.backups));
        return info;
    }
    private static OfficialStream inputStream(Object raw, String actual) {
        if (!(raw instanceof Map<?, ?>) )
            throw new IllegalArgumentException("Invalid stream metadata");
        Map<?, ?> fields = (Map<?, ?>) raw;
        return new OfficialStream(actual, fields.get("source"), fields.get("id"),
                fields.get("bandwidth"), fields.get("backups"), fields.get("codecs"));
    }
    private static void putSources(MethodCall call, Bundle data) {
        for (String key : new String[] {"source", "audio", "userAgent", "referer"}) {
            String value = call.argument(key);
            if (value != null)
                data.putString(key, value);
        }
        OfficialStream video = null;
        for (String key : new String[] {"sourceTrack", "audioTrack"}) {
            Object raw = call.argument(key);
            if (raw == null)
                continue;
            OfficialStream track = inputStream(
                    raw, data.getString(key.equals("sourceTrack") ? "source" : "audio"));
            data.putBundle(key, streamBundle(track));
            if (key.equals("sourceTrack"))
                video = track;
        }
        Object raw = call.argument("videoTracks"), mode = call.argument("automaticVideo");
        if (mode != null && !(mode instanceof Boolean))
            throw new IllegalArgumentException("Invalid video policy");
        if (raw == null) {
            if (Boolean.TRUE.equals(mode))
                throw new IllegalArgumentException("Automatic video requires a ladder");
            return;
        }
        if (!(raw instanceof List<?>) || ((List<?>) raw).size() > 20)
            throw new IllegalArgumentException("Invalid video ladder");
        List<OfficialStream> tracks = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            Object source = value instanceof Map<?, ?> ? ((Map<?, ?>) value).get("source") : null;
            tracks.add(inputStream(value, source instanceof String ? (String) source : null));
        }
        OfficialVideoTracks policy =
                new OfficialVideoTracks(video, tracks, Boolean.TRUE.equals(mode));
        ArrayList<Bundle> values = new ArrayList<>();
        for (OfficialStream track : policy.tracks)
            values.add(streamBundle(track));
        data.putParcelableArrayList("videoTracks", values);
        data.putBoolean("automaticVideo", policy.automatic);
    }
    // Map.of requires API 30; this bridge supports Android 26 and above.
    private static Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2)
            result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    private static Number number(MethodCall call, String key, Number fallback) {
        Object value = call.argument(key);
        return value instanceof Number ? (Number) value : fallback;
    }
    private void send(String op, Bundle data, MethodChannel.Result result, Runnable success) {
        if (remote == null) {
            if (result != null)
                result.error("disconnected", "Official process disconnected", null);
            return;
        }
        int id = ++request;
        long token = session;
        Runnable timeout = () -> {
            if (token != session || !pending.containsKey(id))
                return;
            if (op.equals("screenshot")) {
                Pending capture = pending.remove(id);
                if (capture.result != null)
                    capture.result.success(null);
                return;
            }
            // Termination travels on the service main thread even if the native worker is stuck.
            if (terminationReason == null)
                terminationReason = "timeout_" + op;
            terminate();
            ui.postDelayed(() -> {
                if (token == session && pending.containsKey(id)) {
                    Pending operation = pending.remove(id);
                    if (operation.result != null)
                        operation.result.error(
                                "stop_unconfirmed", "Cannot confirm native process stopped", null);
                    emit(values("session", token, "event", "error", "error", "stop_unconfirmed"));
                }
            }, 3000);
        };
        pending.put(id, new Pending(op, result, success, timeout));
        ui.postDelayed(timeout, op.equals("open") || op.equals("warmUp") ? 45000 : 8000);
        data.putString("op", op);
        data.putLong("session", session);
        Message message = Message.obtain(null, OfficialPlaybackService.COMMAND, id, 0);
        message.setData(data);
        message.replyTo = receiver;
        try {
            remote.send(message);
        } catch (RemoteException e) {
            died("native_process_died");
        }
    }
    private boolean receive(Message message) {
        Bundle data = message.getData();
        if (session == 0 || data.getLong("session") != session) {
            String capture = data.getString("screenshot");
            if (capture != null)
                new java.io.File(capture).delete();
            return true;
        }
        if (message.what == OfficialPlaybackService.REPLY) {
            if (data.getInt("pid", 0) > 0)
                nativePid = data.getInt("pid");
            Pending operation = pending.remove(message.arg1);
            if (operation == null) {
                String capture = data.getString("screenshot");
                if (capture != null)
                    new java.io.File(capture).delete();
                return true;
            }
            ui.removeCallbacks(operation.timeout);
            if (!data.getBoolean("ok")) {
                if (operation.op.equals("close")) {
                    pending.put(message.arg1, operation);
                    ui.postDelayed(operation.timeout, 3000);
                    terminationReason = "command_failed_close";
                    terminate();
                } else if (operation.result != null)
                    operation.result.error("native_command_failed", "Official command failed",
                            data.getString("error"));
                else {
                    terminationReason = "command_failed_" + operation.op;
                    terminate();
                }
            } else {
                if (operation.success != null)
                    operation.success.run();
                if (operation.result != null)
                    operation.result.success(operation.op.equals("warmUp")
                                    ? values("videoCodecs", data.getStringArrayList("videoCodecs"))
                                    : operation.op.equals("screenshot")
                                    ? data.getString("screenshot")
                                    : null);
            }
        } else if (message.what == OfficialPlaybackService.EVENT) {
            if ("preparation_stage".equals(data.getString("event")))
                lastStage = data.getString("stage", "unknown");
            Map<String, Object> event = new HashMap<>();
            for (String key : data.keySet())
                event.put(key, data.get(key));
            emit(event);
        }
        return true;
    }
    private void emit(Map<String, Object> data) {
        if (sink != null)
            sink.success(data);
    }
    private void terminate() {
        if (remote == null)
            return;
        Message message = Message.obtain(null, OfficialPlaybackService.TERMINATE);
        message.replyTo = receiver;
        try {
            remote.send(message);
        } catch (RemoteException ignored) {
        }
    }
    private String exitDiagnostic() {
        processReclaimed = false;
        String result = (terminationReason == null ? "unexpected_exit" : terminationReason) + "/"
                + lastStage;
        if (Build.VERSION.SDK_INT >= 30 && nativePid > 0) {
            try {
                ActivityManager manager =
                        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(
                             context.getPackageName(), nativePid, 4)) {
                    if (info.getPid() != nativePid || info.getTimestamp() < sessionStarted
                            || !info.getProcessName().equals(
                                    context.getPackageName() + ":official_player"))
                        continue;
                    processReclaimed = ProcessReclaimPolicy.canRecover(
                            terminationReason == null && "playback".equals(lastStage),
                            info.getReason(), info.getStatus(), info.getDescription());
                    return result + "/reason_" + info.getReason() + "/status_" + info.getStatus();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return result + "/exit_record_unavailable";
    }
    private void died(String reason) {
        if (retiringIdle
                && (reason.equals("native_process_died") || reason.equals("binding_died"))) {
            // A new create waits for the expiring process to die before binding again.
            long token = session;
            disconnect();
            retiringIdle = false;
            if (creating != null && token != 0)
                bind(token);
            return;
        }
        long token = session;
        String diagnostic = exitDiagnostic();
        cancelWaitingOpen(reason, diagnostic);
        if (creating != null) {
            creating.error(reason, "Official process unavailable", diagnostic);
            creating = null;
        }
        for (Pending operation : pending.values()) {
            ui.removeCallbacks(operation.timeout);
            if (operation.result != null)
                operation.result.error(reason, "Official process unavailable", diagnostic);
        }
        pending.clear();
        cleanup();
        if (token != 0)
            emit(values("session", token, "event", "error", "error", reason, "diagnostic",
                    diagnostic, "processReclaimed",
                    reason.equals("native_process_died") && processReclaimed));
    }
    private void cleanup() {
        cleanup(false);
    }
    private void cleanup(boolean keepRuntime) {
        cancelWaitingOpen("disposed");
        ui.removeCallbacks(retireIdle);
        retiringIdle = false;
        session = 0;
        hostView = null;
        surface = null;
        surfaceGeneration++;
        closing = false;
        for (Pending operation : pending.values()) {
            ui.removeCallbacks(operation.timeout);
            if (operation.result != null)
                operation.result.error("disposed", "Official player disposed", null);
        }
        pending.clear();
        if (keepRuntime && remote != null && bound) {
            // Only an acknowledged full native release may leave an idle runtime alive.
            ui.postDelayed(retireIdle, 30000);
        } else
            disconnect();
        surface = null; // SurfaceHolder owns this borrowed Surface.
    }
    private void disconnect() {
        if (binder != null && death != null)
            binder.unlinkToDeath(death, 0);
        binder = null;
        death = null;
        remote = null;
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
        connection = null;
    }
}
