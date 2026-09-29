package io.github.vstory.hook.mxplay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 环回缩略图服务（hook ③ 把宿主的取图 URL 指到这里）。
 *
 * <p><b>为什么必须有它</b>（2026-09-29 真机日志 + 直打服务端实测，见 hook信息记录.md）：
 * <ol>
 *   <li>宿主取缩略图取的是<b>条目自身 URL</b>（不是同名的 {@code .jpg}），且宿主的图片下载器
 *       （{@code MxImageDownloader}）是一条<b>裸</b> {@code HttpURLConnection}，<b>不发 {@code Range}</b>
 *       ⇒ 它面对的就是「整片视频」；</li>
 *   <li>本机 CloudDrive 又把每个媒体文件暴露成「同名集合 + 同名子项」，能取到字节的那条 URL
 *       仍然是<b>整片</b>（实测 {@code 200} + {@code video/mp4} + {@code len=391840912}）
 *       ⇒ 图片解码必然失败 ⇒ 只留占位图。</li>
 * </ol>
 * 两条都是**对方的既定行为**，本模块改不了；能改的是「宿主拿到什么」——
 * 所以本服务在进程内起一个只监听回环的极小 HTTP 服务，hook ③ 返回它的 URL：
 * 宿主自己的图片加载器<b>照旧**取图**</b>（不需要它支持任何新协议、不需要它改代码），
 * 但它这次拿到的是一张**真的 JPEG**（我们按 {@code Range} 分片取到首段 → 抽帧 → 编码，见 {@link ThumbFrames}）。
 *
 * <p><b>为什么不直接放「同名 .jpg」到服务端</b>：宿主根本不去找同名图（dex 事实：{@code SmbUtil.b}
 * 只用 {@code uri.getHost()+uri.getPath()} 重建条目自身 URL）。
 *
 * <p><b>安全边界</b>（这是本类唯一需要小心的地方）：
 * <ul>
 *   <li>只绑 {@code 127.0.0.1}（不暴露到局域网；{@code ServerSocket} 的地址断言在 verify 的 M 段）；</li>
 *   <li>URL 里<b>只有随机令牌</b>，凭据不进 URL、不进日志（真凭据只活在进程内存的 {@link Source} 里）；</li>
 *   <li>令牌是 16 位十六进制随机数（不可枚举），且表满即整体作废 ⇒ 局域网/同机其它应用拿不到「任意取图」的能力；</li>
 *   <li>只认 {@code GET/HEAD /t/<token>.jpg}，其它路径一律 404 —— 本服务不是通用代理。</li>
 * </ul>
 *
 * <p><b>失败一律退化成「今天的样子」</b>：服务起不来（端口/权限异常）时返回 {@code null}，
 * hook ③ 退回「返回条目自身 URL」的旧行为 ⇒ 缩略图空白，但浏览与播放完全不受影响。
 *
 * <p><b>离机可验证</b>：本类不依赖任何 Android API（日志出口是注入的 {@link Sink}，抽帧是注入的
 * {@link Frames}）⇒ verify 的 M 段用真回环 socket + 假抽帧把「200/404/缓存/令牌稳定性」全部钉住。
 */
final class ThumbServer {

    /** 日志出口（由 MainHook 注入：本类不引用 BuildConfig，才能离机编译）。 */
    interface Sink {
        void info(String msg);

        void debug(String msg);

        void error(String msg, Throwable t);
    }

    /** 抽帧出口（生产实现 {@link ThumbFrames}；离机断言用假实现）。 */
    interface Frames {
        byte[] jpeg(Source source) throws Exception;
    }

    /**
     * 一个待渲染的源：<b>剥掉凭据</b>的 URL + 取流用的真凭据。
     *
     * <p>两者刻意分开：URL 会进日志（脱敏后仍要有信息量），凭据只在请求头里出现。
     */
    static final class Source {
        final String url;
        final String user;
        final String pass;

