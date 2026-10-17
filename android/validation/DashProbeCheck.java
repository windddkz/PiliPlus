package com.example.piliplus.official;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DashProbeCheck {
 static void response(HttpExchange e,byte[] body,String range)throws IOException {
  e.getResponseHeaders().set("Content-Range",range);e.sendResponseHeaders(206,body.length);
  e.getResponseBody().write(body);
 }
 public static void main(String[] args)throws Exception {
  byte[] data=Files.readAllBytes(Path.of(args[0]));
  byte[] invalid=data.clone();ByteBuffer.wrap(invalid).putInt(0,-1);
  byte[] encrypted=data.clone();
  int entry=-1;for(int i=0;i<data.length-4;i++)if(data[i]=='a'&&data[i+1]=='v'&&data[i+2]=='c'&&data[i+3]=='1'){entry=i;break;}
  assert entry>=0;System.arraycopy(new byte[]{'e','n','c','v'},0,encrypted,entry,4);
  int first=0;while(FragmentIndex.inspectAvailable(Arrays.copyOf(data,first),data.length)==null)first++;
  assert first<8192;
  assert FragmentIndex.inspectInfo(Arrays.copyOf(data,first),data.length).codec.equals("H264");
  try {FragmentIndex.inspectAvailable(invalid,data.length);throw new AssertionError("Invalid box accepted");}catch(IllegalArgumentException expected){}
  try {FragmentIndex.inspectAvailable(encrypted,data.length);throw new AssertionError("Encrypted entry accepted");}catch(IllegalArgumentException expected){}
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  var workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(workers);
  AtomicInteger goodRequests=new AtomicInteger(),primaryRequests=new AtomicInteger();
  server.createContext("/",e->{try {
   String path=e.getRequestURI().getPath();
   switch(path){
    case "/missing":primaryRequests.incrementAndGet();e.sendResponseHeaders(503,-1);break;
    case "/bad-range":response(e,data,"bytes 0-999999999/"+data.length);break;
    case "/invalid":response(e,invalid,"bytes 0-"+(data.length-1)+"/"+data.length);break;
    case "/encrypted":response(e,encrypted,"bytes 0-"+(data.length-1)+"/"+data.length);break;
    case "/stall":try{Thread.sleep(5000);}catch(InterruptedException stop){return;}e.sendResponseHeaders(503,-1);break;
    case "/trickle":
     e.getResponseHeaders().set("Content-Range","bytes 0-"+(data.length-1)+"/"+data.length);e.sendResponseHeaders(206,data.length);
     for(byte b:data){e.getResponseBody().write(b);e.getResponseBody().flush();try{Thread.sleep(100);}catch(InterruptedException stop){return;}}break;
    case "/good":goodRequests.incrementAndGet();assert "test-UA".equals(e.getRequestHeaders().getFirst("User-Agent"));response(e,data,"bytes 0-"+(data.length-1)+"/"+data.length);break;
    default:e.sendResponseHeaders(404,-1);
   }
  }catch(IOException closed){}finally{e.close();}});server.start();
  String base="http://127.0.0.1:"+server.getAddress().getPort();
  try {
   for(String path:new String[]{"/missing","/bad-range"}) {
    DashSource found=DashSource.probe(base+path,Arrays.asList(base+path,base+"/good"),"test-UA","");
    assert found.url.equals(base+"/good")&&found.codec.equals("H264");
   }
   assert primaryRequests.get()==1;
   for(String path:new String[]{"/invalid","/encrypted"}) {
    int before=goodRequests.get();
    try{DashSource.probe(base+path,List.of(base+"/good"),"test-UA","");throw new AssertionError("Invalid source rescued without rejection");}
    catch(IllegalArgumentException expected){}assert goodRequests.get()==before;
   }
   long begin=System.nanoTime();
   assert DashSource.probe(base+"/stall",List.of(base+"/good"),"test-UA","").url.endsWith("/good");
   long stallMs=(System.nanoTime()-begin)/1000000;assert stallMs>=3000&&stallMs<7000:stallMs;
   begin=System.nanoTime();
   try{DashSource.probe(base+"/trickle","test-UA","");throw new AssertionError("Unbounded trickle succeeded");}
   catch(SocketTimeoutException expected){}
   long trickleMs=(System.nanoTime()-begin)/1000000;assert trickleMs>=7000&&trickleMs<10000:trickleMs;
   System.out.println("Incremental prefix="+first+" bytes; HTTP fallback, range checks, corrupt/encrypted rejection; stall="+stallMs+"ms, trickle="+trickleMs+"ms passed");
  }finally{server.stop(0);workers.shutdownNow();}
 }
}
