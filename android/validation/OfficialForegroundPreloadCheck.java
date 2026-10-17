package com.example.piliplus.official;

import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises actual runtime ownership with native calls replaced by explicit acknowledgements. */
public final class OfficialForegroundPreloadCheck {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static Field field(String name)throws Exception{Field f=OfficialRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static final Class<?> MEDIA;
    static final Constructor<?> MEDIA_CTOR;
    static final Field MEDIA_ITEM;
    static final Method PREPARE;
    static {
        try {
            MEDIA=Class.forName(OfficialRuntime.class.getName()+"$NativeMedia");
            MEDIA_CTOR=MEDIA.getDeclaredConstructor(Object.class,boolean.class,boolean.class);MEDIA_CTOR.setAccessible(true);
            MEDIA_ITEM=MEDIA.getDeclaredField("item");MEDIA_ITEM.setAccessible(true);
            PREPARE=OfficialRuntime.class.getDeclaredMethod("prepareNativeMedia",String.class,MEDIA,boolean.class,long.class,Bundle.class,long.class);PREPARE.setAccessible(true);
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    public static final class Handle {int released;public void release(){check(released++==0);}}
    static Object media(Handle handle)throws Exception{Object value=MEDIA_CTOR.newInstance(null,false,true);MEDIA_ITEM.set(value,handle);return value;}
    static final class Queue implements OfficialPreloadCache.Backend<Object> {
        final OfficialPreloadCache<Object> cache;
        final Map<Object,long[]> added=new IdentityHashMap<>(),moved=new IdentityHashMap<>();
        final List<Runnable> timers=new ArrayList<>();
        int failures;
        Queue(int capacity){cache=new OfficialPreloadCache<>(this,(delay,action)->timers.add(action),SystemClock::elapsedRealtime,capacity);}
        public void add(long request,long task,Object item){check(added.put(item,new long[]{request,task})==null);}
        public void detach(long request,long task,Object item){check(moved.put(item,new long[]{request,task})==null);}
        public void interrupt(Object item)throws Exception{check(((Handle)MEDIA_ITEM.get(item)).released==0);}
        public void remove(long request,long task,Object item){check(moved.put(item,new long[]{request,task})==null);}
        public void release(Object item)throws Exception{((Handle)MEDIA_ITEM.get(item)).release();}
        public void failed(Exception error){failures++;}
        void ack(Object item,String type){long[] call=moved.get(item);cache.event(call[0],call[1],type,0);}
        void ready(Object item){cache.event(0,added.get(item)[1],"READY",0);}
    }
    static OfficialRuntime runtime(Queue background,Queue foreground,List<String> events)throws Exception{
        OfficialRuntime runtime=new OfficialRuntime(null,new Handler(Looper.myLooper()),event->events.add(event.getString("event")));
        field("preloads").set(runtime,background.cache);field("preloader").set(runtime,background);
        field("foregroundPreloads").set(runtime,foreground.cache);field("foregroundPreloader").set(runtime,foreground);
        field("player").set(runtime,new Handle());field("generation").setLong(runtime,7);field("awaitingPreload").setLong(runtime,7);
        return runtime;
    }
    static void prepare(OfficialRuntime runtime,Object media)throws Exception{
        PREPARE.invoke(runtime,"target",media,false,7L,new Bundle(),SystemClock.elapsedRealtime()+20000);
    }
    static void backgroundBusyAndLateDetach()throws Exception{
        Queue background=new Queue(3),foreground=new Queue(1);List<String> events=new ArrayList<>();
        List<Object> old=new ArrayList<>();
        for(int i=0;i<3;i++){Object value=media(new Handle());old.add(value);check(background.cache.add("bg"+i,value));}
        background.cache.discardExcept(null);
        OfficialRuntime runtime=runtime(background,foreground,events);Handle handle=new Handle();Object target=media(handle);
        // This is also the detached-without-metadata handoff: runtime owns it until enqueue.
        field("item").set(runtime,handle);prepare(runtime,target);
        check(background.cache.size()==3 && foreground.cache.size()==1 && field("item").get(runtime)==null);
        check(field("awaitingPreloader").get(runtime)==foreground && foreground.added.containsKey(target));
        long[] add=foreground.added.get(target),bgAdd=background.added.get(old.get(0));
        check(Arrays.equals(add,bgAdd)); // Native serials collide across the two queues.
        background.ready(old.get(0));check(foreground.moved.isEmpty());
        Method failure=OfficialRuntime.class.getDeclaredMethod("preloadFailure",Object.class,Exception.class);failure.setAccessible(true);
        failure.invoke(runtime,background,new Exception("background failed"));check(events.isEmpty());
        failure.invoke(runtime,foreground,new Exception("foreground failed"));check(events.equals(List.of("error")));events.clear();
        foreground.ready(target);check(foreground.moved.containsKey(target));
        runtime.release();check(handle.released==0);
        foreground.ack(target,"DETACHED");foreground.ack(target,"DETACHED");
        check(handle.released==1 && foreground.cache.size()==0 && background.cache.size()==3);
        for(Object value:old)background.ack(value,"REMOVED");
        check(background.cache.size()==0 && events.isEmpty());
    }
    static void pendingSlotCancel()throws Exception{
        Queue background=new Queue(3),foreground=new Queue(1);List<String> events=new ArrayList<>();
        Handle cancelled=new Handle();Object pending=media(cancelled);check(foreground.cache.add("old",pending));foreground.cache.discardExcept(null);
        Handle extra=new Handle();check(!foreground.cache.add("excess",media(extra)));check(extra.released==1 && cancelled.released==0 && foreground.cache.size()==1);
        OfficialRuntime runtime=runtime(background,foreground,events);Handle held=new Handle();Object target=media(held);field("item").set(runtime,held);
        prepare(runtime,target);check(field("item").get(runtime)==held && !foreground.added.containsKey(target));
        runtime.release();check(held.released==1 && cancelled.released==0);
        foreground.ready(pending);check(cancelled.released==0);
        foreground.ack(pending,"REMOVED");check(cancelled.released==1);
        new Handler(Looper.myLooper()).postDelayed(()->{
            check(!foreground.added.containsKey(target) && events.isEmpty() && held.released==1);
            Looper.myLooper().quit();
        },50);
        Looper.loop();
    }
    static void brokenClose()throws Exception{
        for(boolean foregroundBroken:new boolean[]{false,true}){
            Queue background=new Queue(3),foreground=new Queue(1),broken=foregroundBroken?foreground:background;
            Object value=media(new Handle());broken.cache.add("bad",value);long[] add=broken.added.get(value);
            broken.cache.event(add[0],add[1],"ADDED",-22);check(broken.cache.isBroken());
            OfficialRuntime runtime=runtime(background,foreground,new ArrayList<>());
            try{runtime.close();throw new AssertionError("Broken queue acknowledged close");}
            catch(java.io.IOException expected){check(expected.getMessage().equals("Preload stop unconfirmed"));}
        }
    }
    public static void main(String[] args)throws Exception{
        Looper.prepare();backgroundBusyAndLateDetach();brokenClose();pendingSlotCancel();
        System.out.println("PASS actual runtime foreground isolation, colliding serials, ownership transfer, late detach, cancellation while slot occupied, bounded capacity and both broken-close barriers");
    }
}
