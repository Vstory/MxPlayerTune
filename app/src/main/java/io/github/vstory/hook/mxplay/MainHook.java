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
 * <p>四个 hook（详见知识库 项目开发记录/io.github.vstory.hook.mxplay/）：
 * <ol>
 *   <li>{@code SmbServerEntry#getRootPath()} —— webdav 服务器算出来的 rootPath 带着硬编码的
 *       {@code smb://} 前缀，读侧归一（{@code smb://http://…} → {@code http://…}）
 *       <b>并把凭据内联成 URL userinfo</b>（{@code http://用户:密码@主机:端口/路径}）：
 *       MX 播放前用 {@code wJ.c(uri)} 把 {@code uri.getUserInfo()} 变成
 *       {@code Authorization: Basic …} 选项 —— URL 里没有 userinfo 就等于播放不带鉴权</li>
 *   <li>RemoteDataSource 的目录列举静态方法 —— path 为 http(s) 时走 PROPFIND 列目录，
 *       否则原样放行（SMB 功能不受影响）</li>
 *   <li>{@code SmbUtil} 的缩略图 URL 构造方法 —— 对 http(s) 的**视频**条目返回我们自己起的
 *       环回缩略图服务 URL（{@link ThumbServer}）：宿主取的是条目自身 URL、且整片下载不发
 *       {@code Range} ⇒ 只有把「一张真 JPEG」放到它面前才可能出图；
 *       非视频 / 服务未起来时退回条目 {@code path}</li>
 *   <li>{@code java.net.URL#openConnection()} —— 图片/缩略图取流补 {@code Authorization}：
 *       宿主的图片取流是<b>裸</b> {@code HttpURLConnection}，<b>不</b>把 URL 里的 userinfo
 *       变成鉴权头 ⇒ 否则「能列目录、能播放，只有缩略图空白」（见 {@link #hookUrlAuth}）</li>
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
    private static Class<?> sSmbUtilCls;

    /** 环回缩略图服务（进程内唯一；起不来时保持 null ⇒ hook ③ 退回旧行为）。 */
    private static ThumbServer sThumbServer;

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
            if (sSmbUtilCls == null) {
                sSmbUtilCls = HookTargets.scanSmbUtil(cl, sRemoteEntryCls, names, "SmbUtil");
            }

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
        // hook ①：rootPath 读侧归一（webdav 去 smb:// 前缀 + 凭据内联进 URL）
        hookRootPath(HookTargets.rootPathGetter(sSmbServerCls, HookTargets.M_SMB_GET_ROOT_PATH));

        // hook ②：目录列举（http(s) → PROPFIND）
        boolean bound = RemoteEntries.bind(sRemoteEntryCls);
        if (!bound) {
            sHookFail++;
            DETAIL.append("\n[FAIL] RemoteEntry 构造器绑定");
        }
        hookDirectoryList(HookTargets.directoryListMethod(sRemoteDsCls, new String[]{"a"}), bound);

        // hook ③：缩略图 URL 构造（视频条目 → 环回缩略图服务；否则条目 path）
        if (sSmbUtilCls == null) {
            sSmbUtilCls = HookTargets.smbUtil(sCl, sRemoteEntryCls);
        }
        startThumbServer();
        hookThumbnailUrl(HookTargets.smbUtilBuildUrl(sSmbUtilCls));

        // hook ④：图片/缩略图取流补 Basic 鉴权（宿主的取流不认 URL 里的 userinfo）
        hookUrlAuth();

        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + DETAIL);
        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] [locate] 最终定位: RemoteEntry=" + name(sRemoteEntryCls)
                    + " SmbServerEntry=" + name(sSmbServerCls)
                    + " RemoteDataSource=" + name(sRemoteDsCls)
                    + " ServerDataSource=" + name(serverDsCls)
                    + " SmbUtil=" + name(sSmbUtilCls));
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
                    String rawStr = raw instanceof String ? (String) raw : null;
                    String[] cred = storedCredentialsOf(chain.getThisObject());
                    String fixed = RemoteEntries.normalizeRootPath(rawStr, cred[0], cred[1]);
                    if (BuildConfig.DEBUG) {
                        if (fixed != null && !fixed.equals(raw)) {
                            log(DEBUG, TAG, "[DBG] >> " + desc + " 归一: "
                                    + RemoteEntries.maskUserInfo(rawStr) + " → "
                                    + RemoteEntries.maskUserInfo(fixed));
                        } else {
                            log(DEBUG, TAG, "[DBG] >> " + desc + " 原样: "
                                    + RemoteEntries.maskUserInfo(rawStr));
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

    /**
     * 条目凭据（{@code {user, pass}}，真凭据，空串 = 匿名/无）—— **自己发请求用**（Authorization 头）。
     *
     * <p>真机事实：{@code ServerEditDialog} 落库时对 userName/password {@code Uri.encode} 过
     * ⇒ 这里 {@code Uri.decode} 还原（服务端要的是真凭据）；{@code anonymity != 0} 表示匿名勾选，
     * 此时 MX 自己也只送匿名，凭据一律不带。
     *
     * <p>注意：<b>URL 内联不用这一份</b>（解码后的真凭据里可能有 {@code ?} / {@code #}，
     * 进了 URL 会让解析器在 authority 中途截断）—— 那一处用 {@link #storedCredentialsOf}。
     */
    private static String[] credentialsOf(Object entry) {
        if (entry == null || RemoteEntries.readIntField(entry, "anonymity", 1) != 0) {
            return new String[]{"", ""};
        }
        return new String[]{
                Uri.decode(nullToEmpty(RemoteEntries.readOwnField(entry, "userName"))),
                Uri.decode(nullToEmpty(RemoteEntries.readOwnField(entry, "password")))};
    }

    /**
     * 条目凭据的**落库原值**（{@code {user, pass}}）—— **URL 内联专用**（hook ①）。
     *
     * <p>为什么不解码：见 {@link #credentialsOf} 的反面 —— MX 落库的是 {@code Uri.encode} 形态，
     * 而 MX 的消费者认的就是这一形态（{@code android.net.Uri#getUserInfo()} 会解码，
     * {@code SmbUtil.b} 也直接拼落库原值）⇒ 编码形态进 URL、真凭据出来。
     */
    private static String[] storedCredentialsOf(Object entry) {
        if (entry == null || RemoteEntries.readIntField(entry, "anonymity", 1) != 0) {
            return new String[]{"", ""};
        }
        return new String[]{
                nullToEmpty(RemoteEntries.readOwnField(entry, "userName")),
                nullToEmpty(RemoteEntries.readOwnField(entry, "password"))};
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
                            log(DEBUG, TAG, "[DBG] >> " + desc + " 非 http(s) → 放行 path="
                                    + RemoteEntries.maskUserInfo(path));
                        }
                        return chain.proceed();
                    }
                    if (!entryCtorBound) {
                        log(ERROR, TAG, "[webdav] 条目构造器未绑定 → 无法列出 "
                                + RemoteEntries.maskUserInfo(path));
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
            log(INFO, TAG, "[webdav] " + desc + " path=" + RemoteEntries.maskUserInfo(path)
                    + RemoteEntries.reqSuffix(path, reqUrl)
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
                    sb.append(RemoteEntries.maskUserInfo(RemoteEntries.pathOf(o)))
                            .append('(').append(RemoteEntries.typeOf(o)).append(')');
                }
                log(DEBUG, TAG, "[DBG] << " + desc + " 返回: " + sb);
            }
            return out;
        } catch (Throwable t) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            log(ERROR, TAG, "[webdav] 列目录失败 path=" + RemoteEntries.maskUserInfo(path)
                    + " / " + ms + " ms: " + t, t);
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

    // ===== hook ④：图片取流补 Basic 鉴权 =====

    /**
     * 补鉴权（缩略图链路）的明细配额；「宿主来取图但 URL 无凭据」走 {@link LogBudget#IMAGE_BARE}。
     *
     * <p>真机踩过（见 hook信息记录.md §三.4）：宿主自己取「列目录 URL」时也不带凭据，
     * 两个分支共用一个计数器时，7 条这类噪声会把缩略图那条<b>挤掉</b>
     * （旧代码 {@code AUTH_LOG_MAX=8} 恰好被 7+1 用满 ⇒ 之后 {@code buildThumbUrl} 再被调用也没有日志，
     * 容易被误读成「宿主没去取图」或「命中缓存」）。配额分离与「不被挤掉」由 verify 的 L 段钉住。
     */

    /**
     * 给「URL 里带 userinfo 的 http(s) 取流」补上 {@code Authorization} 头。
     *
     * <p>真机事实（见 hook信息记录.md §三.4）：<b>播放与缩略图走的不是同一套鉴权</b>。
     * 播放交给 MX 自己的 FFPlayer，它会按 {@code uri.getUserInfo()} 生成
     * {@code Authorization} 选项（{@code wJ.c(uri)}）⇒ 带鉴权；而缩略图走
     * {@code hr0.a} → {@code ImageLoader}（{@code LVL}）→ {@code MxImageDownloader}
     * （{@code l50}，UIL 的下载器子类，dex 里保留着源文件名），它的 http 分支就是一条
     * <b>裸</b> {@code HttpURLConnection}：<b>没有一步</b>把 URL 的 userinfo 变成请求头
     * （它只按「响应码不等于 200 就抛」处理）⇒ 服务端 401 ⇒ 缩略图一直空白，且<b>不报错</b>
     * （只有 401 → 显示占位图，日志里什么都没有）。
     *
     * <p>为什么挂在 {@code java.net.URL#openConnection()} 上（不挂混淆名的图片加载类）：
     * <ol>
     *   <li>它是不依赖版本/混淆名的一层，凡走 {@code java.net} 的取流都被覆盖
     *       （宿主自己的取图、OkHttp 的 URLConnection 桥）</li>
     *   <li>载荷零风险：<b>只对 userinfo 非空的 http(s) URL 生效</b>，且已有
     *       {@code Authorization} 时不覆盖 —— 其它网络请求一律原样放行</li>
     * </ol>
     *
     * <p>补的头值与列目录/播放同源（{@link WebDavClient#authOfUserInfo}：userinfo 是编码形态，
     * 解码后再 Basic）。
     */
    private void hookUrlAuth() {
        final String desc = "java.net.URL#openConnection(图片取流补鉴权)";
        try {
            Method noArg = java.net.URL.class.getDeclaredMethod("openConnection");
            Method withProxy = java.net.URL.class.getDeclaredMethod("openConnection", java.net.Proxy.class);
            noArg.setAccessible(true);
            withProxy.setAccessible(true);
            XposedInterface.Hooker h = new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        injectUrlAuth(result);
                    } catch (Throwable t) {
                        if (BuildConfig.DEBUG) {
                            log(DEBUG, TAG, "[DBG] hook ④ 补鉴权异常（已忽略）: " + t);
                        }
                    }
                    return result;
                }
            };
            hook(noArg).intercept(h);
            hook(withProxy).intercept(h);
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

    /** 取流结果若来自「带 userinfo 的 http(s) URL」且还没有 Authorization，就补上。 */
    private void injectUrlAuth(Object result) {
        if (!(result instanceof java.net.URLConnection)) {
            return;
        }
        java.net.URLConnection conn = (java.net.URLConnection) result;
        String raw;
        try {
            raw = conn.getURL() == null ? "?" : conn.getURL().toExternalForm();
        } catch (Throwable t) {
            raw = "?";
        }
        if (!WebDavClient.injectUrlAuth(conn)) {
            // 这条是给「缩略图仍空白」准备的关键线索：说明宿主确实来取图了，但 URL 里没有凭据
            if (BuildConfig.DEBUG && raw.contains("/dav") && LogBudget.IMAGE_BARE.take()) {
                log(DEBUG, TAG, "[DBG] >> 取流未带凭据（URL 无 userinfo）url="
                        + RemoteEntries.maskUserInfo(raw));
            }
            return;
        }
        if (BuildConfig.DEBUG && LogBudget.IMAGE_AUTH.take()) {
            log(DEBUG, TAG, "[DBG] >> 取流补上 Authorization（图片/缩略图链路） url="
                    + RemoteEntries.maskUserInfo(raw) + " conn=" + conn.getClass().getSimpleName());
        }
        armVerdict(conn, raw);
    }

    // ===== hook ④ 的判据：这条取流最后拿到了什么 =====

    /**
     * 「补过鉴权的连接」→ 其 URL。只用于判定日志，键用<b>对象同一性</b>
     * （同一 URL 的多次取流必须各自记账，否则第二次的响应码会被算到第一次头上）。
     */
    private static final java.util.Map<Object, String> sInjected =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<Object, String>());

    /** 已挂钩的「取流结果」方法（按声明类去重，避免同一 Method 挂两遍）。 */
    private static final java.util.Set<String> sVerdictArmed = new java.util.HashSet<String>();


    /**
     * 给「刚补过鉴权的连接」挂一个<b>只读</b>的取流结果判定，把响应码写进日志。
     *
     * <p>为什么非要有这一条（真机教训）：host 的取图链路对失败是<b>静默</b>的 ——
     * 401 只表现为「占位图 + 日志里什么都没有」。而这条链路有两种截然不同的失败：
     * <ul>
     *   <li>响应码 401 ⇒ 我们的头没落到线上（补鉴权没生效）</li>
     *   <li>响应码 206 / 200 ⇒ 取流成功，问题在<b>宿主侧解码</b></li>
     * </ul>
     * 不把响应码打出来，这两者在真机日志里长得一模一样。206 尤其要能看见：服务端只在带
     * {@code Range} 时回 206，而 {@code MxImageDownloader} 是按「不等于 200 就抛」写的。
     *
     * <p>只对「本次真的补过头」的连接记账；判定失败一律静默，绝不影响宿主行为。
     */
    private void armVerdict(final java.net.URLConnection conn, final String raw) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        try {
            synchronized (sInjected) {
                if (sInjected.size() >= 64) {
                    sInjected.clear();
                }
                sInjected.put(conn, raw);
            }
            final Method rc = conn.getClass().getMethod("getResponseCode");
            rc.setAccessible(true);
            final String key = rc.getDeclaringClass().getName() + "#getResponseCode";
            synchronized (sVerdictArmed) {
                if (!sVerdictArmed.add(key)) {
                    return;
                }
            }
            hook(rc).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        logVerdict(chain.getThisObject(), result);
                    } catch (Throwable ignored) {
                        // 判定日志绝不影响取流
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            synchronized (sVerdictArmed) {
                sVerdictArmed.clear();
            }
            log(DEBUG, TAG, "[DBG] hook ④ 取流判定未挂上（不影响取流）: " + t);
        }
    }

    /** 打完一条判定就销账（同一连接只报一次）。 */
    private void logVerdict(Object self, Object code) {
        String url;
        synchronized (sInjected) {
            url = sInjected.remove(self);
        }
        if (url == null || !LogBudget.IMAGE_VERDICT.take()) {
            return;
        }
        String extra = "";
        try {
            if (self instanceof java.net.HttpURLConnection) {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) self;
                extra = " type=" + c.getContentType() + " len=" + c.getContentLength();
            }
        } catch (Throwable ignored) {
            // 读 header 失败不影响判定本身
        }
        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "[DBG] << 取流判定 HTTP " + code + extra + "（206=带 Range，401=鉴权没生效，"
                    + "200=正常）url=" + RemoteEntries.maskUserInfo(url));
        }
    }

    // ===== hook ③：SmbUtil 缩略图 URL 构造 =====

    /** 本 hook 的 DEBUG 明细只打前几次（列表页每次滚动都会调它，逐行打会淹掉日志）。 */

    /**
     * 起环回缩略图服务（{@link ThumbServer}）；失败只是让 hook ③ 退回旧行为，不影响任何其它功能。
     */
    private void startThumbServer() {
        if (sThumbServer != null) {
            return;
        }
        sThumbServer = ThumbServer.start(new ThumbServer.Sink() {
            @Override
            public void info(String msg) {
                log(INFO, TAG, msg);
            }

            @Override
            public void debug(String msg) {
                if (BuildConfig.DEBUG) {
                    log(DEBUG, TAG, "[DBG] " + msg);
                }
            }

            @Override
            public void error(String msg, Throwable t) {
                log(ERROR, TAG, msg, t);
            }
        }, new ThumbFrames());
    }

    /**
     * MX 生成缩略图 URL 时用 {@code uri.getHost() + uri.getPath()} 重建（丢端口、凭据取字段原值）。
     *
     * <p>对 http(s) 的**视频**条目返回环回缩略图服务的 URL：宿主取的是条目自身 URL、
     * 且整片下载<b>不发 {@code Range}</b>（真机实测 {@code 200} + {@code len=391840912} + {@code video/mp4}），
     * 它面对整片视频只能解码失败 ⇒ 只留占位图。环回服务用 {@code Range} 分片取流 + 抽帧，
     * 把一张**真 JPEG** 放到宿主面前（宿主自己的图片加载器照旧工作，不需要它支持任何新东西）。
     *
     * <p>非 http(s)（SMB）、非视频、或服务未起来 ⇒ 一律退回旧行为（返回条目 {@code path}）：
     * 那时最坏也就是今天的「空白缩略图」，绝不会影响浏览与播放。
     */
    private void hookThumbnailUrl(Method target) {
        final String desc = "SmbUtil#buildThumbUrl";
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
                    String path = RemoteEntries.pathOf(entry);
                    if (!HookTargets.isHttpUrl(path)) {
                        return chain.proceed();
                    }
                    ThumbServer server = sThumbServer;
                    if (server != null && ThumbServer.isVideoPath(path)) {
                        String[] cred = credentialsOf(entry);
                        String url = server.urlFor(path, cred[0], cred[1]);
                        if (url != null) {
                            if (BuildConfig.DEBUG && LogBudget.THUMB_URL.take()) {
                                log(DEBUG, TAG, "[DBG] >> " + desc + " 改用环回缩略图 url=" + url
                                        + " ← path=" + RemoteEntries.maskUserInfo(path));
                            }
                            return url;
                        }
                    }
                    if (BuildConfig.DEBUG && LogBudget.THUMB_URL.take()) {
                        log(DEBUG, TAG, "[DBG] >> " + desc + " 改用条目 path="
                                + RemoteEntries.maskUserInfo(path)
                                + (server == null ? "（环回服务未启动）"
                                : (ThumbServer.isVideoPath(path) ? "" : "（非视频扩展名）")));
                    }
                    return path;
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
