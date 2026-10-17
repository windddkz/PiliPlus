package com.example.piliplus.official;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.view.Surface;

/** Non-exported, bound service; all native calls are serialized off the Binder/UI thread. */
public final class OfficialPlaybackService extends Service {
    public static final int COMMAND = 1, EVENT = 2, REPLY = 3, TERMINATE = 4;
    private HandlerThread thread;
    private Handler worker;
    private OfficialRuntime runtime;
    private Messenger client;
    private Surface surface;
    private long session;
    private volatile IBinder owner;
    private final IBinder.DeathRecipient ownerDeath = () -> android.os.Process.killProcess(android.os.Process.myPid());

    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == TERMINATE) {
            if (owner != null && message.replyTo != null && owner.equals(message.replyTo.getBinder()))
                android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if (message.what != COMMAND || message.replyTo == null) return true;
        Message copy = Message.obtain(message);
        worker.post(() -> dispatch(copy));
        return true;
    }));

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("official-player-control"); thread.start(); worker = new Handler(thread.getLooper());
        runtime = new OfficialRuntime(getApplicationContext(), worker, event -> send(EVENT, 0, event));
    }
    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }

    private void send(int what, int id, Bundle data) {
        if (client == null) return;
        data.putLong("session", session);
        Message m = Message.obtain(null, what, id, 0); m.setData(data);
        try { client.send(m); }
        catch (RemoteException e) { android.os.Process.killProcess(android.os.Process.myPid()); }
    }
    private void setSurface(Surface next) throws Exception {
        runtime.surface(next);
        if (surface != null) surface.release();
        surface = next;
    }
    private void dispatch(Message message) {
        Bundle data = message.getData(); data.setClassLoader(getClassLoader());
        String op = data.getString("op", "");
        try {
            if (owner == null) {
                if (!op.equals("create") || data.getLong("session") <= 0) throw new IllegalArgumentException("Create first");
                session = data.getLong("session"); client = message.replyTo; owner = client.getBinder();
                owner.linkToDeath(ownerDeath, 0);
            } else if (!owner.equals(message.replyTo.getBinder()) || session != data.getLong("session")) {
                throw new SecurityException("Stale session");
            }
            switch (op) {
                case "create": break;
                case "open": runtime.open(data, surface); break;
                case "surface": runtime.outputWindow(data); setSurface(data.getParcelable("surface")); break;
                case "play": runtime.play(true); break;
                case "pause": runtime.play(false); break;
                case "mirror": runtime.mirror(data); break;
                case "viewport": runtime.viewport(data); break;
                case "resize": runtime.outputWindow(data); runtime.resize(); break;
                case "seek": runtime.seek(data.getLong("position", -1)); break;
                case "speed": runtime.speed(data.getFloat("speed", Float.NaN)); break;
                case "volume": runtime.volume(data.getFloat("volume", Float.NaN)); break;
                case "close": runtime.release(); runtime.outputWindow(new Bundle()); setSurface(null); break;
                default: throw new IllegalArgumentException("Unknown operation");
            }
            Bundle reply = new Bundle(); reply.putBoolean("ok", true); reply.putInt("pid", android.os.Process.myPid());
            send(REPLY, message.arg1, reply);
            if (op.equals("close")) {
                owner.unlinkToDeath(ownerDeath, 0); owner = null; client = null; session = 0;
            }
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
                cause = cause.getCause();
            if (com.example.piliplus.BuildConfig.DEBUG) {
                if (cause instanceof LinkageError)
                    android.util.Log.e("PiliPlusOfficial", "Runtime class linkage failed", cause);
                else {
                    android.util.Log.e("PiliPlusOfficial", "Command " + op + " failed: " + cause.getClass().getName());
                    for (StackTraceElement frame : cause.getStackTrace())
                        android.util.Log.e("PiliPlusOfficial", frame.toString());
                }
            }
            Bundle reply = new Bundle(); reply.putBoolean("ok", false);
            reply.putString("error", runtime.failureCode(op,cause));
            // Reply to the caller directly; a stale caller must not receive another session's events.
            reply.putLong("session", data.getLong("session"));
            Message out = Message.obtain(null, REPLY, message.arg1, 0); out.setData(reply);
            try { message.replyTo.send(out); } catch (RemoteException ignored) { }
        } finally { message.recycle(); }
    }
    @Override public boolean onUnbind(Intent intent) {
        // No client remains to own native audio; a stuck release must not outlive its owner.
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
    @Override public void onDestroy() {
        if (worker != null) worker.post(() -> { try { runtime.release(); } catch (Exception ignored) { }
            runtime.outputWindow(new Bundle());
            if (surface != null) surface.release(); thread.quitSafely(); });
        super.onDestroy();
    }
}
