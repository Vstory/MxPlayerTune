package io.github.vstory.hook.mxplay;

/**
 * 缩略图目标尺寸与质量（<b>唯一来源</b>）。
 *
 * <p><b>为什么单独一个类、且刻意不 import 任何 Android 类型</b>：本模块只有两类东西能离机断言 ——
 * 纯逻辑（环回服务契约、分片取数）与这里的<b>常量与算式</b>。抽帧本身依赖平台件
 * （{@code MediaMetadataRetriever}）没法定机外跑，但它「缩到多大」是纯算术，放在
 * {@link ThumbFrames} 里就跟着平台依赖一起离线了 ⇒ 拆到这里（verify 的 O 段钉住）。
 *
 * <p><b>为什么是 432×252</b>（原先 640 宽）：宿主要的就是「列表/卡片那一格」——
 * {@code SmbUtil.a(imageView, url, w, h, …)} 收的正是 {@code du.l} 那个 Pair
 * （退化时取 {@code L.f}/{@code L.g}），真机形态 <b>144dp × 84dp</b>（12:7）；
 * 3 倍密度 ⇒ <b>432 × 252 px</b>。而 MX 侧的图片配置又是 {@code RGB_565} 解码
 * ⇒ 给 640 宽只是白花字节 + 让宿主解码器再缩一次。
 *
 * <p>高度只是<b>参考框</b>（宿主那一格是 12:7）：真正的缩放按视频自身比例算
 * （{@link #scaledHeight}）—— 把 16:9 硬压成 12:7 会变形。
 *
 * <p>⚠️ 这个值要改，先动 <b>verify 的 O 段</b>（那里钉着「上限不许超过宿主那一格的量级」
 * 与「宽高比就是 12:7」），否则改了也没人拦。
 */
final class ThumbSize {

    /** 目标宽度上限（宿主那一格 144dp × 3 倍密度）。 */
    static final int WIDTH = 432;

    /** 宿主那一格的高（84dp × 3）—— 只作参考框，不参与变形。 */
    static final int HEIGHT = 252;

    /** JPEG 质量（432 宽下约 15–35 KB）。 */
    static final int QUALITY = 80;

    private ThumbSize() {
    }

    /**
     * 等比缩到宽度上限后应有的高度：源图已够窄 ⇒ 原样返回；源尺寸非法 ⇒ 0。
     *
     * <p>返回 0 表示「不要缩」，调用方需按自己的语义处理（抽帧那边直接回原图）。
     */
    static int scaledHeight(int srcW, int srcH, int maxWidth) {
        if (srcW <= 0 || srcH <= 0 || maxWidth <= 0) {
            return 0;
        }
        if (srcW <= maxWidth) {
            return srcH;
        }
        return Math.max(1, (int) Math.round(srcH * (double) maxWidth / srcW));
    }
}
