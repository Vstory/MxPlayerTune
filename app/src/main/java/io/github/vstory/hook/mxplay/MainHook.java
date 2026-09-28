package io.github.vstory.hook.mxplay;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
 *   <li>DEBUG：定位失败明细（含结构扫描）/ classloader dex 清单 / 每次拦截的参数与返回值 —— 仅 debug 构建
 *       （{@code BuildConfig.DEBUG} 是编译期常量，release 整段裁剪）</li>
 * </ul>
 *
 * <p>定位失败的处理（多进程约定，见知识库 api102开发实战.md §5.5 / §23.4）：只有**主进程**的定位失败才是
 * 真失效（E 级）；子进程本就不加载目标类 → 打说明日志（I 级）后跳过，不报错、不影响 MX 原功能。
 */
public class MainHook extends XposedModule {

    public static final String TAG = "MxPlayerTune";

    /** 被 hook 的目标应用包名（与 META-INF/xposed/scope.list 一致）。 */
    private static final String TARGET_PKG = "com.mxtech.videoplayer.pro";

    private static int sHookOk;
    private static int sHookFail;
    private static final StringBuilder DETAIL = new StringBuilder();

    /** 本进程名（onModuleLoaded 记录）：用于区分主进程 / 子进程，决定定位失败该报 E 还是 I。 */
    private static String sProcessName;

    /** 目标应用版本串（懒取，只打日志用；取不到不致命）。 */
    private static String sTargetVersion;

    /** 定位到的目标类（供 hooker 回调复用）。 */
    private static Class<?> sRemoteEntryCls;
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
        sHookOk = 0;
        sHookFail = 0;
        DETAIL.setLength(0);
        HookTargets.resetDiag();

        // ---- 定位目标（失败即 no-op，不影响 MX 原功能）----
        sRemoteEntryCls = HookTargets.loadQuiet(cl, HookTargets.CLS_REMOTE_ENTRY);
        Class<?> smbServerCls = HookTargets.loadQuiet(cl, HookTargets.CLS_SMB_SERVER_ENTRY);
        sRemoteDsCls = HookTargets.remoteDataSource(cl, sRemoteEntryCls);
        Class<?> serverDsCls = HookTargets.serverDataSource(cl);

        // 快路径（候选混淆名）全失手 → 结构扫描兜底：不看名字，按判据全 dex 找。
        // 仅 debug 版执行（release 依赖上面的候选名常量表，避免每次启动的枚举开销）。
        if (sRemoteDsCls == null && BuildConfig.DEBUG) {
            sRemoteDsCls = HookTargets.scanRemoteDataSource(cl, sRemoteEntryCls);
        }
        if (serverDsCls == null && sRemoteDsCls != null && BuildConfig.DEBUG) {
            serverDsCls = scanServerDataSource(cl);
        }

        log(INFO, TAG, "[locate] process=" + sProcessName + " target=" + TARGET_PKG + " "
                + versionLabel()
                + " RemoteEntry=" + name(sRemoteEntryCls)
                + " SmbServerEntry=" + name(smbServerCls)
                + " RemoteDataSource=" + name(sRemoteDsCls)
                + " ServerDataSource=" + name(serverDsCls));

        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] [locate] classLoader dexPaths=" + HookTargets.dexPaths(cl));
        }

        if (sRemoteDsCls == null) {
            // 主进程 = 真失效（E 级，须修）；子进程 = 本就不加载目标类，属正常（I 级说明日志，勿报错）
            boolean main = isMainProcess();
            log(main ? ERROR : INFO, TAG, "[locate] 未找到目标类 → 模块 no-op（MX 原功能不受影响）"
                    + (main ? "；本进程为主进程，需按下方明细补新混淆名" : "；本进程非主进程，多进程下正常"));
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] [locate] 失败明细:\n" + HookTargets.DIAG);
            }
            return;
        }

        // ---- hook ①：目录列举（阶段 A：仅探针日志；阶段 B 接入 PROPFIND）----
        Method listMethod = HookTargets.directoryListMethod(sRemoteDsCls, new String[]{"a"});
        hookDirectoryList(listMethod);

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + DETAIL);
        if (BuildConfig.DEBUG && HookTargets.DIAG.length() > 0) {
            log(DEBUG, TAG, "[DBG] [locate] 明细（含已修复的失手候选）:\n" + HookTargets.DIAG);
        }
    }

    /** 结构扫描兜底：ServerDataSource（数据源 + 诊断真名）。 */
    private Class<?> scanServerDataSource(ClassLoader cl) {
        java.util.List<String> names = HookTargets.enumClassNames(cl);
        if (names == null) {
            return null;
        }
        int tried = 0;
        long t0 = System.nanoTime();
        for (String n : names) {
            if (n.indexOf('.') >= 0 || n.length() > 6) {
                continue;
            }
            tried++;
            Class<?> c = HookTargets.loadQuiet(cl, n);
            if (c != null && HookTargets.serverDataSourceOf(c)) {
                HookTargets.diag("结构扫描命中(ServerDataSource): " + n + "（比对 " + tried + " 个 / "
                        + (System.nanoTime() - t0) / 1000000L + " ms）");
                return c;
            }
        }
        return null;
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
