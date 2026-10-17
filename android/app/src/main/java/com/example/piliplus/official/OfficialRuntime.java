package com.example.piliplus.official;

import android.content.Context;
import android.os.*;
import android.view.Surface;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** One original player owns audio, video and timing in our dedicated process. */
final class OfficialRuntime {
    interface Events { void send(Bundle data); }
    interface Action { void run() throws Exception; }
    private final Context context;
    private final Handler worker;
    private final Events events;
    private DexClassLoader loader;
    private Object player, item, externalRenderer, externalOutput;
    private OfficialAudioRoute route;
    private Surface outputSurface;
    private Bundle openRequest;
    private long generation, load;
    private boolean initAttempted, prepared, graphActive, dspActive;
    private volatile boolean wantedPlaying;
    private float speed = 1, volume = 1;
    private long startPosition;
    private int width, height, displayWidth, displayHeight, appliedEndpoint;
    private String loudnessMode = "off";
    private String preparationStage = "idle";
    private OfficialLoudness loudness = OfficialLoudness.resolve("off", null, null);

    OfficialRuntime(Context context, Handler worker, Events events) {
        this.context=context; this.worker=worker; this.events=events;
    }
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex=new StringBuilder();
        for(byte b:digest) hex.append(String.format(Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) {
            byte[] bytes=new byte[65536]; for(int n;(n=in.read(bytes))!=-1;)digest.update(bytes,0,n);
        }
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }
    private File materialize(File directory,String name,String expected) throws Exception {
        if(!name.matches("official\\.apk|audio-bridge-v1\\.dex|lib[a-zA-Z0-9_+.-]+\\.so")||!expected.matches("[0-9a-f]{64}"))
            throw new SecurityException("Invalid runtime manifest");
        File result=new File(directory,name);
        if(!result.exists()) {
            File temporary=File.createTempFile("runtime-",".tmp",directory);
            try {
                try(InputStream in=context.getAssets().open("official_runtime/"+name);OutputStream out=new FileOutputStream(temporary)) {
                    if(!temporary.setReadOnly())throw new IOException("Cannot protect runtime");
                    byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)out.write(bytes,0,n);
                }
                if(!expected.equals(hash(temporary)))throw new SecurityException("Packaged runtime hash mismatch");
                if(!temporary.renameTo(result))throw new IOException("Cannot install private runtime");
            } finally {temporary.delete();}
        }
        if(!expected.equals(hash(result)))throw new SecurityException("Cached runtime hash mismatch");
        return result;
    }
    // Android rejects restoring execute permission after modifying a file-backed code page.
    static File playerImage(File directory) throws Exception {
        byte[] bytes=Files.readAllBytes(new File(directory,"libijk.so").toPath());
        if(!hash(bytes).equals("d3971ffa09006581cf612f4d803fc8fd1677fe687f3712ae6d64953e55db5bc2"))
            throw new SecurityException("Unexpected player image");
        ByteBuffer code=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[][] changes={{0x14898c,0x97fffb92,0xd503201f},{0x1a93c4,0x1b0a7d29,0x9b0a7d29},
            {0x1a93c8,0x1ac80928,0x9ac80928},{0x18b488,0x540000ab,0xd503201f}};
        for(int[] change:changes){
            if(code.getInt(change[0])!=change[1])throw new SecurityException("Player instruction mismatch");
            code.putInt(change[0],change[2]);
        }
        String expected=hash(bytes);
        File folder=new File(directory,"player-"+expected);
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create player image directory");
        File image=new File(folder,"libijk.so");
        if(!image.exists()){
            File temporary=File.createTempFile("player-",".tmp",folder);
            try {
                try(OutputStream out=new FileOutputStream(temporary)){
                    if(!temporary.setReadOnly())throw new IOException("Cannot protect player image");
                    out.write(bytes);
                }
                if(!expected.equals(hash(temporary)))throw new SecurityException("Player image write mismatch");
                if(!temporary.renameTo(image))throw new IOException("Cannot install player image");
            }finally{temporary.delete();}
        }
        if(!expected.equals(hash(image)))throw new SecurityException("Cached player image mismatch");
        return folder;
    }
    private void initialize() throws Exception {
        if(loader!=null)return;
        if(initAttempted)throw new IllegalStateException("Runtime initialization previously failed");
        initAttempted=true;
        stage("runtime_assets");
        byte[] bytes;
        try(InputStream in=context.getAssets().open("official_runtime/runtime-manifest.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);bytes=out.toByteArray();
        }
        JSONObject manifest=new JSONObject(new String(bytes,"UTF-8"));
        JSONObject original=manifest.getJSONObject("files"),owned=manifest.getJSONObject("owned");
        if(!"audio_engine".equals(manifest.getString("engine"))||!"arm64-v8a".equals(manifest.getString("abi"))||original.length()!=10||owned.length()!=3)
            throw new SecurityException("Unexpected runtime set");
        File directory=new File(context.getFilesDir(),"official-runtime/"+hash(bytes));
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create runtime directory");
        for(JSONObject group:new JSONObject[]{original,owned})for(Iterator<String> it=group.keys();it.hasNext();) {
            String name=it.next();materialize(directory,name,group.getString(name));
        }
        stage("runtime_image");
        File playerDirectory=playerImage(directory);
        // Run before loading any native library; rollback rebuilds its own assets from its APK.
        OfficialRuntimeCache.Result cache=OfficialRuntimeCache.prune(directory.getParentFile(),directory.getName());
        Bundle cacheEvent=new Bundle();cacheEvent.putString("event","runtime_cache_cleanup");
        cacheEvent.putLong("removedBytes",cache.removedBytes);cacheEvent.putInt("removedDirectories",cache.removedDirectories);
        cacheEvent.putInt("failedDirectories",cache.failedDirectories);emit(cacheEvent);
        // Keep the app's native namespace (including ARM translation), but share only platform Java classes.
        loader=new DexClassLoader(new File(directory,"official.apk").getPath()+File.pathSeparator+new File(directory,"audio-bridge-v1.dex").getPath(),
            context.getCodeCacheDir().getPath(),playerDirectory.getPath()+File.pathSeparator+directory.getPath(),context.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (this) {
                    Class<?> type=findLoadedClass(name);
                    if(type==null) {
                        try {type=Context.class.getClassLoader().loadClass(name);}
                        catch(ClassNotFoundException absent) {type=findClass(name);}
                    }
                    if(resolve)resolveClass(type);
                    return type;
                }
            }
        };
        try {
            stage("library_load");
            invoke(cls("com.bilibili.ijk.utils.IJKLibLoader").getField("INSTANCE").get(null),"ensureLoaded");
            Object logger=construct("com.bilibili.ijk.utils.IJKLog");invoke(logger,"initLogger",false,null,2);
            Field singleton=logger.getClass().getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,logger);
            stage("dsp_register");
            bridge("registerDsp");
            Object params=cls("com.bilibili.ijk.player.IJKNativeParams").getField("INSTANCE").get(null);
            List<?> keys=(List<?>)invoke(params,"nativeKeys$srcs_base_ijk_ijk_library_android");
            if(!keys.contains("ijk.audio_engine.enable"))throw new IllegalStateException("Audio engine option unavailable");
            invoke(params,"nativeSetConfig$srcs_base_ijk_ijk_library_android","ijk.audio_engine.enable","true");
            emit("runtime_ready");
        } catch(Exception | LinkageError error) {loader=null;throw error;}
    }
    private Class<?> cls(String name) throws Exception {return loader.loadClass(name);}
    private static Class<?> boxed(Class<?> t) {
        if(t==int.class)return Integer.class;if(t==long.class)return Long.class;if(t==float.class)return Float.class;
        if(t==boolean.class)return Boolean.class;if(t==double.class)return Double.class;if(t==byte.class)return Byte.class;
        return t;
    }
    private static boolean matches(Class<?>[] types,Object[] args) {
        if(types.length!=args.length)return false;
        for(int i=0;i<types.length;i++)if(args[i]==null?types[i].isPrimitive():!boxed(types[i]).isInstance(args[i]))return false;
        return true;
    }
    private static Object invoke(Object target,String name,Object... args) throws Exception {
        if(target==null)throw new IllegalStateException("Player is not open");
        Class<?> type=target instanceof Class<?>?(Class<?>)target:target.getClass();
        for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&matches(method.getParameterTypes(),args)) {
            method.setAccessible(true);return method.invoke(target instanceof Class<?>?null:target,args);
        }
        throw new NoSuchMethodException(name);
    }
    private Object construct(String name,Object... args) throws Exception {
        for(Constructor<?> c:cls(name).getDeclaredConstructors())if(matches(c.getParameterTypes(),args)) {c.setAccessible(true);return c.newInstance(args);}
        throw new NoSuchMethodException(name+" constructor");
    }
    private Object bridge(String method,Object... args) throws Exception {return invoke(cls("dev.piliplus.dspbridge.DspBridge"),method,args);}
    private void emit(Bundle data) {data.putLong("load",load);events.send(data);}
    private void emit(String name) {Bundle data=new Bundle();data.putString("event",name);emit(data);}
    private void stage(String name) {
        preparationStage=name;
        Bundle event=new Bundle();event.putString("event","preparation_stage");event.putString("stage",name);emit(event);
    }
    String failureCode(String operation,Throwable error) {
        while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();
        return operation+"_"+preparationStage+"_"+error.getClass().getSimpleName();
    }
    private void fail(Throwable error) {
        while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();
        Bundle data=new Bundle();data.putString("event","error");data.putString("error",failureCode("callback",error));emit(data);
    }
    private void run(long token,Action action) {
        if(token!=generation||player==null)return;
        try {action.run();}catch(Throwable error){fail(error);}
    }
    private void updateDisplaySize() throws Exception {
        Object media=invoke(item,"getMediaInfo");
        int w=(Integer)invoke(media,"getWidth"),h=(Integer)invoke(media,"getHeight");
        // MediaCodec dimensions describe allocation; the page needs the source image dimensions.
        displayWidth=w>0&&h>0?w:width;displayHeight=w>0&&h>0?h:height;
    }
    private Object callback(int arity,String event,long token) throws Exception {
        Class<?> type=cls("kotlin.jvm.functions.Function"+arity);
        return Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args)->{
            switch(method.getName()) {
                case "toString":return "PiliPlusOriginalPlayerCallback";
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "invoke":break;
                default:throw new UnsupportedOperationException(method.getName());
            }
            worker.post(()->run(token,()->{
                switch(event) {
                    case "prepared":
                        updateDisplaySize();prepared=true;stage("playback");if(route.endpoint()!=appliedEndpoint){applyRoute();break;}invoke(player,"setSpeed",speed);invoke(player,"setVolume",volume);
                        if(startPosition>0)invoke(player,"seekTo",true,startPosition,5000L);
                        if(wantedPlaying)invoke(player,"start");emit("prepared");heartbeat(token);break;
                    case "completed":wantedPlaying=false;emit("completed");break;
                    case "error":throw new IOException("Native playback failed");
                    case "video_size":
                        width=(Integer)args[1];height=(Integer)args[2];
                        updateDisplaySize();
                        if(externalRenderer!=null&&displayWidth>0&&displayHeight>0)invoke(externalRenderer,"setVideoSize",displayWidth,displayHeight);
                        if(width>0&&height>0&&outputSurface!=null)invoke(player,"setSurfaceSize",width,height);break;
                    case "seek_complete":
                        Bundle seek=new Bundle();seek.putString("event","seek_complete");
                        seek.putLong("position",(Long)args[1]);seek.putLong("target",(Long)args[2]);
                        seek.putLong("duration",(Long)invoke(player,"getDuration"));
                        seek.putBoolean("accurate",(Boolean)args[3]);seek.putBoolean("timedOut",(Boolean)args[4]);emit(seek);break;
                    case "buffering":
                        Bundle b=new Bundle();b.putString("event","buffering");b.putBoolean("buffering",(Boolean)args[1]);emit(b);break;
                    default:emit(event);
                }
            }));
            return cls("Gy1.q").getField("a").get(null);
        });
    }
    void open(Bundle request,Surface surface) throws Exception {
        stage("validate");
        String source=request.getString("source"),audio=request.getString("audio");
        if(source==null||(!source.startsWith("/")&&!source.startsWith("https://")&&!source.startsWith("http://")))throw new IllegalArgumentException("Unsupported source");
        String userAgent=request.getString("userAgent","PiliPlus"),referer=request.getString("referer","https://www.bilibili.com/");
        for(String header:new String[]{userAgent,referer})if(header.length()>4096||header.contains("\r")||header.contains("\n"))throw new IllegalArgumentException("Invalid source header");
        float rate=request.getFloat("speed",1),gain=request.getFloat("volume",1);long position=request.getLong("position",0);
        int endpoint=request.getInt("endpoint",0),virtualizer=request.getInt("virtualizer",1);
        if(!Float.isFinite(rate)||rate<.25f||rate>4||!Float.isFinite(gain)||gain<0||gain>1||position<0||endpoint<0||endpoint>2||virtualizer<0||virtualizer>1)
            throw new IllegalArgumentException("Invalid playback controls");
        release();load=request.getLong("load",1);stage("runtime");initialize();
        long token=++generation;speed=rate;volume=gain;startPosition=position;wantedPlaying=request.getBoolean("play",false);
        openRequest=new Bundle(request);outputSurface=surface;loudnessMode=request.getString("loudnessMode","off");
        Bundle sceneBundle=request.getBundle("loudnessScenes");Map<String,String> scenes=null;
        if(sceneBundle!=null){scenes=new HashMap<>();for(String key:sceneBundle.keySet())scenes.put(key,sceneBundle.getString(key));}
        loudness=OfficialLoudness.resolve(loudnessMode,request.getDoubleArray("loudnessValues"),scenes);
        try {
            stage("audio_route");
            route=new OfficialAudioRoute(context,worker,endpoint,()->run(token,this::applyRoute));appliedEndpoint=route.endpoint();
            stage("graph_adapter");
            bridge("startGraphAdapter",!loudness.filter.isEmpty(),loudness.appInGainDb,
                com.example.piliplus.BuildConfig.DEBUG && request.getBoolean("clockDiagnostics",false),
                com.example.piliplus.BuildConfig.DEBUG && request.getBoolean("startupPrefill",false),
                com.example.piliplus.BuildConfig.DEBUG && request.getBoolean("videoTrace",false));graphActive=true;

            stage("dsp_route");
            bridge("startDspRoute",appliedEndpoint,virtualizer);dspActive=true;
            stage("player_create");
            player=construct("com.bilibili.ijk.player.IJKMediaPlayer");
            for(Object[] binding:new Object[][]{{"setOnPreparedCallback",1,"prepared"},{"setOnCompletionCallback",1,"completed"},{"setOnErrorCallback",2,"error"},
                    {"setOnSeekCompleteCallback",5,"seek_complete"},{"setOnBufferingUpdateCallback",2,"buffering"},{"setOnVideoSizeChangedCallback",3,"video_size"}})
                invoke(player,(String)binding[0],callback((Integer)binding[1],(String)binding[2],token));
            boolean audioOnly=request.getBoolean("audioOnly",false);
            invoke(player,"setAudioOnly",audioOnly);invoke(player,"setVolume",volume);invoke(player,"setSpeed",speed);
            if(!audioOnly&&surface!=null) {
                invoke(cls("tv.danmaku.ijk.media.player.render.core.BiliRenderContext"),"init",context);
                cls("tv.danmaku.ijk.media.player.render.IjkExternalRenderThread").getField("EXTERNAL_RENDER_TYPE").setInt(null,0);
                externalRenderer=construct("tv.danmaku.ijk.media.player.render.IjkExternalRenderThread");
                invoke(externalRenderer,"setDisableSurfaceAlign",true);
                Object render=externalOutput=construct("tv.danmaku.ijk.media.player.render.output.IJKPlayerExternalRender");
                invoke(externalRenderer,"setRenderCallback",render);
                // Flutter owns fitting and cropping; fill its selected output rectangle.
                invoke(render,"aspectRatio",2);
                Object pauseCallback=Proxy.newProxyInstance(loader,new Class<?>[]{cls("tv.danmaku.ijk.media.player.render.IjkExternalRenderThread$GetPlayerPauseStateCallback")},(proxy,method,args)->{
                    switch(method.getName()) {
                        case "toString":return "PiliPlusPlayerPauseState";
                        case "hashCode":return System.identityHashCode(proxy);
                        case "equals":return proxy==args[0];
                        case "isPlayerPause":return !wantedPlaying;
                        default:throw new UnsupportedOperationException(method.getName());
                    }
                });
                invoke(externalRenderer,"setPlayerPauseStateCallback",pauseCallback);
                invoke(externalRenderer,"setAspectRatio",1,1);
                if(!Boolean.TRUE.equals(invoke(externalRenderer,"createWindowSurface",surface)))throw new IOException("Official output window failed");
                mirror(request);
                Surface decoderSurface=(Surface)invoke(externalRenderer,"getAmcSurface");
                if(decoderSurface==null||!decoderSurface.isValid())throw new IOException("Official input surface invalid");
                invoke(player,"setSurface",decoderSurface);
            }
            Map<String,String> options=new HashMap<>();options.put("user_agent",userAgent);options.put("referer",referer);
            Object asset;
            if(audio!=null&&!audio.isEmpty()) {
                if(source.startsWith("/")!=audio.startsWith("/"))throw new IllegalArgumentException("Mixed local/network DASH");
                stage("video_probe");
                DashSource video=DashSource.probe(source,userAgent,referer);
                stage("audio_probe");
                DashSource sound=DashSource.probe(audio,userAgent,referer);
                if(!Arrays.asList("H264","HEVC","AV1").contains(video.codec)||!Arrays.asList("AAC","FLAC","AC3","EAC3").contains(sound.codec))throw new IllegalArgumentException("DASH track types do not match");
                Object scheme=cls("com.bilibili.ijk.media.MediaAssetScheme").getField(source.startsWith("/")?"OFFLINE":"VOD").get(null);
                asset=construct("com.bilibili.ijk.media.MediaAsset$Dash",30280,80,Collections.singletonList(dashStream(video,0,80)),Collections.singletonList(dashStream(sound,1,30280)),scheme,options,null);
            } else {
                Object scheme=cls("com.bilibili.ijk.media.MediaAssetScheme").getField("NORMAL").get(null);
                asset=construct("com.bilibili.ijk.media.MediaAsset$Normal",source,Collections.emptyList(),scheme,options,null);
            }
            stage("prepare");
            Object render=cls("com.bilibili.ijk.player.RenderPath").getField("Direct").get(null);
            Object descriptor=construct("com.bilibili.ijk.player.PlayerItem",asset,0L,false,0,null,null,0,0,render,254,null);
            item=construct("com.bilibili.ijk.player.AndroidNativePlayerItem",descriptor);
            if(!Boolean.TRUE.equals(invoke(player,"switchItem",item)))throw new IOException("switchItem rejected");
            route.start();describeRoute("route_selected");
            worker.postDelayed(()->run(token,()->{if(!prepared)throw new IOException("Preparation timeout");}),20000);
        } catch(Exception | LinkageError error) {try{release();}catch(Exception | LinkageError cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    private Object dashStream(DashSource source,int mediaType,int qn) throws Exception {
        Object codec=cls("com.bilibili.ijk.media.CodecID").getField(source.codec).get(null);
        return construct("com.bilibili.ijk.media.IJKDashStream",mediaType,(Integer)invoke(codec,"getId"),qn,0,source.url,Collections.emptyList());
    }
    private void applyRoute() throws Exception {
        if(route.endpoint()!=appliedEndpoint&&prepared) {
            Bundle next=new Bundle(openRequest);next.putLong("position",(Long)invoke(player,"getCurrentPosition"));
            next.putBoolean("play",wantedPlaying);next.putFloat("speed",speed);next.putFloat("volume",volume);
            Surface current=outputSurface;
            Bundle event=new Bundle();event.putString("event","buffering");event.putBoolean("buffering",true);emit(event);
            open(next,current);
        } else describeRoute("route_changed");
    }
    private void describeRoute(String event) {Bundle data=new Bundle();data.putString("event",event);route.describe(data);emit(data);}
    private void heartbeat(long token) {
        worker.postDelayed(()->run(token,()->{
            route.refresh();if(token!=generation)return;
            JSONObject graph=new JSONObject((String)bridge("getGraphState")),dsp=new JSONObject((String)bridge("getDspState"));
            if(graph.getInt("errors")!=0||graph.getInt("filter_error")!=0)throw new IOException("Audio adaptation failed");
            Bundle b=new Bundle();b.putString("event","state");route.describe(b);
            b.putLong("position",(Long)invoke(player,"getCurrentPosition"));b.putLong("duration",(Long)invoke(player,"getDuration"));
            b.putBoolean("playing",(Boolean)invoke(player,"isPlaying"));b.putFloat("speed",(Float)invoke(player,"getSpeed"));b.putFloat("volume",volume);
            b.putInt("width",displayWidth);b.putInt("height",displayHeight);
            Object monitor=invoke(player,"getPlayerMonitor");long buffered=(Long)invoke(monitor,"getPacketQueueCacheDurationMs",1);
            long duration=b.getLong("duration"),position=b.getLong("position");
            b.putInt("bufferedPercent",duration>0?(int)Math.max(0,Math.min(100,100.0*(position+Math.max(0,buffered))/duration)):0);
            b.putString("audioCodec",dsp.getString("audio_codec"));b.putBoolean("dolbyControlsApplicable",dsp.getInt("configured_decoders")>0);
            b.putLong("nativeDolbyEndpoint",dsp.getInt("endpoint"));b.putInt("sampleRate",graph.getInt("sample_rate"));
            b.putString("channelLayout",Integer.toString(graph.getInt("channels")));
            b.putString("loudnessMode",loudnessMode);b.putString("loudnessState",loudness.reason);b.putString("requestedAudioFilter",loudness.filter);
            b.putDouble("requestedLoudnessGainDb",loudness.appInGainDb);if(Double.isFinite(loudness.target))b.putDouble("loudnessTarget",loudness.target);
            b.putString("nativeEngine","audio_engine");b.putLong("adaptedFrames",graph.getLong("filtered_frames")+graph.getLong("converted_frames"));
            emit(b);heartbeat(token);
        }),250);
    }
    void surface(Surface surface) throws Exception {
        outputSurface=surface;
        if(externalRenderer!=null) {
            if(surface==null)invoke(externalRenderer,"releaseWindowSurface");
            else if(!Boolean.TRUE.equals(invoke(externalRenderer,"createWindowSurface",surface)))throw new IOException("Official output replacement failed");
        } else if(player!=null){invoke(player,"setSurface",surface);if(surface!=null&&width>0&&height>0)invoke(player,"setSurfaceSize",width,height);}
    }
    void mirror(Bundle request) throws Exception {
        if(openRequest==null || request.getLong("load",1)!=load)throw new IllegalStateException("Mirror requires current source");
        boolean x=request.getBoolean("flipX",false),y=request.getBoolean("flipY",false);
        openRequest.putBoolean("flipX",x);openRequest.putBoolean("flipY",y);
        applyDisplayTransform();
    }
    void viewport(Bundle request) throws Exception {
        if(openRequest==null || request.getLong("load",-1)!=load)throw new IllegalStateException("Viewport requires current source");
        for(String key:new String[]{"scaleX","scaleY","offsetX","offsetY"}) {
            float value=request.getFloat(key,Float.NaN);
            if(!Float.isFinite(value) || (key.startsWith("scale") && value<=0) || Math.abs(value)>1000000)throw new IllegalArgumentException("Invalid viewport");
        }
        for(String key:new String[]{"scaleX","scaleY","offsetX","offsetY"})openRequest.putFloat(key,request.getFloat(key));
        applyDisplayTransform();
    }
    private void applyDisplayTransform() throws Exception {
        if(externalOutput==null)return;
        // Crop in the original renderer instead of allocating a decoder-sized Android window.
        invoke(externalOutput,"translate",Math.round(openRequest.getFloat("offsetX",0)),Math.round(openRequest.getFloat("offsetY",0)));
        invoke(externalOutput,"scale",openRequest.getFloat("scaleX",1)*(openRequest.getBoolean("flipX",false)?-1f:1f),openRequest.getFloat("scaleY",1)*(openRequest.getBoolean("flipY",false)?-1f:1f));
    }
    void resize() throws Exception {
        if(externalRenderer!=null && outputSurface!=null && outputSurface.isValid()) {
            // A paused EGL window can still report its old buffer size until it is reattached.
            invoke(externalRenderer,"releaseWindowSurface");
            if(!Boolean.TRUE.equals(invoke(externalRenderer,"createWindowSurface",outputSurface)))throw new IOException("Official output resize failed");
        }
    }
    void play(boolean value) throws Exception {wantedPlaying=value;if(prepared)invoke(player,value?"start":"pause");}
    void seek(long position) throws Exception {
        if(!prepared||position<0)throw new IllegalStateException("Seek requires prepared player and valid position");
        invoke(player,"seekTo",true,position,5000L);
    }
    void speed(float value) throws Exception {
        if(!Float.isFinite(value)||value<.25f||value>4)throw new IllegalArgumentException("Invalid speed");
        if(player!=null)invoke(player,"setSpeed",value);speed=value;
    }
    void volume(float value) throws Exception {
        if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid volume");
        if(player!=null)invoke(player,"setVolume",value);volume=value;
    }
    void release() throws Exception {
        ++generation;prepared=false;wantedPlaying=false;
        if(route!=null){route.stop();route=null;}
        if(player!=null){invoke(player,"release");player=null;}
        if(item!=null){invoke(item,"release");item=null;}
        if(externalRenderer!=null){invoke(externalRenderer,"quitExternalRender");externalRenderer=null;externalOutput=null;}
        if(graphActive){JSONObject result=new JSONObject((String)bridge("finishGraphAdapter"));graphActive=false;
            if(com.example.piliplus.BuildConfig.DEBUG && result.optBoolean("video_trace_enabled")) {
                int rows=(Integer)bridge("finishVideoTrace",new File(context.getFilesDir(),"official-video-trace.tsv").getPath());
                Bundle audit=new Bundle();audit.putString("event","video_trace_saved");audit.putBoolean("restored",result.getBoolean("restored"));audit.putInt("rows",rows);emit(audit);
            }
            if(!result.getBoolean("restored"))throw new IOException("Adapter not restored");}
        if(dspActive){JSONObject result=new JSONObject((String)bridge("finishDspRoute"));dspActive=false;if(!result.getBoolean("slot_restored"))throw new IOException("DSP not restored");}
        width=height=displayWidth=displayHeight=0;openRequest=null;outputSurface=null;loudnessMode="off";loudness=OfficialLoudness.resolve("off",null,null);
    }
}
