# ============================================================================
# R8 规则 —— 只作用于 release 变体（isMinifyEnabled + isShrinkResources）。
#
# 开 R8 的目的不是「瘦身」本身，而是这两条：
#   ① 裁掉 okhttp/okio 里我们**根本用不到**的部分。本模块对这一层的全部需求只有
#      「发一个 PROPFIND / GET，拿到状态码与 chunked 正文，自签 https 放宽校验」——
#      Cache / Cookie / Multipart / FormBody / HTTP2 / PublicSuffix / WebSocket 全用不上。
#   ② 顺带把断言 ⑦ 想要的事**自动**做掉：javac 只做常量折叠，删不掉「写在独立方法里」的
#      `[DBG]` 字符串常量（2026-09-29 真漏过一次：`logVerdict()` 的日志没被调用点包住）；
#      R8 做跨方法可达性分析，`if (BuildConfig.DEBUG)` 恒假 ⇒ 整个分支连同字符串一起消失。
#      ⚠️ 但门禁**不撤**（tools/dex_trim_check.py 仍读产物字节断言）——判据是产物，
#      不能建立「R8 一定会删」这个假设上（R8 版本/full mode 开关都会改变行为）。
#
# 下面 keep 的三类名字有一个共同点：**写在资源或清单里，由系统/框架按名反射加载**，
# 一旦被重命名就**静默失效**（模块列表里看不到、provider 起不来、hook 不装），
# 而 R8 从代码里看不出这些引用（它们不是字节码引用）。
# ============================================================================

# ① 模块入口：resources/META-INF/xposed/java_init.list 里按「类全名」声明。
#    libxposed 的 XposedProvider 按这串名字反射加载入口，混淆即失效。
-keep class io.github.vstory.hook.mxplay.MainHook { *; }

# ①′ 模块自己的其余类（WebDavClient / HookTargets / RemoteEntries / LogBudget）只保留名字：
#     keepnames **不阻止裁剪**，所以体积代价近乎为零（本项目统共 5 个类），
#     换来的是 release 包的真机日志里异常栈仍然可读 —— 本项目整套排查判据都建立在日志上，
#     release 混淆后栈里一旦出现 a.b.c，那些判据就全部失效。
-keepnames class io.github.vstory.hook.mxplay.** { *; }

# ② 模块内声明的 libxposed service provider：AndroidManifest 的
#    <provider android:name="io.github.libxposed.service.XposedProvider">，
#    系统在安装/启动时按名反射实例化。注意它来自 implementation 依赖（打进包内），
#    属 R8 的 program class ⇒ 不 keep 就会被重命名。
-keep class io.github.libxposed.service.XposedProvider { *; }

# ③ libxposed 契约类（api/interface/service）：模块与框架**跨 ClassLoader** 共享的类型，
#    框架侧调的是接口方法签名，本地混淆会让「实现」与「框架认识的契约」对不上。
-keep class io.github.libxposed.** { *; }

# ③′ 注解包只在 compileOnly 的 api.jar 里（运行时由框架提供），但 interface/service jar
#     引用得实实在在（XposedService#getRunningTargets 等 5 处）。R8 找不到该类的**定义**
#     就直接判构建失败（missing class 在 AGP 下是 error 不是 warning）⇒ 必须 dontwarn。
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.**

# 保留行号与源文件名：本项目的排障入口是 LSPosed 日志，栈里没有行号等于没法定位。
# （刻意**不**加 -renamesourcefileattribute —— 这是自用模块，源文件名比「Source」有用。）
-keepattributes SourceFile,LineNumberTable

# okhttp/okio 只保留**名字**（keepnames），刻意**不**用 `-keep` —— 这两者差别很大：
#   · 体积收益的大头是「把用不到的功能整块裁掉」（Cache / Cookie / Multipart / FormBody /
#     WebSocket / HTTP2 / PublicSuffix…），而 keepnames **不阻止裁剪**，只阻止改名；
#   · 真正会被「改名」伤到的是库内部的反射与按类名定位的资源（Platform 那一堆
#     Class.forName 探测、PublicSuffixDatabase 读相对路径的 .gz），
#     而这类问题在离机 harness 里**照不出来**（harness 跑的是未混淆的 SUT）⇒ 只能到真机上才暴露。
# 本模块对 okhttp 的使用面极窄（一个 PROPFIND + 取流 + 自签放宽），拿这点体积换「行为可预期」
# 是划算的：宁可 dex 大几十 KB，也不要一个只在真机上才现形的传输层问题。
-keepnames class okhttp3.** { *; }
-keepnames class okio.** { *; }

# okhttp 3.14.9 的 jar 自带 META-INF/proguard/okhttp3.pro（javax.annotation / ConscryptPlatform
# 两条 dontwarn），AGP 会自动应用；这里补的是它没覆盖到的 JVM-only 平台分支 ——
# 这些类是 R8 的 library 类里找不到的，不 dontwarn 会在构建期直接失败。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
