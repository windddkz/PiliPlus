package com.example.piliplus.official;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;

public final class ProbeEarlyReadCheck {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        assert data.length > 8192;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(workers);
        server.createContext("/video", e -> {
            try {
                e.getResponseHeaders().set(
                        "Content-Range", "bytes 0-" + (data.length - 1) + "/" + data.length);
                e.sendResponseHeaders(206, data.length);
                e.getResponseBody().write(data, 0, 8192);
                e.getResponseBody().flush();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return;
                }
                e.getResponseBody().write(data, 8192, data.length - 8192);
            } catch (java.io.IOException closed) {
            } finally {
                e.close();
            }
        });
        server.start();
        try {
            long start = System.nanoTime();
            var result = DashSource.probe(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/video", "test", "");
            long ms = (System.nanoTime() - start) / 1000000;
            assert result.codec.equals("H264");
            System.out.println("probe elapsed_ms=" + ms);
            assert ms < 1000 : "Probe waited for media body after complete index/codec";
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
