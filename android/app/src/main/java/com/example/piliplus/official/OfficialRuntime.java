package com.example.piliplus.official;

import android.content.Context;
import android.os.*;
import android.view.Surface;
import android.view.SurfaceControl;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

/** One original player owns audio, video and timing in our dedicated process. */
final class OfficialRuntime {
    interface Events { void send(Bundle data); }
    interface Action { void run() throws Exception; }
    private final Context context;
    private final Handler worker;
    private final Events events;
    private DexClassLoader loader;
    private Object player, item, externalRenderer, externalOutput;
    private Object preloader;
    private OfficialPreloadCache<NativeMedia> preloads;
    private final ThreadPoolExecutor preloadProbeWorker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2),r->new Thread(r,"official-preload-probe"));
    private final Map<String,PreloadProbe> preloadProbes=new HashMap<>();
    private long preloadEpoch, awaitingPreload;
    private static final class NativeMedia {
        final Object asset; final boolean hdr, nativeMetadata; Object item; long preloadTarget=2000;
        NativeMedia(Object asset,boolean hdr,boolean nativeMetadata){this.asset=asset;this.hdr=hdr;this.nativeMetadata=nativeMetadata;}
    }
    private static final class PreloadProbe {
        final long epoch; Future<DashSource[]> future;
        PreloadProbe(long epoch){this.epoch=epoch;}
        DashSource[] await() throws InterruptedIOException {
            try {return future.get(300,TimeUnit.MILLISECONDS);}
            catch(ExecutionException | CancellationException | TimeoutException unavailable){return null;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Preload probe wait interrupted");}
            finally {future.cancel(true);}
        }
    }

    private OfficialAudioRoute route;
    private Surface outputSurface;
    private SurfaceControl outputParent;
    private int windowWidth,windowHeight;
    private OfficialHdrSurface hdrSurface;
    void outputWindow(Bundle data) {
        if(Build.VERSION.SDK_INT<33)return;
        SurfaceControl next=data.getParcelable("parent");
        SurfaceControl previous=outputParent;outputParent=next;
        windowWidth=data.getInt("windowWidth");windowHeight=data.getInt("windowHeight");
        try {updateHdrSurface();} finally {if(previous!=null)previous.release();}
    }
    private void updateHdrSurface() {
        if(hdrSurface!=null)hdrSurface.update(outputParent,windowWidth,windowHeight,displayWidth,displayHeight,openRequest);
    }
    private Bundle openRequest;
    private volatile long generation, load;
    private final OfficialResourceRefresh<Bundle> resourceRefresh=new OfficialResourceRefresh<>();
    private boolean initAttempted, prepared, graphActive, dspActive, awaitingMediaInfo;
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
            {0x1a93c8,0x1ac80928,0x9ac80928},{0x18b488,0x540000ab,0xd503201f},
            // AudioGraph accepts 0.25x; the renderer otherwise silently rejects it before reaching the graph.
            {0x156c38,0x1e2c1000,0x1e2a1000}};
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
    void initialize() throws Exception {
        if(loader!=null)return;
        if(initAttempted)throw new IllegalStateException("Runtime initialization previously failed");
        initAttempted=true;
        stage("runtime_assets");
        byte[] bytes;
        try(InputStream in=context.getAssets().open("official_runtime/runtime-manifest.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);bytes=out.toByteArray();
        }
        JSONObject manifest=new JSONObject(new String(bytes,"UTF-8"));
        JSONObject original=manifest.getJSONObject("files"),owned=manifest.getJSONObject("owned"),code=manifest.getJSONObject("code");
        if(!"audio_engine".equals(manifest.getString("engine"))||!"arm64-v8a".equals(manifest.getString("abi"))||original.length()!=9||owned.length()!=3||code.length()!=1||!code.has("official.apk"))
            throw new SecurityException("Unexpected runtime set");
        File directory=new File(context.getFilesDir(),"official-runtime/"+hash(("platform-loader-v1:"+hash(bytes)).getBytes("UTF-8")));
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create runtime directory");
        for(JSONObject group:new JSONObject[]{original,owned,code})for(Iterator<String> it=group.keys();it.hasNext();) {
            String name=it.next();materialize(directory,name,group.getString(name));
        }
        stage("runtime_image");
        File playerDirectory=playerImage(directory);
        // Run before loading any native library; rollback rebuilds its own assets from its APK.
        OfficialRuntimeCache.Result cache=OfficialRuntimeCache.prune(directory.getParentFile(),directory.getName());
        Bundle cacheEvent=new Bundle();cacheEvent.putString("event","runtime_cache_cleanup");
        cacheEvent.putLong("removedBytes",cache.removedBytes);cacheEvent.putInt("removedDirectories",cache.removedDirectories);
        cacheEvent.putInt("failedDirectories",cache.failedDirectories);emit(cacheEvent);
        // Standard platform-parent loading preserves Java isolation and a compilable ART context.
        loader=new DexClassLoader(new File(directory,"official.apk").getPath()+File.pathSeparator+new File(directory,"audio-bridge-v1.dex").getPath(),
            context.getCodeCacheDir().getPath(),playerDirectory.getPath()+File.pathSeparator+directory.getPath(),Context.class.getClassLoader());
        try {
            stage("foundation");
            invoke(cls("com.bilibili.lib.foundation.Foundation"),"init",context,
                context.getSharedPreferences("official_runtime_foundation",Context.MODE_PRIVATE),
                construct("com.bilibili.lib.foundation.Foundation$Configuration"));
            stage("library_load");
            invoke(cls("com.bilibili.ijk.utils.IJKLibLoader").getField("INSTANCE").get(null),"ensureLoaded");
            Object logger=construct("com.bilibili.ijk.utils.IJKLog");invoke(logger,"initLogger",false,null,2);
            Field singleton=logger.getClass().getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,logger);
            stage("dsp_register");
            bridge("registerDsp");
            bridge("installMediaInspection");
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
        updateHdrSurface();
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
                        updateDisplaySize();
                        prepared=true;stage("playback");if(route.endpoint()!=appliedEndpoint)applyRoute();speed(speed);invoke(player,"setVolume",volume);
                        if(startPosition>0)invoke(player,"seekTo",true,startPosition,5000L);
                        if(wantedPlaying)invoke(player,"start");emit("prepared");heartbeat(token);break;
                    case "completed":wantedPlaying=false;emit("completed");break;
                    case "error":
                        int code=(Integer)args[1];
                        release();
                        Bundle failure=new Bundle();failure.putString("event","error");failure.putString("error","native_error_"+code);emit(failure);break;
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
            return cls("kotlin.Unit").getField("INSTANCE").get(null);
        });
    }

    private NativeMedia media(Bundle request,DashSource[] tracks) throws Exception {
        String source=request.getString("source"),audio=request.getString("audio");
        String userAgent=request.getString("userAgent","PiliPlus"),referer=request.getString("referer","https://www.bilibili.com/");
        OfficialStream sourceTrack=streamInfo(request.getBundle("sourceTrack"),source);
        OfficialStream audioTrack=streamInfo(request.getBundle("audioTrack"),audio);
        boolean isLive=request.getBoolean("isLive",false);
        Map<String,String> options=new HashMap<>();options.put("user_agent",userAgent);options.put("referer",referer);
        if(!isLive && useNativeMetadata(sourceTrack,audioTrack)) {
            if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliMedia","native DASH metadata video="+sourceTrack.videoCodec()+" audio=-1");
            Object scheme=cls("com.bilibili.ijk.media.MediaAssetScheme").getField("VOD").get(null);
            Object asset=construct("com.bilibili.ijk.media.MediaAsset$Dash",audioTrack.id,sourceTrack.id,
                Collections.singletonList(dashStream(sourceTrack,0)),Collections.singletonList(dashStream(audioTrack,1)),scheme,options,null);
            return new NativeMedia(asset,false,true);
        }
            Object asset;
            boolean hdr=false;
            if(audio!=null&&!audio.isEmpty()) {
                if(source.startsWith("/")!=audio.startsWith("/"))throw new IllegalArgumentException("Mixed local/network DASH");
                if(tracks==null){stage("dash_probe");
                tracks=DashSource.probePair(source,sourceTrack==null?Collections.emptyList():sourceTrack.backups,
                    audio,audioTrack==null?Collections.emptyList():audioTrack.backups,userAgent,referer);}
                DashSource video=tracks[0],sound=tracks[1];
                hdr=video.hdr;
                if(!Arrays.asList("H264","HEVC","AV1").contains(video.codec)||!Arrays.asList("AAC","FLAC","AC3","EAC3").contains(sound.codec))throw new IllegalArgumentException("DASH track types do not match");
                Object scheme=cls("com.bilibili.ijk.media.MediaAssetScheme").getField(source.startsWith("/")?"OFFLINE":"VOD").get(null);
                // Local or manually supplied tracks retain opaque adapter IDs when metadata is unavailable.
                int videoId=sourceTrack==null?80:sourceTrack.id,audioId=audioTrack==null?30280:audioTrack.id;
                asset=construct("com.bilibili.ijk.media.MediaAsset$Dash",audioId,videoId,Collections.singletonList(dashStream(video,0,videoId,sourceTrack)),Collections.singletonList(dashStream(sound,1,audioId,audioTrack)),scheme,options,null);
            } else {
                // VOD and LIVE enable their respective original HTTP recovery hooks.
                Object scheme=cls("com.bilibili.ijk.media.MediaAssetScheme").getField(isLive?"LIVE":sourceTrack!=null&&!source.startsWith("/")?"VOD":"NORMAL").get(null);
                asset=construct("com.bilibili.ijk.media.MediaAsset$Normal",source,sourceTrack==null?Collections.emptyList():sourceTrack.backups,scheme,options,null);
            }

        return new NativeMedia(asset,hdr,false);
    }
    private static boolean useNativeMetadata(OfficialStream video,OfficialStream audio) {
        return video!=null && audio!=null && video.videoCodec()>=0;
    }
    private Object dashStream(OfficialStream track,int mediaType) throws Exception {
        return construct("com.bilibili.ijk.media.IJKDashStream",mediaType,mediaType==0?track.videoCodec():-1,
            track.id,track.bandwidth,track.source,track.backups);
    }
    private Object nativeItem(Object asset,long position) throws Exception {
        Object render=cls("com.bilibili.ijk.player.RenderPath").getField("Direct").get(null);
        Object unit=cls("kotlin.time.DurationUnit").getField("MILLISECONDS").get(null);
        long duration=(Long)invoke(cls("kotlin.time.DurationKt"),"toDuration",position,unit);
        Object descriptor=construct("com.bilibili.ijk.player.PlayerItem",asset,duration,false,0,null,null,0,0,render,252,null);
        return construct("com.bilibili.ijk.player.AndroidNativePlayerItem",descriptor);
    }
    private String preloadKey(Bundle request) throws Exception {
        String token=request.getString("preloadToken");if(token==null)return null;
        if(!token.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid preload token");
        JSONObject key=new JSONObject();key.put("token",token);key.put("position",request.getLong("position",0));
        for(String name:new String[]{"source","audio","userAgent","referer"})key.put(name,request.getString(name,""));
        for(String name:new String[]{"sourceTrack","audioTrack"}){
            Bundle info=request.getBundle(name);if(info==null)continue;
            JSONObject track=new JSONObject();track.put("source",info.getString("source"));track.put("id",info.getInt("id"));
            track.put("codecs",info.getString("codecs"));track.put("bandwidth",info.getInt("bandwidth"));track.put("backups",new org.json.JSONArray(info.getStringArrayList("backups")));
            key.put(name,track);
        }
        return hash(key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void preloadFailure(Exception error) {
        if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.w("PiliPreload","disabled: "+error.getClass().getSimpleName());
        if(awaitingPreload!=0 && awaitingPreload==generation)fail(error);
    }
    private void preloadResult(String method,Object... args) throws Exception {
        if(((Number)invoke(preloader,method,args)).intValue()!=0)throw new IOException("Native preload command rejected");
    }
    private void ensurePreloader() throws Exception {
        if(preloads!=null)return;
        preloader=construct("com.bilibili.ijk.preload.IJKNativePreloader");
        String add=Arrays.stream(preloader.getClass().getDeclaredMethods()).map(Method::getName)
            .filter(name->name.startsWith("addTask-")).findFirst().orElseThrow(()->new NoSuchMethodException("addTask"));
        preloads=new OfficialPreloadCache<>(new OfficialPreloadCache.Backend<NativeMedia>() {
            public void add(long request,long task,NativeMedia media) throws Exception {preloadResult(add,request,task,media.item,(byte)50,media.preloadTarget);}
            public void detach(long request,long task,NativeMedia media) throws Exception {preloadResult("detachTask",request,task,media.item);}
            public void interrupt(NativeMedia media) throws Exception {
                Object handle=invoke(media.item,"nativePtrOrNull$srcs_base_ijk_ijk_library_android");
                if(!(handle instanceof Long))throw new IllegalStateException("Missing native preload item");
                bridge("interruptPreload",handle);
            }
            public void remove(long request,long task,NativeMedia media) throws Exception {preloadResult("removeTask",request,task,media.item);}
            public void release(NativeMedia media) throws Exception {invoke(media.item,"release");}
            public void failed(Exception error){preloadFailure(error);}
        },(delay,action)->worker.postDelayed(action,delay),SystemClock::elapsedRealtime);
        Object cb=Proxy.newProxyInstance(loader,new Class<?>[]{cls("kotlin.jvm.functions.Function1")},(proxy,method,args)->{
            switch(method.getName()){
                case "toString":return "PiliPlusPreloader";
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "invoke":
                    Object event=args[0];worker.post(()->{
                        try {
                            String type=invoke(event,"getType").toString();int error=(Integer)invoke(event,"getError");
                            long request=(Long)invoke(event,"getRequestId"),task=(Long)invoke(event,"getTaskId");
                            preloads.event(request,task,type,error);
                            if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliPreload",type+" request="+request+" task="+task+" error="+error+" retained="+preloads.size());
                        } catch(Exception error){preloadFailure(error);}
                    });
                    return cls("kotlin.Unit").getField("INSTANCE").get(null);
                default:throw new UnsupportedOperationException(method.getName());
            }
        });
        invoke(preloader,"setEventCallback",cb);
    }
    void preload(Bundle data) throws Exception {
        initialize();String key=preloadKey(data);
        if(key==null || data.getLong("position",0)<0)throw new IllegalArgumentException("Invalid preload request");
        String source=data.getString("source"),audio=data.getString("audio");
        OfficialStream video=streamInfo(data.getBundle("sourceTrack"),source),sound=streamInfo(data.getBundle("audioTrack"),audio);
        if(video==null || sound==null)throw new IllegalArgumentException("Preload requires two validated DASH streams");
        String ua=data.getString("userAgent","PiliPlus"),referer=data.getString("referer","https://www.bilibili.com/");
        for(String header:new String[]{ua,referer})if(header.length()>4096||header.contains("\r")||header.contains("\n"))throw new IllegalArgumentException("Invalid source header");
        if(player!=null)return;
        ensurePreloader();
        if(preloads.isBroken() || preloads.size()+preloadProbes.size()>=3 || preloadProbes.containsKey(key) || preloads.peek(key)!=null)return;
        Bundle request=new Bundle(data);
        if(useNativeMetadata(video,sound)) {
            NativeMedia media=media(request,null);media.item=nativeItem(media.asset,request.getLong("position",0));preloads.add(key,media);
            return;
        }
        PreloadProbe probe=new PreloadProbe(preloadEpoch);preloadProbes.put(key,probe);
        if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliPreload","probe queued");
        try {probe.future=preloadProbeWorker.submit(()->{
            try {
                // Formal playback may take over this task using the exact preload identity.
                DashSource[] tracks=DashSource.probePair(source,video.backups,audio,sound.backups,ua,referer);
                worker.post(()->{
                    if(preloadProbes.get(key)!=probe)return;
                    preloadProbes.remove(key);
                    if(probe.epoch!=preloadEpoch || player!=null || preloads.isBroken())return;
                    try {NativeMedia media=media(request,tracks);media.item=nativeItem(media.asset,request.getLong("position",0));preloads.add(key,media);}
                    catch(Exception error){preloadFailure(error);}
                });
                return tracks;
            } catch(Exception error){worker.post(()->{
                if(preloadProbes.get(key)==probe){preloadProbes.remove(key);if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliPreload","probe skipped: "+error.getClass().getSimpleName());}
            });throw error;}
        });} catch(RejectedExecutionException full){preloadProbes.remove(key);}
    }
    private PreloadProbe takePreloadProbe(String key) {
        ++preloadEpoch;
        PreloadProbe selected=preloadProbes.remove(key);
        for(PreloadProbe probe:preloadProbes.values()){
            probe.future.cancel(true);preloadProbeWorker.remove((Runnable)probe.future);
        }
        preloadProbes.clear();
        return selected;
    }
    void close() throws Exception {
        release();
        // Unconfirmed native ownership is reclaimed by the existing failed-close process barrier.
        if(preloads!=null && preloads.isBroken())throw new IOException("Preload stop unconfirmed");
    }
    private void useItem(Object next,long token,Bundle request) throws Exception {
        item=next;awaitingPreload=0;resourceRefresh.open();
        AtomicInteger resourceUpdates=new AtomicInteger();
        invoke(item,"setResourceUpdateListener",resourceListener(token,new Bundle(request),resourceUpdates));
        if(!Boolean.TRUE.equals(invoke(player,"switchItem",item)))throw new IOException("switchItem rejected");
        route.start();describeRoute("route_selected");
        worker.postDelayed(()->run(token,()->{
            if(prepared)return;
            if(resourceUpdates.get()>0)failResource();
            else {release();throw new IOException("Preparation timeout");}
        }),20000);
    }

    void open(Bundle request,Surface surface) throws Exception {
        stage("validate");
        String source=request.getString("source"),audio=request.getString("audio");
        if(source==null||(!source.startsWith("/")&&!source.startsWith("https://")&&!source.startsWith("http://")))throw new IllegalArgumentException("Unsupported source");
        OfficialStream sourceTrack=streamInfo(request.getBundle("sourceTrack"),source);
        OfficialStream audioTrack=streamInfo(request.getBundle("audioTrack"),audio);
        String userAgent=request.getString("userAgent","PiliPlus"),referer=request.getString("referer","https://www.bilibili.com/");
        for(String header:new String[]{userAgent,referer})if(header.length()>4096||header.contains("\r")||header.contains("\n"))throw new IllegalArgumentException("Invalid source header");
        float rate=request.getFloat("speed",1),gain=request.getFloat("volume",1);long position=request.getLong("position",0);
        boolean isLive=request.getBoolean("isLive",false);
        if(isLive&&(source.startsWith("/")||audio!=null||position!=0))throw new IllegalArgumentException("Live source cannot be split or seeked");
        int endpoint=request.getInt("endpoint",0),virtualizer=request.getInt("virtualizer",1);
        if(!Float.isFinite(rate)||rate<.25f||rate>4||!Float.isFinite(gain)||gain<0||gain>1||position<0||endpoint<0||endpoint>2||virtualizer<0||virtualizer>1)
            throw new IllegalArgumentException("Invalid playback controls");
        String cacheKey=preloadKey(request);
        PreloadProbe pendingProbe=takePreloadProbe(cacheKey);
        try {
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
            boolean hardwareDolby=(Boolean)invoke(cls("tv.danmaku.ijk.media.player.IjkCodecHelper"),"isEac3MediaCodecSupport");
            bridge("startDspRoute",appliedEndpoint,virtualizer,hardwareDolby);dspActive=true;
            stage("player_create");
            player=construct("com.bilibili.ijk.player.IJKMediaPlayer");
            for(Object[] binding:new Object[][]{{"setOnPreparedCallback",1,"prepared"},{"setOnCompletionCallback",1,"completed"},{"setOnErrorCallback",2,"error"},
                    {"setOnSeekCompleteCallback",5,"seek_complete"},{"setOnBufferingUpdateCallback",2,"buffering"},{"setOnVideoSizeChangedCallback",3,"video_size"}})
                invoke(player,(String)binding[0],callback((Integer)binding[1],(String)binding[2],token));
            boolean audioOnly=request.getBoolean("audioOnly",false);
            invoke(player,"setAudioOnly",audioOnly);invoke(player,"setVolume",volume);speed(speed);
            if(preloads!=null)preloads.discardExcept(cacheKey);
            NativeMedia cached=preloads==null?null:preloads.peek(cacheKey);
            if(cached==null && preloads!=null)preloads.discard(cacheKey);
            DashSource[] tracks=null;
            if(cached==null && pendingProbe!=null){
                stage("dash_probe");tracks=pendingProbe.await();
                if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliPreload",tracks==null?"probe takeover missed":"probe takeover reused");
            }
            NativeMedia media=cached==null?media(request,tracks):cached;
            awaitingMediaInfo=media.nativeMetadata;
            stage("prepare");
            if(media.nativeMetadata) {
                ensurePreloader();awaitingPreload=token;
                String key=cacheKey==null?"foreground-"+token:cacheKey;
                prepareNativeMedia(key,media,cached!=null,token,request,SystemClock.elapsedRealtime()+20000);
                return;
            }
            configureOutput(media.hdr);
            if(cached!=null){
                awaitingPreload=token;
                if(preloads.take(cacheKey,ready->consumePreload(ready,token,request)))return;
                awaitingPreload=0;
            }
            useItem(nativeItem(media.asset,0),token,request);
        } catch(Exception | LinkageError error) {try{release();}catch(Exception | LinkageError cleanup){error.addSuppressed(cleanup);}throw error;}
        } finally {
            if(pendingProbe!=null){pendingProbe.future.cancel(true);preloadProbeWorker.remove((Runnable)pendingProbe.future);}
        }
    }
    private void consumePreload(NativeMedia media,long token,Bundle request) {
        if(token!=generation || player==null){try{invoke(media.item,"release");}catch(Exception error){preloadFailure(error);}return;}
        run(token,()->{
            item=media.item;
            if(media.nativeMetadata) {
                Object handle=invoke(item,"nativePtrOrNull$srcs_base_ijk_ijk_library_android");
                if(!(handle instanceof Long))throw new IOException("Missing prepared media handle");
                int kind=(Integer)bridge("classifyHdr",handle);
                if(kind<0 || kind>3)throw new IOException("Unknown prepared HDR metadata");
                configureOutput(kind!=0);awaitingMediaInfo=false;
                if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliMedia","detached HDR type="+kind);
            }
            useItem(item,token,request);
            if(com.example.piliplus.BuildConfig.DEBUG)android.util.Log.d("PiliPreload","consumed same native item");
        });
    }
    private void prepareNativeMedia(String key,NativeMedia media,boolean cached,long token,Bundle request,long deadline) throws Exception {
        if(token!=generation || player==null)return;
        if(preloads.isBroken())throw new IOException("Native metadata preloader unavailable");
        if(SystemClock.elapsedRealtime()>=deadline)throw new IOException("Native metadata preparation timeout");
        if(!cached) {
            // Other candidates retain their handles until REMOVED; do not allocate beyond the cache bound.
            if(preloads.size()>=3) {
                worker.postDelayed(()->run(token,()->prepareNativeMedia(key,media,false,token,request,deadline)),20);
                return;
            }
            media.item=nativeItem(media.asset,0);media.preloadTarget=1;
            if(!preloads.add(key,media))throw new IOException("Native metadata preload rejected");
        }
        if(!preloads.takeReady(key,ready->consumePreload(ready,token,request)))throw new IOException("Native metadata handoff unavailable");
    }
    private void configureOutput(boolean hdr) throws Exception {
        Bundle request=openRequest;Surface surface=outputSurface;
        boolean audioOnly=request.getBoolean("audioOnly",false);
            boolean directHdr=hdr && Build.VERSION.SDK_INT>=33;
            if(!audioOnly&&surface!=null&&!directHdr) {
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
            if(!audioOnly && directHdr) {
                hdrSurface=new OfficialHdrSurface();updateHdrSurface();invoke(player,"setSurface",hdrSurface.surface);
            }
            if(externalRenderer!=null && displayWidth>0 && displayHeight>0)invoke(externalRenderer,"setVideoSize",displayWidth,displayHeight);
            if(width>0 && height>0 && surface!=null)invoke(player,"setSurfaceSize",width,height);
            Bundle display=new Bundle();display.putString("event","display_path");display.putBoolean("directHdr",!audioOnly&&directHdr);emit(display);
    }
    private static OfficialStream streamInfo(Bundle info,String source) {
        return info==null?null:new OfficialStream(source,info.get("source"),info.get("id"),info.get("bandwidth"),info.get("backups"),info.get("codecs"));
    }
    void cancelResourceRefresh() { resourceRefresh.cancel(); }
    boolean resourceUpdate(Bundle data) {
        return data.getLong("load",-1)==load && resourceRefresh.complete(data.getLong("requestId",-1),data);
    }
    private void failResource() throws Exception {
        release();
        Bundle event=new Bundle();event.putString("event","resource_error");event.putString("error","resource_unavailable");emit(event);
    }
    private Object resourceListener(long token,Bundle original,AtomicInteger resourceUpdates) throws Exception {
        return Proxy.newProxyInstance(loader,new Class<?>[]{cls("com.bilibili.ijk.callback.OnResourceUpdateListener")},(proxy,method,args)->{
            switch(method.getName()) {
                case "toString":return "PiliPlusResourceUpdate";
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "onUpdate":break;
                default:throw new UnsupportedOperationException(method.getName());
            }
            if(token!=generation || !resourceRefresh.isOpen())return null;
            // Keep failures pending until the worker terminates this generation.
            resourceUpdates.incrementAndGet();
            try {
                Bundle next=resourceRefresh.await(20000,id->worker.post(()->run(token,()->{
                    Bundle event=new Bundle();event.putString("event","resource_refresh");event.putLong("requestId",id);emit(event);
                })));
                if(token!=generation || !resourceRefresh.isOpen())return null;
                if(next==null || !next.getBoolean("available"))throw new IOException("Resource refresh unavailable");
                Object old=invoke(args[0],"getCurrentAsset");
                Object replacement=updatedAsset(old,original,next);
                if(token!=generation || !resourceRefresh.isOpen())return null;
                worker.post(()->run(token,()->{
                    resourceUpdates.decrementAndGet();
                    for(String key:new String[]{"source","audio"})openRequest.putString(key,next.getString(key));
                    for(String key:new String[]{"sourceTrack","audioTrack"})openRequest.putBundle(key,next.getBundle(key));
                    if(original.getBoolean("isLive",false)) {
                        // A restarted live encoder may reset PTS; old queues then repeatedly starve video.
                        Bundle reopen=new Bundle(openRequest);reopen.putLong("position",0);
                        reopen.putBoolean("play",wantedPlaying);reopen.putFloat("speed",speed);reopen.putFloat("volume",volume);
                        open(reopen,outputSurface);
                    }
                    Bundle event=new Bundle();event.putString("event","resource_updated");event.putLong("requestId",next.getLong("requestId"));emit(event);
                }));
                return original.getBoolean("isLive",false)?null:replacement;
            } catch(Exception error) {
                worker.post(()->run(token,()->{
                    // Returning null alone tells the original DASH director to retry the old URLs.
                    failResource();
                }));
                return null;
            }
        });
    }
    private Object updatedAsset(Object old,Bundle original,Bundle next) throws Exception {
        OfficialStream source=streamInfo(next.getBundle("sourceTrack"),next.getString("source"));
        OfficialStream before=streamInfo(original.getBundle("sourceTrack"),original.getString("source"));
        if(source==null || before==null || source.id!=before.id)throw new IOException("Refresh changed source track");
        Object scheme=invoke(old,"getScheme"),options=invoke(old,"getOption");
        if(original.getBoolean("isLive",false)) {
            if(!old.getClass().getName().endsWith("$Normal") || !"LIVE".equals(scheme.toString()) || next.getString("audio")!=null)
                throw new IOException("Refresh changed live source kind");
            // Continuous FLV/HLS has no finite MP4 index to probe before reconnecting.
            return construct("com.bilibili.ijk.media.MediaAsset$Normal",source.source,source.backups,scheme,options,null);
        }
        String ua=original.getString("userAgent","PiliPlus"),referer=original.getString("referer","https://www.bilibili.com/");
        if(old.getClass().getName().endsWith("$Dash")) {
            OfficialStream audio=streamInfo(next.getBundle("audioTrack"),next.getString("audio"));
            OfficialStream prior=streamInfo(original.getBundle("audioTrack"),original.getString("audio"));
            if(audio==null || prior==null || audio.id!=prior.id)throw new IOException("Refresh changed audio track");
            if(useNativeMetadata(before,prior) && useNativeMetadata(source,audio)) {
                Object oldVideo=((List<?>)invoke(old,"getVideoStreamList")).get(0),oldAudio=((List<?>)invoke(old,"getAudioStreamList")).get(0);
                if(!invoke(oldVideo,"getCodecId").equals(source.videoCodec()) || !invoke(oldAudio,"getCodecId").equals(-1))throw new IOException("Refresh changed codec");
                return construct("com.bilibili.ijk.media.MediaAsset$Dash",audio.id,source.id,
                    Collections.singletonList(dashStream(source,0)),Collections.singletonList(dashStream(audio,1)),scheme,options,null);
            }
            DashSource[] tracks=DashSource.probePair(source.source,source.backups,audio.source,audio.backups,ua,referer);
            DashSource video=tracks[0],sound=tracks[1];
            Object oldVideo=((List<?>)invoke(old,"getVideoStreamList")).get(0),oldAudio=((List<?>)invoke(old,"getAudioStreamList")).get(0);
            Object newVideo=dashStream(video,0,source.id,source),newAudio=dashStream(sound,1,audio.id,audio);
            if(!invoke(oldVideo,"getCodecId").equals(invoke(newVideo,"getCodecId")) || !invoke(oldAudio,"getCodecId").equals(invoke(newAudio,"getCodecId")))
                throw new IOException("Refresh changed codec");
            return construct("com.bilibili.ijk.media.MediaAsset$Dash",audio.id,source.id,Collections.singletonList(newVideo),Collections.singletonList(newAudio),scheme,options,null);
        }
        if(!old.getClass().getName().endsWith("$Normal") || next.getString("audio")!=null)
            throw new IOException("Unsupported resource refresh");
        DashSource video=DashSource.probe(source.source,source.backups,ua,referer);
        return construct("com.bilibili.ijk.media.MediaAsset$Normal",video.url,source.alternativesFor(video.url),scheme,options,null);
    }
    private Object dashStream(DashSource source,int mediaType,int qn,OfficialStream track) throws Exception {
        Object codec=cls("com.bilibili.ijk.media.CodecID").getField(source.codec).get(null);
        return construct("com.bilibili.ijk.media.IJKDashStream",mediaType,(Integer)invoke(codec,"getId"),qn,track==null?0:track.bandwidth,source.url,track==null?Collections.emptyList():track.alternativesFor(source.url));
    }
    private void applyRoute() throws Exception {
        applyEndpoint(route.endpoint());
        describeRoute("route_changed");
    }
    private void applyEndpoint(int endpoint) throws Exception {
        if(endpoint!=appliedEndpoint && prepared) {
            bridge("updateDspEndpoint",endpoint);
            appliedEndpoint=endpoint;
        }
    }
    void testEndpoint(int endpoint) throws Exception {
        if(!com.example.piliplus.BuildConfig.DEBUG)throw new SecurityException("Debug only");
        applyEndpoint(endpoint);
    }
    private void describeRoute(String event) {Bundle data=new Bundle();data.putString("event",event);route.describe(data);emit(data);}
    private void heartbeat(long token) {
        worker.postDelayed(()->run(token,()->{
            route.refresh();if(token!=generation)return;
            JSONObject graph=new JSONObject((String)bridge("getGraphState")),dsp=new JSONObject((String)bridge("getDspState"));
            if(graph.getInt("errors")!=0||graph.getInt("filter_error")!=0)throw new IOException("Audio adaptation failed");
            Bundle b=new Bundle();b.putString("event","state");route.describe(b);
            b.putLong("position",(Long)invoke(player,"getCurrentPosition"));b.putLong("duration",(Long)invoke(player,"getDuration"));
            float nativeSpeed=(Float)invoke(player,"getSpeed");
            b.putBoolean("playing",(Boolean)invoke(player,"isPlaying"));b.putFloat("speed",speed==2f&&nativeSpeed==1.99f?2f:nativeSpeed);b.putFloat("volume",volume);
            b.putInt("width",displayWidth);b.putInt("height",displayHeight);
            Object monitor=invoke(player,"getPlayerMonitor");long buffered=(Long)invoke(monitor,"getPacketQueueCacheDurationMs",1);
            long duration=b.getLong("duration"),position=b.getLong("position");
            b.putInt("bufferedPercent",duration>0?(int)Math.max(0,Math.min(100,100.0*(position+Math.max(0,buffered))/duration)):0);
            b.putString("audioCodec",dsp.getString("audio_codec"));b.putBoolean("dolbyControlsApplicable",dsp.getInt("configured_decoders")>0);
            b.putLong("nativeDolbyEndpoint",dsp.getInt("endpoint"));b.putInt("appliedDolbyEndpoint",dsp.getInt("applied_endpoint"));b.putInt("dolbyEndpointUpdates",dsp.getInt("endpoint_updates"));b.putInt("sampleRate",graph.getInt("sample_rate"));
            b.putString("channelLayout",Integer.toString(graph.getInt("channels")));
            b.putString("loudnessMode",loudnessMode);b.putString("loudnessState",graph.getBoolean("loudness_bypassed_for_codec")?"dolby_codec_bypass":loudness.reason);b.putString("requestedAudioFilter",loudness.filter);
            b.putDouble("requestedLoudnessGainDb",loudness.appInGainDb);if(Double.isFinite(loudness.target))b.putDouble("loudnessTarget",loudness.target);
            b.putString("nativeEngine","audio_engine");b.putLong("adaptedFrames",graph.getLong("filtered_frames")+graph.getLong("converted_frames"));
            emit(b);heartbeat(token);
        }),250);
    }
    void surface(Surface surface) throws Exception {
        outputSurface=surface;
        if(awaitingMediaInfo)return;
        if(prepared && player!=null && externalRenderer==null && hdrSurface==null && surface!=null && !openRequest.getBoolean("audioOnly",false)) {
            configureOutput(false);return;
        }
        if(hdrSurface!=null) {updateHdrSurface();return;}
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
        if(hdrSurface!=null){updateHdrSurface();return;}
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
        if(openRequest.getBoolean("isLive",false))throw new IllegalStateException("Live playback cannot seek");
        invoke(player,"seekTo",true,position,5000L);
    }
    void speed(float value) throws Exception {
        if(!Float.isFinite(value)||value<.25f||value>4)throw new IllegalArgumentException("Invalid speed");
        // Official 9.14 setPlaySpeed uses 1.99 internally while displaying and saving 2x.
        if(player!=null)invoke(player,"setSpeed",value==2f?1.99f:value);speed=value;
    }
    void volume(float value) throws Exception {
        if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid volume");
        if(player!=null)invoke(player,"setVolume",value);volume=value;
    }
    void release() throws Exception {
        cancelResourceRefresh();
        ++generation;
        if(awaitingPreload!=0 && preloads!=null)preloads.discardExcept(null);
        awaitingPreload=0;prepared=false;wantedPlaying=false;awaitingMediaInfo=false;
        if(route!=null){route.stop();route=null;}
        if(player!=null){invoke(player,"release");player=null;}
        if(item!=null){invoke(item,"release");item=null;}
        if(hdrSurface!=null){hdrSurface.close();hdrSurface=null;}
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
