package io.github.vstory.hook.mxplay;

import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * <p>请求策略：优先 {@code HttpURLConnection}（自带 TLS / 重定向 / 连接池），
 * 但其 {@code setRequestMethod} 只接受标准方法、会拒绝 PROPFIND ⇒ 用反射写 {@code method} 字段；
 * 若反射也失败，退化为手写 HTTP/1.1（原始 socket / SSLSocket）。两条路径都走 {@code Connection: close}。
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
            // 必须兜住：JDK 9+ 的模块封装会让 setAccessible 抛 InaccessibleObjectException（RuntimeException），
            // 漏了它就等于没有退化路径 —— 直接崩在 hook 里。该异常类在 Android 上不存在，故不能按类名捕获。
            note("设置 method 失败（" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "）→ 退化为手写 HTTP/1.1");
            return viaRawSocket(url, auth, body);
        }
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
    private static void relaxTls(HttpURLConnection conn) {
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

    static String basicAuth(String user, String pass) {
        String cred = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(cred.getBytes(UTF8));
    }

    /** 主路径：HttpURLConnection（反射写 method 字段，绕过 setRequestMethod 的白名单校验）。 */
    static String viaHttpConnection(String url, String auth, byte[] body) throws IOException,
            NoSuchFieldException, IllegalAccessException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
            f.setAccessible(true);
            f.set(conn, "PROPFIND");
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
            String bodyText = new String(all, split + 4, all.length - split - 4, UTF8);
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

    static String stripUserInfo(String rawUrl) throws IOException {
        URL u = new URL(rawUrl);
        if (u.getUserInfo() == null) {
            return rawUrl;
        }
        String port = u.getPort() > 0 ? ":" + u.getPort() : "";
        return u.getProtocol() + "://" + u.getHost() + port + u.getFile();
    }

    static String userInfoOf(String rawUrl) {
        try {
            return new URL(rawUrl).getUserInfo();
        } catch (Exception e) {
            return null;
        }
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
