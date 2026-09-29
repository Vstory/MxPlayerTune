# MxPlayerTune

MX Player 增强模块（libxposed API 102 / LSPosed）：让 MX Player 的「本地网络」在 SMB 之外支持 **WebDAV**。

> ⚠️ **当前状态：开发中（阶段 B · 浏览/播放已真机跑通；缩略图改为「模块内环回抽帧」实现，待真机复验）**
> - 列目录：真机 http 与 https **都**跑通（一次列出 591 项）。传输层已改用 **OkHttp** ——
>   平台自带的 `HttpURLConnection` 不支持 `PROPFIND` 这类非标准动词，旧版靠反射改框架字段，
>   而 Android 的 https 是委托壳 ⇒ 写壳等于没写 ⇒ 请求以 `POST` 发出去 ⇒ 501
> - 播放：真机 http **已能播放**（凭据内联进 URL 的 userinfo 之后）
> - 缩略图：**改为模块内环回抽帧**（2026-09-29）。原因是宿主那一侧改不动：
>   ① 宿主的缩略图取流取的就是**条目自身 URL**（`SmbUtil.b` 用 `uri.getHost()+uri.getPath()` 原样重建，dex 事实）；
>   ② 本机 CloudDrive 把每个**媒体文件**暴露成「同名集合里放同名子项」，**只有同名子项那条 URL 能取到字节**（集合那条 `GET` 一律 `405`）；
>   ③ 宿主取图**不发 `Range`**（服务端本来支持 —— 带 `Range` 实测 `206` + `Content-Range`）⇒ 它面对 338 MB 的整片只能放弃。
>   解法是**换掉宿主拿到的东西**：hook ③ 把视频条目的缩略图 URL 指向模块进程内一个**只监听 `127.0.0.1`** 的极小 HTTP 服务
>   （`ThumbServer`），它用 `Range` 分片取流 + `MediaMetadataRetriever` 抽首帧 → 一张**真 JPEG** 回给宿主的图片加载器
>   ⇒ 宿主不需要支持任何新东西。⚠️ **这一段待真机复验**（抽帧依赖平台件，是本模块唯一无法离机断言的环节）
> - https 的边界变了：**浏览 / 取图 / 抽帧**都走本模块自己的 OkHttp（含自签证书放宽）⇒ 自签 https 下**也能出缩略图**；
>   只有**播放**仍是 MX 自带 FFmpeg 去取流，那一边的证书校验不在本模块手里

## 功能

**已实现（待真机复验）**

- **WebDAV 支持**：在 MX Player「本地网络 → 添加服务器」的「服务器」栏直接填
  `http(s)://主机:端口/路径` 的 WebDAV 地址 + 账号密码，即可在 MX Player 内**浏览目录**、
  **显示缩略图**并**播放其中的视频**。只读 —— 含浏览与播放，**不含**上传 / 删除 / 重命名。
- 列目录走一次 `PROPFIND`（`Depth: 1`）：目录优先、只显示媒体文件（判据与 SMB 路径一致：用 MX 自己的 `MediaExtensions`）
- 缩略图：**视频条目**的缩略图 URL 改指向模块内的环回服务（`127.0.0.1:<随机端口>`，只回一张真 JPEG）。
  MX 原逻辑用 `getHost()+getPath()` 重建 URL ⇒ **丢端口**；且它取图**整片下载、不发 `Range`** ⇒ 只有把「一张真图」放到它面前才可能出图
  （怎么取到那帧：`Range` 分片取流 + `MediaMetadataRetriever` 抽首帧，见「特殊情况」表）。
  另外给图片取流补 `Authorization`（MX 的图片取流**不认** URL 里的 userinfo ⇒ 不加就是 401、缩略图空白）
- 列目录的 `PROPFIND`：传输层用 **OkHttp**。平台自带的 `HttpURLConnection` 不收 `PROPFIND` 这种非标准动词
  （`setRequestMethod` 有白名单），且 Android 的 https 是**委托壳**（写壳上的字段等于没写 ⇒ 请求会以 `POST` 发出去 ⇒ 服务端 501）——
  旧版为此要反射改框架内部字段，现已整段删除
- 播放：把凭据内联成 URL 的 userinfo（`http://用户:密码@主机:端口/…`）—— MX 用 `uri.getUserInfo()` 生成 `Authorization: Basic` 交给 FFmpeg；**URL 里没有 userinfo 就等于播放不带鉴权**
- 凭据另一条路走 `Authorization: Basic` 请求头（列目录用），**绝不写进请求行**（URL 里的 userinfo 一律先剥掉再发）
- 地址既支持「服务器」与「分享路径」分栏填写，也支持把凭据写进 URL（`http://用户:密码@主机/路径`）
- **自签名证书的 https 能浏览 / 取图 / 抽帧**：本模块自己的请求都放宽证书校验，且只作用于本模块这两条连接（不碰进程全局校验）

