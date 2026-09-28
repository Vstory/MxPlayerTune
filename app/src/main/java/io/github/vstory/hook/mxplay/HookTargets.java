package io.github.vstory.hook.mxplay;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;

/**
 * 目标定位层：MX Player 混淆名随版本漂移，这里用「结构判据」定位，名字只当快路径。
 *
 * <p>已确认事实（MX Player 1.93.4 / versionCode 2001002584）：
 * <ul>
 *   <li>{@code com.mxtech.videoplayer.smb.bean.RemoteEntry} 与 {@code SmbServerEntry}
 *       <b>类名未被混淆</b>（真名，跨版本稳定）—— 可作定位锚点</li>
 *   <li>候选混淆名 {@code LYi0}（RemoteDataSource）/ {@code LHn0}（ServerDataSource）</li>
 * </ul>
 *
 * <p>定位策略（两级，宁可 no-op 也不误 hook）：
 * <ol>
 *   <li><b>快路径</b>：候选名 {@code *_NAMES} + 结构判据</li>
 *   <li><b>结构扫描</b>（仅 debug 版）：枚举 classloader 里全部类名 → 逐个套结构判据</li>
 * </ol>
 *
 * <p><b>扫描的三条硬约束</b>（缺一条就会把「没查完」误报成「不存在」）：
 * <ol>
 *   <li><b>不设比对条数上限</b> —— 上限会让结论变成「查了一半」。只设<b>时间预算</b>，
 *       超时明确报【未定】，绝不写「无命中」</li>
 *   <li><b>不按名字形状过滤</b> —— 只按「框架/三方命名空间前缀」跳过（应用自身 {@code com.mxtech.*}
 *       绝不跳过），且跳过量照数上报。任何「按名字猜目标长什么样」的过滤都是把假设当事实</li>
 *   <li><b>必须报 near-miss</b> —— 「持锚点字段但方法形态不符」的类要逐个列出。
 *       判据失手时它是唯一线索，否则只剩一句无信息的「无命中」</li>
 * </ol>
 *
 * <p>判据（{@link #DETECT_*}）只依赖类型关系，不依赖任何混淆名：
 * <ul>
 *   <li>RemoteDataSource = 持 {@code RemoteEntry}/{@code SmbServerEntry} 类型字段
 *       + 有「static + 返回 List + 唯一参数为自身」的方法</li>
 *   <li>ServerDataSource = 持 ≥2 个 {@code java.io.File} 字段（服务器列表 JSON + 其 .tmp）</li>
 * </ul>
 *
 * <p>失败必返回 null，调用方 no-op 放行（保证 MX Player 原功能不受影响）。
 */
final class HookTargets {

    private HookTargets() {
    }

    /** 未混淆的条目 bean（真名）。 */
    static final String CLS_REMOTE_ENTRY = "com.mxtech.videoplayer.smb.bean.RemoteEntry";
    /** 未混淆的服务器 bean（真名）。 */
    static final String CLS_SMB_SERVER_ENTRY = "com.mxtech.videoplayer.smb.bean.SmbServerEntry";

    /** 锚点所在包（triage 用：列出该包下 classloader 可见的类）。 */
    static final String ANCHOR_PKG = "com.mxtech.videoplayer.smb";

    /**
     * RemoteDataSource 候选混淆名（1.93.4 真机实测 = {@code Yi0}）。升级后在此追加新候选。
     *
     * <p>⚠️ **类名不含描述符前缀**：smali 里写 {@code LYi0;}，但类名只是 {@code Yi0}
     * —— {@code L} 与 {@code ;} 是 dex 类型描述符语法，不是名字的一部分。
     * 曾把 {@code LYi0} 当类名写入本表 ⇒ {@code Class.forName} 永远抛 ClassNotFoundException
     * （真机日志那行「候选名存在性 = false」是唯一提示）。{@link #normalizeName} 已做容错。
     */
    private static final String[] REMOTE_DS_NAMES = {"Yi0"};
    /** ServerDataSource 候选混淆名（1.93.4 真机实测 = {@code Hn0}）。升级后在此追加新候选。 */
    private static final String[] SERVER_DS_NAMES = {"Hn0"};

    private static final String[] REMOTE_DS_METHOD_NAMES = {"a"};
    private static final String[] SERVER_DS_METHOD_NAMES = {"a"};

