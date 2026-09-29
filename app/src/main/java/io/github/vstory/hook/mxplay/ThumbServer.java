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
 *   <li>URL 里<b>只有令牌</b>，凭据不进 URL、不进日志（真凭据只活在进程内存的 {@link Source} 里）；
 *       令牌是 <b>{@code SHA-256(URL + 账号 + 密码)} 的前 16 位</b> ⇒ 不知道密码就推不出来，
 *       同机其它应用拿不到「任意取图」的能力；</li>
 *   <li>只认 {@code GET/HEAD /t/<token>.jpg}，其它路径一律 404 —— 本服务不是通用代理。</li>
 * </ul>
 *
 * <p><b>为什么端口固定、令牌是「算出来的」而不是随机的</b>（2026-09-29 改，动机来自宿主的 dex 事实）：
 * 宿主的图片加载器自带磁盘缓存，而缓存键<b>就是这条 URL 字符串</b>（{@code hr0.a} 把
 * {@code cacheOnDisk}/{@code cacheInMemory} 都置了 true）。端口随机 + 令牌随机 ⇒ 每次进程启动
 * URL 全变 ⇒ <b>宿主的缓存永远命中不了</b>，还每启动一次就往它的缓存目录里塞一整套新条目。
 * 改成「端口固定（首选 {@link #FIXED_PORT}）+ 令牌由条目 URL/账号/密码算出」之后，同一个条目
 * 在<b>任何一次进程启动</b>下都是同一条 URL ⇒ 第二次进同一目录连我们的服务都不会被请求。
 *
 * <p><b>失败要记在负缓存里</b>：渲染失败时宿主只画占位图，而列表重绑会<b>再发一次请求</b>。
 * 不做负缓存的话，一个永远抽不出帧的大文件会被<b>反复抽帧</b>（真机一次 25–37 s）。
 * 所以失败也按令牌记账、{@link #FAIL_TTL_MS} 内直接回 404 不再重试（TTL 有上限，
 * 一次网络抖动不会把一张图永久封死）。
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

    /**
     * 首选监听端口（回环）。<b>刻意选在 Android 的临时端口范围之外</b>：
     * <ul>
     *   <li>{@code ip_local_port_range} 通常是 {@code 32768–60999} —— 选在里面的固定端口，
     *       有可能正被本机某条<b>出站</b>连接的源端口占着，那就绑不上了；</li>
     *   <li>≥ 1024 ⇒ 不需要特权（本模块跑在宿主 App 的普通权限里）；</li>
     *   <li>{@code 0x6D78} = "mx"，便于在 {@code /proc/net/tcp} 里一眼认出是本服务。</li>
     * </ul>
     *
     * <p>绑定失败（被别人占了）时<b>退回随机端口</b>而不是起不来 —— 代价只是宿主的图片磁盘缓存
     * 这一次进程内命中不了（见类注释），浏览与播放不受任何影响。两侧边界由 verify 的 M 段钉住。
     */
    static final int FIXED_PORT = 0x6D78;

    /** Android/Linux 的临时端口范围（断言「FIXED_PORT 在其外」用；见 verify M 段）。 */
    static final int EPHEMERAL_LOW = 32768;
    static final int EPHEMERAL_HIGH = 60999;

    /**
     * 渲染失败的负缓存 TTL：这段时间内同一个令牌直接回 404，不再重抽帧。
     *
     * <p>为什么要它：宿主的列表重绑会<b>再发一次请求</b>（真机日志里同 token 的第二次
     * {@code 200 / 0 ms} 就是命中我们缓存）。没有负缓存时，一个永远抽不出帧的文件会被
     * 反复抽帧（真机一次 25–37 s）—— 列表滚一下就是几十秒的 CPU 与流量。
     *
     * <p>为什么不设成永久：失败可能只是<b>一次</b>网络抖动；TTL 有上限（M 段断言）
     * ⇒ 抖动过后下一次进目录还能自己好起来，不会把一张图永久封死。
     */
    static final long FAIL_TTL_MS = 5 * 60 * 1000L;

    /** 同时渲染的取图线程数（宿主自己也会并发放几个取图请求）。 */
    private static final int THREADS = 3;

    private static final SecureRandom RND = new SecureRandom();

    /** 进程内唯一实例（热重载后新的类加载器会另起一个，旧的回环端口随之作废）。 */
    private static ThumbServer sInstance;

    private final Sink log;
    private final Frames frames;
    private final ServerSocket server;
    private final int port;
    /** 本次是否真的拿到了首选端口（false ⇒ 退回了随机端口，宿主的磁盘缓存这次复用不了）。 */
    private final boolean fixedPortUsed;
    /** 首选端口绑定失败的原因（null = 没失败；只进日志）。 */
    private final Throwable fixedPortWhy;
    private final ExecutorService pool;

    private final Map<String, Source> byToken = new HashMap<>();
    private final Map<String, Object> renderLocks = new HashMap<>();
    /** 失败负缓存（令牌 → 失败记录）：{@link #FAIL_TTL_MS} 内直接回 404，不再重抽帧。 */
    private final Map<String, Failure> failures = new HashMap<>();
    private final LinkedHashMap<String, byte[]> cache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** 失败负缓存 TTL（生产值 {@link #FAIL_TTL_MS}；离机断言用小值把 TTL 语义钉住，不必真等 5 分钟）。 */
    private volatile long failTtlMs = FAIL_TTL_MS;

    /** 一次渲染失败（时间戳 + 简因，只用于日志与 TTL 判定）。 */
    private static final class Failure {
        final long at;
        final String why;

        Failure(long at, String why) {
            this.at = at;
            this.why = why;
        }
    }

    /**
     * 起服务：<b>先试固定端口</b>（{@link #FIXED_PORT}），被占才退回随机端口。
     *
     * <p>固定端口的收益是「宿主的图片磁盘缓存能跨进程命中」（缓存键就是这条 URL）；
     * 退回随机端口时代价仅此一项，服务本身照常工作 —— 所以这里宁可降级也不能起不来。
     */
    private ThumbServer(Sink log, Frames frames) throws IOException {
        this.log = log;
        this.frames = frames;
        // 显式绑 127.0.0.1（不用 getLoopbackAddress()：它可能给出 ::1，而宿主取的是 127.0.0.1）
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket bound;
        Throwable why = null;
        try {
            bound = new ServerSocket(FIXED_PORT, 16, loopback);
        } catch (Throwable t) {
            why = t;
            bound = new ServerSocket(0, 16, loopback);
        }
        this.server = bound;
        this.port = bound.getLocalPort();
        this.fixedPortUsed = (port == FIXED_PORT);
        this.fixedPortWhy = why;
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
                    + "/t/<token>.jpg（仅回环；端口" + (s.fixedPortUsed ? "固定" : "回退随机")
                    + "；凭据不进 URL；宿主取到的是一张真 JPEG）");
            if (!s.fixedPortUsed) {
                log.info("[thumb] 首选端口 " + FIXED_PORT + " 被占用 → 本次退回随机端口 "
                        + s.port + "（服务照常，只是宿主的图片磁盘缓存这次复用不了）: " + s.fixedPortWhy);
            }
            return s;
        } catch (Throwable t) {
            log.error("[thumb] 环回缩略图服务启动失败 → 缩略图沿用旧行为（返回条目自身 URL，必然空白）", t);
            return null;
        }
    }

    int port() {
        return port;
    }

    /** 本次是否拿到了首选端口（离机断言：固定端口是「宿主磁盘缓存能跨进程命中」的前提）。 */
    boolean fixedPortUsed() {
        return fixedPortUsed;
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
            renderLocks.clear();
            failures.clear();
            cache.clear();
        }
        synchronized (ThumbServer.class) {
            sInstance = null;
        }
    }

    /** 失败负缓存 TTL（离机断言用：把它调小就能在不真等 5 分钟的前提下钉住 TTL 语义）。 */
    void failTtlForTest(long ms) {
        this.failTtlMs = ms < 0 ? 0 : ms;
    }

    /**
     * 给一个条目路径登记令牌并返回环回 URL。
     *
     * <p><b>URL 是算出来的，不是发出来的</b>：令牌 = {@code SHA-256(URL + '\n' + 账号 + '\n' + 密码)}
     * 的前 16 位十六进制 ⇒ 同一个条目在<b>任何一次进程启动</b>下都是同一条 URL。
     * 这正是宿主图片磁盘缓存（键 = URL 字符串）能跨进程命中的前提（见类注释）。
     *
     * <p>密码也进摘要 ⇒ 不知道密码就<b>推不出令牌</b>（同机其它应用没法枚举「任意取图」）；
     * 换账号或换密码 → 换令牌 → 宿主缓存与我们的缓存一起作废，这正是想要的语义。
     *
     * <p>返回 {@code null} = 该路径不是可用的 http(s) URL（调用方退回旧行为）。
     */
    String urlFor(String path, String user, String pass) {
        String url = WebDavClient.stripUserInfo(path);
        if (url == null || !HookTargets.isHttpUrl(url)) {
            return null;
        }
        String token = tokenFor(url, user, pass);
        synchronized (this) {
            if (byToken.size() >= MAX_TOKENS && !byToken.containsKey(token)) {
                byToken.clear();
                renderLocks.clear();
                failures.clear();
                cache.clear();
                log.debug("[thumb] 令牌表已满（" + MAX_TOKENS + "）→ 整体作废重发"
                        + "（令牌是算出来的，宿主下次请求会重新登记，URL 不变）");
            }
            byToken.put(token, new Source(url, user, pass));
        }
        return "http://127.0.0.1:" + port + "/t/" + token + ".jpg";
    }

    /**
     * 令牌的纯函数式定义（离机断言直接调它）。
     *
     * <p>刻意用 {@code SHA-256} 而不是 {@code String.hashCode()}：后者只有 32 位、
     * 且可被刻意构造碰撞 —— 令牌决定「谁能取到哪张图」，用 64 位摘要前段更稳。
     * {@code null} 的用户/密码按空串参与（与 {@link Source} 的判据一致）。
     */
    static String tokenFor(String url, String user, String pass) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(utf8(url));
            md.update((byte) '\n');
            md.update(utf8(user == null ? "" : user));
            md.update((byte) '\n');
            md.update(utf8(pass == null ? "" : pass));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(d[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            // SHA-256 是 JLS 要求的必备算法，正常走不到这里；真走到了也不能不给 URL
            // （随机令牌只是失去「跨进程稳定」，功能照常）。
            return String.format(Locale.ROOT, "%016x", RND.nextLong());
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    /**
     * 取 JPEG：命中缓存直接回；命中失败负缓存（TTL 内）直接回 null；否则渲染一次（同一令牌并发只渲染一次）。
     */
    private byte[] jpeg(String token) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            byte[] hit = cache.get(token);
            if (hit != null) {
                return hit;
            }
            Failure f = failures.get(token);
            if (f != null) {
                long age = now - f.at;
                if (age < failTtlMs) {
                    // 宿主列表重绑会再发一次请求：**不要**再抽一次帧（真机一次 25–37 s）
                    if (LogBudget.THUMB_FAILHIT.take()) {
                        log.debug("[thumb] 失败负缓存命中（" + (age / 1000) + " s 前失败过，TTL "
                                + (failTtlMs / 1000) + " s 内不重试）token=" + shortToken(token)
                                + " 上次原因: " + f.why);
                    }
                    return null;
                }
                failures.remove(token);
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
                    failures.remove(token);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (LogBudget.THUMB_RENDER.take()) {
                    log.info("[thumb] 渲染 url=" + src.label() + " → JPEG " + jpeg.length + " 字节 / " + ms + " ms"
                            + (src.how.isEmpty() ? "" : " ← " + src.how));
                }
                return jpeg;
            } catch (Throwable t) {
                String why = whyOf(t);
                synchronized (this) {
                    // 负缓存条数与令牌表同量级封顶：失败也是「一条目一份」，不许无限长
                    if (failures.size() >= MAX_TOKENS) {
                        failures.clear();
                    }
                    failures.put(token, new Failure(System.currentTimeMillis(), why));
                }
                if (LogBudget.THUMB_FAIL.take()) {
                    log.error("[thumb] 渲染失败 url=" + src.label()
                            + "（宿主只会显示占位图，不会报错；" + (failTtlMs / 1000)
                            + " s 内不再重试同一条）", t);
                }
                return null;
            }
        }
    }

    /** 原因串（进日志的失败记录：短、单行）。 */
    private static String whyOf(Throwable t) {
        if (t == null) {
            return "（无）";
        }
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** 令牌的短标识（日志用：只留前 8 位，不把整串令牌铺进日志）。 */
    private static String shortToken(String token) {
        return token == null ? "?" : (token.length() > 8 ? token.substring(0, 8) + "…" : token);
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
