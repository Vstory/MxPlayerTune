#!/usr/bin/env python3
"""发布前断言 ⑦（可执行版）：变体裁剪 + R8 按名引用保真 —— 八组，缺一即拒发。

为什么必须是个脚本而不是「发布前看一眼」：R8 的失效模式**全是静默的** ——
APK 照样装、照样跑，只是「模块永远不生效」或「把实验件发给了用户」，靠 review 发现不了。
历史前科两次（2026-09-28 定位诊断、2026-09-29 取流判定，都是「日志挪出了调用点块」），
2026-09-29 又添一次 keep 规则踩空（`-keep class MainHook { *; }` 把探针一起保住）。

判据一律是**读产物**，不是「我检查过调用点」：
  · dex 字节        —— 变体裁剪、特征串、按名引用的类描述符
  · seeds.txt       —— R8 **实际匹配上**的 keep 规则（规则里写了但没匹配上的不会出现）
  · mapping.txt     —— 存活成员**及其最终名字**（改名与否只有这里看得到）

三份产物各有不可替代的用处，缺一样就有盲区：
  只读 dex ⇒ 看不出「keep 写了但没绑上」；只读 seeds ⇒ 看不出「绑上了却被内联/删除」。

八组断言：
  ① release 的 `[DBG]` 必须 0      ② debug 的 `[DBG]` 必须 >0
  ③ 按名引用闭包：产物自带的 `java_init.list` + 清单 `<provider android:name>` 里的类名，
    必须在**两个变体**的 dex 里都以**原名描述符**存在（R8 从字节码里看不出这类字符串引用）
  ④ 探针只在 debug               ⑤ keep 规则真的绑上了（seeds.txt）
  ⑥ 框架契约存活且未改名（mapping.txt）
  ⑦ release 快路径特征串必须还在（dex 字节）—— 防「可达性被改坏 ⇒ release 包静默变哑巴」
  ⑧ 反向：debug 专属链必须彻底不在 release（mapping.txt）

⚠️ 为什么不做「全量删除面白名单」：R8 的**内联**也会让成员出现在删除清单里，而功能当然还在 ——
实测 `ThumbSize.WIDTH`、`LogBudget.max()`、`WebDavClient.PROPFIND_BODY` 都在删除清单里，
但常量的值已被内联进调用点。拿「删除了什么」当「这段代码没了」的证据，方向本身就偏软，
而且会把「同输入换个 R8 小版本」变成假红。所以改成 ⑦（正向特征串，内联不误伤）+ ⑧（反向点名
debug 专属链），两条都是「错了必现形、对了不误伤」。

⚠️ ⑦ 的口径与维护：特征串是**日志文案**，改动文案要同步这里（漏改 = 假红，属"宁可多拦"）。

用法:
    python3 tools/dex_trim_check.py <debug.apk> <release.apk> --mapping-dir <.../mapping/release>
"""

import os
import struct
import sys
import zipfile

MARKER = b"[DBG]"

# ---------------------------------------------------------------------------
# ③ 的期望：产物自带的「按名引用」声明，脚本不写死（新增 list 文件自动被覆盖）。
#    入口类全名写在 META-INF/xposed/java_init.list（libxposed 按类全名反射加载）；
#    provider 名写在 AndroidManifest 的 <provider android:name>（系统按名反射实例化）。
# ---------------------------------------------------------------------------
INIT_LIST_ENTRY = "META-INF/xposed/java_init.list"

# ---------------------------------------------------------------------------
# ⑤ 的契约：**必须出现在 seeds.txt 里**的 keep 匹配结果。
#    这一条同时覆盖两种静默失效：① keep 规则里签名写错（改了方法名/参数类型但忘同步）；
#    ② 整块 keep 被删（`-keepnames` 顶不上 —— 名字保住了，但类没了照样不生效）。
#    形态说明：构造器在 seeds.txt 里写作 `MainHook()`（不是 `<init>()`）。
# ---------------------------------------------------------------------------
CONTRACT_SEEDS = [
    "io.github.vstory.hook.mxplay.MainHook",
    "io.github.vstory.hook.mxplay.MainHook: MainHook()",
    "io.github.vstory.hook.mxplay.MainHook: void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam)",
    "io.github.vstory.hook.mxplay.MainHook: void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam)",
    "io.github.vstory.hook.mxplay.MainHook: boolean onHotReloading(io.github.libxposed.api.XposedModuleInterface$HotReloadingParam)",
    "io.github.libxposed.service.XposedProvider",
    "io.github.libxposed.service.XposedProvider: XposedProvider()",
]

