# MxPlayerTune

MX Player 增强模块（libxposed API 102 / LSPosed）：让 MX Player 的「本地网络」在 SMB 之外支持 **WebDAV**。

> ⚠️ **当前状态：开发中（阶段 B · 浏览/播放已真机跑通，缩略图待复验）**
> - 列目录：真机 http 与 https **都**跑通（一次列出 591 项）
> - 播放：真机 http **已能播放**（凭据内联进 URL 的 userinfo 之后）
> - 缩略图：仍未显示 —— 已定位到原因（MX 的 `MxImageDownloader` 对 http(s) 用
>   `HttpURLConnection` 取流且**不带任何鉴权头** ⇒ 401 ⇒ 只留占位图），本版加了 hook ④ 补头，
>   待真机复验
> - https 的边界不变：**浏览**可以（本模块自己放宽校验），自签证书下**播放/缩略图**仍可能被
>   MX 自带 FFmpeg 拒

## 功能

**已实现（待真机复验）**

- **WebDAV 支持**：在 MX Player「本地网络 → 添加服务器」的「服务器」栏直接填
  `http(s)://主机:端口/路径` 的 WebDAV 地址 + 账号密码，即可在 MX Player 内**浏览目录**、
  **显示缩略图**并**播放其中的视频**。只读 —— 含浏览与播放，**不含**上传 / 删除 / 重命名。
- 列目录走一次 `PROPFIND`（`Depth: 1`）：目录优先、只显示媒体文件（判据与 SMB 路径一致：用 MX 自己的 `MediaExtensions`）
- 缩略图：改用条目自身的 URL 交给 MX 的图片加载器（MX 原逻辑用 `getHost()+getPath()` 重建 URL ⇒ **丢端口**，非 80/443 的服务器必然加载不出来）；
  并给图片取流补上 `Authorization`（MX 的图片取流**不认** URL 里的 userinfo ⇒ 不加就是 401、缩略图空白）
- 列目录的 `PROPFIND`：动词写到「真正发请求的那个对象」上（Android 的 https 是委托壳，只写壳等于没发出去）；服务端回 501/405 时自动改用手写 HTTP/1.1 重试一次
- 播放：把凭据内联成 URL 的 userinfo（`http://用户:密码@主机:端口/…`）—— MX 用 `uri.getUserInfo()` 生成 `Authorization: Basic` 交给 FFmpeg；**URL 里没有 userinfo 就等于播放不带鉴权**
- 凭据另一条路走 `Authorization: Basic` 请求头（列目录用），**绝不写进请求行**（URL 里的 userinfo 一律先剥掉再发）
- 地址既支持「服务器」与「分享路径」分栏填写，也支持把凭据写进 URL（`http://用户:密码@主机/路径`）
- **自签名证书的 https 能浏览**：本模块的 `PROPFIND` 对 https 放宽证书校验，且只作用于本模块这条连接（不碰进程全局校验）

**待实现**

- 播放链路（seek / 外挂字幕）与缩略图的真机复验 → 阶段 C
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
  - `PROPFIND … → HTTP 501` = **动词没真正发出去**（服务端收到的是 `POST`）。只可能发生在反射写 `method` 写不到「真正发请求的那个对象」时；本模块会自动改用手写 HTTP/1.1 重试，若这行之后仍失败，把日志给我（流水里会写明走了哪条路径）
  - `MalformedURLException: invalid port: …` = 密码里含 `?` / `#` 这类 URL 结构字符，而 URL 里放的是**解码后的真凭据**（编码形态才是对的，见上表）
  - 日志里**一个 hook 调用都没有** → 不是 hook 失效，而是**目录列举没被触发**：停在「本地网络」列表页不算进入目录；目录页**有缓存**时也不会重新列举，**下拉刷新**可强制重新列举
- 缩略图：`[DBG] >> SmbUtil#buildThumbUrl 改用条目 path=…` = 缩略图 URL 已改走条目自身 URL（debug 版只打前 3 次）；
  `[DBG] >> 取流补上 Authorization（图片/缩略图链路）` = 取图请求已补上鉴权头（debug 版只打前 8 次）；
  两者都有、缩略图仍空白 ⇒ 取流已带鉴权，问题在 MX 那边的解码/取帧，日志给我继续查
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
