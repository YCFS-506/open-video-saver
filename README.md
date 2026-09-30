# Open Video Saver

**公开作品保存工具 · Windows 桌面版**

粘贴作品链接，选择视频画质或图文图片，将公开可访问的内容保存到本地。支持抖音、哔哩哔哩、小红书和快手，提供桌面界面与命令行入口。

项目优先使用平台页面提供的播放流，避免使用附加平台水印的下载流。它不对画面做裁剪、模糊或重绘；作品本身已有的文字、作者标识和水印会保留。获取结果取决于作品可见性、平台返回的数据及网络状态。

## 功能

- **自动识别平台与作品类型**：支持作品页面、短链接和包含链接的完整分享文案。
- **视频画质选择**：展示实际分辨率、帧率、编码与预计大小；用“通用格式”“省空间格式”等说明解释 H.264、H.265 和 AV1。
- **音视频保存**：支持 MP4、DASH 分轨合并和快手 HLS 流；合并时直接复制编码数据，不重新压缩。
- **图文选择保存**：预览图片，查看真实像素尺寸，逐张勾选；支持鼠标滚轮和触控板滚动。
- **保留图片与文案**：写入网站返回的图片字节，保留原作品序号，并附 `文案.txt` 与 `来源.txt`。
- **复用解析结果**：读取画质后复用播放地址，减少重复解析；地址失效时重新读取。

## 平台支持

| 平台 | 视频 | 图文 | 获取方式与已知限制 |
| --- | --- | --- | --- |
| 抖音 | 支持 | 支持 | Edge 读取公开视频页面；兼容部分使用 `/share/video/` 路径的旧图文短链。图片可能是平台签名的 WebP 版本。 |
| 哔哩哔哩 | 支持，包括分 P | 支持图片动态及含图片的专栏 | 视频读取公开接口；图文读取页面数据中的图片地址。游客可取得的画质可能受限。 |
| 小红书 | 支持 | 已实现，待更多公开图文样本验证 | 读取公开笔记数据；分享参数需要完整保留，页面可能要求登录或验证。 |
| 快手 | 支持 MP4 / HLS | 支持图集 | 优先读取官方手机网页；失败时使用第三方公开链接解析，最后尝试专用 Edge 窗口。 |

这是 **Windows 桌面项目**。当前不提供 Android APK 或 HarmonyOS HAP；移动端迁移方案见 [移动端说明](docs/mobile.md)。

## 安装与运行

### 环境要求

| 依赖 | 用途 |
| --- | --- |
| Windows 10 / 11 | 当前桌面界面和浏览器适配的目标系统 |
| Python 3.11+，包含 Tkinter | 运行界面与下载逻辑；本机验证环境为 Python 3.14 |
| Microsoft Edge | 打开公开作品页面；使用 Playwright 的 `msedge` 通道 |
| FFmpeg 与 FFprobe，位于 `PATH` | 测量媒体信息、保存 HLS、合并音视频 |
| `requirements.txt` 中的 Python 包 | 网络请求、浏览器操作与图片预览 |

