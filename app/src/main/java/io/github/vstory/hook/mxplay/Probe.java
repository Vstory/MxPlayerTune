package io.github.vstory.hook.mxplay;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 真机探针：把「宿主那套 native 抽帧引擎能不能复用」问出答案（<b>零外网流量</b>）。
 *
 * <p><b>它要回答的三个问题</b>（决定「B 方案」（我们取流 + 宿主抽帧）值不值得做）：
 * <ol>
 *   <li><b>P0</b>：宿主真要的缩略图像素尺寸是多少？（读 {@code SmbUtil.a(…, w, h, …)} 的入参，
 *       而不是拿 144dp 去推算 —— 那块屏幕的密度是我们唯一没法从静态分析里得到的量）</li>
 *   <li><b>P1/P2</b>：同一个<b>本地</b>文件上，平台的 {@code MediaMetadataRetriever} 与 MX 的
 *       {@code MediaReader.extractThumb} 抽出来的首帧<b>是不是同一张</b>、谁更快、
 *       旋转有没有被引擎自己烘焙进 Bitmap。</li>
 *   <li><b>P3</b>：{@code MediaReader.native_create(<b>http://127.0.0.1…</b>, false)} 会不会去取这条
 *       URL？（B 方案的全部前提；用「本地文件 + 回环 http」问，因此<b>不产生任何外网流量</b>）</li>
 * </ol>
 *
 * <p><b>为什么不碰远端 WebDAV</b>：真机上现成的 http 源只有那个自建 WebDAV，用它就要走用户的上游
 * 流量 —— 而本项目对「会产生大额流量的测试」有明确纪律（先授权）。本地文件 + 回环 http 足以
 * 回答「native 会不会发 http 请求」这个**能力性**问题；真要测真实服务端，那是拿到授权之后的下一步。
 *
 * <p><b>怎么触发</b>：debug 版启动时看 {@link #TRIGGER}（本机日志目录）里有没有配置；
 * 有就跑一次、把结论写进 {@link #REPORT}（同时也进 LSPosed 日志）。release 版整类不参与构建
 * （调用点在 {@code if (BuildConfig.DEBUG)} 内 ⇒ R8 连类一起摘掉）。
 *
 * <p><b>零风险的自我约束</b>（探针绝不能把 MX 弄坏）：
 * <ul>
 *   <li>只读 MX 的类与方法，<b>不 hook 生产链路</b>（唯一的 hook 是 P0 那个只读尺寸观察器，
 *       且只在触发文件存在时挂）；</li>
 *   <li>{@code MediaReader} 的 {@code <clinit>} 会调 native 的 {@code nativeClassInit}
 *       ⇒ <b>必须等 {@code libmxvp.so} 载入之后</b>再触发类初始化：否则静态初始化失败会把该类
 *       <b>永久标记为错误</b>（后续连 MX 自己也会拿到 {@code NoClassDefFoundError}）。
 *       所以先轮询 {@code /proc/self/maps} 看库在不在（零副作用），不在就不碰它；</li>
 *   <li>native 句柄一律 {@code native_release}，{@code MediaMetadataRetriever} 一律 {@code release}，
 *       回环 http 服务用完即关。</li>
 * </ul>
 */
final class Probe {

    /** 探针文件目录（= 本机日志目录；MX 有 {@code MANAGE_EXTERNAL_STORAGE}，可直读直写）。 */
    static final String DIR = "/storage/emulated/0/Download/Ai/logs";

    /** 触发文件：存在即跑一次。首行 = 一个<b>本地</b>视频文件的绝对路径；可选的 {@code key=value} 行见 {@link #parse}。 */
    static final String TRIGGER = DIR + "/mxpt-probe.txt";

    /** 结论文件（人类可读；同时会把摘要写进 LSPosed 日志）。 */
    static final String REPORT = DIR + "/mxpt-probe-result.txt";

    /** 等宿主来请求一次缩略图（P0）的上限。 */
    private static final long DEFAULT_WAIT_MS = 25_000L;

    /** 首次尝试前的延迟：别跟 App 启动抢 CPU。 */
    private static final long START_DELAY_MS = 3_000L;

    /** 等 native 库载入的重试次数与间隔（MX 在 App 初始化时 {@code loadLibrary("mxvp")}）。 */
    private static final int LIB_ATTEMPTS = 8;
    private static final long LIB_RETRY_MS = 5_000L;

    /** 帧指纹的网格（8×8 = 64 个采样点，每点 4 bit 亮度 ⇒ 64 个 hex 字符）。 */
    private static final int GRID = 8;

    /** 亮度低于这个值就认为「近似全黑」（首帧是黑场是缩略图最常见的翻车形态）。 */
    private static final int DARK_LUM = 12;

    private static volatile boolean sStarted;

    /**
     * 目标应用的 classloader（{@code MediaReader} 必须用<b>它</b>去加载 —— 模块自己的
     * classloader 看不见 MX 的类）。
     */
    private static volatile ClassLoader sTargetCl;

    /** 日志出口（由 MainHook 注入：本类不直接引用 BuildConfig）。 */
    interface Bridge {
        void info(String msg);

        void error(String msg, Throwable t);

        /** 只读挂一次「宿主请求尺寸」观察器；返回是否挂上。 */
        boolean armSizeProbe(Method entry, SizeSink seen);
    }

    /** P0 的收数口。 */
    interface SizeSink {
        void seen(int w, int h);
    }

    private Probe() {
    }

    /**
     * 触发入口（调用点在 {@code MainHook.onPackageReady} 的 {@code if (BuildConfig.DEBUG)} 内）。
     *
     * <p>非阻塞：开一条 daemon 线程，先读配置，没有触发文件就静默返回（debug 日志一行）。
     */
    static void maybeStart(final ClassLoader cl, final String process, final String version,
                           final Bridge bridge) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        sTargetCl = cl;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sleep(START_DELAY_MS);
                    Config cfg = readConfig();
                    if (cfg == null) {
                        bridge.info("[probe] 未触发（无 " + TRIGGER + " 或首行没写视频路径；放一个含「本地视频绝对路径」的"
                                + "文本文件即可跑一次缩略图抽帧对照）");
                        return;
                    }
                    if (cfg.alreadyDone) {
                        bridge.info("[probe] 已跑过（报告比触发文件新）⇒ 不重复跑；想再跑就重新保存触发文件，"
                                + "或删掉 " + REPORT);
                        return;
                    }
                    runProbe(cfg, cl, process, version, bridge);
                } catch (Throwable t2) {
                    bridge.error("[probe] 异常中止（不影响宿主）", t2);
                }
            }
        }, "MxPlayerTune-probe");
        t.setDaemon(true);
        t.start();
    }

    // ===== 配置 =====

    private static final class Config {
        String src;
        long waitMs = DEFAULT_WAIT_MS;
        int width = ThumbSize.WIDTH;
        int height = ThumbSize.HEIGHT;
        /** 报告比触发文件新 ⇒ 已经跑过，不重复跑（否则每次开 MX 都白跑一轮）。 */
        boolean alreadyDone;
    }

    /**
     * 读触发文件。格式（大小写不敏感，{@code #} 起始行与空行忽略）：
     * <pre>
     * /storage/emulated/0/Movies/foo.mp4     ← 首行：本地视频路径（必填）
     * wait=30000                             ← 等宿主请求缩略图的上限（毫秒，可选）
     * size=432x252                           ← P2/P3 抽帧用的目标尺寸（可选，默认 ThumbSize）
     * </pre>
     *
     * <p><b>幂等</b>：{@link #REPORT} 比触发文件新就认为已经跑过 ⇒ 不重复跑
     * （否则每开一次 MX 都会白等一轮 P0 + 多抽几帧）。想再跑：重新保存触发文件（改 mtime），
     * 或删掉报告文件。
     */
    private static Config readConfig() {
        File f = new File(TRIGGER);
        if (!f.isFile()) {
            return null;
        }
        Config c = new Config();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    if (s.startsWith("wait=")) {
                        c.waitMs = clampLong(s.substring(5), 0, 120_000L, c.waitMs);
                    } else if (s.startsWith("size=")) {
                        int x = s.toLowerCase(java.util.Locale.ROOT).indexOf('x');
                        if (x > 5) {
                            int w = (int) clampLong(s.substring(5, x), 16, 4096, c.width);
                            int h = (int) clampLong(s.substring(x + 1), 16, 4096, c.height);
                            c.width = w;
                            c.height = h;
                        }
                    } else if (c.src == null) {
                        c.src = s;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return null;
        }
        if (c.src == null) {
            return null;
        }
        File rep = new File(REPORT);
        c.alreadyDone = rep.isFile() && rep.lastModified() >= f.lastModified();
        return c;
    }

    private static long clampLong(String s, long lo, long hi, long dflt) {
        try {
            long v = Long.parseLong(s.trim());
            return v < lo ? lo : (v > hi ? hi : v);
        } catch (Throwable t) {
            return dflt;
        }
    }

    // ===== 主流程 =====

    private static void runProbe(Config cfg, ClassLoader cl, String process, String version, Bridge bridge) {
        List<String> out = new ArrayList<>();
        File src = new File(cfg.src);
        DisplayMetrics dm = null;
        try {
            dm = Resources.getSystem().getDisplayMetrics();
        } catch (Throwable ignored) {
            // 取不到密度不影响结论，只是少一行背景信息
        }
        out.add("MxPlayerTune 抽帧探针报告  " + new java.util.Date());
        out.add("进程 = " + process + " / 目标版本 = " + version);
        out.add("源文件 = " + cfg.src + (src.isFile() ? "（" + src.length() + " 字节）" : "（**不存在**）"));
        if (dm != null) {
            out.add("屏幕 = " + dm.widthPixels + "x" + dm.heightPixels + " / density=" + dm.density
                    + " / densityDpi=" + dm.densityDpi
                    + " ⇒ 宿主那一格 144dp×84dp ≈ " + Math.round(144 * dm.density) + "×"
                    + Math.round(84 * dm.density) + " px");
        }
        out.add("目标尺寸 = " + cfg.width + "x" + cfg.height + "（ThumbSize=" + ThumbSize.WIDTH
                + "x" + ThumbSize.HEIGHT + " q" + ThumbSize.QUALITY + "）");
        out.add("");

        // P0：宿主真正要的像素尺寸（等它请求一次缩略图）
        int[] host = probeHostSize(cl, cfg, bridge, out);

        if (!src.isFile()) {
            out.add("结论：源文件不存在 ⇒ 除 P0 外无结论（把首行改成一个本地视频的绝对路径）");
            finish(out, bridge);
            return;
        }

        int w = host != null ? host[0] : cfg.width;
        int h = host != null ? host[1] : cfg.height;

        // P1：平台抽取器（我们现在生产路径用的就是这个）
        Frame p1 = mmrFile(cfg.src);
        line(out, "P1 平台 MMR（本地文件）", p1);

        // P2：MX 自己的 native 引擎（本地文件）
        Frame p2 = nativeFrame(cfg.src, w, h, out);
        line(out, "P2 MX native（本地文件）", p2);

        // P3：MX 自己的 native 引擎 + **回环 http**（B 方案的全部前提）
        Frame p3 = new Frame();
        ProbeRange server = null;
        try {
            server = new ProbeRange(src, extOf(src.getName()));
            p3 = nativeFrame(server.url(), w, h, out);
            line(out, "P3 MX native（回环 http）", p3);
            out.add("     P3 取数明细：请求 " + server.requests() + " 次 / 带 Range "
                    + server.ranged() + " 次 / 首个 Range 起点 " + server.firstOffset()
                    + "（0 = 从头读；-1 = 一条都没带 Range）");
        } catch (Throwable t) {
            p3.err = "探针自身失败: " + t;
            out.add("P3 MX native（回环 http）= 探针自身失败: " + t);
        } finally {
            if (server != null) {
                server.stop();
            }
        }

        // 判定
        out.add("");
        out.add("结论：");
        out.add("  1) 帧是否一致（P1 vs P2，8x8 亮度指纹）: "
                + (p1.ok && p2.ok ? (p1.sig.equals(p2.sig) ? "**一致**" : "不一致 ⇒ 两者取的不是同一瞬间/同一变换")
                : "无法比较（有一侧失败）"));
        out.add("  2) 谁更快（本地文件、同一目标尺寸）: " + speed(p1, p2));
        out.add("  3) native 是否接受回环 http（B 方案的唯一前提）: "
                + (p2.ok ? (p3.ok ? "**接受**（P3 成功）⇒ B 方案可做"
                : "**不接受/失败**（P2 成功而 P3 失败 ⇒ 与库载入无关，是 http 这条路本身不行）"
                        + " 原因: " + p3.err)
                : "无法判定（P2 都没成功，先看 P2 的失败原因）"));
        finish(out, bridge);
    }

    private static String speed(Frame p1, Frame p2) {
        if (!p1.ok || !p2.ok) {
            return "无法比较";
        }
        if (p1.ms == p2.ms) {
            return "一样（均 " + p1.ms + " ms）";
        }
        return p1.ms < p2.ms ? "平台 MMR 更快（" + p1.ms + " vs " + p2.ms + " ms）"
                : "MX native 更快（" + p2.ms + " vs " + p1.ms + " ms）";
    }

    /** P0：等宿主请求一次缩略图，记下它要的像素尺寸。 */
    private static int[] probeHostSize(ClassLoader cl, Config cfg, Bridge bridge, List<String> out) {
        final int[] got = new int[2];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            Class<?> entry = HookTargets.loadQuiet(cl, HookTargets.CLS_REMOTE_ENTRY);
            Class<?> util = HookTargets.smbUtil(cl, entry);
            Method load = HookTargets.smbUtilLoadThumb(util);
            if (load == null) {
                out.add("P0 宿主请求尺寸 = 未取到 SmbUtil 的加载入口（类=" + (util == null ? "null" : util.getName()) + "）");
                return null;
            }
            boolean armed = bridge.armSizeProbe(load, new SizeSink() {
                @Override
                public void seen(int w, int h) {
                    if (w > 0 && h > 0) {
                        got[0] = w;
                        got[1] = h;
                        latch.countDown();
                    }
                }
            });
            if (!armed) {
                out.add("P0 宿主请求尺寸 = 观察器没挂上（钩子安装失败）");
                return null;
            }
            long t0 = System.nanoTime();
            boolean ok = latch.await(cfg.waitMs, TimeUnit.MILLISECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ok) {
                out.add("P0 宿主请求尺寸 = " + got[0] + "x" + got[1] + " px（等了 " + ms
                        + " ms；来自 SmbUtil 的缩略图加载入口）");
                return got;
            }
            out.add("P0 宿主请求尺寸 = 未观察到（等了 " + (cfg.waitMs / 1000) + " s 没等到宿主请求缩略图；"
                    + "需要在这段时间里进一次「本地网络/本地文件」里含视频的目录）");
            return null;
        } catch (Throwable t) {
            out.add("P0 宿主请求尺寸 = 观察失败: " + t);
            return null;
        }
    }

    private static void line(List<String> out, String label, Frame f) {
        out.add(label + " = " + (f.ok ? "OK" + " " + f.ms + " ms " + f.w + "x" + f.h
                + " 旋转=" + f.rot + " 指纹=" + f.sig + " 亮度=" + f.lum + (f.dark ? "（近似全黑）" : "")
                + (f.extra.isEmpty() ? "" : " " + f.extra)
                : "失败: " + f.err));
    }

    // ===== P1：平台 MediaMetadataRetriever =====

    private static Frame mmrFile(String path) {
        Frame f = new Frame();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            f.rot = str(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            long t0 = System.nanoTime();
            Bitmap b = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            f.ms = (System.nanoTime() - t0) / 1_000_000L;
            if (b == null) {
                f.err = "getFrameAtTime 返回 null";
                return f;
            }
            f.w = b.getWidth();
            f.h = b.getHeight();
            int[] lumOut = new int[1];
            f.sig = sig(b, lumOut);
            f.lum = lumOut[0];
            f.dark = f.lum < DARK_LUM;
            f.ok = true;
        } catch (Throwable t) {
            f.err = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
                // 释放失败无影响
            }
        }
        return f;
    }

    // ===== P2/P3：MX 的 native 引擎 =====

    /**
     * 用 MX 自己的 {@code MediaReader} 抽一帧（{@code target} 是文件路径或回环 http URL）。
     *
     * <p>步骤与 MX 自己在 {@code MxImageDownloader.i()} 里写的完全一致：
     * {@code native_create(url, false)} → {@code extractThumb(handle, w, h, 5, true)} → {@code native_release}。
     */
    private static Frame nativeFrame(String target, int w, int h, List<String> out) {
        Frame f = new Frame();
        ClassLoader cl = sTargetCl;
        if (cl == null) {
            f.err = "目标 classloader 未取到";
            return f;
        }
        if (!waitForLib()) {
            f.err = "libmxvp.so 一直没载入（探针刻意不主动 loadLibrary："
                    + "静态初始化失败会把 MediaReader 永久标记为错误，连 MX 自己都用不了）";
            return f;
        }
        Class<?> cls;
        try {
            // initialize=true：MediaReader 的 <clinit> 会调 nativeClassInit，必须让它跑完
            cls = Class.forName("com.mxtech.media.MediaReader", true, cl);
        } catch (Throwable t) {
            f.err = "取 MediaReader 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return f;
        }
        long handle = 0;
        try {
            Method create = cls.getMethod("native_create", String.class, boolean.class);
            Method thumb = cls.getMethod("extractThumb", long.class, int.class, int.class, int.class, boolean.class);
            Method release = cls.getMethod("native_release", long.class);
            Method rot = cls.getMethod("rotation", long.class);
            Method gw = cls.getMethod("width", long.class);
            Method gh = cls.getMethod("height", long.class);
            create.setAccessible(true);
            thumb.setAccessible(true);
            release.setAccessible(true);
            rot.setAccessible(true);
            gw.setAccessible(true);
            gh.setAccessible(true);

            long tCreate = System.nanoTime();
            handle = (Long) create.invoke(null, target, false);
            long createMs = (System.nanoTime() - tCreate) / 1_000_000L;
            if (handle == 0) {
                f.err = "native_create 返回 0（打不开这条源）";
                return f;
            }
            f.rot = String.valueOf(rot.invoke(null, handle));
            int sw = (Integer) gw.invoke(null, handle);
            int sh = (Integer) gh.invoke(null, handle);
            long t0 = System.nanoTime();
            Object b = thumb.invoke(null, handle, w, h, 5, true);
            f.ms = (System.nanoTime() - t0) / 1_000_000L;
            if (!(b instanceof Bitmap)) {
                f.err = "extractThumb 返回 null（w=" + w + " h=" + h + "）";
                return f;
            }
            Bitmap bm = (Bitmap) b;
            f.w = bm.getWidth();
            f.h = bm.getHeight();
            int[] lumOut = new int[1];
            f.sig = sig(bm, lumOut);
            f.lum = lumOut[0];
            f.dark = f.lum < DARK_LUM;
            f.extra = "源=" + sw + "x" + sh + " 打开=" + createMs + " ms 参数=(w=" + w + ",h=" + h + ",5,true)";
            f.ok = true;
        } catch (Throwable t) {
            f.err = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (handle != 0) {
                try {
                    Method release = cls.getMethod("native_release", long.class);
                    release.setAccessible(true);
                    release.invoke(null, handle);
                } catch (Throwable ignored) {
                    // 释放失败无影响
                }
            }
        }
        return f;
    }

    /**
     * 等 {@code libmxvp.so} 被 MX 自己载入（探测方式：读本进程的 {@code /proc/self/maps}）。
     *
     * <p>为什么不用 {@code System.loadLibrary("mxvp")} 自己上：MX 用
     * {@code com.mxtech.app.Apps.j(path, name)} 有它自己的载入顺序（依赖 loader.mx 等），
     * 我们从旁边插一脚可能把它的初始化搞坏。而这个模块的所有动作都有同一条底线：
     * <b>绝不因为自己把宿主弄坏</b>。
     */
    private static boolean waitForLib() {
        for (int i = 0; i < LIB_ATTEMPTS; i++) {
            if (libLoaded()) {
                return true;
            }
            sleep(LIB_RETRY_MS);
        }
        return libLoaded();
    }

    /** 本进程是否已载入 {@code libmxvp.so}（读 {@code /proc/self/maps}；读不到就当已载入，交由调用方试错）。 */
    private static boolean libLoaded() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/self/maps"),
                    Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("libmxvp.so")) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                    // 关不掉无影响
                }
            }
        }
    }

    // ===== 帧指纹 =====

    /**
     * 8×8 采样点的指纹：每点亮度取高 4 bit ⇒ 64 个 hex 字符；均值写进 {@code lumOut[0]}。
     *
     * <p>只用来判「两侧抽的是不是同一张图」：同一张必然逐字符相同；差一个瞬间或差一次旋转必然不同。
     * 刻意<b>不</b>做整图哈希（不同解码器对同源像素可能有 ±1 的抖动，那是噪声不是结论）。
     * 亮度均值单独给出来，是为了认出「首帧就是黑场」这种最常见的翻车形态。
     */
    private static String sig(Bitmap b, int[] lumOut) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return "?";
        }
        StringBuilder sb = new StringBuilder(GRID * GRID);
        long sum = 0;
        int n = 0;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int x = Math.min(w - 1, (int) ((gx + 0.5) * w / GRID));
                int y = Math.min(h - 1, (int) ((gy + 0.5) * h / GRID));
                int p = b.getPixel(x, y);
                int l = (((p >> 16) & 0xff) * 30 + ((p >> 8) & 0xff) * 59 + (p & 0xff) * 11) / 100;
                sum += l;
                n++;
                sb.append(Character.forDigit((l >> 4) & 0xf, 16));
            }
        }
        lumOut[0] = n == 0 ? 0 : (int) (sum / n);
        return sb.toString();
    }

    // ===== 收尾 =====

    /** 日志里给摘要（用户导出 LSPosed 日志就能看），再落一份文件（写不进去也必须留日志，不许静默）。 */
    private static void finish(List<String> out, Bridge bridge) {
        StringBuilder sb = new StringBuilder();
        for (String s : out) {
            sb.append(s).append('\n');
        }
        String text = sb.toString();
        for (String s : out) {
            bridge.info("[probe] " + s);
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(new File(REPORT), false),
                    Charset.forName("UTF-8"));
            w.write(text);
            bridge.info("[probe] 结论已写入 " + REPORT + "（" + text.length() + " 字符）");
        } catch (Throwable t) {
            bridge.error("[probe] 结论文件写不进去（结论见上方日志行）: " + REPORT, t);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                    // 关不掉无影响
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String str(String s) {
        return s == null ? "?" : s;
    }

    private static String extOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /** 一次抽取的结果（P1/P2/P3 共用）。 */
    private static final class Frame {
        boolean ok;
        String err = "";
        long ms;
        int w;
        int h;
        String rot = "?";
        String sig = "?";
        int lum;
        boolean dark;
        String extra = "";
    }
}
