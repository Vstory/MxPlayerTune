#!/usr/bin/env python3
"""发布前断言 ⑦（可执行版）：变体裁剪 + R8 保名 —— 三组，缺一即拒发。

为什么必须是个脚本而不是「检查时看一眼」：release 里漏出 "[DBG] ..." 是**静默**的 ——
APK 照样装、照样跑，只是把一个本该只存在于调试期的字符串发给了用户；靠 review 发现不了。

2026-09-29 真漏过一次：某条 D 级日志写在**独立方法**里（调用点没有
`if (BuildConfig.DEBUG) { … }`），javac 裁剪不掉它的字符串常量 ⇒ release dex 里留下
`[DBG] << 取流判定 HTTP `。所以判据不该是「我检查过调用点」，而是**读产物字节**。

同一天 release 又开了 R8（`isMinifyEnabled` + `isShrinkResources`，见 app/proguard-rules.pro）。
R8 带来一条**新的、同样静默的**失效模式：**按名反射加载的类被改名**。
模块入口写在 resources/META-INF/xposed/java_init.list、provider 写在 AndroidManifest 里，
两处都是「按字符串找类」，R8 从字节码里看不出这类引用 ⇒ 混淆掉就等于模块**装得上但永远不生效**
（LSPosed 模块列表里没有、或 provider 起不来）。所以本脚本除裁剪外，再钉住这两处名字。

口径（与 构建方案.md 断言 ⑦ 一致）：
  - 判据是 dex 字节，**不是**「日志是否打印」/「mapping 里有没有」；
  - `[DBG]` 范围为 MainHook 的调用点：定位层 helper（HookTargets）生成的明细文本随类保留在
    release dex，不在断言范围内 —— 那些文本不含 `[DBG]` 前缀，天然不会被算进来；
  - 同样按字节判，所以非 ASCII 的日志正文也一视同仁（不依赖 grep 的字符类）。

用法: python3 tools/dex_trim_check.py <debug.apk> <release.apk>
"""

import sys
import zipfile

MARKER = b"[DBG]"

# release dex 里**必须**存在的名字（dex 字符串池里是类描述符形态）。
REQUIRED_IN_RELEASE = (
    (b"Lio/github/vstory/hook/mxplay/MainHook;",
     "模块入口类 —— 写在 resources/META-INF/xposed/java_init.list，LSPosed 按类全名反射加载"),
    (b"Lio/github/libxposed/service/XposedProvider;",
     "libxposed service provider —— 写在 AndroidManifest 的 <provider android:name>，系统按名反射实例化"),
)


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


def main(argv):
    if len(argv) != 3:
        raise SystemExit("用法: dex_trim_check.py <debug.apk> <release.apk>")
    debug_blob = dex_bytes(argv[1])
    release_blob = dex_bytes(argv[2])
    debug_n = count(debug_blob)
    release_n = count(release_blob)

    print(f"  debug   {argv[1]}: [DBG] 出现 {debug_n} 次")
    print(f"  release {argv[2]}: [DBG] 出现 {release_n} 次")

    bad = False

    # 两组断言缺一不可：
    #   ① release 必须零命中（否则就是把调试串发给了用户）
    #   ② debug **必须**非零（否则说明裁剪把 debug 也削了、或者日志全被挪进了别处 ——
    #      那样「排障用的包」就没有排障能力，而这条比①更隐蔽：它表现为「很干净」）
    if release_n != 0:
        print(f"::error::{argv[2]} 的 dex 里仍有 {MARKER.decode()} 字符串 {release_n} 处"
              f" —— release 变体不该带 D 级日志（查：是否写在独立方法里、调用点没包 BuildConfig.DEBUG）")
        bad = True
    if debug_n == 0:
        print(f"::error::{argv[1]} 的 dex 里没有任何 {MARKER.decode()} 字符串"
              f" —— 排障包必须能打 D 级日志（否则这个包没有排障能力）")
        bad = True

    # ③ R8 保名：两处「按字符串找类」的引用被混淆 = 包能装、模块不生效（无任何报错）。
    for name, why in REQUIRED_IN_RELEASE:
        if name not in release_blob:
            print(f"::error::{argv[2]} 的 dex 里找不到 {name.decode()} —— {why}；"
                  f"查 app/proguard-rules.pro 的 keep 规则（R8 保名被改/被删？）")
            bad = True
        else:
            print(f"  ✅ release 保名: {name.decode()}")

    if bad:
        return 1
    print("  ✅ 变体裁剪正确（debug 有、release 零命中）+ R8 保名齐全")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