        /**
         * 抽帧层写进来的「实际走了哪条取流路径」诊断（只进日志，不参与任何判定）。
         *
         * <p>放在这里而不是让抽帧层自己打日志：宿主的取图链路对失败是<b>静默</b>的，
         * 「哪一层出的问题」必须和渲染结果落在<b>同一行</b>上，否则会被其它线程的日志冲散。
         */
        volatile String how = "";

        Source(String url, String user, String pass) {
            this.url = url;
            this.user = user;
            this.pass = pass;
        }

        /** 日志标签：只有剥掉凭据的 URL。 */
        String label() {
            return url;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /** 只对本表内的扩展名启用环回服务（否则宿主拿到的是 404 占位图，和今天没区别还多一次往返）。 */
    private static final String[] VIDEO_EXT = {
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts",
            "mpg", "mpeg", "vob", "rmvb", "rm", "asf", "3gp", "ogv", "divx", "f4v", "m2v",
    };

    /** JPEG 缓存条数（每条约几十 KB；滚动列表来回就是这批文件）。 */
    private static final int CACHE_MAX = 24;

    /** 令牌表上限（满即整体作废：宁可让宿主重取一次，也不让表无限长）。 */
    private static final int MAX_TOKENS = 512;

    /** 同时渲染的取图线程数（宿主自己也会并发放几个取图请求）。 */
    private static final int THREADS = 3;

    private static final SecureRandom RND = new SecureRandom();

    /** 进程内唯一实例（热重载后新的类加载器会另起一个，旧的回环端口随之作废）。 */
    private static ThumbServer sInstance;

    private final Sink log;
    private final Frames frames;
    private final ServerSocket server;
    private final int port;
    private final ExecutorService pool;

    private final Map<String, Source> byToken = new HashMap<>();
    private final Map<String, String> tokenByUrl = new HashMap<>();
    private final Map<String, Object> renderLocks = new HashMap<>();    private final LinkedHashMap<String, byte[]> cache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private ThumbServer(Sink log, Frames frames) throws IOException {
        this.log = log;
        this.frames = frames;
        // 显式绑 127.0.0.1（不用 getLoopbackAddress()：它可能给出 ::1，而宿主取的是 127.0.0.1）
        this.server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        this.port = server.getLocalPort();
        this.pool = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MxPlayerTune-thumb-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        Thread acceptor = new Thread(this::acceptLoop, "MxPlayerTune-thumb-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** 起服务（幂等）。失败返回 {@code null}（调用方退回旧行为），绝不抛给 hook 调用点。 */
    static synchronized ThumbServer start(Sink log, Frames frames) {
        if (sInstance != null) {
            return sInstance;
        }
        try {
            ThumbServer s = new ThumbServer(log, frames);
            sInstance = s;
            log.info("[thumb] 环回缩略图服务已启动 http://127.0.0.1:" + s.port
                    + "/t/<token>.jpg（仅回环；凭据不进 URL；宿主取到的是一张真 JPEG）");
            return s;
        } catch (Throwable t) {
            log.error("[thumb] 环回缩略图服务启动失败 → 缩略图沿用旧行为（返回条目自身 URL，必然空白）", t);
            return null;
        }
    }

    int port() {
        return port;
    }

    /** 是否只绑回环（离机断言用：这个服务绝不能暴露到局域网）。 */
    boolean loopbackOnly() {
        return server.getInetAddress().isLoopbackAddress();
    }

    /** 关服务（离机断言收尾用；真机上进程结束即回收，不需要这一步）。 */
    void stopForTest() {
        try {
            server.close();
        } catch (Throwable ignored) {
            // 已关/关不掉都无影响
        }
        pool.shutdownNow();
        synchronized (this) {
            byToken.clear();
            tokenByUrl.clear();
            renderLocks.clear();
            cache.clear();
        }
        synchronized (ThumbServer.class) {
            sInstance = null;
        }
    }

    /**
     * 给一个条目路径登记令牌并返回环回 URL（同一 URL + 同一账号 → 同一个令牌 ⇒ 宿主的
     * 图片缓存与我们的 JPEG 缓存都能命中）。
     *
     * <p>返回 {@code null} = 该路径不是可用的 http(s) URL（调用方退回旧行为）。
     */
    String urlFor(String path, String user, String pass) {
        String url = WebDavClient.stripUserInfo(path);
        if (url == null || !HookTargets.isHttpUrl(url)) {
            return null;
        }
        String key = url + '\n' + (user == null ? "" : user);
        String token;
        synchronized (this) {
            token = tokenByUrl.get(key);
            if (token == null) {
                if (tokenByUrl.size() >= MAX_TOKENS) {
                    byToken.clear();
                    tokenByUrl.clear();
                    renderLocks.clear();
                    cache.clear();
                    log.debug("[thumb] 令牌表已满（" + MAX_TOKENS + "）→ 整体作废重发");
                }
                token = newToken();
                byToken.put(token, new Source(url, user, pass));
                tokenByUrl.put(key, token);
            }
        }
        return "http://127.0.0.1:" + port + "/t/" + token + ".jpg";
    }

    private static String newToken() {
        return String.format(Locale.ROOT, "%016x", RND.nextLong());
    }

    /**
     * 路径是否值得走环回服务（只看末段扩展名）。
     *
     * <p>只对视频启用：音频/字幕/目录走进来的话我们抽不出帧 ⇒ 宿主照样是占位图，
     * 却白白多一次往返与一次失败日志。
     */
    static boolean isVideoPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String seg = WebDavClient.lastSegment(WebDavClient.pathOf(path));
        int q = seg.indexOf('?');
        if (q >= 0) {
            seg = seg.substring(0, q);
        }
        int dot = seg.lastIndexOf('.');
        if (dot < 0 || dot == seg.length() - 1) {
            return false;
        }
        String ext = seg.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : VIDEO_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    // ===== HTTP 循环 =====

    private void acceptLoop() {
        while (true) {
            final Socket sock;
            try {
                sock = server.accept();
            } catch (Throwable t) {
                if (server.isClosed()) {
                    return;
                }
                log.error("[thumb] accept 失败（服务继续）", t);
                continue;
            }
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(sock);
                    }
                });
            } catch (Throwable t) {
                closeQuietly(sock);
            }
        }
    }

