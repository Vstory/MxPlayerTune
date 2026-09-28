package io.github.vstory.hook.mxplay;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;

import android.net.Uri;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * MxPlayerTune 入口（java_init.list 声明）。
 *
 * <p>目标：让 MX Player 的「本地网络」在 SMB 之外支持 WebDAV。
 *
 * <p>两个 hook（详见知识库 项目开发记录/io.github.vstory.hook.mxplay/）：
 * <ol>
 *   <li>{@code SmbServerEntry#getRootPath()} —— webdav 服务器算出来的 rootPath 带着硬编码的
 *       {@code smb://} 前缀，读侧归一（{@code smb://http://…} → {@code http://…}），
 *       使 {@code RemoteEntry.path} 直接是 WebDAV URL（下游播放链路协议无关）</li>
 *   <li>RemoteDataSource 的目录列举静态方法 —— path 为 http(s) 时走 PROPFIND 列目录，
 *       否则原样放行（SMB 功能不受影响）</li>
 * </ol>
 *
 * <p>生命周期：onModuleLoaded → onPackageReady → installHooks
 *
 * <p>日志分层（构建规范，填码时不得删 DEBUG）：
 * <ul>
 *   <li>INFO：模块加载 / onPackageReady / 定位结果 / hook 安装汇总 / 每次 PROPFIND 结果 —— release 也输出</li>
 *   <li>DEBUG：定位明细（全量类名扫描 / near-miss / dex 清单）、请求走了哪条路径、每次拦截的参数与返回值
 *       —— 仅 debug 构建（{@code BuildConfig.DEBUG} 是编译期常量，release 整段裁剪）</li>
 * </ul>
 *
 * <p>定位失败的两种性质（多进程约定，见知识库 api102开发实战.md §5.5 / §23.4）：
 * 子进程本就不加载目标类 → I 级说明日志后跳过；主进程才是真失效（E 级）。
 */
public class MainHook extends XposedModule {

    public static final String TAG = "MxPlayerTune";

    /** 被 hook 的目标应用包名（与 META-INF/xposed/scope.list 一致）。 */
    private static final String TARGET_PKG = "com.mxtech.videoplayer.pro";

    /** 快路径未命中后，二次枚举的延迟（抓「启动后才追加 dex」的动态加载）。 */
    private static final long RESCAN_DELAY_MS = 5000L;

    /** triage 关键词：列出这些词相关的类名，看清锚点邻域。 */
    private static final String[] TRIAGE_NEEDLES = {
            "smb", "Smb", "Remote", "DataSource", "Dav", "dav", "Server", "server",
    };

    private static int sHookOk;
    private static int sHookFail;
    private static final StringBuilder DETAIL = new StringBuilder();

    /** 本进程名（onModuleLoaded 记录）：区分主进程 / 子进程，决定定位失败该报 E 还是 I。 */
    private static String sProcessName;

    /** 目标应用版本串（懒取，只打日志用；取不到不致命）。 */
    private static String sTargetVersion;

    /** 定位与 hook 上下文（后台线程复用）。 */
    private static ClassLoader sCl;
    private static Class<?> sRemoteEntryCls;
    private static Class<?> sSmbServerCls;
    private static Class<?> sRemoteDsCls;

    public MainHook() {
        super();
    }

    // ===== 生命周期 =====

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sProcessName = param.getProcessName();
        log(INFO, TAG, "api102 module loaded, process=" + sProcessName);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        log(INFO, TAG, "[pkg] onPackageReady, classLoader=" + (cl != null ? "non-null" : "NULL!"));
        try {
            installHooks(cl);
        } catch (Throwable t) {
            log(ERROR, TAG, "[ERR] installHooks threw: " + t, t);
        }
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        // 允许热重载；旧 hook handle 由框架自动 unhook（本模块无跨重载 static 状态需恢复）
        return true;
    }

    // ===== hook 安装 =====

    private void installHooks(ClassLoader cl) {
        sCl = cl;
        sHookOk = 0;
        sHookFail = 0;
        DETAIL.setLength(0);
        HookTargets.resetDiag();

        // ---- 定位目标（失败即 no-op，不影响 MX 原功能）----
        sRemoteEntryCls = HookTargets.loadQuiet(cl, HookTargets.CLS_REMOTE_ENTRY);
        sSmbServerCls = HookTargets.loadQuiet(cl, HookTargets.CLS_SMB_SERVER_ENTRY);
        sRemoteDsCls = HookTargets.remoteDataSource(cl, sRemoteEntryCls);
        Class<?> serverDsCls = HookTargets.serverDataSource(cl);

        log(INFO, TAG, "[locate] process=" + sProcessName + " target=" + TARGET_PKG + " "
                + versionLabel()
                + " RemoteEntry=" + name(sRemoteEntryCls)
                + " SmbServerEntry=" + name(sSmbServerCls)
                + " RemoteDataSource=" + name(sRemoteDsCls)
                + " ServerDataSource=" + name(serverDsCls));

        if (sRemoteDsCls == null) {
            if (!BuildConfig.DEBUG) {
                // release 不跑全量扫描（候选名常量表才是 release 的定位手段）
                boolean main = isMainProcess();
                log(main ? ERROR : INFO, TAG, "[locate] 快路径未命中 → 模块 no-op（MX 原功能不受影响）"
                        + (main ? "；本进程为主进程，需装 debug 版跑全量扫描补齐候选名" : "；本进程非主进程，多进程下正常"));
                return;
            }
            log(INFO, TAG, "[locate] 快路径未命中 → 后台全量结构扫描（debug 版兜底）");
            Thread t = new Thread(this::backgroundScan, "MxPlayerTune-locate");
            t.setDaemon(true);
            t.start();
            return;
        }
        installFor(serverDsCls);
    }

    // ===== 后台全量结构扫描（debug 版）=====

    /**
     * 不看名字，遍历 classloader 全部类名套结构判据；若仍无命中，延迟二次枚举抓「事后追加的 dex」。
     */
    private void backgroundScan() {
        ClassLoader cl = sCl;
        try {
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] [locate] dex 清单: " + HookTargets.dexInventory(cl));
            }

            List<String> names = HookTargets.enumClassNames(cl);
            if (names == null) {
                flushDiag("全量扫描");
                log(ERROR, TAG, "[locate] 类名枚举失败 → 无法扫描（明细见上）");
                return;
            }
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] [locate] 枚举类名 " + names.size() + " 个");
            }

            Class<?> ds = HookTargets.scanRemoteDataSource(cl, sRemoteEntryCls, names, "RemoteDataSource");
            Class<?> serverDs = HookTargets.scanServerDataSource(cl, names, "ServerDataSource");

            if (ds != null) {
                sRemoteDsCls = ds;
                flushDiag("全量扫描");
                installFor(serverDs);
                return;
            }

            flushDiag("全量扫描");

            // ---- 二次枚举：抓启动后才追加的 dex（动态加载 / 懒加载模块）----
            Thread.sleep(RESCAN_DELAY_MS);
            List<String> after = HookTargets.enumClassNames(cl);
            List<String> delta = HookTargets.diff(names, after);
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] [locate] 二次枚举（+" + (RESCAN_DELAY_MS / 1000) + "s）: 类名 "
                        + names.size() + " → " + (after == null ? "?" : after.size()) + "，新增 " + delta.size());
            }
            if (after != null && !delta.isEmpty()) {
                Class<?> ds2 = HookTargets.scanRemoteDataSource(cl, sRemoteEntryCls, delta, "RemoteDataSource(二次)");
                Class<?> serverDs2 = HookTargets.scanServerDataSource(cl, delta, "ServerDataSource(二次)");
                flushDiag("二次扫描");
                if (ds2 != null) {
                    sRemoteDsCls = ds2;
                    installFor(serverDs2 != null ? serverDs2 : serverDs);
                    return;
                }
            }

            // ---- 仍无命中：把「为什么」讲清楚，别只留一个 null ----
            boolean main = isMainProcess();
            log(main ? ERROR : INFO, TAG, "[locate] 全量扫描无命中 → 模块 no-op（MX 原功能不受影响）"
                    + (main ? "；本进程为主进程，需按上方明细（near-miss / triage）判断是判据失手还是类不在" : "；本进程非主进程，多进程下正常"));
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] [locate] 【结论性证据】锚点邻域与 near-miss 见上；"
                        + "若 triage 里没有锚点包（" + HookTargets.ANCHOR_PKG + "）以外的可疑类且无 near-miss，"
                        + "说明目标类不在已加载 dex 中");
            }
        } catch (Throwable t) {
            log(ERROR, TAG, "[ERR] 后台扫描异常: " + t, t);
        }
    }

    private void flushDiag(String phase) {
        if (BuildConfig.DEBUG) {
            if (HookTargets.DIAG.length() > 0) {
                log(DEBUG, TAG, "[DBG] [locate] " + phase + "明细:\n" + HookTargets.DIAG);
            }
        }
    }

    /** 装 hook（定位成功后统一走这里）。 */
    private void installFor(Class<?> serverDsCls) {
        // hook ①：rootPath 读侧归一（webdav 去掉 smb:// 前缀）
        hookRootPath(HookTargets.rootPathGetter(sSmbServerCls, HookTargets.M_SMB_GET_ROOT_PATH));

        // hook ②：目录列举（http(s) → PROPFIND）
        boolean bound = RemoteEntries.bind(sRemoteEntryCls);
        if (!bound) {
            sHookFail++;
            DETAIL.append("\n[FAIL] RemoteEntry 构造器绑定");
        }
        hookDirectoryList(HookTargets.directoryListMethod(sRemoteDsCls, new String[]{"a"}), bound);

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + DETAIL);
        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] [locate] 最终定位: RemoteEntry=" + name(sRemoteEntryCls)
                    + " SmbServerEntry=" + name(sSmbServerCls)
                    + " RemoteDataSource=" + name(sRemoteDsCls)
                    + " ServerDataSource=" + name(serverDsCls));
        }
    }

    // ===== hook ①：SmbServerEntry#getRootPath =====

    private void hookRootPath(Method target) {
        final String desc = "SmbServerEntry#getRootPath";
        if (target == null) {
            sHookFail++;
            DETAIL.append("\n[FAIL] ").append(desc).append(": method not found");
            return;
        }
        try {
            target.setAccessible(true);
            hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object raw = chain.proceed();
                    String fixed = RemoteEntries.normalizeRootPath(raw instanceof String ? (String) raw : null);
                    if (BuildConfig.DEBUG) {
                        if (fixed != null && !fixed.equals(raw)) {
                            log(DEBUG, TAG, "[DBG] >> " + desc + " 归一: " + raw + " → " + fixed);
                        } else {
                            log(DEBUG, TAG, "[DBG] >> " + desc + " 原样: " + raw);
                        }
                    }
                    return fixed;
                }
            });
            sHookOk++;
            DETAIL.append("\n[OK] ").append(desc);
        } catch (Throwable t) {
            sHookFail++;
            DETAIL.append("\n[FAIL] ").append(desc).append(": ").append(t);
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] hook FAIL: " + desc + " -> " + t);
            }
        }
    }

    // ===== hook ②：RemoteDataSource 目录列举 =====

    private void hookDirectoryList(Method target, final boolean entryCtorBound) {
        final String desc = "RemoteDataSource#listDir";
        if (target == null) {
            sHookFail++;
            DETAIL.append("\n[FAIL] ").append(desc).append(": method not found");
            return;
        }
        try {
            target.setAccessible(true);
            hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object holder = chain.getArg(0);
                    Object entry = readField(holder, "a");
                    String path = entry == null ? null : RemoteEntries.pathOf(entry);
                    if (!HookTargets.isHttpUrl(path)) {
                        if (BuildConfig.DEBUG) {
                            log(DEBUG, TAG, "[DBG] >> " + desc + " 非 http(s) → 放行 path=" + path);
                        }
                        return chain.proceed();
                    }
                    if (!entryCtorBound) {
                        log(ERROR, TAG, "[webdav] 条目构造器未绑定 → 无法列出 " + path);
                        return new ArrayList<Object>();
                    }
                    return listWebDav(chain, entry, path, desc);
                }
            });
            sHookOk++;
            DETAIL.append("\n[OK] ").append(desc);
        } catch (Throwable t) {
            sHookFail++;
            DETAIL.append("\n[FAIL] ").append(desc).append(": ").append(t);
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] hook FAIL: " + desc + " -> " + t);
            }
        }
    }

    /** WebDAV 分支：PROPFIND 列目录 → 造 MX 条目。失败给空列表 + E 级日志（MX 原功能不受影响）。 */
    private Object listWebDav(XposedInterface.Chain chain, Object entry, String path, String desc) {
        WebDavClient.resetNotes();
        RemoteEntries.MediaFilter filter = null;
        long t0 = System.nanoTime();
        try {
            // 凭据：与 MX 自身判据一致（anonymity == 0 才带凭据），字段被 Uri.encode 过 ⇒ 先 decode
            int anonymity = RemoteEntries.readIntField(entry, "anonymity", 1);
            String user = "";
            String pass = "";
            if (anonymity == 0) {
                user = Uri.decode(nullToEmpty(RemoteEntries.readOwnField(entry, "userName")));
                pass = Uri.decode(nullToEmpty(RemoteEntries.readOwnField(entry, "password")));
            }
            filter = RemoteEntries.MediaFilter.open(sCl);
            // 目录的 path 不带结尾斜杠（构造器拼的），请求前按类型补上，否则服务端多回 301/非集合
            String reqUrl = RemoteEntries.requestUrl(path, RemoteEntries.typeOf(entry));
            List<WebDavClient.Child> children = WebDavClient.list(reqUrl, user, pass);
            ArrayList<Object> out = RemoteEntries.build(entry, children, filter);
            int dirs = 0;
            for (WebDavClient.Child c : children) {
                if (c.dir) {
                    dirs++;
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            log(INFO, TAG, "[webdav] " + desc + " path=" + path
                    + (reqUrl.equals(path) ? "" : " → req=" + reqUrl)
                    + " → 子项 " + children.size() + "（目录 " + dirs + "）"
                    + " 过滤=" + filter.kind() + " 返回 " + out.size() + " 项 / " + ms + " ms"
                    + (anonymity == 0 ? " 带凭据" : " 匿名"));
            if (BuildConfig.DEBUG) {
                if (WebDavClient.NOTES.length() > 0) {
                    log(DEBUG, TAG, "[DBG] [webdav] 明细:\n" + WebDavClient.NOTES);
                }
                StringBuilder sb = new StringBuilder();
                for (Object o : out) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    if (sb.length() > 300) {
                        sb.append('…');
                        break;
                    }
                    sb.append(RemoteEntries.pathOf(o)).append('(').append(RemoteEntries.typeOf(o)).append(')');
                }
                log(DEBUG, TAG, "[DBG] << " + desc + " 返回: " + sb);
            }
            return out;
        } catch (Throwable t) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            log(ERROR, TAG, "[webdav] 列目录失败 path=" + path + " / " + ms + " ms: " + t, t);
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] [webdav] 失败明细:\n" + WebDavClient.NOTES);
            }
            return new ArrayList<Object>();
        } finally {
            if (filter != null) {
                filter.close();
            }
        }
    }

    // ===== 工具 =====

    /**
     * 本进程是否为目标应用主进程。
     *
     * <p>进程名取不到时按「主进程」处理 —— 宁可多报一条 E，也不要静默吞掉真失效。
     */
    private static boolean isMainProcess() {
        return sProcessName == null || TARGET_PKG.equals(sProcessName);
    }

    /** 目标应用版本（走系统 Context 的 PackageManager；取不到不致命）。 */
    private static String versionLabel() {
        if (sTargetVersion != null) {
            return sTargetVersion;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object ctx = at.getMethod("getSystemContext").invoke(thread);
            Object pm = ctx.getClass().getMethod("getPackageManager").invoke(ctx);
            Object pi = pm.getClass().getMethod("getPackageInfo", String.class, int.class)
                    .invoke(pm, TARGET_PKG, 0);
            sTargetVersion = "ver=" + field(pi, "versionName") + "(" + field(pi, "versionCode") + ")";
        } catch (Throwable t) {
            sTargetVersion = "ver=?(" + t.getClass().getSimpleName() + ")";
        }
        return sTargetVersion;
    }

    private static String field(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return String.valueOf(f.get(obj));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String name(Class<?> c) {
        return c == null ? "null" : c.getName();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Object readField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        for (Class<?> k = obj.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                // 继续上溯
            }
        }
        return null;
    }
}
