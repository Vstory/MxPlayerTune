package io.github.vstory.hook.mxplay;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * MX 条目对象的构造与列表组装（全部走反射，签名取自真机 dex，见知识库 hook信息记录.md）。
 *
 * <p>签名事实（1.93.4 / versionCode 2001002584，MT 工作区 yrz2qi0l 实测）：
 * <ul>
 *   <li>{@code RemoteEntry.<init>(RemoteEntry parent, String name, String[] subNames, int type)}
 *       —— 路径由构造器内部算：{@code parent.path [+"/"] + Uri.encode(name)}，
 *       因此传进去的 name 必须是<b>已解码</b>的显示名（否则会二次编码出 {@code %25}）</li>
 *   <li>{@code RemoteEntry}<b>只</b>声明 name/path/subNames/subPaths/type；服务器与凭据字段继承自
 *       {@code SmbServerEntry}（private），需沿父类链找</li>
 *   <li>类型常量取自 {@code RemoteEntry$FileType}：FILE=0 / DIRECTORY=1 / LINK=2 / CARD=0x31</li>
 * </ul>
 */
final class RemoteEntries {

    private static final int TYPE_FILE = 0;
    private static final int TYPE_DIRECTORY = 1;

    /** 内置兜底扩展名表（MX 自身的 {@code MediaExtensions} 不可用时使用）。 */
    private static final String[] MEDIA_EXT = {
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "m4v", "3gp", "mpg", "mpeg",
            "rmvb", "rm", "asf", "vob", "ogv", "divx", "f4v", "mts", "m2v", "dat",
            "mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "wma", "ape", "alac", "amr", "mid",
            "srt", "ass", "ssa", "sub", "idx", "vtt", "smi", "ttml", "sup", "mpl", "m3u8",
    };

    private RemoteEntries() {
    }

    // ===== RemoteEntry 构造（反射，缓存）=====

    private static Constructor<?> sCtor;
    private static Field sPathField;
    private static Field sTypeField;

    /** 绑定 RemoteEntry 类（缓存构造器与字段）。返回是否可用。 */
    static boolean bind(Class<?> remoteEntryCls) {
        if (remoteEntryCls == null) {
            return false;
        }
        if (sCtor != null && sCtor.getDeclaringClass() == remoteEntryCls) {
            return true;
        }
        try {
            sCtor = remoteEntryCls.getDeclaredConstructor(
                    remoteEntryCls, String.class, String[].class, int.class);
            sCtor.setAccessible(true);
            sPathField = remoteEntryCls.getField("path");
            sTypeField = remoteEntryCls.getField("type");
            return true;
        } catch (Throwable t) {
            sCtor = null;
            HookTargets.diag("RemoteEntry 构造器绑定失败: " + t);
            return false;
        }
    }

    /** 读条目的 path / type（诊断与判定用）。 */
    static String pathOf(Object entry) {
        return readString(entry, sPathField, "path");
    }