# ---------------------------------------------------------------------------
# ⑥ 的契约：框架**按签名调用/实例化**的那几个成员，必须在 mapping.txt 里存活且**未改名**。
#    与 ⑤ 是两份独立产物上的同一件事（seeds=「规则绑上了」、mapping=「字节还在」）。
#    为什么这些成员不可能被 R8 内联掉：它们是入口点（框架从外部直接调），内联需要调用点，
#    而调用点在框架侧、不在本 APK 里。
#    升级 libxposed 时若 XposedModuleInterface 的签名变了，这张表要跟着核。
#    ⚠️ 形态差异：构造器在 mapping.txt 里写作 `<init>`，而在 seeds.txt 里写作 `MainHook()`
#    —— 同一件事两套拼法（R8 的两份产物各自沿用不同惯例），写表时别照抄另一份。
# ---------------------------------------------------------------------------
CONTRACT_MAPPING = [
    ("io.github.vstory.hook.mxplay.MainHook", None,
     "模块入口类 —— java_init.list 按全名反射加载，改名即「装得上、永远不生效」"),
    ("io.github.vstory.hook.mxplay.MainHook", "<init>",
     "入口类的无参构造器 —— 框架反射 newInstance 要用"),
    ("io.github.vstory.hook.mxplay.MainHook", "onModuleLoaded",
     "XposedModuleInterface 生命周期回调 —— 框架按接口签名叫，名字/签名变即调不到"),
    ("io.github.vstory.hook.mxplay.MainHook", "onPackageReady",
     "XposedModuleInterface 生命周期回调 —— 全部 hook 安装都在这里发起"),
    ("io.github.vstory.hook.mxplay.MainHook", "onHotReloading",
     "XposedModuleInterface 生命周期回调 —— module.prop 里 autoHotReload=true"),
    ("io.github.libxposed.service.XposedProvider", None,
     "libxposed service provider —— 清单 <provider android:name> 按名反射实例化"),
    ("io.github.libxposed.service.XposedProvider", "<init>",
     "provider 的构造器 —— 系统实例化要用"),
]

# ---------------------------------------------------------------------------
# ⑦ 的契约：release dex 里**必须还在**的特征串。
#    为什么不用「成员名必须在 mapping 里」：内联会让成员名消失而功能仍在（见文件头 ⚠️），
#    特征串是**方法体内部**的常量 —— 内联后会跟着进调用点，所以"在"就是真的在。
#    每条都对应一个「静默变成哑巴」的失效面。
# ---------------------------------------------------------------------------
FAST_PATH_MARKERS = [
    ("[pkg] onPackageReady, classLoader=",
     "onPackageReady 的入口日志 —— 回调体没被折成空壳"),
    ("[locate] 快路径未命中",
     "installHooks 里 release 走的那条分支 —— 安装链在 release 里可达"),
    ("[webdav] 列目录失败 path=",
     "hookDirectoryList / listWebDav 的失败分支 —— 列目录 hook 在"),
    ("[thumb] 环回缩略图服务已启动 http://127.0.0.1:",
     "ThumbServer 启动日志 —— 缩略图服务本体在"),
    ("[thumb] 失败负缓存命中（",
     "失败负缓存分支 —— 不是只留了个空壳"),
]

# ---------------------------------------------------------------------------
# ⑧ 的反向契约：debug 专属链**必须不在** release 的 mapping 里。
#    这几条钉的是「收窄 keep + `if (!BuildConfig.DEBUG) return;` 守卫」这轮的成果：
#    守卫被改坏 / keep 又被放宽 ⇒ debug 链重回 release（体积 + 把调试期才该跑的东西带给用户）。
#    反向断言的固有局限：若某成员被**内联**，名字也会消失（本组会静默通过）——
#    所以这几条都挑「有多个调用点的大方法」，R8 不会把它们内联进那个几行的回调里。
# ---------------------------------------------------------------------------
MUST_BE_ABSENT = [
    ("io.github.vstory.hook.mxplay.Probe", None, "真机抽帧探针（debug 专属，另有 ④ 从 dex 字节再钉一遍）"),
    ("io.github.vstory.hook.mxplay.ProbeRange", None, "探针的回环 Range 服务（同上）"),
    ("io.github.vstory.hook.mxplay.MainHook", "backgroundScan", "全量结构扫描是 debug 兜底入口"),
    ("io.github.vstory.hook.mxplay.MainHook", "startProbe", "探针调用点"),
    ("io.github.vstory.hook.mxplay.MainHook", "hookSizeProbe", "探针的尺寸观察器挂载点"),
    ("io.github.vstory.hook.mxplay.HookTargets", "scanRemoteDataSource", "全量扫描 - RemoteDataSource 判据"),
    ("io.github.vstory.hook.mxplay.HookTargets", "scanServerDataSource", "全量扫描 - ServerDataSource 判据"),
    ("io.github.vstory.hook.mxplay.HookTargets", "scanSmbUtil", "全量扫描 - SmbUtil 判据"),
    ("io.github.vstory.hook.mxplay.HookTargets", "scanAll", "全量扫描共用骨架"),
    ("io.github.vstory.hook.mxplay.HookTargets", "enumClassNames", "类名枚举（靠反射读 DexPathList）"),
    ("io.github.vstory.hook.mxplay.HookTargets", "dexInventory", "dex 清单诊断"),
    ("io.github.vstory.hook.mxplay.HookTargets", "describe", "锚点形态诊断文本"),
]