    /**
     * 缩略图 URL 构造器候选混淆名（1.93.4 真机实测 = {@code hr0}，source 名 SmbUtil.java）。
     *
     * <p>为什么需要它：MX 自己重建缩略图 URL 时用 {@code uri.getHost() + uri.getPath()}
     * ⇒ <b>端口被丢掉</b>（非 80/443 的 WebDAV 必然拿不到缩略图）。结构判据见
     * {@link #smbUtilMismatch}：纯静态工具类（无字段）+ 一个 {@code (RemoteEntry)String} +
     * 一个 {@code (ImageView,String,int,int,Drawable)V}。
     */
    private static final String[] SMB_UTIL_NAMES = {"hr0"};

    /** SmbUtil 判据里用到的两个参数类型名（按名字比对 ⇒ 无需 import Android 类，离机也能验证）。 */
    private static final String CLS_IMAGE_VIEW = "android.widget.ImageView";
    private static final String CLS_DRAWABLE = "android.graphics.drawable.Drawable";

    /**
     * 扫描跳过前缀：框架 / 三方命名空间（只为省时间）。
     *
     * <p>判据要求目标持 {@code RemoteEntry} 字段 ⇒ 这些命名空间下不可能有目标，
     * 且应用自身命名空间（{@code com.mxtech.*}）与默认包短名**一律不在跳过列表内**。
     * 跳过量在每个结果行里照数上报，不静默。
     */
    private static final String[] SKIP_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "dalvik.", "libcore.",
            "android.", "androidx.", "kotlin.", "kotlinx.",
            "com.google.", "com.android.internal.", "org.apache.", "org.jetbrains.",
    };

    /** 结构扫描硬时间预算：超时即报【未定】，绝不当「不存在」。 */
    private static final long SCAN_BUDGET_MS = 30000L;

    /** near-miss 记录条数上限。 */
    private static final int NEAR_MISS_MAX = 10;

    /** 定位失败原因流水（由调用方在打印后 {@link #resetDiag()}）。 */
    static final StringBuilder DIAG = new StringBuilder();

    static void diag(String line) {
        DIAG.append("  ").append(line).append('\n');
    }

    static void resetDiag() {
        DIAG.setLength(0);
    }

    // ===== 类加载 =====

    /**
     * 把 smali / dex 类型描述符容错归一成类名：{@code LYi0;} → {@code Yi0}，
     * {@code Lcom/x/y/Z;} → {@code com.x.y.Z}。
     *
     * <p>存量教训：候选混淆名常从 smali 里抄，而 smali 的类型是 {@code L…;} 形态。
     * 归一是防这类抄写错误再次静默失效的兜底（真名不受影响）。
     */
    static String normalizeName(String raw) {
        String s = raw.trim();
        if (s.length() > 2 && s.charAt(0) == 'L' && s.endsWith(";")) {
            return s.substring(1, s.length() - 1).replace('/', '.');
        }
        return s;
    }

    /** 按名加载（initialize=false：绝不触发目标类 &lt;clinit&gt;），失败原因记入 DIAG。 */
    static Class<?> load(ClassLoader cl, String name) {
        String n = normalizeName(name);
        try {
            return Class.forName(n, false, cl);
        } catch (Throwable t) {
            diag(n + " → 取不到类: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * {@code SmbServerEntry#getRootPath()} —— 条目 path 的唯一来源，也是 hook ① 的落点。
     *
     * <p>选它而不是 {@code buildRootPath()} 的依据（真机 dex 的 xref 实测，见 hook信息记录.md）：
     * {@code rootPath} 字段<b>只</b>被 {@code getRootPath()} 读、只被 {@code buildRootPath()} 写；
     * 而 {@code getRootPath()} 只有 3 个调用点，其中就是给 {@code RemoteEntry.path} 赋值的那一个。
     * ⇒ 在<b>读侧</b>一次归一，既覆盖新建条目、也覆盖 Gson 反序列化后直接读旧值的路径，
     * 且不必重写 MX 自己的字符串拼装逻辑（反斜杠剥除、斜杠处理都交给它）。
     */
    static Method rootPathGetter(Class<?> smbServerCls, String method) {
        if (smbServerCls == null) {
            diag("hook ① 跳过: SmbServerEntry 未取到，无法定位 " + method);
            return null;
        }
        try {
            Method m = smbServerCls.getDeclaredMethod(method);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            diag("hook ① 定位失败: " + smbServerCls.getName() + "#" + method + " → " + t);
            return null;
        }
    }

    /** hook ① 目标方法名（1.93.4 未混淆）。 */
    static final String M_SMB_GET_ROOT_PATH = "getRootPath";

    /** http/https 判定（WebDAV 走这两个 scheme）。 */
    static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        return s.regionMatches(true, 0, "http://", 0, 7)
                || s.regionMatches(true, 0, "https://", 0, 8);
    }

    /** 静默按名加载（锚点类用，失败不记流水）。 */
    static Class<?> loadQuiet(ClassLoader cl, String name) {
        try {
            return Class.forName(normalizeName(name), false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 定位：快路径 =====

    /** 快路径：候选名 + 结构判据。 */
    static Class<?> remoteDataSource(ClassLoader cl, Class<?> anchor) {
        for (String name : REMOTE_DS_NAMES) {
            Class<?> c = load(cl, name);
            if (c == null) {
                continue;
            }
            String why = remoteDsMismatch(c, anchor);
            if (why == null) {
                return c;
            }
            diag(name + " → 结构不符: " + why);
        }
        return null;
    }

    /** 快路径：候选名 + 结构判据。 */
    static Class<?> serverDataSource(ClassLoader cl) {
        for (String name : SERVER_DS_NAMES) {
            Class<?> c = load(cl, name);
            if (c == null) {
                continue;
            }
            String why = serverDsMismatch(c);
            if (why == null) {
                return c;
            }
            diag(name + " → 结构不符: " + why);
        }
        return null;
    }

    // ===== 定位：SmbUtil（缩略图 URL 构造器，hook ③）=====

    /** 快路径：候选混淆名 + 结构判据。 */
    static Class<?> smbUtil(ClassLoader cl, Class<?> anchor) {
        for (String name : SMB_UTIL_NAMES) {
            Class<?> c = load(cl, name);
            if (c == null) {
                continue;
            }
            String why = smbUtilMismatch(c, anchor);
            if (why == null) {
                return c;
            }
            diag(name + " → 结构不符: " + why);
        }
        return null;
    }

    /** 结构扫描 SmbUtil：不看名字，套「纯静态工具类」判据。 */
    static Class<?> scanSmbUtil(ClassLoader cl, Class<?> anchor, List<String> names, String label) {
        if (anchor == null || names == null || names.isEmpty()) {
            return null;
        }
        for (String cand : SMB_UTIL_NAMES) {
            diag("候选名存在性: \"" + cand + "\" = " + names.contains(cand));
        }
        return scanAll(cl, names, label, new Judge() {
            @Override
            public Class<?> visit(String name, Class<?> c, List<String> nearMiss) {
                String why = smbUtilMismatch(c, anchor);
                if (why == null) {
                    return c;
                }
                // 只对「像工具类」的类报 near-miss，否则 1.5 万个类里全是噪音
                if (c.getDeclaredFields().length == 0 && nearMiss.size() < NEAR_MISS_MAX) {
                    String sig = staticMethods(c);
                    if (sig.contains(anchor.getSimpleName()) || sig.contains("ImageView")) {
                        nearMiss.add(name + " 纯静态但形态不符: " + why);
                    }
                }
                return null;
            }
        });
    }

    /** 判 SmbUtil 形态；符合返回 null，否则返回不符原因（供诊断日志）。 */
    static String smbUtilMismatch(Class<?> c, Class<?> anchor) {
        if (c == null) {
            return "类未取到";
        }
        if (anchor == null) {
            return "锚点 RemoteEntry 未取到";
        }
        if (c.getDeclaredFields().length != 0) {
            return "有 " + c.getDeclaredFields().length + " 个字段（纯静态工具类应无字段）";
        }
        Method url = null;
        Method view = null;
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getReturnType() == String.class && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == anchor) {
                url = m;
            } else if (m.getReturnType() == void.class && m.getParameterCount() == 5
                    && CLS_IMAGE_VIEW.equals(m.getParameterTypes()[0].getName())
                    && m.getParameterTypes()[1] == String.class
                    && m.getParameterTypes()[2] == int.class
                    && m.getParameterTypes()[3] == int.class
                    && CLS_DRAWABLE.equals(m.getParameterTypes()[4].getName())) {
                view = m;
            }
        }
        if (url == null) {
            return "无 static (RemoteEntry)String（static 方法: " + staticMethods(c) + "）";
        }
        if (view == null) {
            return "无 static (ImageView,String,int,int,Drawable)V（缩略图加载入口；static 方法: "
                    + staticMethods(c) + "）";
        }
        return null;
    }

    /** 取 SmbUtil 的 URL 构造方法 {@code (RemoteEntry)String}。 */
    static Method smbUtilBuildUrl(Class<?> cls) {
        if (cls == null) {
            return null;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == String.class
                    && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    // ===== 定位：结构扫描（debug 版兜底）=====

    /** 结构扫描 RemoteDataSource：遍历给定类名全集，套「持锚点字段 + 列表方法」判据。 */
    static Class<?> scanRemoteDataSource(ClassLoader cl, Class<?> anchor, List<String> names, String label) {
        if (anchor == null) {
            diag("结构扫描跳过: 锚点 " + CLS_REMOTE_ENTRY + " 未取到，无法套判据");
            return null;
        }
        if (names == null || names.isEmpty()) {
            return null;
        }
        for (String cand : REMOTE_DS_NAMES) {
            boolean in = names.contains(cand);
            diag("候选名存在性: \"" + cand + "\" = " + in
                    + (in ? "（类在 → 查判据/加载环节）" : "（不在 → 这一版的名字与常量表不同）"));
        }
        diag("锚点形态 " + anchor.getSimpleName() + ": " + describe(anchor));
        return scanAll(cl, names, label, new Judge() {
            @Override
            public Class<?> visit(String name, Class<?> c, List<String> nearMiss) {
                if (!hasAnchorField(c, anchor)) {
                    return null;
                }
                if (directoryListMethod(c, REMOTE_DS_METHOD_NAMES) != null) {
                    return c;
                }
                if (nearMiss.size() < NEAR_MISS_MAX) {
                    nearMiss.add(name + " 持锚点字段但无列表方法（字段: " + fieldKinds(c)
                            + "；static 方法: " + staticMethods(c) + "）");
                }
                return null;
            }
        });
    }

    /** 结构扫描 ServerDataSource：遍历给定类名全集，套「≥2 个 File 字段」判据。 */
    static Class<?> scanServerDataSource(ClassLoader cl, List<String> names, String label) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        for (String cand : SERVER_DS_NAMES) {
            diag("候选名存在性: \"" + cand + "\" = " + names.contains(cand));
        }
        return scanAll(cl, names, label, new Judge() {
            @Override
            public Class<?> visit(String name, Class<?> c, List<String> nearMiss) {
                int files = fileFieldCount(c);
                if (files >= 2) {
                    return c;
                }
                // near-miss：「只差一个 File 字段」是判据过严时唯一能救命的线索（别只剩一句无命中）
                if (files == 1 && nearMiss.size() < NEAR_MISS_MAX) {
                    nearMiss.add(name + " 仅 1 个 File 字段（字段: " + fieldKinds(c) + "）");
                }
                return null;
            }
        });
    }

    /** 扫描判据回调。 */
    private interface Judge {
        /** 返回命中的类；null = 继续。 */
        Class<?> visit(String name, Class<?> c, List<String> nearMiss);
    }

    /**
     * 全量扫描骨架（无条数上限，只有时间预算；超时报【未定】）。
     *
     * <p>三种结局都自述证据：命中 / 全量比完无命中 / 超时未定。
     */
    private static Class<?> scanAll(ClassLoader cl, List<String> names, String label, Judge judge) {
        int skipped = 0;
        int examined = 0;
        int loaded = 0;
        int loadFailed = 0;
        boolean timedOut = false;
        List<String> nearMiss = new ArrayList<>();
        long t0 = System.nanoTime();
        Class<?> hit = null;
        for (String n : names) {
            if (skipByPrefix(n)) {
                skipped++;
                continue;
            }
            if (System.nanoTime() - t0 > SCAN_BUDGET_MS * 1000000L) {
                timedOut = true;
                break;
            }
            examined++;
            Class<?> c = loadQuiet(cl, n);
            if (c == null) {
                loadFailed++;
                continue;
            }
            loaded++;
            hit = judge.visit(n, c, nearMiss);
            if (hit != null) {
                break;
            }
        }
        String tail = "（枚举 " + names.size() + " / 跳过框架 " + skipped + " / 比对 " + examined
                + " / 加载成功 " + loaded + " / 加载失败 " + loadFailed + " / " + ms(t0) + " ms）";
        if (hit != null) {
            diag(label + " 命中: " + hit.getName() + tail);
        } else if (timedOut) {
            diag(label + " 【未定】超时中断: 已比对 " + examined + " / 未比对 "
                    + (names.size() - skipped - examined) + tail
                    + " ⇒ 不能判定为「不存在」，需加大预算重扫");
        } else {
            diag(label + " 全量比完无命中" + tail);
        }
        for (String s : nearMiss) {
            diag(label + " near-miss: " + s);
        }
        return hit;
    }

    // ===== 结构判据（只依赖类型关系）=====

    /** 判 RemoteDataSource 形态；符合返回 null，否则返回不符原因（供诊断日志）。 */
    private static String remoteDsMismatch(Class<?> c, Class<?> anchor) {
        if (anchor == null) {
            return "锚点 RemoteEntry 未取到";
        }
        if (!hasAnchorField(c, anchor)) {
            return "无 " + anchor.getSimpleName() + " 类型字段（字段: " + fieldKinds(c) + "）";
        }
        if (directoryListMethod(c, REMOTE_DS_METHOD_NAMES) == null) {
            return "无列表方法（static + 返回 List + 唯一参数为自身）：static 方法 " + staticMethods(c);
        }
        return null;
    }

    /** 持锚点类型字段（RemoteEntry 本身，或其父类 SmbServerEntry 声明）。 */
    private static boolean hasAnchorField(Class<?> c, Class<?> anchor) {
        Class<?> serverEntry = loadQuiet(c.getClassLoader(), CLS_SMB_SERVER_ENTRY);
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == anchor || (serverEntry != null && t == serverEntry)) {
                return true;
            }
        }
        return false;
    }

    /** 判 ServerDataSource 形态；符合返回 null，否则返回不符原因。 */
    private static String serverDsMismatch(Class<?> c) {
        if (fileFieldCount(c) >= 2) {
            return null;
        }
        return "java.io.File 字段少于 2 个（字段: " + fieldKinds(c) + "）";
    }

    /**
     * 找目录列举方法：static + 返回 List（ArrayList 亦满足）+ 唯一参数为自身类型。
     *
     * <p>按结构定位而非按方法名 —— 方法名也被混淆（1.93.4 为 {@code a}）。
     */
    static Method directoryListMethod(Class<?> dsCls, String[] nameHints) {
        if (dsCls == null) {
            return null;
        }
        for (String hint : nameHints) {
            try {
                Method m = dsCls.getDeclaredMethod(hint, dsCls);
                if (isDirectoryListShape(m, dsCls)) {
                    return m;
                }
            } catch (Throwable ignored) {
                // 继续按结构扫
            }
        }
        for (Method m : dsCls.getDeclaredMethods()) {
            if (isDirectoryListShape(m, dsCls)) {
                return m;
            }
        }
        return null;
    }

    /** 读服务器列表方法：static + 返回 List（读取 smb_list_data.json）。 */
    static Method serverLoadMethod(Class<?> serverDsCls, String[] nameHints) {
        if (serverDsCls == null) {
            return null;
        }
        for (String hint : nameHints) {
            try {
                Method m = serverDsCls.getDeclaredMethod(hint, serverDsCls);
                if (isServerLoadShape(m, serverDsCls)) {
                    return m;
                }
            } catch (Throwable ignored) {
                // 继续按结构扫
            }
        }
        for (Method m : serverDsCls.getDeclaredMethods()) {
            if (isServerLoadShape(m, serverDsCls)) {
                return m;
            }
        }
        return null;
    }

    private static boolean isDirectoryListShape(Method m, Class<?> dsCls) {
        return Modifier.isStatic(m.getModifiers())
                && List.class.isAssignableFrom(m.getReturnType())
                && m.getParameterCount() == 1
                && m.getParameterTypes()[0] == dsCls;
    }

    private static boolean isServerLoadShape(Method m, Class<?> dsCls) {
        return Modifier.isStatic(m.getModifiers())
                && List.class.isAssignableFrom(m.getReturnType())
                && m.getParameterCount() == 1
                && m.getParameterTypes()[0] == dsCls;
    }

    private static int fileFieldCount(Class<?> c) {
        int n = 0;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == File.class) {
                n++;
            }
        }
        return n;
    }

    /** 字段类型摘要（诊断用，形如 {@code a:RemoteEntry,d:ArrayList}）。 */
    static String fieldKinds(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Field f : c.getDeclaredFields()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (sb.length() > 160) {
                sb.append("…");
                break;
            }
            sb.append(f.getName()).append(':').append(f.getType().getSimpleName());
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    /** static 方法摘要（诊断用，形如 {@code a(Xy7):List}）。 */
    static String staticMethods(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (sb.length() > 200) {
                sb.append("…");
                break;
            }
            sb.append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ps[i].getSimpleName());
            }
            sb.append("):").append(m.getReturnType().getSimpleName());
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    /** 类形态摘要：字段 + 方法名（诊断锚点用，判「这一版是否与静态分析同源」）。 */
    static String describe(Class<?> c) {
        if (c == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("字段[").append(fieldKinds(c)).append("] 方法[");
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (n++ > 0) {
                sb.append(',');
            }
            if (sb.length() > 260) {
                sb.append("…");
                break;
            }
            sb.append(m.getName());
        }
        return sb.append(']').toString();
    }

    /** 是否框架/三方命名空间（跳过只为省时间；应用自身命名空间永不跳过）。 */
    private static boolean skipByPrefix(String name) {
        for (String p : SKIP_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ===== classloader 内省 =====

    /**
     * 枚举 classloader 可见的全部类名（走 DexPathList.dexElements[].dexFile.entries()）。
     *
     * <p>纯反射取隐藏字段，失败返回 null 并记流水（不抛）。
     */
    static List<String> enumClassNames(ClassLoader cl) {
        try {
            Object pathList = readField(cl, "pathList");
            if (pathList == null) {
                diag("枚举失败: 取不到 BaseDexClassLoader.pathList");
                return null;
            }
            Object[] elements = asArray(readField(pathList, "dexElements"));
            if (elements == null || elements.length == 0) {
                diag("枚举失败: 取不到 DexPathList.dexElements");
                return null;
            }
            TreeSet<String> out = new TreeSet<>();
            for (Object el : elements) {
                Object dexFile = readField(el, "dexFile");
                if (dexFile == null) {
                    continue;
                }
                Object en = invoke(dexFile, "entries");
                if (!(en instanceof Enumeration)) {
                    continue;
                }
                Enumeration<?> e = (Enumeration<?>) en;
                while (e.hasMoreElements()) {
                    Object n = e.nextElement();
                    if (n instanceof String) {
                        out.add((String) n);
                    }
                }
            }
            if (out.isEmpty()) {
                diag("枚举失败: dex 元素均未取到类名");
                return null;
            }
            return new ArrayList<>(out);
        } catch (Throwable t) {
            diag("枚举异常: " + t);
            return null;
        }
    }

    /**
     * dex 全景：逐个 dex 元素给出路径 + 该类名数（判「枚举是否覆盖全部 dex / 是否事后追加」）。
     */
    static String dexInventory(ClassLoader cl) {
        try {
            Object pathList = readField(cl, "pathList");
            Object[] elements = asArray(readField(pathList, "dexElements"));
            if (elements == null) {
                return "?(取不到 dexElements)";
            }
            StringBuilder sb = new StringBuilder("元素 ").append(elements.length).append(" 个:");
            for (Object el : elements) {
                String path = str(readField(el, "path"));
                Object dexFile = readField(el, "dexFile");
                int count = -1;
                if (dexFile != null) {
                    Object en = invoke(dexFile, "entries");
                    if (en instanceof Enumeration) {
                        count = 0;
                        Enumeration<?> e = (Enumeration<?>) en;
                        while (e.hasMoreElements()) {
                            e.nextElement();
                            count++;
                        }
                    }
                }
                sb.append("\n    ").append(count).append(" 个类  ").append(path);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?(" + t.getClass().getSimpleName() + ")";
        }
    }

    /** 名字含任一关键词的类名（triage：看清锚点邻域长什么样）。 */
    static String namesLike(List<String> names, String[] needles, int max) {
        if (names == null) {
            return "?(无类名全集)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int total = 0;
        for (String name : names) {
            if (!hitAny(name, needles)) {
                continue;
            }
            total++;
            if (n++ >= max) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(name);
        }
        if (total == 0) {
            return "(0 个)";
        }
        return total + " 个" + (total > n ? "（列前 " + n + "）" : "") + ": " + sb;
    }

    private static boolean hitAny(String name, String[] needles) {
        for (String needle : needles) {
            if (name.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 差集（新增的名字）。 */
    static List<String> diff(List<String> before, List<String> after) {
        List<String> out = new ArrayList<>();
        if (after == null) {
            return out;
        }
        for (String n : after) {
            if (before == null || !before.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    // ===== 反射小工具（沿父类链找字段，绕过隐藏 API 可见性）=====

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续上溯
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field f = findField(target.getClass(), name);
            return f == null ? null : f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object[] asArray(Object o) {
        return (o instanceof Object[]) ? (Object[]) o : null;
    }

    private static Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "?" : o.toString();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }
}
