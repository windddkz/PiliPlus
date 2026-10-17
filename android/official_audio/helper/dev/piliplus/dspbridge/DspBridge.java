package dev.piliplus.dspbridge;

/** Loaded beside the hash-locked original runtime in its dedicated player process. */
public final class DspBridge {
    static { System.loadLibrary("piliplus_audio_dsp"); System.loadLibrary("piliplus_audio_graph"); }
    public static native String registerDsp();
    public static native void interruptPreload(long nativeItem);
    public static native void startGraphAdapter(boolean loudnessEnabled, double gainDb, boolean diagnostics, boolean prefill, boolean videoDiagnostics);
    public static native String getGraphState();
    public static native int finishVideoTrace(String path);
    public static native String finishGraphAdapter();
    public static native void startDspRoute(int endpoint, int virtualizer, boolean hardwareDolby);
    public static native void updateDspEndpoint(int endpoint);
    public static native String getDspState();
    public static native String finishDspRoute();
}
