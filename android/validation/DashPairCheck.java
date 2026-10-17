package com.example.piliplus.official;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DashPairCheck {
    static long probes() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t->t.isAlive() && t.getName().equals("official-dash-probe")).count();
    }
    static void joined() throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(probes()!=0 && System.nanoTime()<deadline)Thread.sleep(10);
        assert probes()==0 : "Probe worker leaked";
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        byte[] video=Files.readAllBytes(root.resolve("fragmented-dash-video.mp4"));
        byte[] audio=Files.readAllBytes(root.resolve("fragmented-dash-audio.m4a"));
        byte[] invalid=video.clone();ByteBuffer.wrap(invalid).putInt(0,-1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        AtomicReference<CountDownLatch> barrier=new AtomicReference<>(new CountDownLatch(2));
        AtomicInteger missing=new AtomicInteger(),finished=new AtomicInteger();
        server.createContext("/",e->{try {
            String path=e.getRequestURI().getPath();
            assert "test-UA".equals(e.getRequestHeaders().getFirst("User-Agent"));
            assert "identity".equals(e.getRequestHeaders().getFirst("Accept-Encoding"));
            if(path.equals("/missing")){missing.incrementAndGet();e.sendResponseHeaders(503,-1);return;}
            if(path.startsWith("/together")) {
                barrier.get().countDown();
                if(!barrier.get().await(1500,TimeUnit.MILLISECONDS)){e.sendResponseHeaders(503,-1);return;}
            }
            byte[] body=path.endsWith("audio")?audio:path.equals("/invalid")?invalid:video;
            e.getResponseHeaders().set("Content-Range","bytes 0-"+(body.length-1)+"/"+body.length);
            e.sendResponseHeaders(206,body.length);
            if(path.startsWith("/blocked")) {
                barrier.get().countDown();Thread.sleep(9000);
            } else if(path.startsWith("/interrupt")) {
                barrier.get().countDown();
                // An incomplete box keeps the reader active until interrupted.
                for(int i=0;i<3;i++){e.getResponseBody().write(body[i]);e.getResponseBody().flush();Thread.sleep(200);}
            } else {
                if(path.startsWith("/slow"))Thread.sleep(400);
                e.getResponseBody().write(body);finished.incrementAndGet();
            }
        }catch(IOException closed){}catch(InterruptedException stop){Thread.currentThread().interrupt();}finally{e.close();}});
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            // The old sequential path cannot satisfy the two-request server barrier.
            try {DashSource.probe(base+"/together-video","test-UA","");throw new AssertionError("Serial path unexpectedly passed");}
            catch(IOException expected){}
            barrier.set(new CountDownLatch(2));
            DashSource[] pair=DashSource.probePair(base+"/together-video",List.of(),base+"/together-audio",List.of(),"test-UA","");
            assert pair[0].codec.equals("H264") && pair[1].codec.equals("AAC");joined();
            pair=DashSource.probePair(base+"/missing",List.of(base+"/slow-video"),base+"/missing",List.of(base+"/audio"),"test-UA","");
            assert missing.get()==2 && pair[0].url.endsWith("slow-video") && pair[1].url.endsWith("audio");joined();
            int before=finished.get();
            try {DashSource.probePair(base+"/slow-video",List.of(),base+"/invalid",List.of(),"test-UA","");throw new AssertionError("Malformed track accepted");}
            catch(IllegalArgumentException expected){}
            assert finished.get()==before+2 : "Returned before sibling finished";joined();
            pair=DashSource.probePair(root.resolve("fragmented-dash-video.mp4").toAbsolutePath().toString(),List.of(),
                root.resolve("fragmented-dash-audio.m4a").toAbsolutePath().toString(),List.of(),"test-UA","");
            assert pair[0].codec.equals("H264") && pair[1].codec.equals("AAC");joined();
            for(String path:List.of("/interrupt","/blocked")) {
                barrier.set(new CountDownLatch(2));AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
                Thread caller=new Thread(()->{try {
                    DashSource.probePair(base+path+"-video",List.of(),base+path+"-audio",List.of(),"test-UA","");
                    failure.set(new AssertionError("Interrupted pair completed"));
                }catch(Throwable e){failure.set(e);interrupted.set(Thread.currentThread().isInterrupted());}});
                caller.start();assert barrier.get().await(3,TimeUnit.SECONDS);caller.interrupt();caller.join(2000);
                assert !caller.isAlive() && failure.get() instanceof InterruptedIOException && interrupted.get();joined();
            }
            System.out.println("Serial barrier rejected; parallel H264/AAC order, independent fallback, invalid sibling, local files, interruption and worker cleanup passed");
        } finally {server.stop(0);workers.shutdownNow();}
    }
}
