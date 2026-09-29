package io.github.vstory.hook.mxplay;

/**
 * 一次性明细日志的「配额」：同一条日志只打前几次就够（目录滚动 / 列表刷新会反复触发）。
 *
 * <p><b>为什么要单独一个类（真机事故）</b>：配额一旦被<b>共用</b>，噪声就会把关键那条挤掉。
 * 真机原样（见 hook信息记录.md §三.4）：hook ④ 的「补上 Authorization（缩略图链路）」与
 * 「宿主来取图但 URL 无凭据」原本共用一个计数器（上限 8），而宿主自己取列目录 URL 时也不带
 * 凭据 ⇒ 7 条噪声 + 1 条真线索正好用满，之后 {@code buildThumbUrl} 再被调用<b>也没有日志</b>。
 * 这会把人引向两个错误结论：「宿主没去取图」或「命中缓存了」——实际只是配额用光。
 *
 * <p>所以这里的规矩是：<b>一条日志一个配额，关键那条给足</b>，并且把「配额对象本身」暴露出来
 * 供离机断言钉住（verify 的 L 段钉「关键配额不被噪声配额挤掉」与「三个配额互不相同」）。
 *
 * <p>线程安全：取名额可能发生在多个取图线程上（真机日志里就能看到 4 个线程号），
 * 故 {@link #take()} 用同步；计数只增不减（不需要 reset，进程内一次性）。
 */
final class LogBudget {

    /** hook ④：补鉴权成功（图片/缩略图链路）—— 判断缩略图为什么不出来的<b>关键</b>线索。 */
    static final LogBudget IMAGE_AUTH = new LogBudget(8);

    /** hook ④：宿主来取图、但 URL 里没有凭据 —— 噪声多，另给一份小配额，绝不与上面共用。 */
    static final LogBudget IMAGE_BARE = new LogBudget(3);

    /** hook ④：取流判定（响应码）。 */
    static final LogBudget IMAGE_VERDICT = new LogBudget(6);

    /** hook ③：缩略图 URL 构造。 */
    static final LogBudget THUMB_URL = new LogBudget(3);

    /** 环回缩略图服务：每个请求一行（200 / 命中缓存 / 404）。 */
    static final LogBudget THUMB_SERVE = new LogBudget(8);

    /** 环回缩略图服务：渲染成功一行（含耗时与**实际走的那条取流路径**）。 */
    static final LogBudget THUMB_RENDER = new LogBudget(6);

    /** 环回缩略图服务：渲染失败一行 —— 宿主只会显示占位图、不报错，这一行是唯一线索，给足。 */
    static final LogBudget THUMB_FAIL = new LogBudget(8);

    private final int max;
    private int used;

    LogBudget(int max) {
        this.max = max < 0 ? 0 : max;
    }

    /** 取一个名额；返回 true 才允许打这条日志。 */
    synchronized boolean take() {
        if (used >= max) {
            return false;
        }
        used++;
        return true;
    }

    synchronized int used() {
        return used;
    }

    int max() {
        return max;
    }
}