**待实现**

- 播放链路（seek / 外挂字幕）的真机复验 → 阶段 C
- 缩略图的**真机复验**（代码已就位：`[thumb]` 日志会说明「服务起来了没 / 抽到帧了没 / 走的哪条取流路径」）
- 错误提示 / 超时重试 / 协议标识 → 阶段 D
- 「所有视频 / 文件夹」伪条目、列目录缓存

> 目标应用**无需自建任何界面**：MX Player 的「服务器」字段本身不校验格式，填 `http(s)://` 地址即可入库，模块只负责让该地址按 WebDAV 协议工作。

## 开关

本模块**无 UI、无开关**，功能默认全开。

- 作用域已**静态声明**（`META-INF/xposed/scope.list` 中声明 `com.mxtech.videoplayer.pro`）⇒ 在 LSPosed 中启用模块即可，**无需手动勾选作用域**
- 支持**热重载**（在 LSPosed 中更新模块后无需重启目标应用）

## 特殊情况

| 事项 | 说明 |
|---|---|
| **仅适配单个版本** | 当前仅适配 MX Player Pro `1.93.4`（versionCode 2001002584）。换版本后若目标定位失败，模块**不会安装任何 hook**（自动 no-op）—— MX Player 原功能与 SMB 不受影响 |
| **需重启目标应用** | 启用 / 更新模块后须**强制停止 MX Player 再打开**（注入发生在进程启动时） |
| **需 Pro 版** | 作用域为 `com.mxtech.videoplayer.pro`（**专业版**）；免费版 `com.mxtech.videoplayer.ad` 未适配 |
| **别勾「匿名」** | 勾了就按匿名处理（与 MX 自身判据一致）：列目录与播放都不带凭据，需要账号密码的服务器会 401 |
| **HTTPS 只保证「浏览 / 取图 / 抽帧」** | 本模块自己的请求（列目录、图片取流、缩略图抽帧）在自签证书下也可用；但**播放**是 MX 自带 FFmpeg 去取流，证书校验在它那边，本模块管不到 ⇒ **自签名证书下播放可能仍失败**。想用 https 播放请用受信证书，否则用 http |
| **视频缩略图：由模块内环回服务出图** | 宿主取缩略图取的是**条目自身 URL**（不是同名的 `.jpg` —— `SmbUtil.b` 用 `uri.getHost()+uri.getPath()` 原样重建，dex 事实），且**不发 `Range`**；本机 CloudDrive 又只让「同名子项」那条 URL 能取到字节（集合那条 `GET` 一律 `405`）⇒ 宿主只能拿到整片视频、解码必然失败。所以本模块不指望宿主，而是**在进程内起一个只监听 `127.0.0.1` 的小 HTTP 服务**（随机端口），由它 `Range` 分片取流 + `MediaMetadataRetriever` 抽**首帧** → JPEG 回给宿主的图片加载器。取舍与边界：① 只对**视频扩展名**启用（音频/字幕仍走旧行为）；② 抽的是**首帧**（这一路要避开「文件中间区域的随机读」，真机实测那里一次要 ~10 s）；③ 抽帧结果按条缓存（同一视频第二次请求直接命中，不再取流）；④ 服务只绑回环、URL 里只有**随机令牌**（凭据不进 URL、不进日志）⇒ 其它应用拿不到「任意取图」的能力；⑤ 服务起不来 / 渲染失败时**退回旧行为**（返回条目 URL，最坏就是空白缩略图），浏览与播放不受任何影响 |
| **缩略图端口是随机的** | 环回服务每次进程启动绑一个随机端口（日志里会打出来）。宿主若缓存了**上一次进程**的 URL，会拿到 `404` ⇒ 显示占位图，不会报错、也不会影响其它功能 |
| **凭据会出现在 URL 里（编码形态）** | 为了让 MX 的播放与缩略图链路带上鉴权，用户名密码会内联进条目的 URL —— 且是 **MX 落库的 `Uri.encode` 形态**（`android.net.Uri#getUserInfo()` 会解码，所以 MX 造 Authorization 头时拿到的仍是真凭据）。仅存在于进程内存（MX 自己就把密码明文存在 `smb_list_data.json`，它的缩略图 URL 也是这个形状）；本模块日志一律脱敏成 `***:***` |

## 安装

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed)（1.9+，支持 libxposed API 102）
2. 安装本模块 APK（[Releases](../../releases)）
3. 在 LSPosed 中启用模块（作用域已静态声明，无需手动勾选）
4. 强制停止 MX Player 后重新打开

## 验证（当前阶段）

本模块无界面，生效与否看 LSPosed 日志（tag `MxPlayerTune`）：

