package com.example.piliplus.official;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

final class DashSource {
    final String url, codec;
    final long size;
    private DashSource(String url, String codec, long size) { this.url = url; this.codec = codec; this.size = size; }

    static DashSource probe(String source, String userAgent, String referer) throws Exception {
        final int limit = 2 * 1024 * 1024;
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            long length;
            if (source.startsWith("/")) {
                File file = new File(source); length = file.length(); input = new FileInputStream(file);
            } else {
                URL url = new URL(source);
                if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https"))
                    throw new IllegalArgumentException("Unsupported DASH scheme");
                connection = (HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                connection.setRequestProperty("Range", "bytes=0-" + (limit - 1));
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", userAgent); connection.setRequestProperty("Referer", referer);
                int code = connection.getResponseCode();
                if (code == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    if (range == null || !range.matches("bytes 0-[0-9]+/[0-9]+")) throw new IOException("Invalid range response");
                    length = Long.parseLong(range.substring(range.indexOf('/') + 1));
                } else if (code == 200) length = connection.getContentLengthLong();
                else throw new IOException("DASH probe failed");
                input = connection.getInputStream();
            }
            if (length <= 0) throw new IOException("Unknown DASH length");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384]; int count;
            while (bytes.size() < limit && (count = input.read(buffer, 0, Math.min(buffer.length, limit - bytes.size()))) != -1)
                bytes.write(buffer, 0, count);
            String codec = FragmentIndex.inspect(bytes.toByteArray(), length);
            return new DashSource(source, codec, length);
        } finally {
            if (input != null) input.close();
            if (connection != null) connection.disconnect();
        }
    }
}
