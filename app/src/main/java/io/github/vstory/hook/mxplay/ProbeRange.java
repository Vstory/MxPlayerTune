package io.github.vstory.hook.mxplay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 探针专用的极小 HTTP 服务：<b>只把磁盘上一个本地文件按 {@code Range} 供出去</b>
 * （仅监听 {@code 127.0.0.1}）。
 *
 * <p><b>为什么必须有它</b>（{@link Probe} 的 P3）：要回答「宿主那套 native 抽帧引擎
 * （{@code MediaReader.native_create(url, …)}）能不能吃 http」，就必须给它一条 http URL。
 * 而「真机上唯一现成的 http 源」是远端 WebDAV —— 那会<b>产生外网流量</b>（需授权）。
 * 用本类把<b>本地文件</b>包成 http，就能在<b>零外网流量</b>的前提下把这个决定性问题问出来。
 *
 * <p>它不是通用文件服务器，也<b>不进入生产路径</b>：只在探针运行期间存在（探针是 debug 专属），
 * 只认 {@code GET/HEAD}，只服务构造时给的那一个文件，路径随便（native 只要 URL 里有
 * 像样的扩展名即可，这里照样会带上 {@code .mp4}）。
 *
 * <p>支持的能力刻意与 CloudDrive 对齐（这样结论才可迁移到真实场景）：
 * {@code Range} → {@code 206 + Content-Range + Accept-Ranges}；不带 {@code Range} → {@code 200 + Content-Length}；
 * 越界 → {@code 416}。另记账 {@link #requests()} / {@link #ranged()} / {@link #firstOffset()}
 * ——「native 到底有没有用 Range 读」本身就是 B 方案值不值得做的一条关键证据。
 */
final class ProbeRange {

    /** 单次响应的分片上限（native 要多大给多大，这里只防一次写出巨量数据）。 */
    private static final int MAX_CHUNK = 4 << 20;

    private final File file;
    private final long size;
    private final ServerSocket server;
    private final int port;
    private final ExecutorService pool;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger ranged = new AtomicInteger();
    private final AtomicLong firstOffset = new AtomicLong(-1);
    private final String suffix;

    ProbeRange(File file, String suffix) throws IOException {
        this.file = file;
        this.size = file.length();
        this.suffix = suffix == null || suffix.isEmpty() ? "" : suffix;
        // 探针只存在几十秒，端口只用临时端口即可：种唯一目的是「有一条 http URL」，不追求稳定
        this.server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        this.port = server.getLocalPort();
        this.pool = Executors.newFixedThreadPool(4);
        Thread t = new Thread(this::acceptLoop, "MxPlayerTune-probe-http");
        t.setDaemon(true);
        t.start();
    }

    /** native 侧要用的 URL（回环 + 一个像视频的路径）。 */
    String url() {
        return "http://127.0.0.1:" + port + "/probe/local" + suffix;
    }

    /** 是否只绑回环（离机断言用：探针的服务绝不能暴露到局域网）。 */
    boolean loopbackOnly() {
        return server.getInetAddress().isLoopbackAddress();
    }

    int requests() {
        return requests.get();
    }

    int ranged() {
        return ranged.get();
    }

    /** 第一条带 {@code Range} 的请求的起始偏移（-1 = 一条都没带）。 */
    long firstOffset() {
        return firstOffset.get();
    }

    void stop() {
        try {
            server.close();
        } catch (Throwable ignored) {
            // 已关/关不掉都无影响
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (true) {
            final Socket sock;
            try {
                sock = server.accept();
            } catch (Throwable t) {
                if (server.isClosed()) {
                    return;
                }
                continue;
            }
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        serve(sock);
                    }
                });
            } catch (Throwable t) {
                closeQuietly(sock);
            }
        }
    }

    private void serve(Socket sock) {
        try {
            sock.setSoTimeout(15000);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            String line = readLine(in);
            if (line == null) {
                return;
            }
            int sp1 = line.indexOf(' ');
            int sp2 = line.lastIndexOf(' ');
            String method = sp1 > 0 ? line.substring(0, sp1) : "?";
            String range = null;
            String h;
            int guard = 0;
            while ((h = readLine(in)) != null && !h.isEmpty() && guard++ < 64) {
                if (h.regionMatches(true, 0, "Range:", 0, 6)) {
                    range = h.substring(6).trim();
                }
            }
            requests.incrementAndGet();
            boolean head = "HEAD".equals(method);
            if (!"GET".equals(method) && !head) {
                write(out, 405, "text/plain", 0, null, false);
                return;
            }
            long[] r = parseRange(range);
            if (r == null) {
                write(out, 200, mimeOf(), size, null, head);
                return;
            }
            if (r[0] >= size) {
                write(out, 416, "text/plain", 0, null, head);
                return;
            }
            ranged.incrementAndGet();
            firstOffset.compareAndSet(-1, r[0]);
            long end = Math.min(r[1], size - 1);
            if (end - r[0] + 1 > MAX_CHUNK) {
                end = r[0] + MAX_CHUNK - 1;
            }
            write(out, 206, mimeOf(), end - r[0] + 1, r[0], head);
        } catch (Throwable ignored) {
            // 探针不因单条请求失败而中断
        } finally {
            closeQuietly(sock);
        }
    }

    /** 回响应（{@code skip >= 0} 时才真的送正文）。 */
    private void write(OutputStream out, int code, String mime, long len, Long skip, boolean head)
            throws IOException {
        StringBuilder sb = new StringBuilder(200);
        sb.append("HTTP/1.1 ").append(code).append(' ')
                .append(code == 200 ? "OK" : code == 206 ? "Partial Content" : code == 416 ? "Range Not Satisfiable" : "Method Not Allowed")
                .append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (code == 206 && skip != null) {
            sb.append("Content-Range: bytes ").append(skip).append('-').append(skip + len - 1)
                    .append('/').append(size).append("\r\n");
        }
        sb.append("Content-Length: ").append(code == 416 || code == 405 ? 0 : len).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
        if (head || code == 416 || code == 405) {
            return;
        }
        long from = (code == 206 && skip != null) ? skip : 0;
        RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            f.seek(from);
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = f.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) {
                    break;
                }
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        } finally {
            f.close();
        }
    }

    /** {@code bytes=a-b} / {@code bytes=a-} → {@code {a,b}}；无 Range 或认不出返回 null。 */
    private long[] parseRange(String header) {
        if (header == null || !header.startsWith("bytes=")) {
            return null;
        }
        String s = header.substring(6).trim();
        int dash = s.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start = Long.parseLong(s.substring(0, dash).trim());
            String e = s.substring(dash + 1).trim();
            long end = e.isEmpty() ? size - 1 : Long.parseLong(e);
            return new long[]{start, end};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 容器类型：按 <b>URL 上那个扩展名</b>（{@code suffix}）判，而不是磁盘文件名 ——
     * 所见即所声明：客户端看到的 URL 是什么扩展名，就回什么 Content-Type。
     * 磁盘上的测试文件往往是临时名（{@code …mp4}），拿它判就会与 URL 自相矛盾。
     */
    private String mimeOf() {
        String n = (suffix.isEmpty() ? file.getName() : suffix).toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (n.endsWith(".webm")) {
            return "video/webm";
        }
        if (n.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        return "video/mp4";
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 8192) {
                throw new IOException("请求行/头过长");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
            // 关不掉无影响
        }
    }
}
