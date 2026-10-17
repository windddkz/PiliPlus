package com.example.piliplus.official;

import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.*;

/** Checks the actual runtime's queue without creating a decoder or network request. */
public final class OfficialPreloadQueueCheck {
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    static Field field(String name) throws Exception {
        Field f=OfficialRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    public static void main(String[] args) throws Exception {
        List<String> calls=new ArrayList<>();
        OfficialPreloadCache<String> cache=new OfficialPreloadCache<>(new OfficialPreloadCache.Backend<String>() {
            public void add(long r,long t,String i){calls.add("add:"+i);}
            public void detach(long r,long t,String i){throw new AssertionError();}
            public void interrupt(String i){calls.add("interrupt:"+i);}
            public void remove(long r,long t,String i){calls.add("remove:"+i);}
            public void release(String i){calls.add("release:"+i);}
            public void failed(Exception e){throw new AssertionError(e);}
        },(delay,action)->{},SystemClock::elapsedRealtime);
        OfficialRuntime runtime=new OfficialRuntime(null,null,null);
        field("preloads").set(runtime,cache);
        @SuppressWarnings("unchecked") Map<String,Bundle> queue=(Map<String,Bundle>)field("pendingPreloads").get(runtime);
        Method drain=OfficialRuntime.class.getDeclaredMethod("drainPreloads");drain.setAccessible(true);
        Method take=OfficialRuntime.class.getDeclaredMethod("takePreloadProbe",String.class);take.setAccessible(true);
        for(String key:new String[]{"a","b","c"})check(cache.add(key,key));
        Bundle request=new Bundle();request.putLong("preloadQueuedAt",SystemClock.elapsedRealtime());
        queue.put("next",request);drain.invoke(runtime);
        check(cache.size()==3 && queue.size()==1 && calls.equals(List.of("add:a","add:b","add:c","interrupt:a","remove:a")));
        drain.invoke(runtime);check(calls.size()==5 && queue.size()==1);
        cache.event(999,1,"REMOVED",0);drain.invoke(runtime);check(cache.size()==3 && calls.size()==5);
        take.invoke(runtime,new Object[]{null});check(queue.isEmpty());
        drain.invoke(runtime);check(calls.size()==5);
        Bundle expired=new Bundle();expired.putLong("preloadQueuedAt",SystemClock.elapsedRealtime()-30001);
        queue.put("expired",expired);drain.invoke(runtime);check(queue.isEmpty() && calls.size()==5);
        queue.put("b",request);drain.invoke(runtime);check(queue.isEmpty() && calls.size()==5);
        field("player").set(runtime,new Object());queue.put("next",request);
        drain.invoke(runtime);check(queue.isEmpty() && calls.size()==5);
        System.out.println("Actual queue: capacity, no early release/allocation, wrong ACK, open cancellation, expiry, duplicate and foreground guards passed");
    }
}
