# MxPlayerTune

MX Player 增强模块（libxposed API 102 / LSPosed）：让 MX Player 的「本地网络」在 SMB 之外支持 **WebDAV**。

> ⚠️ **当前状态：开发中（阶段 A · 探针）** —— 模块骨架与目标定位已就位，**WebDAV 浏览功能尚未实现**。
> 装上后暂无用户可感知的新功能，本页功能说明为**规划内容**，请勿据此期待可用效果。

## 功能

**规划功能（尚未实现）**

- **WebDAV 支持**：在 MX Player「本地网络 → 添加服务器」的「服务器」栏直接填 `http(s)://主机:端口/路径` 的 WebDAV 地址 + 账号密码，即可在 MX Player 内**浏览该 WebDAV 的目录**并**播放其中的视频**。只读 —— 含浏览与播放，**不含**上传 / 删除 / 重命名。

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

## 安装

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed)（1.9+，支持 libxposed API 102）
2. 安装本模块 APK（[Releases](../../releases)）
3. 在 LSPosed 中启用模块（作用域已静态声明，无需手动勾选）
4. 强制停止 MX Player 后重新打开

## 验证（当前阶段）

本模块无界面，生效与否看 LSPosed 日志（tag `MxPlayerTune`）：

- 装好并重启 MX Player 后，日志依次出现 `api102 module loaded` → `[locate] …` → `installHooks done: 1 OK / 0 FAIL` = **注入与目标定位成功**
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