- 装好并重启 MX Player 后，日志依次出现 `api102 module loaded` → `[locate] … ` → `installHooks done: 4 OK / 0 FAIL`
  = **注入、目标定位、四个 hook 均成功**（`[OK] SmbServerEntry#getRootPath` / `RemoteDataSource#listDir` / `SmbUtil#buildThumbUrl` / `java.net.URL#openConnection(图片取流补鉴权)`）
- 进「本地网络」点开 WebDAV 服务器后：
  - `[webdav] RemoteDataSource#listDir path=… → 子项 n（目录 m）… 返回 k 项` = **列目录成功**
  - `[webdav] 列目录失败 path=…` = 列目录报错，同一条日志带 HTTP 状态或异常原因（`401/403` 查账号密码与匿名勾选，`404` 查地址与结尾斜杠，`SSLHandshakeException` 查证书）
  - `PROPFIND … → HTTP 501` = 服务端**不认识这个动词**。旧版曾在 https 上因「动词没发出去（被发成 `POST`）」而报这行（原因是反射写框架字段写到了委托壳上）—— 改用 OkHttp 后这一类已不存在 ⇒ 现在看到它，先确认**服务端本身**支持 WebDAV 的 `PROPFIND`
  - `CLEARTEXT communication to … not permitted` = 宿主 App 的明文（http）策略不允许该地址 —— 与 WebDAV 地址是否用 http 有关，把日志给我
  - `MalformedURLException: invalid port: …` = 密码里含 `?` / `#` 这类 URL 结构字符，而 URL 里放的是**解码后的真凭据**（编码形态才是对的，见上表）
  - 日志里**一个 hook 调用都没有** → 不是 hook 失效，而是**目录列举没被触发**：停在「本地网络」列表页不算进入目录；目录页**有缓存**时也不会重新列举，**下拉刷新**可强制重新列举
- 缩略图（三行日志，依次对应「谁在取图 → 我们抽到没有 → 宿主拿到什么」）：
  - `[thumb] 环回缩略图服务已启动 http://127.0.0.1:<端口>/t/<token>.jpg` = 服务起来了（**没这行**就是没起来，缩略图会退回旧行为）
  - `[thumb] 渲染 url=… → JPEG <字节> / <毫秒> ← 分片取流 Range（总长=… 头窗=… 请求=…）` = **抽帧成功**。
    箭头后面是**实际走的那条取流路径**：`分片取流 Range` 是首选，`平台取流 URL+headers` 是退路（退路在自签 https 上会失败）
  - `[thumb] 渲染失败 url=…` = 抽帧失败（E 级，带原因）—— 这一行是唯一线索：**宿主那边对失败是静默的**（只显示占位图，什么都不报）
  - `[DBG] >> SmbUtil#buildThumbUrl 改用环回缩略图 url=… ← path=…` = 这个条目的缩略图走了环回服务（debug 版只打前 3 次）；
    同一行若显示「改用条目 path=…（非视频扩展名）」= 该条目不是视频，按旧行为
  - `[DBG] >> 取流补上 Authorization（图片/缩略图链路）` = 取图请求已补上鉴权头（debug 版只打前 8 次）；
    **走环回服务的条目不会再出现这行**（那是宿主第一次直接取条目 URL 时的事）
  - `[DBG] << 取流判定 HTTP <码> … len=…` = 这次取流**最终拿到的响应码与长度**（同一条连接只报一次）。读法：
  `401` = 这条没带鉴权头（该服务端对无鉴权**一律** 401，不分路径存不存在）；
  `404` = 头到了、路径不存在（宿主在探测同名字幕 / 画质变体，正常噪声，不影响真实资源）；
  `200 + len=整片大小 + type=video/mp4` = 头到了，但拿到的是**视频本体**（这正是缩略图过去必然空白的原因）；
  `206` = 服务端按 `Range` 回了分片
- 列目录那行的 `req=` 段（仅当实际请求 URL 与条目 path 不同才出现，如补结尾斜杠）与其它日志一样**已脱敏**（`***:***`）
- 日志完全无 `MxPlayerTune` → 模块未启用或未生效（检查 LSPosed 中的启用状态与作用域）

## 版本

- 当前版本：`v1.0.0`（versionCode 1）
- libxposed API：102（minApiVersion 101）
- minSdkVersion：26（Android 8.0）
- targetSdkVersion：37

## 下载

每次构建发布**两个包**，见 [Releases](../../releases)：

| 包 | 说明 |
|---|---|
| `..._release.apk` | 日常使用（调试日志已在编译期裁掉） |
| `..._debug.apk` | 排障用（保留调试日志） |

两个包**同一把密钥签名**，可互相覆盖安装，不必先卸载。

## 许可证

本项目基于 [GPL-3.0](LICENSE) 协议开源。
