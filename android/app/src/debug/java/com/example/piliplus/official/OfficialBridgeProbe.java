package com.example.piliplus.official;

import android.content.Context;
import android.os.Bundle;
import android.view.SurfaceView;
import io.flutter.plugin.common.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Production plugin and real service with an Activity-owned SurfaceView; not a Flutter rendering test. */
final class OfficialBridgeProbe {
    private final OfficialPlayerPlugin plugin=new OfficialPlayerPlugin();
    private final BiConsumer<String,Bundle> record;
    private long session;
    private final SurfaceView view;

    OfficialBridgeProbe(Context context,SurfaceView view,BiConsumer<String,Bundle> record,Consumer<Bundle> event)throws Exception {
        this.view=Objects.requireNonNull(view);this.record=record;
        field("context",context.getApplicationContext());
        field("sink",new EventChannel.EventSink(){
            @Override public void success(Object raw){
                Map<?,?> data=(Map<?,?>)raw;
                if(session!=0 && ((Number)data.get("session")).longValue()==session)event.accept(bundle(data));
            }
            @Override public void error(String code,String message,Object details){Bundle data=new Bundle();data.putString("error",code);record.accept("failure_bridge_events",data);}
            @Override public void endOfStream(){}
        });
        Bundle data=new Bundle();data.putString("platformArch",System.getProperty("os.arch"));data.putBoolean("realService",true);
        data.putString("surfaceOwner","Activity SurfaceView; no Flutter engine");record.accept("bridge_probe_ready",data);
    }
    private void field(String name,Object value)throws Exception {
        Field f=OfficialPlayerPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value);
    }
    private static Bundle bundle(Map<?,?> source) {
        Bundle out=new Bundle();
        for(Map.Entry<?,?> entry:source.entrySet()){
            String key=(String)entry.getKey();Object value=entry.getValue();
            if(value==null||value instanceof String)out.putString(key,(String)value);
            else if(value instanceof Boolean)out.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)out.putInt(key,(Integer)value);
            else if(value instanceof Long)out.putLong(key,(Long)value);
            else if(value instanceof Float)out.putFloat(key,(Float)value);
            else if(value instanceof Double)out.putDouble(key,(Double)value);
            else if(value instanceof int[])out.putIntArray(key,(int[])value);
            else throw new IllegalArgumentException("Unexpected event type: "+key);
        }
        return out;
    }
    private static Map<String,Object> values(Bundle source) {
        Map<String,Object> out=new HashMap<>();
        for(String key:source.keySet()){
            Object value=source.get(key);
            if(value instanceof Bundle)value=values((Bundle)value);
            else if(value instanceof double[]){List<Double> list=new ArrayList<>();for(double n:(double[])value)list.add(n);value=list;}
            out.put(key,value);
        }
        return out;
    }
    void send(String op,Bundle data,Runnable reply) {
        if(op.equals("close")&&session==0){if(reply!=null)reply.run();return;}
        String method=op.equals("close")?"dispose":op;
        Map<String,Object> args=values(data);if(!method.equals("create"))args.put("session",session);
        if(method.equals("open"))try {field("surface",view.getHolder().getSurface());}
        catch(Exception error){record.accept("failure_bridge_surface",null);return;}
        plugin.onMethodCall(new MethodCall(method,args),new MethodChannel.Result(){
            @Override public void success(Object value){
                if(method.equals("create"))session=((Number)((Map<?,?>)value).get("session")).longValue();
                Bundle audit=new Bundle();audit.putString("method",method);audit.putLong("pluginSession",session);record.accept("command_ok",audit);
                if(method.equals("dispose"))session=0;
                if(reply!=null)reply.run();
            }
            @Override public void error(String code,String message,Object details){Bundle audit=new Bundle();audit.putString("method",method);audit.putString("error",code);record.accept("failure_bridge_command",audit);}
            @Override public void notImplemented(){record.accept("failure_bridge_not_implemented",null);}
        });
    }
    void destroy() {
        for(String name:new String[]{"terminate","cleanup"})try {
            Method method=OfficialPlayerPlugin.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(plugin);
        }catch(Exception error){record.accept("failure_bridge_destroy",null);}
        session=0;
    }
}
