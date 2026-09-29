#!/usr/bin/env python3
"""断言 ⑦ 的**自测**：证明 `dex_trim_check.py` 真的会失败。

为什么必须有这个文件：一个永远不会失败的门禁 = 没有门禁。上面那八组断言全是「读产物做判断」，
而「判断代码本身写错了」是完全静默的 —— 比如拼错一个常量、把 `in` 写成 `not in`、
或某组因异常提前 return 0。CI 里绿得毫无意义，直到某天真出了事才发现它早就瞎了。

做法：拿**真产物**当基线，逐例做**最小篡改**，每例都必须
  ① 退出码非 0，且 ② 输出里出现**该组特有**的报错字样（否则「碰巧因为别的原因红了」也算过）。

覆盖：基线（未篡改必须绿）+ 八组各自至少一例 + 「账本缺失必须硬失败」。

用法:
    python3 tools/dex_trim_check_selftest.py <debug.apk> <release.apk> --mapping-dir <.../mapping/release>
"""

import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.join(HERE, "dex_trim_check.py")

M_DBG_MARKER = b"[pkg] onPackageReady, classLoader="
PROVIDER = "io.github.libxposed.service.XposedProvider"


def rewrite_zip(src, dst, overrides):
    """按 ZipInfo 原样复制，只换掉 overrides 里的条目正文。"""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, overrides.get(item.filename, zin.read(item.filename)))


def patch_dex(apk_src, apk_dst, old, new):
    """在 classes*.dex 里做**等长**字节替换（不解析 dex，门禁也只是做字节搜索）。

    注意 debug 包可能是多 dex（`classes.dex` + `classes2.dex`…），目标串只在其中一个里，
    所以按条目各判各的，别对每个条目都断言「必须含它」。
    """
    assert len(old) == len(new), "等长替换：长度不等会破坏后续所有偏移"
    with zipfile.ZipFile(apk_src) as zf:
        overrides, hits = {}, 0
        for n in zf.namelist():
            if n.startswith("classes") and n.endswith(".dex"):
                blob = zf.read(n)
                if old in blob:
                    hits += blob.count(old)
                    overrides[n] = blob.replace(old, new)
        assert hits, f"{old!r} 不在 {apk_src} 的任何 classes*.dex 里，篡改无效"
    rewrite_zip(apk_src, apk_dst, overrides)


