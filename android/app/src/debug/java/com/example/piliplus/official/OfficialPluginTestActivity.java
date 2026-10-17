package com.example.piliplus.official;

import android.app.Activity;
import android.content.*;
import android.os.*;
import io.flutter.plugin.common.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Controlled Binder ownership and deferred-open checks; real Flutter rendering is checked separately. */
public final class OfficialPluginTestActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ServiceConnection> bindings = new ArrayList<>();
    private final List<Peer> peers = new ArrayList<>();
    private final ComponentName component = new ComponentName("owned.fixture", "Playback");
    private OfficialPlayerPlugin plugin;
    private boolean finished;
    private IBinder.DeathRecipient retiredDeath;
    private String scenario;
    private int errors, openErrors, oldMessages;
    private final class Peer {
        int messages, opens;
        final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == OfficialPlaybackService.TERMINATE_IDLE) {
                ui.postDelayed(() -> {
                    try {
                        retiredDeath.binderDied();
                    } catch (Exception e) {
                        finishResult(false, e.toString());
                    }
                }, 100);
                return true;
            }
            if (message.what == OfficialPlaybackService.TERMINATE) {
                if (scenario.equals("command_timeout"))
                    bindings.get(0).onServiceDisconnected(component);
                return true;
            }
            Bundle data = message.getData();
            messages++;
            if ("open".equals(data.getString("op"))) {
                opens++;
                if (scenario.equals("command_timeout") || scenario.equals("native_exit")) {
                    Bundle stage = new Bundle();
                    stage.putLong("session", data.getLong("session"));
                    stage.putString("event", "preparation_stage");
                    stage.putString("stage", "graph_adapter");
                    Message event = Message.obtain(null, OfficialPlaybackService.EVENT);
                    event.setData(stage);
                    try {
                        message.replyTo.send(event);
                    } catch (RemoteException e) {
                        finishResult(false, "stage_failed");
                    }
                    ui.postDelayed(() -> {
                        try {
                            if (scenario.equals("native_exit")) {
                                bindings.get(0).onServiceDisconnected(component);
                                return;
                            }
                            Map<?, ?> pending = (Map<?, ?>) field("pending");
                            Object operation = pending.values().iterator().next();
                            Field timeout = operation.getClass().getDeclaredField("timeout");
                            timeout.setAccessible(true);
                            ((Runnable) timeout.get(operation)).run();
                        } catch (Exception e) {
                            finishResult(false, e.toString());
                        }
                    }, 100);
                    return true;
                }
            }
            Bundle reply = new Bundle();
            reply.putLong("session", data.getLong("session"));
            reply.putBoolean("ok",
                    !("close".equals(data.getString("op"))
                            && scenario.equals("close_stop_timeout")));
            Message out = Message.obtain(null, OfficialPlaybackService.REPLY, message.arg1, 0);
            out.setData(reply);
            try {
                message.replyTo.send(out);
            } catch (RemoteException e) {
                finishResult(false, "reply_failed");
            }
            return true;
        }));
    }
    private Object field(String name) throws Exception {
        Field f = OfficialPlayerPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(plugin);
    }
    private void field(String name, Object value) throws Exception {
        Field f = OfficialPlayerPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(plugin, value);
    }
    private long session() throws Exception {
        return (Long) field("session");
    }
    private void call(String name, Object args, Consumer<Object> success) {
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "aarch64");
            plugin.onMethodCall(new MethodCall(name, args), new MethodChannel.Result() {
                public void success(Object value) {
                    success.accept(value);
                }
                public void error(String code, String message, Object details) {
                    if (name.equals("dispose") && scenario.equals("close_stop_timeout")) {
                        ui.post(() -> {
                            try {
                                if (!code.equals("stop_unconfirmed") || session() == 0
                                        || errors != 1) {
                                    finishResult(false, "unconfirmed_stop_state");
                                    return;
                                }
                                ((IBinder.DeathRecipient) field("death")).binderDied();
                                ui.postDelayed(() -> {
                                    try {
                                        finishResult(session() == 0 && errors == 2,
                                                "late_death_after_stop_timeout");
                                    } catch (Exception e) {
                                        finishResult(false, e.toString());
                                    }
                                }, 100);
                            } catch (Exception e) {
                                finishResult(false, e.toString());
                            }
                        });
                    } else if (name.equals("open")
                            && (scenario.equals("command_timeout")
                                    || scenario.equals("native_exit"))) {
                        String expected = (scenario.equals("command_timeout") ? "timeout_open"
                                                                              : "unexpected_exit")
                                + "/graph_adapter/exit_record_unavailable";
                        ui.post(() -> {
                            try {
                                finishResult(code.equals("native_process_died")
                                                && expected.equals(details) && session() == 0
                                                && errors == 1,
                                        "death_classification:" + details);
                            } catch (Exception e) {
                                finishResult(false, e.toString());
                            }
                        });
                    } else if (name.equals("open")
                            && ((scenario.equals("cancel_wait") && code.equals("disposed"))
                                    || (scenario.equals("surface_timeout")
                                            && code.equals("surface_timeout"))))
                        openErrors++;
                    else
                        finishResult(false, name + ":" + code);
                }
                public void notImplemented() {
                    finishResult(false, "not_implemented");
                }
            });
        } catch (Exception e) {
            finishResult(false, e.toString());
        } finally {
            if (arch == null)
                System.clearProperty("os.arch");
            else
                System.setProperty("os.arch", arch);
        }
    }
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        scenario = getIntent().getStringExtra("scenario");
        if ("platform_flush".equals(scenario)) {
            platformFlush();
            return;
        }
        if ("real_runtime".equals(scenario)) {
            realRuntime();
            return;
        }
        if (!Arrays.asList("cache_reuse", "idle_race", "close_stop_timeout", "stale_disconnect",
                           "stale_binding_died", "stale_null", "stale_connect",
                           "current_disconnect", "cancel_wait", "surface_timeout",
                           "audio_without_view", "command_timeout", "native_exit")
                        .contains(scenario)) {
            finish();
            return;
        }
        try {
            plugin = new OfficialPlayerPlugin();
            field("context", new ContextWrapper(this) {
                @Override
                public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
                    bindings.add(connection);
                    Peer peer = new Peer();
                    peers.add(peer);
                    ui.post(()
                                    -> connection.onServiceConnected(
                                            component, peer.messenger.getBinder()));
                    return true;
                }
                @Override
                public void unbindService(ServiceConnection connection) {}
            });
            field("sink", new EventChannel.EventSink() {
                public void success(Object event) {
                    if (event instanceof Map && "error".equals(((Map<?, ?>) event).get("event")))
                        errors++;
                }
                public void error(String code, String message, Object details) {
                    errors++;
                }
                public void endOfStream() {}
            });
            ui.postDelayed(() -> finishResult(false, "test_timeout"), 9000);
            call("create", null, this::created);
        } catch (Exception e) {
            finishResult(false, e.toString());
        }
    }
    private void platformFlush() {
        new Thread(() -> {
            android.media.AudioTrack track = null;
            try {
                for (int round = 0; round < 3; round++) {
                    int size = android.media.AudioTrack.getMinBufferSize(48000, 12, 2);
                    if (size <= 0)
                        throw new IllegalStateException("Invalid minimum buffer");
                    track = new android.media.AudioTrack.Builder()
                                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                                                .setUsage(1)
                                                                .setContentType(2)
                                                                .setAllowedCapturePolicy(1)
                                                                .build())
                                    .setAudioFormat(new android.media.AudioFormat.Builder()
                                                            .setSampleRate(48000)
                                                            .setChannelMask(12)
                                                            .setEncoding(2)
                                                            .build())
                                    .setBufferSizeInBytes(size)
                                    .setTransferMode(1)
                                    .build();
                    short[] tone = new short[960];
                    track.play();
                    for (int block = 0; block < 200; block++) {
                        for (int i = 0; i < 480; i++) {
                            tone[2 * i] = (short) (4000
                                    * Math.sin(2 * Math.PI * 317 * (block * 480 + i) / 48000));
                            tone[2 * i + 1] = (short) (3000
                                    * Math.sin(2 * Math.PI * 733 * (block * 480 + i) / 48000));
                        }
                        if (track.write(tone, 0, tone.length) != tone.length)
                            throw new IOException("Short tone write");
                    }
                    track.pause();
                    Thread.sleep(1000);
                    track.flush();
                    short[] zeros = new short[size / 2];
                    if (track.write(zeros, 0, zeros.length) != zeros.length)
                        throw new IOException("Short prefill");
                    track.play();
                    Arrays.fill(tone, (short) 0);
                    for (int block = 0; block < 300; block++)
                        if (track.write(tone, 0, tone.length) != tone.length)
                            throw new IOException("Short silence write");
                    track.pause();
                    track.flush();
                    track.release();
                    track = null;
                    Thread.sleep(500);
                }
                ui.post(() -> finishResult(true, "platform_pause_flush_play_completed"));
            } catch (Exception e) {
                ui.post(() -> finishResult(false, e.toString()));
            } finally {
                if (track != null)
                    track.release();
            }
        }, "owned-platform-flush").start();
    }
    private void created(Object raw) {
        try {
            long token = ((Number) ((Map<?, ?>) raw).get("session")).longValue();
            if (field("surface") != null || field("hostView") != null)
                throw new IllegalStateException("Unexpected output before mount");
            if (scenario.equals("command_timeout") || scenario.equals("native_exit")) {
                call("open", Map.of("session", token, "audioOnly", true, "source", "owned-fixture"),
                        ignored -> finishResult(false, "unexpected_open_success"));
                return;
            }
            if (scenario.equals("audio_without_view")) {
                call("open", Map.of("session", token, "audioOnly", true, "source", "owned-fixture"),
                        ignored
                        -> finishResult(peers.get(0).opens == 1 && errors == 0,
                                "audio_opened_without_surface"));
                return;
            }
            if (scenario.equals("cancel_wait") || scenario.equals("surface_timeout")) {
                call("open", Map.of("session", token, "source", "owned-fixture"),
                        ignored -> finishResult(false, "opened_without_surface"));
                Object waiting = field("waitingOpen");
                if (waiting == null || peers.get(0).opens != 0)
                    throw new IllegalStateException("Not waiting");
                if (scenario.equals("surface_timeout")) {
                    Field timeout = waiting.getClass().getDeclaredField("timeout");
                    timeout.setAccessible(true);
                    ((Runnable) timeout.get(waiting)).run();
                    finishResult(openErrors == 1 && field("waitingOpen") == null
                                    && peers.get(0).opens == 0,
                            "timeout_completed_once");
                } else
                    call("dispose", Map.of("session", token), ignored -> {
                        try {
                            finishResult(openErrors == 1 && session() == 0
                                            && field("waitingOpen") == null
                                            && peers.get(0).opens == 0,
                                    "cancel_completed_once");
                        } catch (Exception e) {
                            finishResult(false, e.toString());
                        }
                    });
                return;
            }
            call("dispose", Map.of("session", token), ignored -> {
                if (scenario.equals("close_stop_timeout")) {
                    finishResult(false, "unconfirmed_stop_reported_success");
                    return;
                }
                oldMessages = peers.get(0).messages;
                if (!scenario.equals("cache_reuse"))
                    try {
                        retiredDeath = (IBinder.DeathRecipient) field("death");
                        ((Runnable) field("retireIdle")).run();
                    } catch (Exception e) {
                        finishResult(false, e.toString());
                        return;
                    }
                call("create", null, this::createdAgain);
            });
        } catch (Exception e) {
            finishResult(false, e.toString());
        }
    }
    private void createdAgain(Object ignored) {
        if (scenario.equals("cache_reuse")) {
            try {
                finishResult(bindings.size() == 1 && peers.get(0).messages == oldMessages + 1
                                && session() != 0 && errors == 0,
                        "acknowledged_close_reuses_binding");
            } catch (Exception e) {
                finishResult(false, e.toString());
            }
            return;
        }
        if (bindings.size() != 2) {
            finishResult(false, "retiring_binding_reused");
            return;
        }
        switch (scenario) {
            case "stale_disconnect":
                bindings.get(0).onServiceDisconnected(component);
                break;
            case "stale_binding_died":
                bindings.get(0).onBindingDied(component);
                break;
            case "stale_null":
                bindings.get(0).onNullBinding(component);
                break;
            case "stale_connect":
                bindings.get(0).onServiceConnected(component, peers.get(0).messenger.getBinder());
                break;
        }
        retiredDeath.binderDied();
        if (scenario.equals("current_disconnect"))
            bindings.get(1).onServiceDisconnected(component);
        ui.postDelayed(() -> {
            try {
                boolean dead = scenario.equals("current_disconnect");
                finishResult(peers.get(0).messages == oldMessages
                                && (dead ? session() == 0 && errors == 1
                                         : session() != 0 && errors == 0),
                        "session_ownership");
            } catch (Exception e) {
                finishResult(false, e.toString());
            }
        }, 100);
    }
    private long token(Object value) {
        return ((Number) ((Map<?, ?>) value).get("session")).longValue();
    }
    private void realRuntime() {
        try {
            plugin = new OfficialPlayerPlugin();
            field("context", getApplicationContext());
            ui.postDelayed(() -> finishResult(false, "real_runtime_timeout"), 65000);
            call("create", null, first -> call("warmUp", Map.of("session", token(first)), ready -> {
                try {
                    int firstPid = (Integer) field("nativePid");
                    call("dispose", Map.of("session", token(first)),
                            closed -> call("create", null, reused -> {
                                try {
                                    if ((Integer) field("nativePid") != firstPid)
                                        throw new IllegalStateException("Runtime not reused");
                                    plugin.onMethodCall(new MethodCall("warmUp",
                                                                Map.of("session", token(first))),
                                            new MethodChannel.Result() {
                                                public void success(Object value) {
                                                    finishResult(false, "old_session_accepted");
                                                }
                                                public void notImplemented() {
                                                    finishResult(false, "old_session_not_checked");
                                                }
                                                public void error(String code, String message,
                                                        Object details) {
                                                    if (!code.equals("stale_session")) {
                                                        finishResult(false, "wrong_stale_error");
                                                        return;
                                                    }
                                                    call("dispose",
                                                            Map.of("session", token(reused)),
                                                            done -> raceRealRuntime(firstPid));
                                                }
                                            });
                                } catch (Exception e) {
                                    finishResult(false, e.toString());
                                }
                            }));
                } catch (Exception e) {
                    finishResult(false, e.toString());
                }
            }));
        } catch (Exception e) {
            finishResult(false, e.toString());
        }
    }
    private void raceRealRuntime(int firstPid) {
        try {
            ((Runnable) field("retireIdle")).run();
            if (!(Boolean) field("retiringIdle"))
                throw new IllegalStateException("Retirement not entered");
            // Same main-thread callback: the new create must queue before the death callback arrives.
            call("create", null, raced -> {
                try {
                    int nextPid = (Integer) field("nativePid");
                    if (nextPid == firstPid)
                        throw new IllegalStateException("Retiring process reused");
                    call("warmUp", Map.of("session", token(raced)), ready -> {
                        android.os.Process.killProcess(nextPid);
                        awaitRealState("session", 0L,
                                ()
                                        -> call("create", null,
                                                recovered
                                                -> call("warmUp",
                                                        Map.of("session", token(recovered)),
                                                        warm
                                                        -> call("dispose",
                                                                Map.of("session", token(recovered)),
                                                                closed -> {
                                                                    try {
                                                                        ((Runnable) field(
                                                                                 "retireIdle"))
                                                                                .run();
                                                                        awaitRealState("binder",
                                                                                null,
                                                                                ()
                                                                                        -> finishResult(
                                                                                                true,
                                                                                                "real_reuse_stale_session_retirement_race_death_recovery"),
                                                                                SystemClock.uptimeMillis()
                                                                                        + 5000);
                                                                    } catch (Exception e) {
                                                                        finishResult(false,
                                                                                e.toString());
                                                                    }
                                                                }))),
                                SystemClock.uptimeMillis() + 5000);
                    });
                } catch (Exception e) {
                    finishResult(false, e.toString());
                }
            });
        } catch (Exception e) {
            finishResult(false, e.toString());
        }
    }
    private void awaitRealState(String name, Object expected, Runnable next, long deadline) {
        if (finished)
            return;
        try {
            if (Objects.equals(field(name), expected)) {
                next.run();
                return;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                finishResult(false, "state_timeout_" + name);
                return;
            }
            ui.postDelayed(() -> awaitRealState(name, expected, next, deadline), 20);
        } catch (Exception e) {
            finishResult(false, e.toString());
        }
    }
    private void finishResult(boolean passed, String reason) {
        if (finished)
            return;
        finished = true;
        try (FileWriter out =
                        new FileWriter(new File(getFilesDir(), "official-plugin-check.json"))) {
            out.write(new JSONObject()
                              .put("scenario", scenario)
                              .put("passed", passed)
                              .put("reason", reason)
                              .toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (plugin != null)
            for (String name : new String[] {"terminate", "cleanup"})
                try {
                    Method m = OfficialPlayerPlugin.class.getDeclaredMethod(name);
                    m.setAccessible(true);
                    m.invoke(plugin);
                } catch (Exception ignored) {
                }
        finish();
    }
}
