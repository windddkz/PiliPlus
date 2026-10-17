package com.example.piliplus.official;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;

public final class OfficialVideoBundleCheck {
    private static int cases;
    private static Method policy,key;
    private static OfficialRuntime runtime;
    private static Bundle stream(int id,String version) {
        Bundle b=new Bundle();b.putString("source","https://cdn.example/"+id+"?version="+version);b.putInt("id",id);
        b.putInt("bandwidth",id*1000);b.putString("codecs",id<1000?"avc1.640033":null);b.putStringArrayList("backups",new ArrayList<>());return b;
    }
    private static Bundle request(boolean automatic) {
        Bundle b=new Bundle();Bundle video=stream(120,"a"),audio=stream(30280,"a");
        b.putString("source",video.getString("source"));b.putString("audio",audio.getString("source"));
        b.putBundle("sourceTrack",video);b.putBundle("audioTrack",audio);b.putString("preloadToken","video286");
        b.putParcelableArrayList("videoTracks",new ArrayList<>(Arrays.asList(stream(32,"a"),video)));b.putBoolean("automaticVideo",automatic);return b;
    }
    private static String key(Bundle b) throws Exception {return (String)key.invoke(runtime,b);}
    private static void require(boolean value) {if(!value)throw new AssertionError("Contract check failed");cases++;}
    private static void reject(Bundle b) throws Exception {
        try{policy.invoke(null,b);throw new AssertionError("Invalid bundle accepted");}
        catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;cases++;}
    }
    public static void main(String[] args)throws Exception {
        policy=OfficialRuntime.class.getDeclaredMethod("videoPolicy",Bundle.class);policy.setAccessible(true);
        key=OfficialRuntime.class.getDeclaredMethod("preloadKey",Bundle.class);key.setAccessible(true);
        runtime=new OfficialRuntime(null,null,b->{});
        Bundle fixed=request(false),auto=request(true);
        require(!key(fixed).equals(key(auto)));
        Bundle reversed=request(false);Collections.reverse(reversed.<Bundle>getParcelableArrayList("videoTracks"));require(key(fixed).equals(key(reversed)));
        Bundle lowChanged=request(false);lowChanged.<Bundle>getParcelableArrayList("videoTracks").set(0,stream(32,"b"));require(!key(fixed).equals(key(lowChanged)));
        Bundle legacy=request(false);legacy.remove("videoTracks");legacy.remove("automaticVideo");require(policy.invoke(null,legacy)==null);require(!key(fixed).equals(key(legacy)));
        Bundle autoMissing=new Bundle(legacy);autoMissing.putBoolean("automaticVideo",true);reject(autoMissing);
        for(String flag:new String[]{"isLive","audioOnly"}) {Bundle b=request(false);b.putBoolean(flag,true);reject(b);}
        Bundle noAudio=request(false);noAudio.remove("audioTrack");reject(noAudio);
        Bundle missing=request(false);missing.<Bundle>getParcelableArrayList("videoTracks").remove(1);reject(missing);
        Bundle wrongSource=request(false);wrongSource.putString("source","https://other.example/video");reject(wrongSource);
        Bundle empty=request(false);empty.putParcelableArrayList("videoTracks",new ArrayList<Bundle>());reject(empty);
        Bundle nullTrack=request(false);nullTrack.<Bundle>getParcelableArrayList("videoTracks").add(null);reject(nullTrack);
        Bundle wrongCodec=request(false);wrongCodec.<Bundle>getParcelableArrayList("videoTracks").get(0).putString("codecs","av01.0.08M.08");reject(wrongCodec);
        Bundle audioChanged=request(false);Bundle sound=stream(30280,"b");audioChanged.putString("audio",sound.getString("source"));audioChanged.putBundle("audioTrack",sound);require(!key(fixed).equals(key(audioChanged)));
        Bundle position=request(false);position.putLong("position",5000);require(!key(fixed).equals(key(position)));
        System.out.println("PASS "+cases+" actual Android Bundle / native policy / preload identity cases");
    }
}
