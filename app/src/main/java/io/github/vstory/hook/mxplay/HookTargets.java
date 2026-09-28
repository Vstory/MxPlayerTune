package io.github.vstory.hook.mxplay;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * 目标定位层：MX Player 混淆名随版本漂移，这里用「候选名 + 结构验证」双保险。
 *
 * <p>已确认事实（MX Player 1.93.4 / versionCode 2001002584）：
 * <ul>
 *   <li>{@code com.mxtech.videoplayer.smb.bean.RemoteEntry} 与 {@code SmbServerEntry}
 *       <b>类名未被混淆</b>（真名，跨版本稳定）—— 可作定位锚点</li>
 *   <li>{@code RemoteDataSource} → 混淆名 {@code LYi0}（.source 文件名保留在 dex 调试信息中）</li>
 *   <li>{@code ServerDataSource} → 混淆名 {@code LHn0}</li>
 * </ul>
 *
 * <p>定位策略：候选名命中 **且** 结构符合判据才采纳；全部失败返回 null，调用方须 no-op 放行
 * （保证 MX Player 原功能不受影响）。
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

    /** 按名加载（initialize=false：绝不触发目标类 &lt;clinit&gt;）。 */
    static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 定位 RemoteDataSource：持 RemoteEntry 字段 + 目录列举静态方法。 */
    static Class<?> remoteDataSource(ClassLoader cl, Class<?> remoteEntryCls) {
        for (String name : REMOTE_DS_NAMES) {
            Class<?> c = load(cl, name);
            if (c != null && looksLikeRemoteDataSource(c, remoteEntryCls)) {
                return c;
            }
        }
        return null;
    }

    /** 定位 ServerDataSource：持 2×java.io.File 字段（服务器列表 JSON + 其 .tmp）。 */
    static Class<?> serverDataSource(ClassLoader cl) {
        for (String name : SERVER_DS_NAMES) {
            Class<?> c = load(cl, name);
            if (c != null && looksLikeServerDataSource(c)) {
                return c;
            }
        }
        return null;
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
        // 优先按名提示（结构仍须通过）
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
                    && java.util.List.class.isAssignableFrom(m.getReturnType())) {
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

    private static boolean looksLikeRemoteDataSource(Class<?> c, Class<?> remoteEntryCls) {
        if (remoteEntryCls == null) {
            return false;
        }
        boolean hasEntryField = false;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == remoteEntryCls) {
                hasEntryField = true;
                break;
            }
        }
        if (!hasEntryField) {
            return false;
        }
        return directoryListMethod(c, REMOTE_DS_METHOD_NAMES) != null;
    }

    private static boolean looksLikeServerDataSource(Class<?> c) {
        int fileFields = 0;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == File.class) {
                fileFields++;
            }
        }
        return fileFields >= 2;
    }
}
