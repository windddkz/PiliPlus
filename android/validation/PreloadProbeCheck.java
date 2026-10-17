package com.example.piliplus.official;

import java.io.InterruptedIOException;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the actual runtime's private task ownership without opening Android or native output. */
public final class PreloadProbeCheck {
    static Class<?> probeType;
    static Field futureField;
    static Method await;
    static Object probe(Future<DashSource[]> future) throws Exception {
        Constructor<?> constructor=probeType.getDeclaredConstructor(long.class);constructor.setAccessible(true);
        Object probe=constructor.newInstance(0L);futureField.set(probe,future);return probe;
    }
    static Object result(Object probe) throws Exception {
        try {return await.invoke(probe);}
        catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }
    static void check(boolean value) {if(!value)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        probeType=Class.forName("com.example.piliplus.official.OfficialRuntime$PreloadProbe");
        futureField=probeType.getDeclaredField("future");futureField.setAccessible(true);
        await=probeType.getDeclaredMethod("await");await.setAccessible(true);
        DashSource[] tracks=new DashSource[0];
        check(result(probe(CompletableFuture.completedFuture(tracks)))==tracks);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<DashSource[]> finishing=worker.submit(()->{Thread.sleep(40);return tracks;});
            check(result(probe(finishing))==tracks);
            CompletableFuture<DashSource[]> failed=new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("invalid index"));
            check(result(probe(failed))==null);
            CompletableFuture<DashSource[]> cancelled=new CompletableFuture<>();cancelled.cancel(true);
            check(result(probe(cancelled))==null);
            CountDownLatch started=new CountDownLatch(1),stopped=new CountDownLatch(1);
            Future<DashSource[]> slow=worker.submit(()->{
                started.countDown();try {Thread.sleep(10000);return tracks;}finally{stopped.countDown();}
            });
            check(started.await(1,TimeUnit.SECONDS));long begin=System.nanoTime();
            check(result(probe(slow))==null);
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
            check(elapsed>=200 && elapsed<1500 && slow.isCancelled() && stopped.await(1,TimeUnit.SECONDS));
            CompletableFuture<DashSource[]> pending=new CompletableFuture<>();
            AtomicBoolean preserved=new AtomicBoolean();
            Thread caller=new Thread(()->{
                try {Thread.currentThread().interrupt();result(probe(pending));throw new AssertionError();}
                catch(InterruptedIOException expected){preserved.set(Thread.currentThread().isInterrupted());}
                catch(Exception error){throw new RuntimeException(error);}
            });
            caller.start();caller.join(1000);check(!caller.isAlive() && preserved.get() && pending.isCancelled());
        } finally {worker.shutdownNow();check(worker.awaitTermination(2,TimeUnit.SECONDS));}

        Class<?> runtimeType=Class.forName("com.example.piliplus.official.OfficialRuntime");
        Constructor<?> constructor=runtimeType.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object runtime=constructor.newInstance(new Object[]{null,null,null});
        Field mapField=runtimeType.getDeclaredField("preloadProbes");mapField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)mapField.get(runtime);
        Field poolField=runtimeType.getDeclaredField("preloadProbeWorker");poolField.setAccessible(true);
        ThreadPoolExecutor pool=(ThreadPoolExecutor)poolField.get(runtime);
        Method take=runtimeType.getDeclaredMethod("takePreloadProbe",String.class);take.setAccessible(true);
        try {
            FutureTask<DashSource[]> selected=new FutureTask<>(()->tracks),other=new FutureTask<>(()->tracks);
            Object chosen=probe(selected);map.put("exact",chosen);map.put("other",probe(other));
            check(take.invoke(runtime,"exact")==chosen);
            check(!selected.isCancelled() && other.isCancelled() && map.isEmpty());
            check(take.invoke(runtime,"exact")==null);
            selected.run();check(result(chosen)==tracks);
            FutureTask<DashSource[]> mismatch=new FutureTask<>(()->tracks);map.put("exact",probe(mismatch));
            check(take.invoke(runtime,"different")==null && mismatch.isCancelled() && map.isEmpty());
            FutureTask<DashSource[]> noToken=new FutureTask<>(()->tracks);map.put("exact",probe(noToken));
            check(take.invoke(runtime,new Object[]{null})==null && noToken.isCancelled() && map.isEmpty());
            CountDownLatch active=new CountDownLatch(1),retired=new CountDownLatch(1);
            Future<DashSource[]> blocking=pool.submit(()->{
                active.countDown();try {Thread.sleep(10000);return tracks;}finally{retired.countDown();}
            });
            check(active.await(1,TimeUnit.SECONDS));
            Future<DashSource[]> queued=pool.submit(()->tracks);
            Future<DashSource[]> unwanted=pool.submit(()->tracks);
            map.put("blocking",probe(blocking));map.put("selected",probe(queued));map.put("unwanted",probe(unwanted));
            Object transferred=take.invoke(runtime,"selected");
            check(result(transferred)==tracks && retired.await(1,TimeUnit.SECONDS));
            check(blocking.isCancelled() && unwanted.isCancelled() && pool.getQueue().isEmpty());
        } finally {pool.shutdownNow();check(pool.awaitTermination(2,TimeUnit.SECONDS));}
        System.out.println("Actual probe task: completed/in-flight reuse, failure/cancel/timeout, interrupt preservation, exact identity, single consumption and unrelated cancellation passed");
    }
}