    /** 一次请求：极小的 HTTP/1.1（只认 GET/HEAD + {@code /t/<token>.jpg}）。 */
    private void handle(Socket sock) {
        long t0 = System.nanoTime();
        String method = "?";
        String path = "?";
        int code = 500;
        String note = "";
        int bytes = 0;
        try {
            sock.setSoTimeout(5000);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            String line = readLine(in);
            if (line == null) {
                return;
            }
            int sp1 = line.indexOf(' ');
            int sp2 = line.lastIndexOf(' ');
            if (sp1 > 0) {
                method = line.substring(0, sp1);
                path = sp2 > sp1 ? line.substring(sp1 + 1, sp2) : line.substring(sp1 + 1);
            }
            // 请求头我们一个都不用；必须读完，否则 keep-alive 的下一行会串位
            int guard = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty() && guard++ < 64) {
                // 丢弃
            }
            if (path.startsWith("/") && !path.isEmpty()) {
                int cut = path.length();
                for (int i = 1; i < path.length(); i++) {
                    char c = path.charAt(i);
                    if (c == '?' || c == '#') {
                        cut = i;
                        break;
                    }
                }
                path = path.substring(0, cut);
            }

            byte[] jpeg = null;
            String token = tokenOf(path);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                code = 405;
                note = "只认 GET/HEAD";
            } else if (token == null) {
                code = 404;
                note = "路径不是 /t/<token>.jpg";
            } else if (!known(token)) {
                code = 404;
                note = "令牌未登记（多半是上一次进程的缓存 URL）";
            } else {
                jpeg = jpeg(token);
                if (jpeg == null) {
                    code = 404;
                    note = "渲染失败（原因见上一条 E 级日志）";
                } else {
                    code = 200;
                    bytes = jpeg.length;
                }
            }

