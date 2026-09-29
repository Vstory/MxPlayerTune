package io.github.vstory.hook.mxplay;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 「按 {@code Range} 分片取流」的 {@link MediaDataSource}：把服务端的分片能力喂给媒体抽取器。
 *
 * <p><b>为什么需要它</b>：宿主的图片下载器整片下载（不发 {@code Range}），而我们自己抽帧
 * （{@link ThumbFrames}）必须能<b>随机寻址</b>—— MP4 的 {@code moov} 可能在文件尾、首帧的关键帧
 * 也可能在几十 MB 之后。本机 CloudDrive 实测支持分片（带 {@code Range} ⇒ {@code 206} +
 * {@code Content-Range}），所以这条路走得通。
 *
 * <p><b>为什么不让平台自己取流</b>（{@code MediaMetadataRetriever.setDataSource(url, headers)}）：
 * 那条路是原生 HTTP，<b>不认自签证书</b>（本模块的浏览/取图都能放宽 TLS，播放/抽帧却不该退步），
 * 也拿不到任何可诊断的中间状态。所以首选本类（走模块自己的 OkHttp：鉴权、自签放宽、连接池都是同一套），
 * 平台取流只作退路（见 {@link ThumbFrames}）。
 *
 * <p><b>为什么这一路单独要一条「读超时更宽」的客户端</b>（真机实测，2026-09-29）：同一个 338 MB 的 mp4，
 * 顺序区（0 / 1 MiB / 4 MiB / 8 MiB / 尾部）读 64 KiB 只要 0.1–0.7 s，而<b>中间区域</b>的一次随机读要
 * <b>~10.07 s</b>（上云服务端要先去上游 seek）。用列目录那条 10 s 超时，抽取器一次深跳就必然超时。
 *
 * <p><b>两个必须当场对的判据</b>（错了都不会报错，只会「缩略图空白」）：
 * <ol>
 *   <li>{@link #getSize()} 必须返回真实总长：{@code MediaDataSource} 的 {@code -1} 表示「不可寻址」，
 *       抽取器会直接拒收（{@code IllegalArgumentException}）⇒ 总长取自 {@code Content-Range}，取不到就抛；</li>
 *   <li>{@code 206} 的区间起点必须与我们请求的一致，否则复制的字节落在错误偏移上
 *       （静默产生「垃圾数据 → 解码失败」）⇒ 起点不符即抛（见 {@link #firstOf}）。</li>
 * </ol>
 *
 * <p>首段（默认 1 MiB）在构造时一次取回并留在内存：抽取器对文件头的读法是一串小读
 * （解析 atom），若每读一次发一个请求，真机上就是几十次往返。窗口内的读全部命中内存。
 */
final class RangeSource extends MediaDataSource {

    /** 头窗大小（构造时一次取回；抽取器解析 atom 的读绝大多数落在这里）。 */
    static final int HEAD_BYTES = 1 << 20;

    /** 单次 {@code readAt} 最多向服务端要多少字节。 */
    static final int MAX_READ = 1 << 20;

    private final String url;
    private final String auth;
    private final OkHttpClient client;
    private final byte[] head = new byte[HEAD_BYTES];
    private final long total;
    private final boolean rangeIgnored;

    private int headLen;
    private int requests = 1;

    /**
     * 探测总长并取回头窗。
     *
     * <p>服务端若忽略 {@code Range}（回 {@code 200}）：总长取 {@code Content-Length}（拿不到即抛），
     * 并标记 {@link #rangeIgnored} ⇒ 之后的随机读一律快速失败（而不是悄悄读错位置）。
     */
    RangeSource(String url, String auth) throws IOException {
        this.url = url;
        this.auth = auth;
        this.client = WebDavClient.fetchClient();
        long size;
        boolean ignored = false;
        int len;
        Response resp = client.newCall(request(0, HEAD_BYTES)).execute();
        try {
            int code = resp.code();
            if (code == 206) {
                size = totalOf(resp.header("Content-Range"));
                if (size < 0) {
                    throw new IOException("206 但 Content-Range 不可用（" + resp.header("Content-Range")
                            + "）⇒ 拿不到总长，抽帧不可行 url=" + url);
                }
                long start = firstOf(resp.header("Content-Range"));
                if (start != 0) {
                    throw new IOException("206 的区间起点不是 0（" + resp.header("Content-Range")
                            + "）⇒ 分片语义不可信 url=" + url);
                }
                len = readInto(resp, head, 0, (int) Math.min(HEAD_BYTES, size));
            } else if (code == 200) {
                long cl = resp.body() == null ? -1 : resp.body().contentLength();
                ignored = true;
                len = readInto(resp, head, 0, HEAD_BYTES);
                if (cl < 0) {
                    throw new IOException("服务端忽略 Range 且未给 Content-Length ⇒ 无法确定总长 url=" + url);
                }
                size = cl;
            } else {
                throw new IOException("取流 HTTP " + code + "（期望 206/200）url=" + url);
            }
        } finally {
            resp.close();
        }
        this.total = size;
        this.headLen = len;
        this.rangeIgnored = ignored;
    }

    @Override
    public long getSize() {
        return total;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (size <= 0) {
            return 0;
        }
        if (position >= total) {
            return -1;
        }
        int want = (int) Math.min((long) size, MAX_READ);
        if (position < headLen) {
            int avail = (int) Math.min(headLen - position, want);
            System.arraycopy(head, (int) position, buffer, offset, avail);
            return avail;
        }
        if (rangeIgnored) {
            throw new IOException("服务端忽略 Range（不支持分片）⇒ 只能顺序读，抽帧不可行 position="
                    + position + " url=" + url);
        }
        return fetch(position, buffer, offset, want);
    }

    @Override
    public void close() {
        // 连接由共享客户端持有，这里无可关资源
    }

    /** 诊断（进日志用；不含凭据）。 */
    String describe() {
        return "总长=" + total + " 头窗=" + headLen + " 请求=" + requests
                + (rangeIgnored ? "（服务端忽略了 Range）" : "");
    }

    /** 断言/诊断用：本实例用的客户端（必须是「抽帧那条宽读超时」的，见 {@link WebDavClient#fetchClient()}）。 */
    OkHttpClient client() {
        return client;
    }

    private int fetch(long position, byte[] buffer, int offset, int want) throws IOException {
        requests++;
        Response resp = client.newCall(request(position, want)).execute();
        try {
            int code = resp.code();
            if (code == 416) {
                return -1;   // 越过文件尾（抽取器偶发探测）
            }
            if (code != 206) {
                throw new IOException("分片取流 HTTP " + code + "（期望 206）pos=" + position + " url=" + url);
            }
            long start = firstOf(resp.header("Content-Range"));
            if (start != position) {
                throw new IOException("206 的区间起点不符（要 " + position + "，回 "
                        + resp.header("Content-Range") + "）⇒ 偏移不可信 url=" + url);
            }
            return readInto(resp, buffer, offset, want);
        } finally {
            resp.close();
        }
    }

    private Request request(long position, int len) {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + position + "-" + (position + len - 1))
                .header("User-Agent", "MxPlayerTune");
        if (auth != null && !auth.isEmpty()) {
            b.header("Authorization", auth);
        }
        return b.build();
    }

    private static int readInto(Response resp, byte[] buf, int off, int max) throws IOException {
        if (max <= 0 || resp.body() == null) {
            return 0;
        }
        InputStream in = resp.body().byteStream();
        int n = 0;
        while (n < max) {
            int r = in.read(buf, off + n, max - n);
            if (r <= 0) {
                break;
            }
            n += r;
        }
        return n;
    }

    // ===== Content-Range 解析（纯函数，离机可验证）=====

    /** {@code bytes 0-1048575/391840912} → 起点（认不出返回 -1）。 */
    static long firstOf(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        String s = contentRange.trim();
        int sp = s.indexOf(' ');
        if (sp >= 0) {
            s = s.substring(sp + 1);
        }
        int dash = s.indexOf('-');
        if (dash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(s.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code bytes 0-1048575/391840912} → 总长（{@code *} 或不认识返回 -1）。 */
    static long totalOf(String contentRange) {
        if (contentRange == null) {
            return -1;
        }
        String s = contentRange.trim();
        int slash = s.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        String t = s.substring(slash + 1).trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
