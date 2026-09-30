# 移动端迁移说明

当前发布的是 Windows 桌面版。APK / HAP 尚未实现、打包或进行真机验证。

## 平台与安装包

| 目标 | 安装包 / 工具链 | 验证要求 |
| --- | --- | --- |
| Android，包括基于 Android 的小米 / Redmi 系统 | APK；Android SDK 与 Gradle | 按实际系统版本测试安装、文件保存、后台下载与系统分享入口。 |
| 支持安装 Android 应用的华为设备 | Android APK | 需要确认具体机型及系统版本；不能仅凭“鸿蒙”名称判断兼容性。 |
| HarmonyOS NEXT 原生应用 | HAP / APP；DevEco Studio、HarmonyOS SDK 与 ArkTS | 独立适配界面、网络、媒体处理、文件保存及签名流程。 |

[PyInstaller](https://pyinstaller.org/en/stable/) 用于打包桌面 Python 程序，不是 Android APK 构建工具。Python 移动端可评估 [python-for-android](https://python-for-android.readthedocs.io/en/latest/) / [Buildozer](https://buildozer.readthedocs.io/en/stable/)，但这不解决当前桌面组件的迁移问题。

## 当前源码不能直接装到手机的原因

- Tkinter 桌面界面需要替换为适合触摸操作的移动界面。
- Edge / Playwright 的当前运行方式不能照搬到普通 Android 应用；公开页面的脚本数据读取需要用移动平台的浏览器组件或网络客户端重新实现。
- `ffmpeg` / `ffprobe` 外部命令依赖 Windows 的运行环境，移动端需要对应的媒体处理方案。
- Windows 文件路径和目录选择需要替换为移动系统的相册、共享存储及文件选择接口。

## 建议实施顺序

1. Android 版本先覆盖“粘贴链接 / 接收系统分享 → 读取作品 → 选择画质或图片 → 保存”的完整流程。
2. 复用平台识别规则和解析思路，逐平台实现并用实际分享样本验证；不承诺所有桌面访问方式能直接在手机上运行。
3. 使用系统媒体库保存视频和图片，提供清楚的任务进度、失败原因与重试操作。
4. 优先测试目标小米设备，再验证其他 Android 设备及支持 APK 的华为设备。
5. 根据实际 HarmonyOS NEXT 需求，建立原生鸿蒙适配及 HAP 签名、安装验证流程。

迁移时应保留桌面版对真实媒体规格的展示、图片选择、来源信息和第三方链接服务的说明。是否支持某个系统，应以成功安装并完成下载的设备测试记录为准。

官方资料：[Android 应用构建](https://developer.android.com/build)、[HarmonyOS 开发平台](https://developer.huawei.com/consumer/cn/develop/)、[HarmonyOS 应用程序包](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/hap-package)。