可从 [Python 官网](https://www.python.org/downloads/windows/)、[Microsoft Edge 官网](https://www.microsoft.com/edge) 和 [FFmpeg 下载页](https://ffmpeg.org/download.html) 安装系统依赖。程序使用已安装的 Edge，无需额外下载 Playwright Chromium。

### 从源码启动

在 PowerShell 中执行：

```powershell
git clone https://github.com/YCFS-506/open-video-saver.git
cd open-video-saver
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe app.pyw
```

完成安装后，可以双击 `start_windows.cmd` 启动。检查 FFmpeg 配置：

```powershell
ffmpeg -version
ffprobe -version
```

### 桌面使用流程

1. 粘贴作品链接或整段分享文案，点击 **读取作品**。
2. 视频选择对应画质；图文点击 **预览并选择图片**，勾选后确认。
3. 设置保存目录，点击 **开始下载**。
4. 点击 **打开保存目录** 查看结果。

直接点击“开始下载”也会先读取作品。图文在写入保存目录前要求确认图片选择；预览阶段会临时读取图片数据，用于展示真实尺寸，并复用这些数据保存选中的图片。

默认保存目录为程序旁边的 `已下载视频`。图文会生成独立文件夹，例如：

```text
已下载视频/
└── 抖音_<作品ID>_图文/
    ├── 01.webp
    ├── 05.webp
    ├── 文案.txt
    └── 来源.txt
```

`01`、`05` 表示原作品中的第 1、5 张图片，不会因选择部分图片而重新编号。

### 命令行

```powershell
# 接受完整分享文案，或只传入链接
.\.venv\Scripts\python.exe downloader.py '<作品分享链接>' --output '.\downloads'

# 请求当前公开页面返回的最高画质
.\.venv\Scripts\python.exe downloader.py '<作品分享链接>' --quality best --output '.\downloads'

# 查看参数说明
.\.venv\Scripts\python.exe downloader.py --help
```

`--quality 1080` 为默认策略，通常优先选择不超过 1080p 的可用版本；`best` 按适配器的最高画质策略选择。命令行当前会保存图文的全部图片，逐张选择请使用桌面界面。

## Windows 打包

提供 PyInstaller 构建脚本，用于生成 Windows 可运行目录和 ZIP 文件：

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
powershell -ExecutionPolicy Bypass -File .\scripts\build_windows.ps1
```

输出位于 `dist/OpenVideoSaver/`，压缩包为 `dist/OpenVideoSaver-windows.zip`。分发时应保留整个程序目录，不能只复制其中的 `.exe`。打包版本包含 Python 运行时和 Python 依赖，**仍需要安装 Edge，并将 FFmpeg / FFprobe 加入 `PATH`**。

PyInstaller 生成当前桌面系统的可执行程序；它不能把本项目转换成 Android 安装包。构建脚本不自动下载浏览器，也不捆绑 FFmpeg 二进制文件。

## 网络行为与本地数据

- 不读取用户日常 Edge 的浏览器资料或现有账号 Cookie。
- 抖音、小红书和哔哩哔哩图文使用新的浏览器环境；网页自身可能要求登录或验证。
- 快手官方网页解析失败时，会把用户提供的**公开作品链接**发送到 `https://api.bugpk.com/api/kuaishou`。该第三方服务不属于本项目，程序不向其发送浏览器 Cookie。使用回退服务取得的流会在界面标注。
- 快手浏览器回退使用独立资料目录 `%LOCALAPPDATA%\OpenVideoSaver\KuaishouBrowserDirect`，由用户手动完成可能出现的验证。
- 快手请求与专用浏览器优先直连；其他平台请求可能受到系统网络配置影响。
- 下载文件、文案和来源信息只写入用户选择的本地目录。Git 仓库不包含下载作品、浏览器资料或账号凭据。

## 常见问题

**为什么保存的画质不等于网页上的最高档位？**

程序显示平台当前公开返回的媒体规格。作品原始规格、游客权限、播放地址有效期及平台压缩都会影响结果。1440×1080 等尺寸也可能来自作品本身的画面比例，程序不强制拉伸到 1920×1080。

**图片保存后是否还会被压缩？**

不会。预览会缩小显示，保存时写入收到的原始图片文件。平台返回的图片可能已被压缩；作者上传的低分辨率图片也不会在下载后变成高清图。

**遇到登录、滑块验证或解析失败怎么办？**

先确认作品公开、链接完整且能在浏览器打开。网站更新、网络配置或访问限制都可能导致解析失败。快手浏览器回退会等待用户手动验证；本项目没有自动破解验证的功能。

**能否下载私密作品或仅凭 `blob:` 地址保存？**

当前输入入口用于支持平台的公开作品链接。私密作品、直播、付费内容和通用媒体地址下载不在当前支持范围内。

## 项目结构

```text
app.pyw                 Tkinter 桌面界面与图片选择
downloader.py           下载入口、命令行与抖音适配
platforms.py            分享文本提取与平台识别
bilibili.py             哔哩哔哩视频 / 图文适配
xiaohongshu.py          小红书视频 / 图文适配
kuaishou.py             快手视频 / 图集及回退路径
image_posts.py          图片预览缓存与有序保存
scripts/build_windows.ps1  Windows 构建脚本
tests/                  无网络回归测试
docs/mobile.md          Android / HarmonyOS 迁移说明
```

运行离线回归检查：

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m compileall -q app.pyw downloader.py platforms.py bilibili.py xiaohongshu.py kuaishou.py image_posts.py
```

平台接口会变化，离线测试不能证明所有在线作品都能下载。提交问题时请说明平台、错误信息、系统及依赖版本；公开分享链接可作为复现样本，避免提交 Cookie、访问令牌或浏览器资料。

## 参考与许可

解析思路参考了以下公开项目及其文档；参考仓库副本未包含在本项目中：

- [DyExtract](https://github.com/Hartcher1996/DyExtract) 与 [yt-dlp](https://github.com/yt-dlp/yt-dlp)：分享链接、公开播放流、编码与音视频分轨。
- [videodl](https://github.com/CharlesPikachu/videodl) 与 [KS-Downloader](https://github.com/JoeanAmier/KS-Downloader)：快手网页作品数据结构。
- [short-videos](https://github.com/kangleizhui/short-videos) 与 [kuaishou-mcp](https://github.com/fysh1010/kuaishou-mcp)：快手手机页面字段与公开链接解析参考。

项目源码采用 [MIT License](LICENSE)。第三方依赖遵循各自的许可证。保存和使用作品时请尊重作者权益及适用的平台规则。
