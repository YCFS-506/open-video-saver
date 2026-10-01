# Android 与鸿蒙 APK 说明

Android 版位于 `android/`，当前版本为 `0.2.0-alpha.3`。它使用 Java 原生界面与 Android SDK 构建，不将桌面 Tkinter / Playwright 打入手机应用。

## 目标系统

| 系统 | 交付形式 | 状态 |
| --- | --- | --- |
| Android 8.0+，包括基于 Android 的小米 / Redmi 系统 | APK | 已实现；需在具体机型检查安装、平台解析及后台保存。 |
| 支持安装 Android APK 的鸿蒙设备 | 同一 APK | 本次优先测试目标；尚无鸿蒙真机验证记录。 |
| HarmonyOS NEXT 原生应用 | HAP / APP | 当前没有原生包，不能将本 APK 视为 NEXT 原生应用。 |

[PyInstaller](https://pyinstaller.org/en/stable/) 用于桌面程序打包。APK 由 Android Gradle Plugin 8.7.3、Gradle 8.9、JDK 17 和 Android SDK 35 构建；最低运行 API 为 26，目标 API 为 35。

## 操作流程

1. 粘贴作品链接或包含链接的整段文案。也可在平台应用的系统分享菜单中选择“公开作品保存”；若平台仅提供内部分享，请复制链接后粘贴。
2. 点击“读取作品”，应用识别抖音、哔哩哔哩、小红书或快手。平台网页默认隐藏，在后台保持正常加载；如需手动登录或验证，可点击“查看网页”，完成后可隐藏。
3. 文案出现后可点击“一键复制文案”，无需先保存视频或图片。视频选择分辨率 / 帧率 / 编码；图文预览逐张显示，查看下载文件的实际尺寸并勾选需要保存的图片。
4. 点击“保存到手机”，允许对应系统需要的存储或通知权限。Android 10+ 不需要读取整个相册的权限；Android 8 / 9 需要共享存储写入权限。拒绝通知权限不阻止 Android 13+ 的保存操作。
5. 完成后弹出“保存成功”，显示实际保存数量和位置，按钮附近也保留完成提示。在相册或文件管理器查看结果。保存中按钮禁用，成功保存过的作品再次保存需确认，应用重启后仍有效。前台服务用于持续显示进度，支持取消；应用被系统强制终止后，任务不会自动恢复。

图文保存保留原作品序号，例如选择第 1、3 张会得到 `01.png`、`03.png`。预览阶段下载图片用于显示真实尺寸，只有勾选的图片会写入共享媒体库；图片字节不会重压缩。

读取优化：在页面首批内容提交时开始检查作品数据，不等待全部装饰资源；图片预览同时读取最多三张，按原序号更新；哔哩哔哩的两类画质请求同时进行。界面显示实际耗时。速度仍受平台响应、验证与原图片大小影响，不保证每个链接固定提速比例；画质选项及图片文件不会因此降低规格。

复制只取平台返回的作品文案，不会把截短的分享文本当作完整正文。哔哩哔哩视频包含标题与简介。换行、话题与链接按读取结果保留；平台没有返回文案时按钮禁用。点击时将文案写入系统剪贴板，Android 13+ 使用系统复制提示，旧系统显示应用提示。

## 平台实现与边界

| 平台 | Android 解析路径 | 注意事项 |
| --- | --- | --- |
| 抖音 | 分享短链重定向、应用内 WebView 的作品详情响应；图文页面图片及公开播放地址回退 | 页面可能要求验证；页面回退取得的画质可能较少，图文数量仍需要在线样本核对。 |
| 哔哩哔哩 | 公开视频接口、DASH 音视频流、图文 WebView 状态数据 | 游客画质受平台返回值影响；音视频使用系统 MediaExtractor / MediaMuxer 合并。 |
| 小红书 | WebView 的公开笔记状态、图片地址及视频流 | 完整保留分享参数；部分笔记要求登录 / 验证，手机端需继续验证在线样本。 |
| 快手 | 官方手机网页的 INIT_STATE，必要时应用内 WebView | 无第三方解析回退；不自动处理滑块验证。 |

本版已实现以上解析器，但尚未在目标手机验证四个平台的实际分享作品。桌面版的在线成功记录不能代替手机端验证；首次测试请分别核对平台、图片数量、画质选项、音轨和保存位置。

视频使用原有编码数据重新封装为 MP4，不进行分辨率放大、补帧或画面重绘。HLS 目前支持完整公开播放列表中的普通 TS / fMP4 分段；直播、加密媒体及字节范围分段会明确报错。H.265、AV1 或特殊音轨的封装支持取决于设备系统，失败时可以选择平台实际提供的 H.264 版本。

## 文件位置与数据

```text
Movies/OpenVideoSaver/<平台>_<ID>_<时间>.mp4
Pictures/OpenVideoSaver/<平台>_<ID>_<时间>/01.webp
Download/OpenVideoSaver/<平台>_<ID>_<时间>/文案.txt
Download/OpenVideoSaver/<平台>_<ID>_<时间>/来源.txt
```

Android 10+ 通过 MediaStore 写入，由系统分配最终文件位置；视频和图片可由相册检索。文案与来源放在下载目录，与媒体文件通过同一作品 ID 和时间关联。

应用无统计上报和服务器端下载组件。作品解析请求发送到对应平台及其媒体 CDN。WebView 的站点 Cookie 保留在此应用的数据中，不读取日常浏览器资料、不要求粘贴 Cookie；清除应用数据会清除这些站点数据与缓存。预览缓存可能在退出后保留，由系统缓存管理或清除应用缓存清理。

成功保存记录仅保存在应用私有数据中，不上传。记录按平台与作品 ID 识别，选择另一组图片或另一档画质时仍会询问是否再次保存；确认后可以继续保存。删除相册文件不会自动删除此记录；清除应用数据或卸载会清除记录。旧版未记录的保存结果不能追溯识别。

## 构建与检查

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

在 Android Studio 或已连接模拟器 / 设备的环境中运行设备检查，需要先生成测试用的原创视频与音频：

```powershell
New-Item -ItemType Directory -Force app/src/androidTest/assets
ffmpeg -f lavfi -i testsrc2=size=160x90:rate=30 -t 1 -c:v libx264 -pix_fmt yuv420p app/src/androidTest/assets/video.mp4
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -t 1 -c:a aac app/src/androidTest/assets/audio.m4a
ffmpeg -f lavfi -i testsrc2=size=160x90:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 1 -c:v libx264 -c:a aac -pix_fmt yuv420p -f mpegts app/src/androidTest/assets/sample.ts
.\gradlew.bat connectedDebugAndroidTest
```

CI 在 Android 10（API 29）与 Android 15（API 35）模拟器检查分享入口、未解析前的按钮状态、完整多段文案复制、图片字节 / 序号 / 文案、实际保存服务的成功弹窗与重复保存拦截、隐藏网页的有效布局及慢图片未完成前的读取，以及合并后的音视频轨道、尺寸和帧数。并行预览另有离线检查，覆盖三路并发、原序号、取消和错误回调。测试文件由 FFmpeg 或本地测试生成，不下载第三方作品；模拟器检查不证明平台线上解析或鸿蒙 / 小米厂商系统兼容性。

测试 APK 使用每次构建的调试签名，当前没有长期发布签名或应用商店版本。签名变化时不能覆盖安装，需要卸载旧测试包后安装新包。版本升级、后台省电策略、相册显示和四个平台的实际网络行为，应以设备测试记录为准。

官方资料：[Android 构建](https://developer.android.com/build)、[媒体格式支持](https://developer.android.com/media/platform/supported-formats)、[共享存储](https://developer.android.com/training/data-storage/shared/media)、[HarmonyOS 开发平台](https://developer.huawei.com/consumer/cn/develop/)。
