package com.example.piliplus.official;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;

final class DashSource {
    final String url, codec;
    final long size;
    final boolean hdr;
    private DashSource(String url, FragmentIndex.Info info, long size) {
        this.url = url;
        this.codec = info.codec;
        this.hdr = info.hdr;
        this.size = size;
    }

    static DashSource probe(String source, String userAgent, String referer) throws Exception {
        return probe(source, Collections.emptyList(), userAgent, referer);
    }

    static DashSource[] probePair(String video, List<String> videoBackups, String audio,
            List<String> audioBackups, String userAgent, String referer) throws Exception {
        ExecutorService workers =
                Executors.newFixedThreadPool(2, r -> new Thread(r, "official-dash-probe"));
        try {
            // Finish both bounded probes before exposing an asset or starting another open.
            List<Future<DashSource>> results = workers.invokeAll(
                    Arrays.asList(()
                                          -> probe(video, videoBackups, userAgent, referer),
                            () -> probe(audio, audioBackups, userAgent, referer)));
            return new DashSource[] {results.get(0).get(), results.get(1).get()};
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception)
                throw (Exception) cause;
            if (cause instanceof Error)
                throw (Error) cause;
            throw new IOException("DASH probe failed", cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("DASH probes interrupted");
        } finally {
            workers.shutdownNow();
        }
    }

    static DashSource probe(String source, List<String> alternatives, String userAgent,
            String referer) throws Exception {
        if (source.startsWith("/") && !alternatives.isEmpty())
            throw new IllegalArgumentException("Local DASH cannot use network alternatives");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        unique.add(source);
        unique.addAll(alternatives);
        List<String> candidates = new ArrayList<>(unique);
        long deadline = System.nanoTime() + 8000000000L;
        IOException failure = null;
        for (int i = 0; i < candidates.size(); i++) {
            int remaining = remainingMillis(deadline);
            long attempt =
                    System.nanoTime() + Math.max(1, remaining / (candidates.size() - i)) * 1000000L;
            try {
                return probeOne(candidates.get(i), userAgent, referer, Math.min(deadline, attempt));
            } catch (IOException error) {
                if (Thread.currentThread().isInterrupted())
                    throw error;
                failure = error;
            }
        }
        throw failure;
    }

    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("DASH probe interrupted");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            throw new SocketTimeoutException("DASH probe budget exhausted");
        return (int) Math.max(1, remaining / 1000000L);
    }

    private static DashSource probeOne(
            String source, String userAgent, String referer, long deadline) throws Exception {
        final int limit = 2 * 1024 * 1024;
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            long length;
            if (source.startsWith("/")) {
                File file = new File(source);
                length = file.length();
                input = new FileInputStream(file);
            } else {
                URL url = new URL(source);
                if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https"))
                    throw new IllegalArgumentException("Unsupported DASH scheme");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(remainingMillis(deadline));
                connection.setReadTimeout(remainingMillis(deadline));
                connection.setRequestProperty("Range", "bytes=0-" + (limit - 1));
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Referer", referer);
                int code = connection.getResponseCode();
                if (code == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    if (range == null || !range.matches("bytes 0-[0-9]+/[0-9]+"))
                        throw new IOException("Invalid range response");
                    try {
                        int slash = range.indexOf('/');
                        length = Long.parseLong(range.substring(slash + 1));
                        long end = Long.parseLong(range.substring(8, slash));
                        long declared = connection.getContentLengthLong();
                        if (end >= length || end >= limit || (declared >= 0 && declared != end + 1))
                            throw new IOException("Inconsistent range response");
                    } catch (NumberFormatException error) {
                        throw new IOException("Invalid range response");
                    }
                } else if (code == 200)
                    length = connection.getContentLengthLong();
                else
                    throw new IOException("DASH probe failed");
                input = connection.getInputStream();
            }
            if (length <= 0)
                throw new IOException("Unknown DASH length");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while (bytes.size() < limit) {
                int remaining = remainingMillis(deadline);
                if (connection != null)
                    connection.setReadTimeout(remaining);
                count = input.read(buffer, 0, Math.min(buffer.length, limit - bytes.size()));
                if (count == -1)
                    break;
                bytes.write(buffer, 0, count);
                FragmentIndex.Info ready =
                        FragmentIndex.inspectAvailable(bytes.toByteArray(), length);
                if (ready != null)
                    return new DashSource(source, ready, length);
            }
            if (bytes.size() < Math.min(length, limit))
                throw new EOFException("Truncated DASH prefix");
            FragmentIndex.Info info = FragmentIndex.inspectInfo(bytes.toByteArray(), length);
            return new DashSource(source, info, length);
        } finally {
            try {
                if (connection != null)
                    connection.disconnect();
            } finally {
                if (input != null)
                    input.close();
            }
        }
    }
}