    static int typeOf(Object entry) {
        if (entry == null || sTypeField == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return sTypeField.getInt(entry);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static String readString(Object obj, Field f, String name) {
        if (obj == null) {
            return null;
        }
        try {
            if (f != null) {
                Object v = f.get(obj);
                return v == null ? null : v.toString();
            }
            return readOwnField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿父类链读字段（child entry 的服务器/凭据字段继承自 SmbServerEntry）。 */
    static String readOwnField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        for (Class<?> k = obj.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                return v == null ? null : v.toString();
            } catch (Throwable ignored) {
                // 继续上溯
            }
        }
        return null;
    }

    static int readIntField(Object obj, String name, int def) {
        if (obj == null) {
            return def;
        }
        for (Class<?> k = obj.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (Throwable ignored) {
                // 继续上溯
            }
        }
        return def;
    }

    // ===== rootPath 归一（hook ① 的核心，纯函数便于离机验证）=====

    /**
     * 把 MX 自己算出的 rootPath 归一。
     *
     * <p>{@code buildRootPath()} 里 {@code "smb://"} 是硬编码前缀 ⇒ 用户填
     * {@code http://host:port/dav} 时得到 {@code smb://http://host:port/dav}
     * （见 hook信息记录.md）。本方法只对该形状做手术，SMB 条目原样返回 ⇒
     * 不会改变任何既有 SMB 行为。
     */
    static String normalizeRootPath(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.regionMatches(true, 0, "smb://", 0, 6)) {
            String rest = raw.substring(6);
            if (HookTargets.isHttpUrl(rest)) {
                return rest;
            }
        }
        return raw;
    }

    /**
     * 读侧归一 + **凭据内联**（hook ① 的生产入口；user/pass 传 MX 落库的原值，空 = 不带）。
     *
     * <p>为什么播放链路必须把凭据写进 URL（真机 dex 事实，见 hook信息记录.md §三）：
     * <ol>
     *   <li>播放：{@code ActivityScreen.T3} 把「URI → 选项」表存进静态字段，播放前
     *       {@code l.I} 会调 {@code wJ.c(uri)} —— 它对 {@code uri.getUserInfo()} 做
     *       {@code "Basic "+base64} 并作为 {@code Authorization} 选项交给 FFPlayer。
     *       <b>URL 里没有 userinfo ⇒ 播放请求不带 Authorization ⇒ 服务端 401 ⇒ 放不了</b></li>
     *   <li>缩略图：MX 的 {@code SmbUtil.b(entry)} 本来就是「scheme://[域;][用户:密码@]主机+路径」
     *       这一形状（它自己就把凭据拼进 URL），但用 {@code getHost()/getPath()} 重建 ⇒ 丢端口</li>
     * </ol>
     * 两种形状都要求凭据出现在 URL 的 userinfo 里。SMB 条目与非 http(s) 一律原样（SMB 不受影响）。
     *
     * <p><b>userinfo 必须是「落库原值（编码形态）」而不是解码后的真凭据</b>（真机事故，见
     * hook信息记录.md §三.2）：密码里有 {@code ?} / {@code #} 时，把真凭据直接写进 URL 会让
     * 解析器在 authority <b>中途</b>截断 —— 真机日志原样是
     * {@code MalformedURLException: invalid port: $WC6&$}（{@code $WC6&$} 就是密码里
     * {@code ?} 之前那一段）⇒ 列目录全灭。而 MX 的消费者本来认的就是编码形态：
     * {@code android.net.Uri#getUserInfo()} <b>会解码</b>（官方文档 "decoded"）
     * ⇒ 编码形态进 URL、真凭据进 Authorization 头。
     */
    static String normalizeRootPath(String raw, String user, String pass) {
        String s = normalizeRootPath(raw);
        if (s == null || !HookTargets.isHttpUrl(s)) {
            return s;
        }
        return withUserInfo(s, user, pass);
    }

    /**
     * 把凭据作为 userinfo 内联进 http(s) URL：{@code http://用户:密码@host:port/路径}。
     *
     * <p>传 MX 落库的原值（{@code ServerEditDialog} 存库前已用 {@code Uri.encode} 过，见
     * {@link #userInfoSafe}）—— 与 MX 自己 {@code SmbUtil.b} 拼进缩略图 URL 的是同一种形态。
     */
    static String withUserInfo(String url, String user, String pass) {
        if (url == null || user == null || user.isEmpty()) {
            return url;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int authStart = scheme + 3;
        if (url.lastIndexOf('@', authorityEnd(url, authStart) - 1) >= authStart) {
            return url;   // 用户自己就把凭据写进「服务器」栏了 ⇒ 不叠加
        }
        return url.substring(0, authStart) + userInfoSafe(user) + ":" + userInfoSafe(pass) + "@"
                + url.substring(authStart);
    }

    /**
     * authority 段结束位置（{@code ://} 之后第一个 {@code /}；没有则到串尾）。
     *
     * <p>刻意<b>不</b>把 {@code ?} / {@code #} 当结束符：真凭据形态下它们可能就落在 userinfo 里，
     * 按 {@code ?} 截断会漏掉后面的 {@code @} —— 这正是 {@code java.net.URL} 在真机上翻车的地方
     * （见 {@link #withUserInfo}）。
     */
    static int authorityEnd(String url, int authStart) {
        int end = url.indexOf('/', authStart);
        return end < 0 ? url.length() : end;
    }

    /**
     * userinfo 取值兜底：把会破坏 URL 结构的字符补成百分号编码。
     *
     * <p>正常输入是 MX 落库的 {@code Uri.encode} 结果（无裸 {@code ?} / {@code #} / 空格 / {@code +}），
     * 本方法只做两件事：<b>保留合法</b> {@code %XX}（绝不二次编码）、<b>补编码</b>裸的结构字符。
     * 于是「落库值是编码形态」（真机现状）与「落库值是原值」（万一 MX 改）两种输入都能得到
     * 可解析的 URL，且 {@code Uri#getUserInfo()} 解码后都等于真凭据。
     *
     * <p>安全集按 RFC 3986 userinfo 取：unreserved + sub-delims（{@code : @ / ? # + %}
     * 与空白、控制字符、非 ASCII 一律编码）。
     */
    static String userInfoSafe(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            int width = Character.charCount(cp);
            if (cp == '%' && width == 1 && i + 2 < v.length()
                    && isHex(v.charAt(i + 1)) && isHex(v.charAt(i + 2))) {
                sb.append('%');   // 已是 %XX ⇒ 原样保留
            } else if (isUserInfoSafe(cp)) {
                sb.appendCodePoint(cp);
            } else {
                for (byte b : new String(Character.toChars(cp))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    sb.append('%')
                            .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
            i += width;
        }
        return sb.toString();
    }

    private static boolean isUserInfoSafe(int cp) {
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')) {
            return true;
        }
        return "-._~!$&'()*,;=".indexOf(cp) >= 0;
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * 日志脱敏：userinfo 换成 {@code ***:***}。
     *
     * <p>凭据内联后 {@code entry.path} 里就有密码，而 hook ① 的「归一」行、hook ② 的
     * {@code path=} / 返回条目列表都是日志热点 ⇒ 打日志前一律过这里（MX 自己也不是没干过：
     * 它的缩略图 URL 同样带密码）。
     */
    static String maskUserInfo(String url) {
        if (url == null) {
            return null;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int authStart = scheme + 3;
        int slash = url.indexOf('/', authStart);
        int at = url.indexOf('@', authStart);
        if (at < 0 || (slash >= 0 && at > slash)) {
            return url;
        }
        return url.substring(0, authStart) + "***:***" + url.substring(at);
    }

    /**
     * 列目录日志里「实际请求 URL」那一段：与条目 path 相同则省略，不同才接上 —— 且**必须脱敏**。
     *
     * <p>为什么单独一个方法（真机事故，2026-09-29）：条目 {@code path} 一路都过了
     * {@link #maskUserInfo}，唯独 {@code req=} 那一处直接拼了原始 {@code reqUrl} ⇒ 日志里落下
     * {@code req=http://用户:编码后的密码@host:29798/dav/…} —— 与 README「本模块日志一律脱敏成
     * {@code ***:***}」的承诺不符（该日志是要贴给别人看的）。脱敏写进这个方法是一种结构约束：
     * 调用点拿不到「不脱敏」的写法 ⇒ 接缝处不会再漏第二次。
     */
    static String reqSuffix(String path, String reqUrl) {
        if (reqUrl == null || reqUrl.equals(path)) {
            return "";
        }
        return " → req=" + maskUserInfo(reqUrl);
    }

    // ===== 列表规划（纯逻辑，可离机验证）=====

    /** 规划项：显示名 + 同词干并列名 + MX 类型。 */
    static final class Plan {
        final String name;
        final String[] subNames;
        final int type;

        Plan(String name, String[] subNames, int type) {
            this.name = name;
            this.subNames = subNames;
            this.type = type;
        }

        @Override
        public String toString() {
            return name + "(type=" + type + (subNames == null ? "" : ",sub=" + subNames.length) + ")";
        }
    }

    /** 媒体判定回调（生产实现见 {@link MediaFilter}）。 */
    interface MediaJudge {
        boolean isMedia(String name);
    }

    /**
     * 把 DAV 子项整理成 MX 期望的列表形态：目录在前；文件按媒体过滤；
     * 同词干的文件（如 {@code movie.mp4} + {@code movie.srt}）互相写入 {@code subNames}
     * —— 与 MX 自身 SMB 路径的 subNames 语义一致（供 {@code getSubUris()} 用）。
     */
    static List<Plan> plan(List<WebDavClient.Child> children, MediaJudge judge) {
        List<WebDavClient.Child> dirs = new ArrayList<>();
        List<WebDavClient.Child> files = new ArrayList<>();
        for (WebDavClient.Child c : children) {
            if (c.dir) {
                dirs.add(c);
            } else if (judge == null || judge.isMedia(c.name)) {
                files.add(c);
            }
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (WebDavClient.Child c : files) {
            String stem = stemOf(c.name);
            List<String> g = groups.get(stem);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(stem, g);
            }
            g.add(c.name);
        }
        List<Plan> out = new ArrayList<>(dirs.size() + files.size());
        for (WebDavClient.Child c : dirs) {
            out.add(new Plan(c.name, null, TYPE_DIRECTORY));
        }
        for (WebDavClient.Child c : files) {
            List<String> g = groups.get(stemOf(c.name));
            out.add(new Plan(c.name, (g != null && g.size() > 1) ? g.toArray(new String[0]) : null, TYPE_FILE));
        }
        return out;
    }

    /**
     * 请求 URL 归一：目录（type=1）必须以 {@code /} 结尾。
     *
     * <p>为什么必须：条目的 path 由构造器拼成 {@code parent.path + "/" + name}，<b>不带</b>结尾斜杠；
     * 而多数 WebDAV 服务端对「集合路径缺结尾斜杠」会回 301 或按非集合处理 ⇒ 列目录直接失败。
     * 所以请求前按条目类型补斜杠（文件不减不加）。
     */
    static String requestUrl(String path, int type) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (type == TYPE_DIRECTORY && !path.endsWith("/")) {
            return path + "/";
        }
        return path;
    }

    /** 词干（去掉最后一个扩展名，小写）。 */
    static String stemOf(String name) {
        String n = name == null ? "" : name;
        int i = n.lastIndexOf('.');
        return (i > 0 ? n.substring(0, i) : n).toLowerCase(Locale.ROOT);
    }

    /** 造条目列表（返回 {@code ArrayList} —— 与被 hook 方法的返回类型一致）。 */
    static ArrayList<Object> build(Object parent, List<WebDavClient.Child> children, MediaJudge judge)
            throws Exception {
        List<Plan> plans = plan(children, judge);
        ArrayList<Object> out = new ArrayList<>(plans.size());
        for (Plan p : plans) {
            if (sCtor == null) {
                throw new IllegalStateException("RemoteEntry 构造器未绑定");
            }
            out.add(sCtor.newInstance(parent, p.name, p.subNames, p.type));
        }
        return out;
    }

    // ===== 媒体过滤器 =====

    /**
     * 媒体过滤：优先用 MX 自己的 {@code com.mxtech.media.MediaExtensions}
     * （{@code w()} 取实例 → {@code i(String)} 判媒体 → {@code close()} 归还），
     * 与 SMB 路径同判据；反射失败则退回内置扩展名表。
     */
    static final class MediaFilter implements MediaJudge {
        private final Object impl;
        private final Method isMedia;
        private final Method close;
        private final String kind;

        private MediaFilter(Object impl, Method isMedia, Method close, String kind) {
            this.impl = impl;
            this.isMedia = isMedia;
            this.close = close;
            this.kind = kind;
        }

        static MediaFilter open(ClassLoader cl) {
            try {
                Class<?> me = Class.forName("com.mxtech.media.MediaExtensions", false, cl);
                Method w = me.getDeclaredMethod("w");
                w.setAccessible(true);
                Method i = me.getDeclaredMethod("i", String.class);
                i.setAccessible(true);
                Method c = me.getDeclaredMethod("close");
                c.setAccessible(true);
                Object impl = w.invoke(null);
                if (impl == null) {
                    throw new IllegalStateException("w() 返回 null");
                }
                return new MediaFilter(impl, i, c, "MX MediaExtensions");
            } catch (Throwable t) {
                return new MediaFilter(null, null, null,
                        "内置扩展名表（MediaExtensions 不可用: " + t.getClass().getSimpleName() + "）");
            }
        }

        String kind() {
            return kind;
        }

        @Override
        public boolean isMedia(String name) {
            if (impl != null) {
                try {
                    Object r = isMedia.invoke(impl, name);
                    if (r instanceof Boolean) {
                        return (Boolean) r;
                    }
                } catch (Throwable ignored) {
                    // 反射调用失败 → 退回内置表
                }
            }
            return byExtension(name);
        }

        void close() {
            if (impl != null && close != null) {
                try {
                    close.invoke(impl);
                } catch (Throwable ignored) {
                    // 归还失败无影响
                }
            }
        }

        static boolean byExtension(String name) {
            int i = (name == null ? "" : name).lastIndexOf('.');
            if (i < 0 || i == name.length() - 1) {
                return false;
            }
            String ext = name.substring(i + 1).toLowerCase(Locale.ROOT);
            for (String e : MEDIA_EXT) {
                if (e.equals(ext)) {
                    return true;
                }
            }
            return false;
        }
    }
}
