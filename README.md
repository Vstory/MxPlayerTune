# MxPlayerTune

MX Player 增强模块（libxposed API 102 / LSPosed）：让 MX Player 的「本地网络」在 SMB 之外支持 **WebDAV**。

> ⚠️ **当前状态：开发中（阶段 B · 浏览/播放已真机跑通；缩略图已查明是「宿主侧取流方式 + 本服务端结构」的硬限制）**
> - 列目录：真机 http 与 https **都**跑通（一次列出 591 项）。传输层已改用 **OkHttp** ——
>   平台自带的 `HttpURLConnection` 不支持 `PROPFIND` 这类非标准动词，旧版靠反射改框架字段，
>   而 Android 的 https 是委托壳 ⇒ 写壳等于没写 ⇒ 请求以 `POST` 发出去 ⇒ 501
> - 播放：真机 http **已能播放**（凭据内联进 URL 的 userinfo 之后）
> - 缩略图：**仍空白，但原因已钉死、且在当前服务端上无法由本模块修**（2026-09-29 真机日志 + 直打服务端实测）：
>   ① 宿主的缩略图取流取的就是**条目自身 URL**（`SmbUtil.b` 用 `uri.getHost()+uri.getPath()` 原样重建，dex 事实）；
>   ② 本机 CloudDrive 把每个**媒体文件**暴露成「同名集合里放同名子项」，其中**只有同名子项那条 URL 能取到字节**
>   （集合那条 `GET` 一律 `405`，带不带 `Range` 都一样）；
>   ③ 宿主取图**不发 `Range`**（服务端本来支持 —— 带 `Range` 实测 `206` + `Content-Range`）⇒ 它面对 380 MB 的整片
>   只能放弃 ⇒ 只留占位图。
>   本模块该做的都生效了：补鉴权头（hook ④）、把端口与凭据带进缩略图 URL（hook ③）⇒ **取流判定是 `200`，不是 `401`**
> - https 的边界不变：**浏览**可以（本模块自己放宽校验），自签证书下**播放/缩略图**仍可能被
>   MX 自带 FFmpeg / 图片加载器拒（那两者的证书校验不在本模块手里）

## 功能

**已实现（待真机复验）**

- **WebDAV 支持**：在 MX Player「本地网络 → 添加服务器」的「服务器」栏直接填
  `http(s)://主机:端口/路径` 的 WebDAV 地址 + 账号密码，即可在 MX Player 内**浏览目录**、
  **显示缩略图**并**播放其中的视频**。只读 —— 含浏览与播放，**不含**上传 / 删除 / 重命名。
- 列目录走一次 `PROPFIND`（`Depth: 1`）：目录优先、只显示媒体文件（判据与 SMB 路径一致：用 MX 自己的 `MediaExtensions`）
- 缩略图：改用条目自身的 URL 交给 MX 的图片加载器（MX 原逻辑用 `getHost()+getPath()` 重建 URL ⇒ **丢端口**，非 80/443 的服务器必然加载不出来）；
  并给图片取流补上 `Authorization`（MX 的图片取流**不认** URL 里的 userinfo ⇒ 不加就是 401、缩略图空白）。
  这两处只负责**把宿主的取图请求原样发得出去**；视频条目最终能否显示缩略图取决于服务端能否给出一张图，见文首状态与「特殊情况」表
- 列目录的 `PROPFIND`：传输层用 **OkHttp**。平台自带的 `HttpURLConnection` 不收 `PROPFIND` 这种非标准动词
  （`setRequestMethod` 有白名单），且 Android 的 https 是**委托壳**（写壳上的字段等于没写 ⇒ 请求会以 `POST` 发出去 ⇒ 服务端 501）——
  旧版为此要反射改框架内部字段，现已整段删除
- 播放：把凭据内联成 URL 的 userinfo（`http://用户:密码@主机:端口/…`）—— MX 用 `uri.getUserInfo()` 生成 `Authorization: Basic` 交给 FFmpeg；**URL 里没有 userinfo 就等于播放不带鉴权**
- 凭据另一条路走 `Authorization: Basic` 请求头（列目录用），**绝不写进请求行**（URL 里的 userinfo 一律先剥掉再发）
- 地址既支持「服务器」与「分享路径」分栏填写，也支持把凭据写进 URL（`http://用户:密码@主机/路径`）
- **自签名证书的 https 能浏览**：本模块的 `PROPFIND` 对 https 放宽证书校验，且只作用于本模块这条连接（不碰进程全局校验）

**待实现**

- 播放链路（seek / 外挂字幕）的真机复验 → 阶段 C。
  **缩略图那条已结案**（服务端结构 + 宿主取流方式所致，见文首与「特殊情况」表），不再排期
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
| **HTTPS 只保证「浏览」与「取图」** | 本模块自己的请求（列目录）与图片取流（补 `Authorization`）在自签证书下也可用；但**播放**是 MX 自带 FFmpeg 去取流，证书校验在它那边，本模块管不到 ⇒ **自签名证书下播放可能仍失败**。想用 https 播放请用受信证书，否则用 http |
| **视频缩略图出不来（服务端结构 + 宿主取流方式所致）** | 宿主取缩略图时取的是**条目自身 URL**（不是同名的 `.jpg` —— `SmbUtil.b` 用 `uri.getHost()+uri.getPath()` 原样重建，dex 事实），且**不发 `Range`**。本机 CloudDrive 把每个媒体文件暴露成「同名集合 + 同名子项」，**只有同名子项那条 URL 能取到字节**（集合那条 `GET` 一律 `405`）⇒ 宿主只能拿到整片视频（实测 `200` + `len=391840912` + `video/mp4`）⇒ 解码必然失败。服务端上也没有任何图片可给（库里除一个 `.7z` 外全是这种「集合形态的媒体文件」）⇒ 要出图需要宿主侧「分片取流 + 抽帧」，不在本模块能力范围内 |
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
- 缩略图：`[DBG] >> SmbUtil#buildThumbUrl 改用条目 path=…` = 缩略图 URL 已改走条目自身 URL（debug 版只打前 3 次）；
  `[DBG] >> 取流补上 Authorization（图片/缩略图链路）` = 取图请求已补上鉴权头（debug 版只打前 8 次）；
  `[DBG] << 取流判定 HTTP <码> … len=…` = 这次取流**最终拿到的响应码与长度**（同一条连接只报一次）。读法：
  `401` = 这条没带鉴权头（该服务端对无鉴权**一律** 401，不分路径存不存在）；
  `404` = 头到了、路径不存在（宿主在探测同名字幕 / 画质变体，正常噪声，不影响真实资源）；
  `200 + len=整片大小 + type=video/mp4` = 头到了，但宿主拿到的是**视频本体** ⇒ 视频缩略图在这类服务端上必然空白（见文首）；
  `206` = 服务端按 `Range` 回了分片（那种情况下宿主才有机会抽到帧）
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
