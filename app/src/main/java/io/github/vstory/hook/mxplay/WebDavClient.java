package io.github.vstory.hook.mxplay;

import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * WebDAV 客户端：一次 {@code PROPFIND Depth: 1} 列出目录。
 *
 * <p>依赖说明（刻意选择，勿随手换）：
 * <ul>
 *   <li>XML 走 {@code javax.xml.parsers}（DOM）而非 {@code android.util.Xml}：
 *       前者在 Android 与宿主 JVM 上都存在 ⇒ 解析层能离机抓错验证（本项目规矩：写完必做抓错验证）</li>
 *   <li>百分号解码用 {@code android.net.Uri.decode}：与 {@code RemoteEntry} 构造器内部的
 *       {@code Uri.encode} 同源，能保证「解码一次 → 再编码一次」正好还原，不产生 {@code %25}</li>
 * </ul>
 *
 * <p>请求策略：优先 {@code HttpURLConnection}（自带 TLS / 重定向 / 连接池 / 分块解码），
 * 但其 {@code setRequestMethod} 只接受标准方法、会拒绝 PROPFIND ⇒ 用反射写 {@code method} 字段
 * （<b>必须写到「真正发请求的那个对象」上</b>，见 {@link #applyMethod}：Android 的
 * {@code HttpsURLConnection} 是委托壳，只写壳上的字段等于没写）；若反射也失败，
 * 退化为手写 HTTP/1.1（原始 socket / SSLSocket，自己解 chunked）。
 *
 * <p>凭据：绝不写进 URL（URL 里的 userinfo 会被剥掉后单独用于 Authorization 头）——
 * 因为 MX 的 {@code RemoteEntry.getSecurityPath()} 见到 {@code @} 会拼上 {@code smb://} 前缀。
 */
final class WebDavClient {

    /** 与 MX 自身 SMB 超时同量级（{@code SMB2Client.setTimeout(10000)}）。 */
    static final int TIMEOUT_MS = 10_000;

    /** PROPFIND 请求体：只要 resourcetype（判目录）与 getcontentlength。 */
    static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                    + "<D:resourcetype/><D:getcontentlength/>"
                    + "</D:prop></D:propfind>";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 诊断流水（调用方打印后 {@link #resetNotes()}），记录「走的哪条请求路径」等。 */
    static final StringBuilder NOTES = new StringBuilder();

    /** https 宽松校验的 Socket 工厂（懒建；null = 不可用，退回严格校验）。 */
    private static SSLSocketFactory sRelaxFactory;

    static void note(String line) {
        NOTES.append("  ").append(line).append('\n');
    }

    static void resetNotes() {
        NOTES.setLength(0);
    }

    /** 目录子项（解析结果；MX 的 type 由调用方决定）。 */
    static final class Child {
        final String name;
        final boolean dir;
        final long size;

        Child(String name, boolean dir, long size) {
            this.name = name;
            this.dir = dir;
            this.size = size;
        }

        @Override
        public String toString() {
            return (dir ? "[D] " : "[F] ") + name;
        }
    }

    private WebDavClient() {
    }

    /** 列目录：PROPFIND → 解析 → 去自身 → 排序（目录在前，各自按名不区分大小写）。失败抛 IOException。 */
    static List<Child> list(String url, String user, String pass) throws IOException {
        String xml = propfind(url, user, pass);
        List<Child> children = parse(xml, url);
        Collections.sort(children, new Comparator<Child>() {
            @Override
            public int compare(Child a, Child b) {
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return children;
    }

    // ===== 请求 =====

    private static String propfind(String rawUrl, String user, String pass) throws IOException {
        String url = stripUserInfo(rawUrl);
        String auth = authOf(rawUrl, user, pass);
        byte[] body = PROPFIND_BODY.getBytes(UTF8);
        try {
            String xml = viaHttpConnection(url, auth, body);
            note("请求路径: HttpURLConnection（反射设 method=PROPFIND）");
            return xml;
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
            note("HttpURLConnection 不接受 PROPFIND（" + e.getClass().getSimpleName()
                    + "）→ 退化为手写 HTTP/1.1");
            return viaRawSocket(url, auth, body);
        } catch (java.net.ProtocolException e) {
            note("HttpURLConnection 拒绝该 method（" + e.getMessage() + "）→ 退化为手写 HTTP/1.1");
            return viaRawSocket(url, auth, body);
        } catch (RuntimeException e) {
            // 必须兜住：JDK 9+ 的模块封装（或 Android 的隐藏 API 限制）会让 setAccessible 抛
            // RuntimeException，漏了它就等于没有退化路径 —— 直接崩在 hook 里。
            note("设置 method 失败（" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "）→ 退化为手写 HTTP/1.1");
            return viaRawSocket(url, auth, body);
        } catch (IOException e) {
            // 服务端回「动词不认」：几乎只有一个解释 —— 我们写的 method 没真正落到发请求的那个
            // 对象上（Android 的 https 是委托壳；若某个版本连委托对象都碰不到，写就会静默失效）。
            // 这时立刻用自己手写的 HTTP/1.1 重试一次：PROPFIND 幂等，重试无副作用。
            if (verbRejected(e)) {
                note("服务端回「动词不认」（" + brief(e.getMessage()) + "）→ 改用手写 HTTP/1.1 重试");
                return viaRawSocket(url, auth, body);
            }
            throw e;
        }
    }

    /**
     * 服务端回「不认识这个动词」的两种状态码（{@code 501 Not Implemented} / {@code 405 Method Not Allowed}）。
     *
     * <p>用来判「是不是我们的动词没发出去」：CloudDrive 的 WebDAV 对 PROPFIND 一律 207、
     * 对 POST 才裸 501（真机 + 离机均已实测）；WebDAV 服务端不会对合法的 PROPFIND 回 501。
     */
    static boolean verbRejected(IOException e) {
        String m = e == null ? null : e.getMessage();
        return m != null && (m.contains("→ HTTP 501") || m.contains("→ HTTP 405"));
    }

    /**
     * 放宽 https 的证书校验（**仅本模块的 PROPFIND 用**）。
     *
     * <p>为什么需要：自建 WebDAV（群晖 / Alist / nginx 自签 / 私有 CA）几乎都不是受信证书，
     * 默认校验下 {@code PROPFIND} 直接 {@code SSLHandshakeException: Trust anchor for
     * certification path not found} ⇒ 列目录全灭（真机日志原样如此）。
     *
     * <p>纪律：只设到<b>本条连接</b>上（{@code setSSLSocketFactory} / 返回专用 Socket），
     * 绝不碰 {@code HttpsURLConnection.setDefaultSSLSocketFactory} —— 那会把整个 MX 进程的
     * 证书校验一起废掉。宽松校验初始化失败时返回 null，调用方退回严格校验。
     *
     * <p>范围限制：只影响「浏览/列目录」。播放与缩略图走 MX 自带的 FFmpeg，证书校验在它那边
     * （本模块管不到）⇒ 自签名 https 上播放仍可能失败，请用 http 或受信证书。
     */
    private static SSLSocketFactory relaxFactory() {
        if (sRelaxFactory == null) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, new java.security.SecureRandom());
                sRelaxFactory = ctx.getSocketFactory();
            } catch (Throwable t) {
                note("https 宽松校验初始化失败（" + t + "）→ 退回严格校验");
                return null;
            }
        }
        return sRelaxFactory;
    }

    /** https 连接套用宽松校验（含主机名）；http 原样返回。 */
    static void relaxTls(HttpURLConnection conn) {
        if (!(conn instanceof HttpsURLConnection)) {
            return;
        }
        SSLSocketFactory f = relaxFactory();
        if (f == null) {
            return;
        }
        HttpsURLConnection https = (HttpsURLConnection) conn;
        https.setSSLSocketFactory(f);
        https.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
            @Override
            public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                return true;
            }
        });
        note("https: 已放宽证书校验（自签名 / 私有 CA 可用；仅本条连接）");
    }

    /** 凭据来源：显式参数优先，其次 URL 里的 userinfo（两种都按 RFC 7617 走 Authorization 头）。 */
    private static String authOf(String rawUrl, String user, String pass) {
        if (user != null && !user.isEmpty()) {
            return basicAuth(user, pass == null ? "" : pass);
        }
        return authOfUserInfo(rawUrl);
    }

    /**
     * 从 URL 的 userinfo 生成 {@code Authorization} 头值（无 userinfo 返回 {@code null}）。
     *
     * <p>URL 里放的 userinfo 是<b>编码形态</b>（MX 落库值）⇒ 这里 {@code Uri.decode} 还原真凭据
     * （与 {@code wJ.c(uri)} 对 {@code Uri#getUserInfo()} 的处理一致，见 hook信息记录.md §三）。
     *
     * <p>hook ④（图片取流补鉴权）复用这一条 —— 列目录、播放、缩略图三处必须是<b>同一套</b>判据，
     * 否则会出现「能列目录、能播放，就是缩略图空白」这种最难查的组合。
     */
    static String authOfUserInfo(String rawUrl) {
        if (!HookTargets.isHttpUrl(rawUrl)) {
            return null;   // SMB 条目的 URL 也可能带 userinfo，但那套凭据归 SMB 客户端
        }
        String ui = userInfoOf(rawUrl);
        if (ui == null || ui.isEmpty()) {
            return null;
        }
        int c = ui.indexOf(':');
        if (c < 0) {
            return basicAuth(Uri.decode(ui), "");
        }
        return basicAuth(Uri.decode(ui.substring(0, c)), Uri.decode(ui.substring(c + 1)));
    }

    /**
     * 给一条「URL 里带 userinfo 的 http(s) 取流」补上 {@code Authorization} 头（hook ④ 的载荷）。
     *
     * <p>动机（真机事实，见 hook信息记录.md §三.4）：缩略图/图片走 Glide，
     * 而 Glide 的取流<b>不</b>把 URL 的 userinfo 变成鉴权头 ⇒ 服务端 401 ⇒ 缩略图空白且无报错；
     * 播放那条链路则自带 {@code Authorization} 选项（{@code wJ.c(uri)}）⇒ 所以「能播放、缩略图空」。
     *
     * <p>边界：只认 http(s) + userinfo 非空 + 尚无 {@code Authorization}；已有头<b>绝不覆盖</b>
     * （宿主自己设的优先）。任何异常都静默返回 false —— 这是取图链路的旁挂补丁，
     * 宁可不补，也不能把宿主的取流打断。
     *
     * <p><b>已实测的边界（委托壳的第二个表现）</b>：{@code setRequestProperty()} 落到委托对象、
     * {@code getRequestProperty()} 读的却是壳 ⇒ 补完头<b>当场读回来仍是 null</b>（但服务端确实收到了）。
     * 因此「已有头就不覆盖」这条只是<b>尽力而为</b>：真正能保证的是「只对有 userinfo 的
     * http(s) URL 生效、且值一定来自该 userinfo」。离机验证里这两条分别用
     * 「真实取流端到端」与「假连接对象」两条用例钉住（见 verify 的 K 段）。
     *
     * @return true = 本次真的补上了头
     */
    static boolean injectUrlAuth(java.net.URLConnection conn) {
        if (conn == null) {
            return false;
        }
        try {
            java.net.URL u = conn.getURL();
            if (u == null) {
                return false;
            }
            String raw = u.toExternalForm();
            if (!HookTargets.isHttpUrl(raw)) {
                return false;
            }
            String auth = authOfUserInfo(raw);
            if (auth == null) {
                return false;
            }
            if (conn.getRequestProperty("Authorization") != null) {
                return false;
            }
            conn.setRequestProperty("Authorization", auth);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String basicAuth(String user, String pass) {
        String cred = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(cred.getBytes(UTF8));
    }

    /**
     * 把目标动词写进 {@code method} 字段 —— <b>写到「真正发请求的那个对象」上</b>。
     *
     * <p>为什么不能只写 {@code conn}（真机事故，见 hook信息记录.md §三.3）：Android 的
     * {@code HttpsURLConnection} 是<b>委托壳</b>（{@code HttpsURLConnectionImpl} 持有
     * {@code delegate}），壳把 {@code getRequestMethod()/getResponseCode()/getOutputStream()}
     * 都转发给 delegate，而 <b>delegate 才是拿 {@code method} 字段构建请求行的那一个</b>。
     * 只写壳上的继承字段 ⇒ delegate 仍是 {@code GET}；随后我们调 {@code getOutputStream()}
     * （{@code setDoOutput(true)} 导致）又把它翻成 {@code POST} ⇒ 服务端收到 POST。
     * 真机日志原样（{@code CloudDrive} 的 WebDAV：PROPFIND→207 / POST→501）：
     *
     * <pre>
     * [webdav] 列目录失败 path=https://***:***@127.0.0.1:29799/dav/ / 6 ms:
     *     java.io.IOException: PROPFIND https://127.0.0.1:29799/dav/ → HTTP 501
     *     at io.github.vstory.hook.mxplay.WebDavClient.viaHttpConnection(WebDavClient.java:254)
     * </pre>
     *
     * 而 http 上同样代码是好的 —— 因为 {@code URL.openConnection()} 在 http 上直接给出
     * 具体实现类（没有委托壳）。这个「http 能列、https 报 501」的不对称就是本方法的由来。
     *
     * <p>写法：沿对象自身类链把每个名为 {@code method} 的 {@code String} 字段都写一遍
     * （兼容「子类自己声明同名字段」的遮蔽情形），再对<b>成员类型为
     * {@code HttpURLConnection} 的字段</b>取出的委托对象递归写一层（Android 委托壳只有一层）。
     *
     * @return 实际写入的字段个数（写入 0 处说明形态变了 ⇒ 日志里必须能看见）
     */
    static int applyMethod(HttpURLConnection conn, String verb) throws IllegalAccessException {
        int n = writeMethod(conn, verb);
        for (Class<?> k = conn.getClass(); k != null && k != HttpURLConnection.class; k = k.getSuperclass()) {
            for (Field g : k.getDeclaredFields()) {
                if (!HttpURLConnection.class.isAssignableFrom(g.getType())) {
                    continue;
                }
                g.setAccessible(true);
                Object d = null;
                try {
                    d = g.get(conn);
                } catch (IllegalAccessException | RuntimeException ignored) {
                    // 取不到委托对象不致命：壳上那份已经写了
                }
                if (d instanceof HttpURLConnection && d != conn) {
                    n += writeMethod((HttpURLConnection) d, verb);
                }
            }
        }
        return n;
    }

    /**
     * 沿类链写 {@code method} 字段（每个声明它的层级都写）。
     *
     * <p>刻意<b>不吞</b> {@code setAccessible} 抛出的异常：JDK 9+ 的模块封装会让它抛
     * {@code InaccessibleObjectException}（RuntimeException）—— 那正是「这条路走不通，
     * 该退化为手写 HTTP/1.1」的信号，必须冒到 {@link #propfind} 的 catch 里去。
     */
    private static int writeMethod(HttpURLConnection c, String verb) throws IllegalAccessException {
        int n = 0;
        for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
            Field f;
            try {
                f = k.getDeclaredField("method");
            } catch (NoSuchFieldException e) {
                continue;
            }
            if (f.getType() != String.class) {
                continue;
            }
            f.setAccessible(true);
            f.set(c, verb);
            n++;
        }
        return n;
    }

    /** 目录/文件请求方法（PROPFIND 是 WebDAV 列目录的唯一动词）。 */
    private static final String METHOD_PROPFIND = "PROPFIND";

    /** 主路径：HttpURLConnection（反射写 method 字段，绕过 setRequestMethod 的白名单校验）。 */
    static String viaHttpConnection(String url, String auth, byte[] body) throws IOException,
            NoSuchFieldException, IllegalAccessException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            int written = applyMethod(conn, METHOD_PROPFIND);
            note("method 写入 " + written + " 处，conn=" + conn.getClass().getName()
                    + "（委托壳时必须连 delegate 一起写）");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Depth", "1");
            conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            conn.setRequestProperty("User-Agent", "MxPlayerTune");
            if (auth != null) {
                conn.setRequestProperty("Authorization", auth);
            }
            relaxTls(conn);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setDoOutput(true);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code != 207 && code != 200) {
                throw new IOException("PROPFIND " + url + " → HTTP " + code + " " + brief(text));
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /** 退化路径：手写 HTTP/1.1（PROPFIND + Connection: close，读到 EOF 即响应结束）。 */
    static String viaRawSocket(String urlStr, String auth, byte[] body) throws IOException {
        URL u = new URL(urlStr);
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String host = u.getHost();
        String path = (u.getFile() == null || u.getFile().isEmpty()) ? "/" : u.getFile();

        Socket socket = null;
        try {
            if (tls) {
                SSLSocketFactory f = relaxFactory();
                SSLSocket ssl = (SSLSocket) (f != null ? f : SSLSocketFactory.getDefault()).createSocket(host, port);
                if (f == null) {
                    // 宽松校验不可用时保持严格的 HTTPS 主机名校验（宁可报错也不静默降级）
                    SSLParameters p = ssl.getSSLParameters();
                    p.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(p);
                } else {
                    note("https: 已放宽证书校验（自签名 / 私有 CA 可用；仅本条连接）");
                }
                ssl.startHandshake();
                socket = ssl;
            } else {
                socket = new Socket(host, port);
            }
            socket.setSoTimeout(TIMEOUT_MS);

            StringBuilder head = new StringBuilder();
            head.append("PROPFIND ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host);
            if ((tls && port != 443) || (!tls && port != 80)) {
                head.append(':').append(port);
            }
            head.append("\r\n");
            head.append("Depth: 1\r\n");
            head.append("Content-Type: application/xml; charset=utf-8\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("User-Agent: MxPlayerTune\r\n");
            if (auth != null) {
                head.append("Authorization: ").append(auth).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(UTF8));
            out.write(body);
            out.flush();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            byte[] all = buf.toByteArray();
            int split = indexOfCrlfCrlf(all);
            if (split < 0) {
                throw new IOException("响应不完整（未找到头部结束标记），收到 " + all.length + " 字节");
            }
            String header = new String(all, 0, split, UTF8);
            String bodyText = decodeBody(all, split + 4, header);
            int code = statusOf(header);
            if (code != 207 && code != 200) {
                throw new IOException("PROPFIND " + urlStr + " → HTTP " + code + " " + brief(bodyText));
            }
            return bodyText;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关闭失败无影响
                }
            }
        }
    }

    private static int statusOf(String header) throws IOException {
        int eol = header.indexOf('\r');
        String first = eol < 0 ? header : header.substring(0, eol);
        String[] parts = first.split(" ");
        if (parts.length < 2) {
            throw new IOException("响应状态行无法解析: " + first);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("响应状态行无法解析: " + first, e);
        }
    }

    private static int indexOfCrlfCrlf(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfCrlf(byte[] b, int from) {
        for (int i = from; i + 1 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 手写路径的响应体解码：{@code Transfer-Encoding: chunked} 要自己拆帧。
     *
     * <p>为什么必须有：本机的服务端（CloudDrive WebDAV）<b>所有</b> 207 都是 chunked 回的
     * （真机实测响应体开头就是 {@code 17D\r\n<?xml …}）—— 不拆帧的话喂给 DOM 的是
     * {@code "17D\r\n<?xml"} ⇒ 报「解析失败」，而失败原因看起来跟分块毫无关系。
     * 主路径由 {@code HttpURLConnection} 代为拆帧，这条退化路径只能自己来。
     */
    static String decodeBody(byte[] all, int bodyStart, String header) throws IOException {
        byte[] raw = new byte[Math.max(0, all.length - bodyStart)];
        System.arraycopy(all, Math.min(bodyStart, all.length), raw, 0, raw.length);
        String te = headerValue(header, "Transfer-Encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            return dechunk(raw);
        }
        String cl = headerValue(header, "Content-Length");
        if (cl != null) {
            try {
                int n = Integer.parseInt(cl.trim());
                if (n >= 0 && n <= raw.length) {
                    return new String(raw, 0, n, UTF8);
                }
            } catch (NumberFormatException ignored) {
                // 长度字段不可信就按整体取
            }
        }
        return new String(raw, 0, raw.length, UTF8);
    }

    /** 拆 chunked（{@code <hex 长度>CRLF 数据 CRLF … 0CRLF}）；读到不完整处即停，能拿多少算多少。 */
    static String dechunk(byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (true) {
            int eol = indexOfCrlf(b, i);
            if (eol < 0) {
                break;
            }
            String line = new String(b, i, eol - i, UTF8).trim();
            int semi = line.indexOf(';');
            if (semi >= 0) {
                line = line.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(line.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("chunk 长度无法解析: \"" + line + "\"");
            }
            i = eol + 2;
            if (size == 0) {
                break;
            }
            int n = Math.min(size, b.length - i);
            if (n > 0) {
                out.write(b, i, n);
            }
            i += size + 2;
        }
        return new String(out.toByteArray(), UTF8);
    }

    /** 取头部字段值（不区分大小写；头部块已按 CRLF 分行）。 */
    static String headerValue(String header, String name) {
        String[] lines = header.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(c + 1).trim();
            }
        }
        return null;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败无影响
        }
        return new String(buf.toByteArray(), UTF8);
    }

    // ===== 解析（纯逻辑，可离机验证）=====

    /**
     * 解析 207 响应体。
     *
     * <p>按 <b>localName</b> 匹配（忽略 {@code D:}/{@code d:}/{@code lp1:} 等前缀与命名空间差异），
     * 剔除「自身」那条 response（按解码后的路径比对，能同时容忍以 {@code /} 结尾与不结尾两种写法）。
     */
    static List<Child> parse(String xml, String requestUrl) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(UTF8)));
        } catch (Exception e) {
            throw new IOException("207 响应解析失败: " + e + "（前 160 字: " + brief(xml) + "）", e);
        }
        if (doc.getDocumentElement() == null) {
            throw new IOException("207 响应为空文档");
        }
        String selfPath = trimEndSlash(pathOf(Uri.decode(requestUrl)));
        List<Child> out = new ArrayList<>();
        List<Element> responses = descendants(doc.getDocumentElement(), "response");
        for (Element resp : responses) {
            Element href = firstDescendant(resp, "href");
            if (href == null) {
                continue;
            }
            String decoded = Uri.decode(text(href));
            if (trimEndSlash(pathOf(decoded)).equals(selfPath)) {
                continue;
            }
            String name = lastSegment(decoded);
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            boolean dir = firstDescendant(resp, "collection") != null;
            long size = -1L;
            Element len = firstDescendant(resp, "getcontentlength");
            if (len != null) {
                try {
                    size = Long.parseLong(text(len).trim());
                } catch (NumberFormatException ignored) {
                    size = -1L;
                }
            }
            out.add(new Child(name, dir, size));
        }
        note("207 解析: response " + responses.size() + " 条 → 子项 " + out.size() + " 条（已剔除自身）");
        return out;
    }

    /** 递归收集 localName 匹配的元素（大小写不敏感，兼容非规范服务器）。 */
    static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        collect(root, localName, out);
        return out;
    }

    private static void collect(Element el, String localName, List<Element> out) {
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (localName.equalsIgnoreCase(localNameOf(e))) {
                out.add(e);
            }
            collect(e, localName, out);
        }
    }

    static Element firstDescendant(Element root, String localName) {
        if (localName.equalsIgnoreCase(localNameOf(root))) {
            return root;
        }
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element found = firstDescendant((Element) n, localName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static String localNameOf(Node n) {
        String ln = n.getLocalName();
        if (ln != null) {
            return ln;
        }
        String nn = n.getNodeName();
        int i = nn.indexOf(':');
        return i >= 0 ? nn.substring(i + 1) : nn;
    }

    static String text(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    /** 取 URL/路径部分（去掉 scheme+host），用于「自身」判定。 */
    static String pathOf(String s) {
        if (s == null) {
            return "";
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            return slash < 0 ? "/" : s.substring(slash);
        }
        return s;
    }

    /** 末段名（去掉结尾 {@code /} 后取最后一个 {@code /} 之后的部分）。 */
    static String lastSegment(String s) {
        String t = trimEndSlash(s == null ? "" : s);
        int slash = t.lastIndexOf('/');
        return slash < 0 ? t : t.substring(slash + 1);
    }

    static String trimEndSlash(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 去掉 URL 里的 userinfo（{@code http://u:p@host:29798/dav} → {@code http://host:29798/dav}）。
     *
     * <p>刻意用<b>字符串手术</b>、不用 {@code new URL(rawUrl)}：真机上密码含 {@code ?} 时，
     * {@code new URL} 会把 spec 在 {@code ?} 处截断 ⇒ authority 只剩 {@code user:$WC6&$} ⇒
     * {@code MalformedURLException: invalid port: $WC6&$}（列目录全灭，见 hook信息记录.md §三.2）。
     * 本方法对任何形状都不抛，且 authority 一律按「{@code ://} 之后第一个 {@code /}」切
     * （不把 {@code ?} / {@code #} 当结束符 —— 它们可能就在 userinfo 里）。
     */
    static String stripUserInfo(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        int scheme = rawUrl.indexOf("://");
        if (scheme < 0) {
            return rawUrl;
        }
        int authStart = scheme + 3;
        int at = userInfoAt(rawUrl, authStart);
        if (at < authStart) {
            return rawUrl;
        }
        return rawUrl.substring(0, authStart) + rawUrl.substring(at + 1);
    }

    static String userInfoOf(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        int scheme = rawUrl.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        int authStart = scheme + 3;
        int at = userInfoAt(rawUrl, authStart);
        return at < authStart ? null : rawUrl.substring(authStart, at);
    }

    /** authority 段里 userinfo 与 host 的分隔 {@code @} 位置（无则 -1）。 */
    private static int userInfoAt(String url, int authStart) {
        int end = url.indexOf('/', authStart);
        return url.lastIndexOf('@', (end < 0 ? url.length() : end) - 1);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
