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
 *   <li>{@code RemoteDataSource} → 混淆名 {@code LYi0}；{@code ServerDataSource} → {@code LHn0}
 *       （R8 已重打包到默认包，dex 的 source_file 属性仍保留真名，仅用于静态分析）</li>
 * </ul>
 *
 * <p>定位策略（两级，宁可 no-op 也不误 hook）：
 * <ol>
 *   <li><b>快路径</b>：候选混淆名 {@code *_NAMES} + 结构验证（升级后在此追加新名）</li>
 *   <li><b>结构扫描</b>（仅 debug 版）：枚举 classloader 里全部类名 → 逐个套结构判据，
 *       完全不看名字。用于「混淆名已变」时<b>直接把新名字查出来</b>，查到后补进 {@code *_NAMES} 固化</li>
 * </ol>
 *
 * <p>判据（{@link #DETECT_*}）只依赖类型关系，不依赖任何混淆名：
 * <ul>
 *   <li>RemoteDataSource = 持 {@code RemoteEntry} 类型字段 + 有「static + 返回 ArrayList + 唯一参数为自身」的方法</li>
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

    /** RemoteDataSource 候选混淆名（1.93.4 = LYi0）。升级后在此追加新候选。 */
    private static final String[] REMOTE_DS_NAMES = {"LYi0"};
    /** ServerDataSource 候选混淆名（1.93.4 = LHn0）。升级后在此追加新候选。 */
    private static final String[] SERVER_DS_NAMES = {"LHn0"};

    private static final String[] REMOTE_DS_METHOD_NAMES = {"a"};
    private static final String[] SERVER_DS_METHOD_NAMES = {"a"};

    /** 结构扫描上限（超过则跳过并记数，防启动期拖慢）。 */
    private static final int SCAN_MAX_CANDIDATES = 4000;

    /** 定位失败原因流水（只增不清，由调用方在打印后 {@link #resetDiag()}）。 */
    static final StringBuilder DIAG = new StringBuilder();

    static void diag(String line) {
        DIAG.append("  ").append(line).append('\n');
    }

    static void resetDiag() {
        DIAG.setLength(0);
    }

    // ===== 类加载 =====

    /** 按名加载（initialize=false：绝不触发目标类 &lt;clinit&gt;），失败原因记入 DIAG。 */
    static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            diag(name + " → 取不到类: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 静默按名加载（锚点类用，失败不记流水）。 */
    static Class<?> loadQuiet(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 定位：RemoteDataSource =====

    /** 定位 RemoteDataSource：持 RemoteEntry 字段 + 目录列举静态方法。 */
    static Class<?> remoteDataSource(ClassLoader cl, Class<?> remoteEntryCls) {
        for (String name : REMOTE_DS_NAMES) {
            Class<?> c = load(cl, name);
            if (c == null) {
                continue;
            }
            String why = remoteDsMismatch(c, remoteEntryCls);
            if (why == null) {
                return c;
            }
            diag(name + " → 结构不符: " + why);
        }
        return null;
    }

    /**
     * 结构扫描：不看名字，枚举 classloader 可见类名逐个套 RemoteDataSource 判据。
     *
     * <p>仅在快路径全部失败时调用（仅 debug 版）—— 命中即打印真名，供补进 {@link #REMOTE_DS_NAMES}。
     */
    static Class<?> scanRemoteDataSource(ClassLoader cl, Class<?> remoteEntryCls) {
        List<String> names = enumClassNames(cl);
        if (names == null) {
            return null;
        }
        if (remoteEntryCls == null) {
            diag("结构扫描跳过: 锚点 RemoteEntry 未取到，无法套判据");
            return null;
        }
        boolean sawOldName = names.contains(REMOTE_DS_NAMES[0]);
        // 关键诊断：枚举结果里到底有没有旧候选名 —— 唯一能区分
        // 「设备上装的 APK 与静态分析的那份混淆映射不同」vs「类在、只是加载/判据环节有问题」的证据。
        // 必须在命中判定**之前**打印（命中会提前 return，早前版本因此把它吞掉了）。
        for (String cand : REMOTE_DS_NAMES) {
            boolean in = names.contains(cand);
            diag("旧候选名存在性: 枚举结果含 \"" + cand + "\" = " + in
                    + (in ? "（类在 → 查判据/加载环节）" : "（类不在 → 设备上这版的混淆名与本文件常量不同）"));
        }
        int tried = 0;
        int over = 0;
        long t0 = System.nanoTime();
        for (String n : names) {
            if (!looksObfuscated(n)) {
                continue;
            }
            if (tried >= SCAN_MAX_CANDIDATES) {
                over++;
                continue;
            }
            tried++;
            Class<?> c = loadQuiet(cl, n);
            if (c != null && remoteDsMismatch(c, remoteEntryCls) == null) {
                diag("结构扫描命中: " + n + "（枚举 " + names.size() + " 个类名 / 比对 " + tried
                        + " 个 / " + ms(t0) + " ms）");
                return c;
            }
        }
        diag("结构扫描无命中: 枚举 " + names.size() + " 个类名 / 比对 " + tried + " 个 / " + ms(t0) + " ms"
                + (over > 0 ? "（超上限跳过 " + over + "）" : ""));
        return null;
    }

    // ===== 定位：ServerDataSource =====

    /** 定位 ServerDataSource：持 2×java.io.File 字段（服务器列表 JSON + 其 .tmp）。 */
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

    // ===== 结构判据（只依赖类型关系）=====

    /** 判 RemoteDataSource 形态；符合返回 null，否则返回不符原因（供诊断日志）。 */
    private static String remoteDsMismatch(Class<?> c, Class<?> remoteEntryCls) {
        if (remoteEntryCls == null) {
            return "锚点 RemoteEntry 未取到";
        }
        boolean hasEntryField = false;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == remoteEntryCls) {
                hasEntryField = true;
                break;
            }
        }
        if (!hasEntryField) {
            return "无 " + remoteEntryCls.getSimpleName() + " 类型字段（字段: " + fieldKinds(c) + "）";
        }
        Method m = directoryListMethod(c, REMOTE_DS_METHOD_NAMES);
        if (m == null) {
            return "无目录列举方法（static + 返回 ArrayList + 唯一参数为自身）";
        }
        return null;
    }

    /** 结构判据（供结构扫描复用）：是否 ServerDataSource 形态。 */
    static boolean serverDataSourceOf(Class<?> c) {
        return c != null && serverDsMismatch(c) == null;
    }

    /** 判 ServerDataSource 形态；符合返回 null，否则返回不符原因。 */
    private static String serverDsMismatch(Class<?> c) {
        if (fileFieldCount(c) >= 2) {
            return null;
        }
        return "java.io.File 字段少于 2 个（字段: " + fieldKinds(c) + "）";
    }

    /**
     * 找目录列举方法：static + 返回 ArrayList + 唯一参数为自身类型。
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
    static Method serverLoadMethod(Class<?> serverDsCls) {
        if (serverDsCls == null) {
            return null;
        }
        for (Method m : serverDsCls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == serverDsCls
                    && List.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        return null;
    }

    private static boolean isDirectoryListShape(Method m, Class<?> dsCls) {
        return Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == ArrayList.class
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

    /** 字段类型摘要（仅用于诊断日志，形如 {@code RemoteEntry,d:ArrayList,k:Fn0}）。 */
    private static String fieldKinds(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Field f : c.getDeclaredFields()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (sb.length() > 120) {
                sb.append("…");
                break;
            }
            sb.append(f.getName()).append(':').append(f.getType().getSimpleName());
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    /** 混淆名候选形态：默认包（不含点）+ 短名 —— 只用来缩小结构扫描面。 */
    private static boolean looksObfuscated(String name) {
        return name.indexOf('.') < 0 && name.length() <= 6;
    }

    // ===== classloader 内省（诊断 + 结构扫描数据源）=====

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
            int dexCount = 0;
            for (Object el : elements) {
                Object dexFile = readField(el, "dexFile");
                if (dexFile == null) {
                    continue;
                }
                dexCount++;
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
                diag("枚举失败: " + dexCount + " 个 dex 均未取到类名");
                return null;
            }
            return new ArrayList<>(out);
        } catch (Throwable t) {
            diag("枚举异常: " + t);
            return null;
        }
    }

    /** classloader 的 dex 路径清单（判定「设备上装的是哪一份 APK」用）。 */
    static String dexPaths(ClassLoader cl) {
        try {
            Object pathList = readField(cl, "pathList");
            Object[] elements = asArray(readField(pathList, "dexElements"));
            if (elements == null) {
                return "?(取不到 dexElements)";
            }
            StringBuilder sb = new StringBuilder();
            for (Object el : elements) {
                Object p = readField(el, "path");
                if (p != null) {
                    sb.append(p).append(' ');
                }
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? "?(元素无 path 字段)" : s;
        } catch (Throwable t) {
            return "?(" + t.getClass().getSimpleName() + ")";
        }
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

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }
}
