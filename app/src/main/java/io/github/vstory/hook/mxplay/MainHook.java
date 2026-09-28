package io.github.vstory.hook.mxplay;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * MxPlayerTune 入口（java_init.list 声明）。
 *
 * <p>目标：让 MX Player 的「本地网络」在 SMB 之外支持 WebDAV。
 *
 * <p>工作原理（详见知识库 项目开发记录/io.github.vstory.hook.mxplay/）：
 * <ol>
 *   <li>hook {@code SmbServerEntry#buildRootPath()} —— http(s) 地址不加 {@code smb://} 前缀，
 *       使 {@code RemoteEntry.path} 直接是 WebDAV URL（下游播放链路协议无关）</li>
 *   <li>hook RemoteDataSource 的目录列举方法 —— path 为 http(s) 时走 PROPFIND 列目录，
 *       否则原样放行（SMB 功能不受影响）</li>
 * </ol>
 *
 * <p>生命周期：onModuleLoaded → onPackageReady → installHooks
 *
 * <p>日志分层（构建规范，填码时不得删 DEBUG）：
 * <ul>
 *   <li>INFO：模块加载 / onPackageReady / 定位结果 / hook 安装汇总 OK-FAIL —— release 也输出</li>
 *   <li>DEBUG：定位明细（全量类名扫描 / near-miss / dex 清单 / 每次拦截的参数与返回值）—— 仅 debug 构建
 *       （{@code BuildConfig.DEBUG} 是编译期常量，release 整段裁剪）</li>
 * </ul>
 *
 * <p>定位失败的两种性质（多进程约定，见知识库 api102开发实战.md §5.5 / §23.4）：
 * 子进程本就不加载目标类 → I 级说明日志后跳过；主进程才是真失效（E 级）。
 *
 * <p>全量结构扫描为何放<b>后台线程</b>：类加载会级联加载父类，耗时不可预判；
 * 扫描可能达数秒，放 onPackageReady 主线程会拖慢目标 App 启动。扫描只读、且命中后才装 hook，
 * 故放后台安全。
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

        if (sRemoteDsCls != null) {
            installFor(serverDsCls);
            return;
        }

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

    /** 打印定位明细（D 级：调用点必须包在 {@code if (BuildConfig.DEBUG)} 里，release 编译期消除）。 */
    private void flushDiag(String phase) {
        if (HookTargets.DIAG.length() > 0 && BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] [locate] " + phase + "明细:\n" + HookTargets.DIAG);
        }
    }

    /** 装 hook（定位成功后统一走这里）。 */
    private void installFor(Class<?> serverDsCls) {
        Method listMethod = HookTargets.directoryListMethod(sRemoteDsCls, new String[]{"a"});
        hookDirectoryList(listMethod);

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + DETAIL);
        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] [locate] 最终定位: RemoteEntry=" + name(sRemoteEntryCls)
                    + " SmbServerEntry=" + name(sSmbServerCls)
                    + " RemoteDataSource=" + name(sRemoteDsCls)
                    + " ServerDataSource=" + name(serverDsCls));
        }
    }

    // ===== 工具 =====

    /** http/https 判定（WebDAV 走这两个 scheme）。 */
    static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String low = s.toLowerCase();
        return low.startsWith("http://") || low.startsWith("https://");
    }

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

    private static String readStringField(Object obj, String field) {
        if (obj == null) {
            return "null";
        }
        try {
            Field f = obj.getClass().getField(field);
            Object v = f.get(obj);
            return v == null ? "null" : v.toString();
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static int readIntField(Object obj, String field) {
        if (obj == null) {
            return Integer.MIN_VALUE;
        }
        try {
            Field f = obj.getClass().getField(field);
            return f.getInt(obj);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private void hookDirectoryList(Method target) {
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
                    Object entry = chain.getArg(0);
                    String path = readStringField(entry, "path");
                    if (BuildConfig.DEBUG) {
                        log(DEBUG, TAG, "[DBG] >> " + desc + " entry=" + entry);
                        log(DEBUG, TAG, "[DBG] >> " + desc + " name=" + readStringField(entry, "name")
                                + " type=" + readIntField(entry, "type")
                                + " path=" + path);
                        log(DEBUG, TAG, "[DBG] >> " + desc + " isWebDav=" + isHttpUrl(path));
                    }
                    Object result = chain.proceed();
                    if (BuildConfig.DEBUG) {
                        int n = (result instanceof java.util.List) ? ((java.util.List<?>) result).size() : -1;
                        log(DEBUG, TAG, "[DBG] << " + desc + " ret.size=" + n);
                    }
                    return result;
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
}
