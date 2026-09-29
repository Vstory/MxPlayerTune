package io.github.vstory.hook.mxplay;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽帧 → JPEG（环回缩略图服务的内容生产，见 {@link ThumbServer}）。
 *
 * <p><b>两条取流路径，日志里说清走了哪条</b>：
 * <ol>
 *   <li><b>首选：分片取流</b>（{@link RangeSource} + {@code setDataSource(MediaDataSource)}）——
 *       走模块自己的 OkHttp：鉴权、自签证书放宽、连接池都是浏览/取图那一套，http 与 https 一致；</li>
 *   <li><b>退路：平台取流</b>（{@code setDataSource(url, headers)}）—— 原生 HTTP，<b>不认自签证书</b>，
 *       但它对「MMR 不接受 Java 侧 {@code MediaDataSource}」这类设备差异免疫。</li>
 * </ol>
 * 走哪条不写进日志就无法区分「缩略图出不来」到底是哪一层的问题（宿主的取图链路对失败是静默的），
 * 所以成功那行会带上实际路径（见 {@link ThumbServer.Source#how}）。
 *
 * <p><b>取哪一帧</b>：<b>首帧</b>（{@code OPTION_CLOSEST_SYNC}）。
 *
 * <p>为什么不是「10% 处那一眼」（原方案）：真机实测这些文件是 <b>faststart</b>
 * （{@code ftyp + moov(1.34 MB) + mdat}，moov 就在文件头），首帧所需的数据全在「头窗 + 头几 MB」里
 * —— 而本服务端<b>中间区域</b>的随机读要 ~10 s（见 {@link RangeSource} 的实测）。
 * 抽帧多一次深跳，就可能把宿主的图片加载器拖到超时（它一超时就是「占位图」，等于没修）。
 * 换言之：这里用「首帧」换「稳定出图」——一张稍显平淡的图，好过一张永远不出现的图。
 *
 * <p><b>为什么这一段不做离机断言</b>：{@code MediaMetadataRetriever} 是平台件，离机没有等价物。
 * 因此本类刻意保持「薄」——所有会被判错的逻辑（分片区间、总长、偏移校验、HTTP 应答、缓存与令牌）
 * 都在 {@link RangeSource} 与 {@link ThumbServer} 里，那两个类由 verify 的 M/N 段逐条钉住。
 */
final class ThumbFrames implements ThumbServer.Frames {

    /** 生成图宽度上限（宿主列表/卡片的展示尺寸远小于此；再大只是白费流量）。 */
    static final int MAX_WIDTH = 640;

    /** JPEG 质量（80 在 640 宽下约 30–60 KB）。 */
    static final int QUALITY = 80;

    @Override
    public byte[] jpeg(ThumbServer.Source src) throws Exception {
        String auth = (src.user == null || src.user.isEmpty())
                ? null
                : WebDavClient.basicAuth(src.user, src.pass == null ? "" : src.pass);

        Bitmap frame = null;
        Throwable first = null;
        RangeSource range = null;
        try {
            range = new RangeSource(src.url, auth);
            frame = frameFrom(range, null, src.url);
            src.how = "分片取流 Range（" + range.describe() + "）";
        } catch (Throwable t) {
            first = t;
        } finally {
            if (range != null) {
                range.close();
            }
        }

        if (frame == null) {
            try {
                frame = frameFrom(null, auth, src.url);
                src.how = "平台取流 URL+headers（分片取流先失败了：" + brief(first) + "）";
            } catch (Throwable t2) {
                throw new IOException("两条取流路径都没抽到帧：① 分片取流 = " + brief(first)
                        + "；② 平台取流 = " + brief(t2), t2);
            }
        }

        Bitmap scaled = scale(frame, MAX_WIDTH);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                throw new IOException("JPEG 编码失败（compress 返回 false）");
            }
            return out.toByteArray();
        } finally {
            if (scaled != frame) {
                scaled.recycle();
            }
            frame.recycle();
        }
    }

    /**
     * 用一条取流路径抽一帧。
     *
     * <p>{@code range != null} 走分片取流（{@code MediaDataSource}），否则走平台取流（URL + 头）。
     * 两种都失败时抛异常（调用方决定是否换路径）。
     */
    private static Bitmap frameFrom(RangeSource range, String auth, String url) throws IOException {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (range != null) {
                mmr.setDataSource(range);
            } else {
                mmr.setDataSource(url, headersOf(auth));
            }
            Bitmap b = frameOf(mmr);
            if (b == null) {
                throw new IOException("抽取器没给出任何帧");
            }
            return b;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
                // 释放失败无影响
            }
        }
    }

    /** 取帧：首帧（关键帧），拿不到再退「任意一帧」。 */
    private static Bitmap frameOf(MediaMetadataRetriever mmr) {
        Bitmap b = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (b == null) {
            b = mmr.getFrameAtTime(-1);
        }
        return b;
    }

    private static Map<String, String> headersOf(String auth) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "MxPlayerTune");
        if (auth != null && !auth.isEmpty()) {
            h.put("Authorization", auth);
        }
        return h;
    }

    /** 等比缩到宽度上限（原图已够小则原样返回）。 */
    private static Bitmap scale(Bitmap src, int maxWidth) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxWidth || w <= 0) {
            return src;
        }
        int h2 = Math.max(1, (int) Math.round(h * (double) maxWidth / w));
        return Bitmap.createScaledBitmap(src, maxWidth, h2, true);
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "（无）";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
