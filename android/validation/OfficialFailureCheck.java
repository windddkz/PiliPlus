package com.example.piliplus.official;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public final class OfficialFailureCheck {
    public static void main(String[] args) throws Exception {
        OfficialRuntime runtime = new OfficialRuntime(null, null, data -> {});
        var stage = OfficialRuntime.class.getDeclaredField("preparationStage");
        stage.setAccessible(true);
        stage.set(runtime, "audio_probe");
        Throwable error = new InvocationTargetException(new InvocationTargetException(
                new IOException("https://example.invalid/audio?token=private")));
        assert runtime.failureCode("open", error).equals("open_audio_probe_IOException");
        stage.set(runtime, "runtime");
        assert runtime.failureCode("open", new UnsatisfiedLinkError("private path"))
                .equals("open_runtime_UnsatisfiedLinkError");
        System.out.println("Failure stage, nested cause and private-message exclusion passed");
    }
}