# 探针特征串：release 必须没有、debug 必须有。
PROBE_MARKERS = ["/proc/self/maps", "mxpt-probe", "probe/local", "[probe] "]


# ---------------------------------------------------------------------------
# 产物读取
# ---------------------------------------------------------------------------
def dex_bytes(apk_path):
    """把 APK 里所有 classes*.dex 拼起来（只看字节，不解析 dex）。"""
    blob = bytearray()
    with zipfile.ZipFile(apk_path) as zf:
        names = sorted(n for n in zf.namelist()
                       if n.startswith("classes") and n.endswith(".dex"))
        if not names:
            raise SystemExit(f"::error::{apk_path} 里没有 classes*.dex，无法判裁剪")
        for n in names:
            blob += zf.read(n)
    return bytes(blob)


def count(blob):
    n = 0
    i = blob.find(MARKER)
    while i >= 0:
        n += 1
        i = blob.find(MARKER, i + len(MARKER))
    return n


def axml_string_pool(buf, off):
    """最小 AXML 字符串池解析（够用即可：只为了拿元素属性里的类名）。"""
    ctype, hsize, csize = struct.unpack_from("<HHI", buf, off)
    assert ctype == 0x0001, f"不是字符串池 chunk: {ctype:#x}"
    scount, _stycount, flags, sstart, _stystart = struct.unpack_from("<IIIII", buf, off + 8)
    utf8 = bool(flags & 0x100)
    out = []
    for i in range(scount):
        so = struct.unpack_from("<I", buf, off + hsize + 4 * i)[0]
        p = off + sstart + so
        if utf8:
            n = buf[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | buf[p]
                p += 1
            n2 = buf[p]
            p += 1
            if n2 & 0x80:
                n2 = ((n2 & 0x7F) << 8) | buf[p]
                p += 1
            out.append(buf[p:p + n2].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(buf[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out, off + csize


def axml_elements(buf):
    """产出 (元素名, {属性名: 字符串值})。属性名不带 `android:` 前缀（命名空间是单独字段）。"""
    ctype, hsize, _csize = struct.unpack_from("<HHI", buf, 0)
    assert ctype == 0x0003, "不是 XML chunk"
    pool, p = axml_string_pool(buf, hsize)
    while p + 8 <= len(buf):
        ctype, _h, csize = struct.unpack_from("<HHI", buf, p)
        if ctype == 0x0102:  # START_ELEMENT
            # node 头 16 字节（chunk 头 8 + lineNumber 4 + comment 4）之后是 attrExt
            _ns, namei, astart, asize, acount, _ididx = struct.unpack_from("<IIHHHH", buf, p + 16)
            attrs = {}
            for k in range(acount):
                ao = p + 16 + astart + k * asize
                _ns2, ani, raw = struct.unpack_from("<III", buf, ao)
                if raw != 0xFFFFFFFF:
                    attrs[pool[ani]] = pool[raw]
            yield pool[namei], attrs
        if csize <= 0:
            break
        p += csize


def name_refs(apk_path):
    """产物自带的「只以字符串存在」的类引用 → [(类全名, 出处)]。"""
    refs = []
    with zipfile.ZipFile(apk_path) as zf:
        names = zf.namelist()
        if INIT_LIST_ENTRY not in names:
            raise SystemExit(f"::error::{apk_path} 里没有 {INIT_LIST_ENTRY} —— "
                             f"入口声明丢了（resources/META-INF/xposed/ 没被打进包？）")
        for raw in zf.read(INIT_LIST_ENTRY).decode("utf-8", "replace").splitlines():
            s = raw.strip()
            if s and not s.startswith("#"):
                refs.append((s, INIT_LIST_ENTRY))
        axml = zf.read("AndroidManifest.xml")
    found = 0
    for ename, attrs in axml_elements(axml):
        if ename == "provider" and attrs.get("name"):
            refs.append((attrs["name"], "AndroidManifest <provider android:name>"))
            found += 1
    if not found:
        raise SystemExit("::error::清单里解析不到任何 <provider android:name> —— "
                         "解析失败不能当「没问题」放过（provider 起不来 = 模块列表里看不到）")
    return refs


def descriptor(fqcn):
    return ("L" + fqcn.replace(".", "/") + ";").encode()


def read_seeds(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return {ln.strip() for ln in fh if ln.strip()}


def read_mapping(path):
    """→ (类原名 → 最终名, 类原名 → {成员最终名})。"""
    classes, members = {}, {}
    cur = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            if line.startswith(" ") or line.startswith("\t"):
                if cur is not None and " -> " in line:
                    members.setdefault(cur, set()).add(line.rsplit(" -> ", 1)[1].strip())
                continue
            if " -> " in line and line.rstrip().endswith(":"):
                orig, new = line.rstrip()[:-1].split(" -> ", 1)
                cur = orig.strip()
                classes[cur] = new.strip()
                members.setdefault(cur, set())
    return classes, members


def member_present(members, cls, name):
    """mapping 里的成员行只有**最终名**，所以用后缀比对（`<init>` 等尖括号名照样成立）。"""
    for m in members.get(cls, ()):  # noqa: SIM110
        if m == name:
            return True
    return False


def main(argv):
    apks, mapping_dir = [], None
    args = argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--mapping-dir":
            i += 1
            if i >= len(args):
                raise SystemExit("::error::--mapping-dir 后面缺路径")
            mapping_dir = args[i]
        else:
            apks.append(args[i])
        i += 1
    if len(apks) != 2 or not mapping_dir:
        raise SystemExit(
            "用法: dex_trim_check.py <debug.apk> <release.apk> --mapping-dir <.../mapping/release>")

    seeds_p = os.path.join(mapping_dir, "seeds.txt")
    mapping_p = os.path.join(mapping_dir, "mapping.txt")
    for p in (seeds_p, mapping_p):
        if not os.path.isfile(p):
            print(f"::error::找不到 {p} —— 这条门禁要求 release **开着 R8**（isMinifyEnabled=true）"
                  f"并保留 mapping 产物；没有它就无法判「keep 绑上了没 / 成员还在不在」")
            return 1

    debug_blob = dex_bytes(apks[0])
    release_blob = dex_bytes(apks[1])
    debug_n = count(debug_blob)
    release_n = count(release_blob)
    seeds = read_seeds(seeds_p)
    classes, members = read_mapping(mapping_p)

    print(f"  debug   {apks[0]}: [DBG] 出现 {debug_n} 次")
    print(f"  release {apks[1]}: [DBG] 出现 {release_n} 次")
    print(f"  账本     {mapping_dir}: seeds {len(seeds)} 行 / mapping {len(classes)} 个类")

    bad = False

    # ①② 变体裁剪：缺一不可。
    #   只判 release 会漏掉「debug 也被削没了 ⇒ 排障包没有排障能力」，而那条表现为「很干净」。
    if release_n != 0:
        print(f"::error::{apks[1]} 的 dex 里仍有 {MARKER.decode()} 字符串 {release_n} 处"
              f" —— release 变体不该带 D 级日志（查：是否写在独立方法里、调用点没包 BuildConfig.DEBUG）")
        bad = True
    if debug_n == 0:
        print(f"::error::{apks[0]} 的 dex 里没有任何 {MARKER.decode()} 字符串"
              f" —— 排障包必须能打 D 级日志（否则这个包没有排障能力）")
        bad = True

    # ③ 按名引用闭包：名字从产物自己身上读，不在脚本里写死（写死就会和清单/资源各自漂移）。
    #    两个变体都要断言：debug 缺了 ⇒ 排障包也是坏的，只是没人看。
    refs = name_refs(apks[1])
    print(f"  按名引用（从产物自证）{len(refs)} 处：")
    for fqcn, where in refs:
        desc = descriptor(fqcn)
        in_rel = desc in release_blob
        in_dbg = desc in debug_blob
        if in_rel and in_dbg:
            print(f"    ✅ {fqcn}  ({where})")
            continue
        print(f"::error::{fqcn} 在 {'release' if not in_rel else 'debug'} dex 里找不到描述符 {desc.decode()}"
              f"（出处：{where}）—— 这类引用只以**字符串**存在，R8 从字节码里看不出 ⇒ "
              f"改名即「包能装、模块永远不生效」，且无任何报错。查 app/proguard-rules.pro 的 keep 规则")
        bad = True

    # ④ 探针必须只在 debug（判据是产物字节：漏出去 = 用户包里带一个会读写设备文件的实验件）。
    for marker in PROBE_MARKERS:
        m = marker.encode()
        if m in release_blob:
            print(f"::error::{apks[1]} 的 dex 里出现了探针特征串 {marker} —— "
                  f"探针是 debug 专属（见 Probe.java 的调用点与 app/proguard-rules.pro 的 ① 注释）")
            bad = True
        elif m in debug_blob:
            print(f"  ✅ release 无探针 / debug 有: {marker}")
        else:
            print(f"::error::{apks[0]} 的 dex 里没有探针特征串 {marker} —— "
                  f"排障包里的探针被削掉了？（那样真机对照实验根本跑不起来，而日志只会说「没触发」）")
            bad = True

    # ⑤ keep 规则真的绑上了。seeds.txt 是 R8 对 keep 规则的**匹配结果**：
    #    规则里写了但没匹配上的（名字写错、签名漂移、整块被删）都不会出现 —— 而这是静默的。
    for want in CONTRACT_SEEDS:
        if want in seeds:
            print(f"  ✅ keep 已绑定: {want}")
        else:
            print(f"::error::seeds.txt 里没有「{want}」—— 该 keep 规则**没绑上任何东西**："
                  f"要么签名写错（方法名/参数类型变过？），要么整块被删。"
                  f"后果是「包能装、模块永远不生效」，而构建期不会有任何报错。见 app/proguard-rules.pro")
            bad = True

    # ⑥ 框架契约存活且未改名（mapping.txt 上查，与 ⑤ 是两份独立产物）。
    wanted_classes = {c for c, _m, _w in CONTRACT_MAPPING}
    for cls in wanted_classes:
        if cls not in classes:
            print(f"::error::mapping.txt 里没有类 {cls} —— 整个类被裁掉了？（框架按名加载它）")
            bad = True
        elif classes[cls] != cls:
            print(f"::error::类 {cls} 被改名为 {classes[cls]} —— 按名反射加载即失效")
            bad = True
    for cls, member, why in CONTRACT_MAPPING:
        if member is None:
            if cls in classes and classes[cls] == cls:
                print(f"  ✅ 契约类存活未改名: {cls}（{why}）")
            continue
        if cls not in classes:
            continue  # 上面已报过
        if member_present(members, cls, member):
            print(f"  ✅ 契约成员存活: {cls}.{member}（{why}）")
        else:
            print(f"::error::{cls}.{member} 不在 release 的 mapping 里 —— {why}。"
                  f"先看 usage.txt 里它是被删还是被内联；入口点/回调**不可能**被合法内联"
                  f"（调用点在框架侧），所以这条基本只有一种解释：keep 规则或可达性被改坏了")
            bad = True

    # ⑦ release 快路径特征串必须还在（内联不误伤：常量会跟着进调用点）。
    for text, why in FAST_PATH_MARKERS:
        b = text.encode()
        in_rel = b in release_blob
        in_dbg = b in debug_blob
        if in_rel and in_dbg:
            print(f"  ✅ release 快路径在: {text}（{why}）")
        elif not in_rel and not in_dbg:
            print(f"::error::两个变体的 dex 里都没有 {text}（{why}）—— "
                  f"要么这段代码被删/不可达，要么这条特征串随文案改过（改文案要同步这张表）")
            bad = True
        else:
            print(f"::error::{text} 只在 {'debug' if in_dbg else 'release'} 里 —— "
                  f"变体行为不一致（{why}）；release 缺它 = 该功能在正式包里静默失效")
            bad = True

    # ⑧ 反向：debug 专属链必须彻底不在 release。
    for cls, member, why in MUST_BE_ABSENT:
        where = cls if member is None else f"{cls}.{member}"
        hit = (cls not in classes) if member is None else (not member_present(members, cls, member))
        if hit:
            print(f"  ✅ release 无 debug 专属链: {where}（{why}）")
        else:
            print(f"::error::release 的 mapping 里出现了 {where} —— {why}。"
                  f"查 `if (!BuildConfig.DEBUG) return;` 那道守卫与 app/proguard-rules.pro 的 keep 宽度")
            bad = True

    if bad:
        return 1
    print("  ✅ 八组全过：变体裁剪正确 + 按名引用未改名 + 探针仅在 debug + keep 已绑定 + "
          "契约存活 + release 快路径在 + debug 专属链已彻底移出")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