            try {
                if ("HEAD".equals(method)) {
                    writeHead(out, code, 0);
                } else if (jpeg != null) {
                    writeHead(out, code, bytes);
                    out.write(jpeg);
                } else {
                    byte[] body = ("MxPlayerTune thumb: " + code + " " + note + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    writeHead(out, code, body.length);
                    out.write(body);
                }
                out.flush();
            } catch (Throwable t) {
                // 客户端提前断开（宿主的图片加载器超时 / 用户翻页）：**渲染结果已经在缓存里**，
                // 下一次同样的请求立刻命中 ⇒ 这一次断开不影响最终出图。所以只留 debug 明细。
                code = 499;
                note = "客户端提前断开（渲染结果已缓存）";
                if (LogBudget.THUMB_SERVE.take()) {
                    log.debug("[thumb] " + path + " 响应写失败：" + t);
                }
            }
        } catch (Throwable t) {
            code = 500;
            note = "处理异常: " + t;
            log.error("[thumb] 请求处理异常 method=" + method + " path=" + path, t);
        } finally {
            closeQuietly(sock);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (code == 200) {
                if (LogBudget.THUMB_SERVE.take()) {
                    log.debug("[thumb] GET " + path + " → 200 image/jpeg " + (bytes / 1024) + " KB / " + ms + " ms");
                }
            } else if (LogBudget.THUMB_SERVE.take()) {
                log.debug("[thumb] " + method + " " + path + " → " + code + "（" + note + "） / " + ms + " ms");
            }
        }
    }

    /** {@code /t/<token>.jpg} → token（认不出返回 null）。 */
    static String tokenOf(String path) {
        if (path == null || !path.startsWith("/t/")) {
            return null;
        }
        String rest = path.substring(3);
        int dot = rest.indexOf('.');
        if (dot > 0) {
            rest = rest.substring(0, dot);
        }
        if (rest.isEmpty() || rest.length() > 64) {
            return null;
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return rest;
    }

    private synchronized boolean known(String token) {
        return byToken.containsKey(token);
    }

    /** 取 JPEG：命中缓存直接回；否则渲染一次（同一令牌并发只渲染一次）。 */
    private byte[] jpeg(String token) {
        synchronized (this) {
            byte[] hit = cache.get(token);
            if (hit != null) {
                return hit;
            }
        }
        Object lock;
        synchronized (this) {
            lock = renderLocks.get(token);
            if (lock == null) {
                lock = new Object();
                renderLocks.put(token, lock);
            }
        }
        synchronized (lock) {
            synchronized (this) {
                byte[] hit = cache.get(token);
                if (hit != null) {
                    return hit;
                }
            }
            Source src;
            synchronized (this) {
                src = byToken.get(token);
            }
            if (src == null) {
                return null;
            }
            long t0 = System.nanoTime();
            try {
                byte[] jpeg = frames.jpeg(src);
                if (jpeg == null || jpeg.length < 512) {
                    throw new IOException("抽帧结果不合理（len=" + (jpeg == null ? -1 : jpeg.length) + "）");
                }
                synchronized (this) {
                    cache.put(token, jpeg);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (LogBudget.THUMB_RENDER.take()) {
                    log.info("[thumb] 渲染 url=" + src.label() + " → JPEG " + jpeg.length + " 字节 / " + ms + " ms"
                            + (src.how.isEmpty() ? "" : " ← " + src.how));
                }
                return jpeg;
            } catch (Throwable t) {
                if (LogBudget.THUMB_FAIL.take()) {
                    log.error("[thumb] 渲染失败 url=" + src.label() + "（宿主只会显示占位图，不会报错）", t);
                }
                return null;
            }
        }
    }

    private static void writeHead(OutputStream out, int code, int len) throws IOException {
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Content-Type: ").append(code == 200 ? "image/jpeg" : "text/plain; charset=utf-8").append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        if (code == 200) {
            sb.append("Cache-Control: public, max-age=86400\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            default:
                return "Internal Server Error";
        }
    }

    /** 读一行（只认 CRLF；宿主自己就是 HttpURLConnection，不会发怪东西）。 */
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
