package com.example.piliplus.official;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.util.*;

/** Observes media routing policy; never opens another track or changes system routing. */
final class OfficialAudioRoute {
    private final AudioManager manager;
    private final Handler worker;
    private final Runnable changed;
    private final int override;
    private boolean listening;
    private int[] ids = new int[0];
    private int[] types = new int[0];
    private String evidence = "unavailable";
    private long epoch;
    private int endpoint = 1;
    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refresh(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refresh(); }
    };

    OfficialAudioRoute(Context context, Handler worker, int override, Runnable changed) {
        this.manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.worker = worker; this.override = override; this.changed = changed;
        update(false);
    }
    int endpoint() { return endpoint; }
    void start() {
        if (override != 0 || manager == null || listening) return;
        listening = true;
        manager.registerAudioDeviceCallback(callback, worker);
    }
    void stop() {
        if (listening) { listening = false; manager.unregisterAudioDeviceCallback(callback); }
    }
    void refresh() { if (listening) update(true); }
    private void update(boolean notify) {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        String origin = "unavailable";
        if (override != 0) origin = "test_override";
        else if (manager != null) {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    devices.addAll(manager.getAudioDevicesForAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()));
                    if (!devices.isEmpty()) origin = "media_policy_prediction";
                } catch (SecurityException | UnsupportedOperationException ignored) { }
            }
            if (devices.isEmpty()) {
                try {
                    devices.addAll(Arrays.asList(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)));
                    if (!devices.isEmpty()) origin = "connected_devices_inference";
                } catch (SecurityException ignored) { }
            }
        }
        devices.sort(Comparator.comparingInt(AudioDeviceInfo::getId));
        int[] nextIds = new int[devices.size()], nextTypes = new int[devices.size()];
        for (int i = 0; i < devices.size(); ++i) { nextIds[i] = devices.get(i).getId(); nextTypes[i] = devices.get(i).getType(); }
        int nextEndpoint = override != 0 ? override : AudioEndpointPolicy.endpoint(nextTypes);
        if (epoch == 0 || endpoint != nextEndpoint || !evidence.equals(origin) || !Arrays.equals(ids, nextIds)
            || !Arrays.equals(types, nextTypes)) {
            endpoint = nextEndpoint; evidence = origin; ids = nextIds; types = nextTypes; ++epoch;
            if (notify) changed.run();
        }
    }
    void describe(Bundle data) {
        data.putInt("dolbyEndpoint", endpoint); data.putString("routeEvidence", evidence);
        data.putIntArray("routeDeviceTypes", types.clone()); data.putLong("routeEpoch", epoch);
        // No AudioTrack handle is exposed by the original JNI; actual device/timestamp remains unavailable.
        data.putBoolean("actualRouteVerified", false);
    }
}
