package com.example.piliplus.official;

import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Uses the installed APK's real Flutter bridge, rather than a hand-written Bundle. */
public final class OfficialSourceBundleCheck {
    public static void main(String[] args) throws Exception {
        Class<?> callType = Class.forName("io.flutter.plugin.common.MethodCall");
        Method sources = Class.forName("com.example.piliplus.official.OfficialPlayerPlugin")
                                 .getDeclaredMethod("putSources", callType, Bundle.class);
        sources.setAccessible(true);
        for (String scope : new String[] {"account_1", "account_2", null}) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("source", "https://example.invalid/video");
            fields.put("audio", "https://example.invalid/audio");
            fields.put("preloadScope", "request-generation");
            if (scope != null)
                fields.put("cacheScope", scope);
            Object call =
                    callType.getConstructor(String.class, Object.class).newInstance("open", fields);
            Bundle result = new Bundle();
            sources.invoke(null, call, result);
            if (!java.util.Objects.equals(scope, result.getString("cacheScope")))
                throw new AssertionError("Bridge dropped persistent cache scope");
            for (String key : new String[] {"source", "audio", "preloadScope"})
                if (!fields.get(key).equals(result.getString(key)))
                    throw new AssertionError("Bridge changed " + key);
        }
        System.out.println("PASS installed APK source bridge preserves account and media identity");
    }
}
