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
 *   <li>INFO：模块加载 / onPackageReady / hook 安装汇总 OK-FAIL —— release 也输出</li>
 *   <li>DEBUG：定位细节 + 每次拦截的参数 / 是否拦截 / 返回值 —— 仅 debug 构建
 *       （{@code BuildConfig.DEBUG} 是编译期常量，release 整段裁剪）</li>
 * </ul>
 */
public class MainHook extends XposedModule {

    public static final String TAG = "MxPlayerTune";

    private static int sHookOk;
    private static int sHookFail;
    private static final StringBuilder DETAIL = new StringBuilder();

    /** 定位到的目标类（供 hooker 回调复用）。 */
    private static Class<?> sRemoteEntryCls;
    private static Class<?> sRemoteDsCls;

    public MainHook() {
        super();
    }

    // ===== 生命周期 =====

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(INFO, TAG, "api102 module loaded");
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

        // ---- 定位目标（失败即 no-op，不影响 MX 原功能）----
        sRemoteEntryCls = HookTargets.load(cl, HookTargets.CLS_REMOTE_ENTRY);
        Class<?> smbServerCls = HookTargets.load(cl, HookTargets.CLS_SMB_SERVER_ENTRY);
        sRemoteDsCls = HookTargets.remoteDataSource(cl, sRemoteEntryCls);
        Class<?> serverDsCls = HookTargets.serverDataSource(cl);

        log(INFO, TAG, "[locate] RemoteEntry=" + name(sRemoteEntryCls)
                + " SmbServerEntry=" + name(smbServerCls)
                + " RemoteDataSource=" + name(sRemoteDsCls)
                + " ServerDataSource=" + name(serverDsCls));

        if (sRemoteDsCls == null) {
            log(ERROR, TAG, "[locate] RemoteDataSource 定位失败 —— 本版本未适配，模块 no-op"
                    + "（MX 原功能不受影响；需按 HookTargets 的候选名清单补充新混淆名）");
            return;
        }

        // ---- hook ①：目录列举（阶段 A：仅探针日志；阶段 B 接入 PROPFIND）----
        Method listMethod = HookTargets.directoryListMethod(sRemoteDsCls, new String[]{"a"});
        hookDirectoryList(listMethod);

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + DETAIL);
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

    // ===== 工具 =====

    /** http/https 判定（WebDAV 走这两个 scheme）。 */
    static boolean isHttpUrl(String s) {
        if (s == null) {
            return false;
        }
        String low = s.toLowerCase();
        return low.startsWith("http://") || low.startsWith("https://");
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
}
