package io.github.vstory.hook.mxplay;

import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * WebDAV 客户端：一次 {@code PROPFIND Depth: 1} 列出目录。
 *
 * <p><b>传输层为什么是 OkHttp</b>（不是随手选的，换掉它请先读完这段）：
 * 平台自带的 {@code HttpURLConnection.setRequestMethod} 有<b>动词白名单</b>，PROPFIND 当场被拒
 * （{@code ProtocolException: Invalid HTTP method: PROPFIND}，离机 JDK 与 Android 同款行为）。
 * 绕过它只能<b>反射写 {@code method} 字段</b>，而 Android 的 {@code HttpsURLConnection} 是
 * <b>委托壳</b>（{@code HttpsURLConnectionImpl} 持有 delegate）⇒ 写壳不生效、请求以 {@code POST}
 * 发出 ⇒ 服务端 501（真机事故：<b>http 能列目录、https 全灭</b>，见 hook信息记录.md §三.3）。
 * OkHttp 没有这道白名单（{@code PROPFIND} 是普通动词），委托壳问题从构造上不存在，
 * 且自带 chunked 拆帧 / 连接池 / 自签 TLS 配置 ⇒ 反射、手写 HTTP/1.1、手写 chunked 拆帧、
 * 501 自检重试<b>全部删除</b>。
 *
 * <p>依赖说明（刻意选择，勿随手换）：
 * <ul>
 *   <li>依赖 {@code com.squareup.okhttp3:okhttp:3.14.9}：<b>纯 Java</b>，dex 实测 441 KB
 *       （okhttp+okio）；4.12.0 是 Kotlin 编译，okhttp+okio-jvm 单独就 1.08 MB（2.56 倍），
 *       真机包实测增量 3.14.9 为 <b>+361 KB</b>。
 *       <b>注意别照抄网上那条「4.x 会拖 kotlin-stdlib」的说法</b>：本模块的依赖树里
 *       {@code kotlin-stdlib} <b>本来就有</b>（AGP 带进来的，改造前的包里就有 {@code kotlin/} 资源），
 *       所以 4.x 的代价是「多背 ~1.08 MB 的 OkHttp 本体」，不是「多背 3 MB 全家桶」——
 *       但结论不变：这一层只用来发一个 PROPFIND，没有理由背 1 MB</li>
 *   <li>XML 走 {@code javax.xml.parsers}（DOM）而非 {@code android.util.Xml}：
 *       前者在 Android 与宿主 JVM 上都存在 ⇒ 解析层能离机抓错验证（本项目规矩：写完必做抓错验证）</li>
 *   <li>百分号解码用 {@code android.net.Uri.decode}：与 {@code RemoteEntry} 构造器内部的
 *       {@code Uri.encode} 同源，能保证「解码一次 → 再编码一次」正好还原，不产生 {@code %25}</li>
 * </ul>
 *
 * <p>凭据：绝不写进 URL（URL 里的 userinfo 会被剥掉后单独用于 Authorization 头）——
 * 因为 MX 的 {@code RemoteEntry.getSecurityPath()} 见到 {@code @} 会拼上 {@code smb://} 前缀。
 */
final class WebDavClient {

    /** 与 MX 自身 SMB 超时同量级（{@code SMB2Client.setTimeout(10000)}）。 */
    static final int TIMEOUT_MS = 10_000;

    /**
     * 抽帧（{@link RangeSource}）那一路的**读**超时：刻意比列目录宽得多。
     *
     * <p>真机实测（2026-09-29，直打本机 CloudDrive，同一个 338 MB 的 mp4）：
     * 顺序区读 64 KiB 只要 0.1–0.7 s，但<b>文件中间区域</b>的一次随机读要 <b>~10.07 s</b>
     * （上云服务端要先去上游 seek）。用列目录那条 10 s 超时，抽取器一次深跳就必然超时
     * ⇒ 又变回「缩略图空白且什么都不报」。
     */
    static final int FETCH_READ_TIMEOUT_MS = 30_000;

    /** PROPFIND 请求体：只要 resourcetype（判目录）与 getcontentlength。 */
    static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                    + "<D:resourcetype/><D:getcontentlength/>"
                    + "</D:prop></D:propfind>";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 列目录的动词（WebDAV 唯一一个）。平台客户端不认它，OkHttp 认。 */
    private static final String METHOD_PROPFIND = "PROPFIND";

    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");

    /** 诊断流水（调用方打印后 {@link #resetNotes()}），记录「走的哪条请求路径」等。 */
    static final StringBuilder NOTES = new StringBuilder();

    /** 共享客户端（懒建）：连接池与线程池因此有界，不在宿主进程里反复新建。 */
    private static OkHttpClient sClient;

    /** 抽帧取流专用的共享客户端（同配置，只把读超时放宽；见 {@link #FETCH_READ_TIMEOUT_MS}）。 */
    private static OkHttpClient sFetchClient;

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

    // ===== 请求（OkHttp：动词无白名单、chunked 透明解、连接池复用）=====

    private static String propfind(String rawUrl, String user, String pass) throws IOException {
        String url = stripUserInfo(rawUrl);
        String auth = authOf(rawUrl, user, pass);
        Request.Builder rb = new Request.Builder()
                .url(url)
                .method(METHOD_PROPFIND, RequestBody.create(XML, PROPFIND_BODY))
                .header("Depth", "1")
                .header("User-Agent", "MxPlayerTune");
        if (auth != null) {
            rb.header("Authorization", auth);
        }
        note("请求路径: OkHttp（动词无白名单 ⇒ 无需反射；chunked 由库拆帧）");
        Response resp = null;
        try {
            resp = client().newCall(rb.build()).execute();
            int code = resp.code();
            String text = resp.body() == null ? "" : resp.body().string();
            if (code != 207 && code != 200) {
                throw new IOException("PROPFIND " + url + " → HTTP " + code + " " + brief(text));
            }
            return text;
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    /**
     * 共享客户端（懒建、加锁）。
     *
     * <p>固定 {@code HTTP_1_1}：目标是自建 WebDAV（CloudDrive / Alist / nginx / 群晖），
     * 不需要 h2 协商 —— 少一类与本地服务端的协议意外（真机服务端是 CloudDrive，
     * 所有 207 都是 HTTP/1.1 + chunked 回的）。
     */
    private static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = build(TIMEOUT_MS);
        }
        return sClient;
    }

    /**
     * 列目录用的共享客户端（可见入口：抽帧与列目录必须是**同一套** TLS 语义
     * —— 同一个 socket 工厂与 trustManager，否则会出现「能列目录、能取图，就是缩略图空白」）。
     */
    static OkHttpClient httpClient() {
        return client();
    }

    /**
     * 抽帧分片取流用的客户端（懒建、加锁）：配置与 {@link #client()} 完全一致，只有**读超时更宽**。
     *
     * <p>为什么不共用一条：列目录想要「快到点就报错」（与 MX 的 SMB 超时同量级），而抽帧的
     * 一次深跳随机读在真机上实测要 ~10 s ⇒ 同一条超时会把抽帧打成「永远超时」。
     * 分开成两条，两边的取舍各自成立，也从构造上避免「为了抽帧把列目录也拖成 30 s」。
     */
    static synchronized OkHttpClient fetchClient() {
        if (sFetchClient == null) {
            sFetchClient = build(FETCH_READ_TIMEOUT_MS);
        }
        return sFetchClient;
    }

    /** 建客户端（两条共享客户端只差读超时这一个参数：TLS 放宽与协议版本完全一致）。 */
    private static OkHttpClient build(long readTimeoutMs) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1));
        SSLSocketFactory f = relaxFactory();
        if (f != null) {
            // OkHttp 要求 socket 工厂与 trustManager 成对传入（它要拿 trustManager 做主机名校验）
            b.sslSocketFactory(f, TRUST_ALL);
            b.hostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                @Override
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
            note("https: 已放宽证书校验（自签名 / 私有 CA 可用；仅本客户端）");
        }
        return b.build();
    }

    /**
     * 放宽 https 证书校验（**仅本模块自己的请求用**）。
     *
     * <p>为什么需要：自建 WebDAV（群晖 / Alist / nginx 自签 / 私有 CA）几乎都不是受信证书，
     * 默认校验下 {@code PROPFIND} 直接 {@code SSLHandshakeException: Trust anchor for
     * certification path not found} ⇒ 列目录全灭（真机日志原样如此；离机对照组同样必须失败）。
     *
     * <p>纪律：只挂到<b>本模块自己的客户端</b>上，绝不碰
     * {@code HttpsURLConnection.setDefaultSSLSocketFactory} —— 那会把整个 MX 进程的证书校验一起废掉。
     * 宽松校验初始化失败时返回 null，调用方退回严格校验。
     *
     * <p>范围限制：只影响「浏览/列目录」。播放与缩略图走 MX 自己的取流（FFmpeg / 图片加载器），
     * 证书校验在它们那边（本模块只管得到 {@code java.net} 的取流）。
     */
    private static synchronized SSLSocketFactory relaxFactory() {
        if (sRelaxFactory == null) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{TRUST_ALL}, new java.security.SecureRandom());
                sRelaxFactory = ctx.getSocketFactory();
            } catch (Throwable t) {
                note("https 宽松校验初始化失败（" + t + "）→ 退回严格校验");
                return null;
            }
        }
        return sRelaxFactory;
    }

    /** 宽松校验用的 trustManager（与 {@link #relaxFactory()} 成对交给 OkHttp）。 */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
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
    };

    /**
     * 本方是否真的启用了宽松证书校验（诊断/验证用）。
     *
     * <p>为什么不直接看日志：那条「已放宽证书校验」在<b>建客户端时只打一次</b>
     * （客户端是共享的），所以同一进程里第二次列目录就看不到了。要判「为什么自签也能连上」，
     * 判这个属性比判日志行可靠。
     */
    static boolean tlsRelaxed() {
        return relaxFactory() != null;
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
     * <p>动机（真机事实，见 hook信息记录.md §三.4）：缩略图/图片走宿主的图片加载器
     * （{@code hr0.a} → {@code LVL} → {@code MxImageDownloader}，UIL 子类），它的 http 取流是
     * <b>裸</b> {@code HttpURLConnection}，<b>不</b>把 URL 的 userinfo 变成鉴权头
     * ⇒ 服务端 401 ⇒ 缩略图空白且无报错；
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
     * <p>注意：本方法<b>只管鉴权头</b>，不碰 TLS —— 宿主自己在自签 https 上的证书校验
     * 不在本模块的处置范围内（要动它属于另一个决定，见 CHANGELOG 遗留项）。
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

    /** 截断成一行（进日志前用；异常消息里带状态码与响应体开头，见真机日志格式）。 */
    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
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