def write_text(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def read_text(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def run_gate(debug_apk, release_apk, mapping_dir):
    proc = subprocess.run(
        [sys.executable, GATE, debug_apk, release_apk, "--mapping-dir", mapping_dir],
        capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


def main(argv):
    apks, mapping_dir = [], None
    args = argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--mapping-dir":
            i += 1
            mapping_dir = args[i]
        else:
            apks.append(args[i])
        i += 1
    if len(apks) != 2 or not mapping_dir:
        raise SystemExit("用法: dex_trim_check_selftest.py <debug.apk> <release.apk> "
                         "--mapping-dir <.../mapping/release>")
    debug_apk, release_apk = apks

    work = tempfile.mkdtemp(prefix="dexcheck-selftest-")
    results = []

    def case(name, expect_substr, *, debug=None, release=None, mapping=None, mapping_missing=False):
        """跑一例：expect_substr=None 表示期望绿（基线）。"""
        d = debug or debug_apk
        r = release or release_apk
        if mapping_missing:
            m = os.path.join(work, f"empty-{name}")
            os.makedirs(m, exist_ok=True)
        else:
            m = os.path.join(work, f"map-{name}")
            os.makedirs(m, exist_ok=True)
            src = mapping or mapping_dir
            for f in ("seeds.txt", "mapping.txt"):
                if os.path.isfile(os.path.join(src, f)):
                    shutil.copy(os.path.join(src, f), os.path.join(m, f))
        code, out = run_gate(d, r, m)
        if expect_substr is None:
            ok = code == 0
            detail = "期望绿" if not ok else ""
        else:
            ok = code != 0 and expect_substr in out
            detail = f"期望非 0 且含「{expect_substr}」"
        results.append((name, ok, code, detail))
        if not ok:
            print(f"  ✗ {name}（{detail}，实际退出码 {code}）")
            print("    " + "\n    ".join(out.strip().splitlines()[-6:]))
        else:
            print(f"  ✓ {name}  → 退出码 {code}")

    def seeded(name):
        """复制一份 mapping 目录，用 mutator 改；返回 (seeds_path, mapping_path)。"""
        d = os.path.join(work, f"m-{name}")
        os.makedirs(d, exist_ok=True)
        for f in ("seeds.txt", "mapping.txt"):
            shutil.copy(os.path.join(mapping_dir, f), os.path.join(d, f))
        return d

    print("断言 ⑦ 自测（篡改真产物 ⇒ 必须被对应那组拦下）")

    # 基线：未篡改必须绿（否则所有负例的“红”都不说明问题）。
    case("基线（未篡改）", None)

    # ①②④ 变体裁剪类：把 debug 包当 release 用 → 既有 [DBG] 又有探针。
    case("①②④ debug 包冒充 release", "不该带 D 级日志", release=debug_apk)

    # ② debug 侧被削：把 release 包当 debug 用 → debug 必须非零那条要响。
    case("② release 包冒充 debug", "没有任何 [DBG]", debug=release_apk)

    # ③ 按名引用：入口清单里多一个不存在的类名。
    init_apk = os.path.join(work, "bad-init.apk")
    def _mut_init(src, dst):
        with zipfile.ZipFile(src) as zf:
            cur = zf.read("META-INF/xposed/java_init.list").decode()
        write_text(os.path.join(work, "java_init.list.bak"), cur)
        rewrite_zip(src, dst, {"META-INF/xposed/java_init.list":
                               (cur.strip() + "\nio.github.vstory.hook.mxplay.Nope\n").encode()})
    _mut_init(release_apk, init_apk)
    case("③ 入口清单里塞一个不存在的类", "找不到描述符", release=init_apk)

    # ③ provider 改名（清单里等长改成另一个名字）→ dex 里没有该描述符。
    prov_apk = os.path.join(work, "bad-provider.apk")
    with zipfile.ZipFile(release_apk) as zf:
        axml = zf.read("AndroidManifest.xml")
        old_b = PROVIDER.encode("utf-16-le")
        assert old_b in axml, "篡改前先确认清单里确实是 UTF-16LE 存储"
        new_b = (PROVIDER[:-1] + "X").encode("utf-16-le")
        overrides = {"AndroidManifest.xml": axml.replace(old_b, new_b)}
    rewrite_zip(release_apk, prov_apk, overrides)
    case("③ 清单 provider 改名", "找不到描述符", release=prov_apk)

    # ⑤ keep 没绑上：seeds 里删掉 onPackageReady 的种子行。
    d = seeded("keep")
    lines = [ln for ln in read_text(os.path.join(d, "seeds.txt")).splitlines()
             if "onPackageReady" not in ln]
    write_text(os.path.join(d, "seeds.txt"), "\n".join(lines) + "\n")
    case("⑤ keep 规则没绑上回调", "没绑上任何东西", mapping=d)

    # ⑥ 契约类被改名。
    d = seeded("renamed")
    txt = read_text(os.path.join(d, "mapping.txt"))
    old = "io.github.vstory.hook.mxplay.MainHook -> io.github.vstory.hook.mxplay.MainHook:"
    assert old in txt
    write_text(os.path.join(d, "mapping.txt"),
               txt.replace(old, "io.github.vstory.hook.mxplay.MainHook -> a.b.C:"))
    case("⑥ 入口类被改名", "被改名为", mapping=d)

    # ⑥ 契约成员消失（回调名变了 = 框架调不到）。
    d = seeded("member")
    txt = read_text(os.path.join(d, "mapping.txt"))
    assert " -> onPackageReady" in txt
    write_text(os.path.join(d, "mapping.txt"), txt.replace(" -> onPackageReady", " -> xxxxx"))
    case("⑥ 回调成员消失", "不在 release 的 mapping 里", mapping=d)

    # ⑦ release 快路径特征串消失。两个分支各测一次，因为它们的结论不同：
    #    只 release 缺 = 变体行为不一致；两边都缺 = 代码被删/文案改过（要同步那张表）。
    mut_apk = os.path.join(work, "no-marker-rel.apk")
    patch_dex(release_apk, mut_apk, M_DBG_MARKER, b"X" * len(M_DBG_MARKER))
    case("⑦ 特征串只在 debug 有（变体不一致）", "只在 debug", release=mut_apk)

    mut_both_dbg = os.path.join(work, "no-marker-both-dbg.apk")
    mut_both_rel = os.path.join(work, "no-marker-both-rel.apk")
    patch_dex(debug_apk, mut_both_dbg, M_DBG_MARKER, b"X" * len(M_DBG_MARKER))
    patch_dex(release_apk, mut_both_rel, M_DBG_MARKER, b"X" * len(M_DBG_MARKER))
    case("⑦ 特征串两边都没了（代码被删/文案改过）", "两个变体的 dex 里都没有",
         debug=mut_both_dbg, release=mut_both_rel)

    # ⑧ debug 专属链重回 release（探针类出现在 mapping 里）。
    d = seeded("probe")
    write_text(os.path.join(d, "mapping.txt"),
               read_text(os.path.join(d, "mapping.txt"))
               + "\nio.github.vstory.hook.mxplay.Probe -> io.github.vstory.hook.mxplay.Probe:\n")
    case("⑧ debug 专属链重回 release", "出现了 io.github.vstory.hook.mxplay.Probe", mapping=d)

    # 账本缺失：必须**硬失败**，不许「找不到就跳过」。
    case("账本缺失必须硬失败", "找不到", mapping_missing=True)

    shutil.rmtree(work, ignore_errors=True)

    failed = [n for n, ok, _c, _d in results if not ok]
    print(f"\n自测 {len(results) - len(failed)}/{len(results)} 通过")
    if failed:
        print("::error::断言 ⑦ 自测未过：" + "、".join(failed))
        return 1
    print("  ✅ 八组断言各自都能被失败触发（门禁不是瞎的）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
