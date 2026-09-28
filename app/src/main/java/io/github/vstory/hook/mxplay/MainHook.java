package io.github.vstory.hook.mxplay;

import static android.util.Log.DEBUG;
import static android.util.Log.INFO;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * api102 模块入口（java_init.list 声明）。
 *
 * <p>生命周期：onModuleLoaded → onPackageReady → installHooks
 * <p>热重载：onHotReloading 返回 true → 旧 hook handle 自动 unhook → installHooks 重装
 *
 * <p>📌 新项目修改点：
 * <ol>
 *   <li>包名 / 模块名（由 new_module_java.sh 替换，或手动改 namespace/applicationId/label）</li>
 *   <li>{@link #installHooks}：填要 hook 的类与方法（复制 {@code addXxxHook} 范例）</li>
 *   <li>scope.list：填被 hook 应用的包名</li>
 * </ol>
 *
 * <p>📌 日志分层（模板默认规范，禁止在填码时删掉 DEBUG 记录）：
 * <ul>
 *   <li><b>INFO</b>（debug + release 都输出）：模块加载 / onPackageReady / hook 安装汇总 OK/FAIL —— 正式版只需这些</li>
 *   <li><b>DEBUG</b>（仅 debug 构建输出）：类/方法匹配细节 + <b>每次拦截调用的参数、是否拦截、返回值</b>。
 *       写法必须是调用点直接 <code>if (BuildConfig.DEBUG) { log(DEBUG, TAG, ...); }</code>，
 *       release 编译时 BuildConfig.DEBUG=false 是编译期常量 → 该分支字节码与字符串常量整体不进入 dex（真正裁剪，非运行时跳过）。</li>
 * </ul>
 */
public class MainHook extends XposedModule {

    public static final String TAG = "MxPlayerTune";

    private static int sHookOk;
    private static int sHookFail;
    private static StringBuilder sHookDetail;

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
        installHooks(cl);
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        // 允许热重载；旧 hook handle 由框架自动 unhook
        return true;
    }

    // ===== 入口：在此填要 hook 的类与方法 =====

    private void installHooks(ClassLoader cl) {
        sHookOk = 0;
        sHookFail = 0;
        sHookDetail = new StringBuilder();

        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] installHooks start, classLoader=" + cl);
        }

        // 示例：addXxxHook(cl, "com.example.target.TargetClass", "targetMethod");（范例见类底部）
        // addXxxHook(cl, "com.example.target.TargetClass", "targetMethod");

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + sHookDetail);
    }

    // ===== 范例 hook（新增 hook 时整段复制改类名/方法名/逻辑即可）=====

    /** 范例：hook 无参/有参方法，拦截打 DEBUG 日志、改参、返回自定义值。 */
    private void addXxxHook(ClassLoader cl, String clsName, String methodName) {
        String desc = clsName + "#" + methodName;
        try {
            Class<?> cls = cl.loadClass(clsName);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                // 📌 同名单参适配：需要时在此按参数个数/类型精确匹配（见本类下方 hookMethodBySig 注释）
                target = m;
                if (BuildConfig.DEBUG) {
                    log(DEBUG, TAG, "[DBG] hook target matched: " + m);
                }
                break;
            }
            if (target == null) {
                throw new NoSuchMethodException(desc);
            }
            target.setAccessible(true);
            hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // ① 进方法：打 DEBUG（参数是啥）—— release 编译期整段裁剪
                    if (BuildConfig.DEBUG) {
                        log(DEBUG, TAG, "[DBG] >> " + desc + " args=" + chain.getArgs());
                    }
                    // ② 三种常用改法（按需保留一种）：
                    //   a) 拦截不执行原方法，返回自定义值：
                    //      if (BuildConfig.DEBUG) { log(DEBUG, TAG, "[DBG] << " + desc + " BLOCKED, return custom"); }
                    //      return Boolean.TRUE;
                    //   b) 改第 0 参再继续：
                    //      Object a0 = chain.getArg(0);
                    //      if (a0 instanceof String) { return chain.proceed(new Object[]{"改后值"}); }
                    //   c) 默认：原方法继续
                    Object result = chain.proceed();
                    // ③ 出方法：打 DEBUG（返回了啥）—— release 编译期整段裁剪
                    if (BuildConfig.DEBUG) {
                        log(DEBUG, TAG, "[DBG] << " + desc + " ret=" + result);
                    }
                    return result;
                }
            });
            sHookOk++;
            sHookDetail.append("\n[OK] ").append(desc);
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] hook installed OK: " + desc);
            }
        } catch (Throwable e) {
            sHookFail++;
            sHookDetail.append("\n[FAIL] ").append(desc).append(": ").append(e.getMessage());
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] hook install FAIL: " + desc + " -> " + e);
            }
        }
    }

    /** 按 类名+方法名+参数类型数组 精确 hook（方法重载/混淆同名多参时用，替代上面简单按名匹配）。 */
    protected void hookMethodBySig(ClassLoader cl, String clsName, String methodName,
                                   Class<?>[] paramTypes, XposedInterface.Hooker hooker) {
        String desc = clsName + "#" + methodName;
        try {
            Class<?> cls = cl.loadClass(clsName);
            Method target = cls.getDeclaredMethod(methodName, paramTypes);
            target.setAccessible(true);
            hook(target).intercept(hooker);
            sHookOk++;
            sHookDetail.append("\n[OK] ").append(desc);
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] hook installed OK(sig): " + desc);
            }
        } catch (Throwable e) {
            sHookFail++;
            sHookDetail.append("\n[FAIL] ").append(desc).append(": ").append(e.getMessage());
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "[DBG] hook install FAIL(sig): " + desc + " -> " + e);
            }
        }
    }
}
