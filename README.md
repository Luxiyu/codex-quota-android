# Codex 额度 · Android

在手机上独立登录并查看 Codex 剩余额度，支持 **2×2** 和 **4×2** 桌面小组件。

这是个人自用的开源项目，**与 OpenAI 无官方关联**。当前版本 **v0.2.1**，已在一加 15、Android 16 上完成真实账号与桌面小组件验证。

[下载 APK / 查看 Release](https://github.com/Luxiyu/codex-quota-android/releases/latest)

## 功能

- 查看 **5 小时额度**与**周额度**的剩余比例、下次恢复时间及倒计时。
- 在 APP 内查看可用免费重置次数、每项重置权益的到期时间。
- 使用官方设备码登录，支持手动刷新、验证登录续期和退出登录。
- 保存本地额度缓存；缺失数据显示“—”，查询失败保留旧数据并标记状态。
- 提供深色圆角、绿色胶囊进度条的 **2×2 / 4×2 桌面小组件**，只显示额度与恢复时间。

项目仅查询额度，不会消耗重置权益，也不提供模型对话或推理功能。

## 安装与使用

### 安装 APK

1. 前往 [最新 Release](https://github.com/Luxiyu/codex-quota-android/releases/latest)，下载 `.apk` 文件。
2. 在手机上打开安装包，按系统提示允许该来源安装应用。
3. 打开“Codex 额度”。

最低要求：**Android 9 / API 28、ARM64（arm64-v8a）**。当前发布包采用 **debug 签名**，用于个人测试；其他设备兼容性尚未充分验证。

APK 约 **132 MiB**，因为内含约 **247 MB** 的官方 Codex 静态组件。安装时需要为解压后的组件及应用数据预留空间。

### 登录并查询

1. 点击“开始登录”，复制页面中的设备码。
2. 打开 APP 提供的官方授权页面，使用自己的 ChatGPT 账户输入设备码并授权。
3. 返回 APP，等待账号和额度查询完成。
4. 需要时点击“刷新”或“验证登录续期”。退出登录会清除本地额度缓存并更新小组件。

若官方授权页面提示需要开启设备码登录，请按照官方页面指引，在账户设置中自行启用。额度查询使用登录后的账户权限，**不需要 OpenAI API Key，也不需要电脑传送登录凭证**。

### 添加桌面小组件

在桌面长按空白处，进入启动器的“小组件”列表，找到“Codex 额度”，添加 **2×2** 或 **4×2** 款式。APP 主界面不提供小组件管理区域。

- 点击卡片：打开 APP。
- 点击 **↻**：请求刷新额度。
- 数字变灰：当前为需要重新确认的缓存数据，请刷新或打开 APP 查看状态。

小组件约每 **30 分钟**安排一次后台刷新；实际执行时间受网络、Android 省电策略和启动器行为影响。恢复时间到达后也需要重新查询服务端，应用不会自行把额度设置为 100%。

## 从源码构建

Release 提供 APK 和源码 ZIP；GitHub 也提供对应标签的源码归档。**源码不包含 Codex 二进制组件**，首次构建前需要运行下载脚本。

### 开发环境

- Android Studio 或可用的 Android SDK 命令行环境。
- **JDK 17 或更高版本**；Gradle Daemon 已固定 JDK 25，并配置自动获取路径，本机使用 Android Studio 内置 JBR 启动 Wrapper。
- Android SDK 平台 **37.0**、Build Tools **36.0.0**。
- **Python 3.10 或更高版本**，用于下载并校验官方组件。
- 首次构建需要联网获取 Gradle、Maven 依赖和官方 Codex 包。

项目固定使用 **AGP 9.1.1、Gradle 9.3.1、Kotlin 2.2.10**，目标 Android API 36。请使用仓库中的 Gradle Wrapper。

### 准备源码与 SDK

```bash
git clone https://github.com/Luxiyu/codex-quota-android.git
cd codex-quota-android
```

在项目根目录创建 `local.properties`，填写自己的 SDK 路径。该文件不提交到仓库。

```properties
# Linux 示例；macOS / Windows 请替换为实际 SDK 路径。
sdk.dir=/home/your-name/Android/Sdk
```

Windows 可使用正斜杠，例如 `sdk.dir=C:/Users/your-name/AppData/Local/Android/Sdk`。也可通过 Android Studio 打开项目并配置 SDK。

### macOS / Linux

确保 `JAVA_HOME` 指向已安装的 JDK，然后执行：

```bash
python3 tools/fetch_codex_core.py
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

### Windows PowerShell

确保 `JAVA_HOME` 指向已安装的 JDK，然后执行：

```powershell
py -3 tools/fetch_codex_core.py
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

下载脚本固定获取官方 **Codex 0.159.2 Linux ARM64 musl** 包，校验包的 SHA-512 与组件的 SHA-256，仅提取所需组件到 `app/src/main/jniLibs/arm64-v8a/libcodex.so`。已有文件校验不匹配时，脚本会停止并保留原文件。

如同步软件在构建目录产生重复文件，可使用 `-PcodexBuildDirectory=/path/to/separate-build` 指定独立输出目录。此时 APK 位于指定目录的 `outputs/apk/debug/`。

## 工作原理与数据安全

```text
Compose 页面 / WorkManager 小组件刷新
                  ↓
        共享的手机本地 Codex 运行时
                  ↓
          官方 Codex app-server
                  ↓
            官方账户服务
```

应用调用官方组件的登录、账户和额度查询 RPC；组件负责设备码授权及令牌刷新。前台页面与后台小组件共享运行时，并串行处理账户操作，避免同时修改登录状态。

静态 Linux 组件无法完整使用 Android 网络设施，因此应用提供仅监听 **127.0.0.1** 的 CONNECT 隧道：由 Android 解析域名并转发加密字节，**不解密 TLS 内容**。TLS 连接与证书校验仍由官方组件执行，可信证书来自 Android 的 `AndroidCAStore`。隧道仅允许官方域名的 HTTPS 443 端口。

登录状态保存在应用私有目录中的独立 `CODEX_HOME`，不会读取或复制电脑上的 Codex 凭证。应用禁用备份，不记录令牌或设备码，也没有项目自建的账号中转服务器。

当前凭证采用官方组件的文件存储方式，**尚未增加 Android Keystore 封装**。卸载应用会删除本地登录状态；登录状态失效时需要重新授权。

## 验证与兼容性

v0.2.1 已在 **一加 15 / Android 16 / ARM64** 上验证：

- 手机独立设备码登录和真实额度查询。
- 强制登录续期后的额度查询。
- APP 冷启动后保留登录状态并重新查询。
- 2×2 与 4×2 小组件的实际桌面显示、刷新及点击打开 APP。
- 浅色主界面的深色状态栏图标。

构建流程包含单元测试及 Android Lint。单元测试任务最长执行时间为 60 秒，覆盖额度解析、未知字段、账户切换、授权结果匹配等边界逻辑。

官方 `app-server` **不是专门为本项目提供的稳定公共额度 API**；服务端协议、登录流程或字段变更可能影响兼容性。后台长期运行与其他厂商启动器尚未全面验证。

## 常见问题

**为什么 APK 这么大？**  
当前将完整官方静态组件打包到应用中，压缩后 APK 约 132 MiB。项目优先验证手机独立查询链路，尚未精简组件。

**为什么恢复时间到了，额度没有立即变满？**  
页面和小组件显示最后一次查询结果。恢复时间是服务端返回的信息；刷新成功后才更新实际额度。

**小组件没有按时刷新怎么办？**  
先点击 ↻，或者打开 APP 查询。后台任务可能被网络及系统省电策略延迟。启动器若缓存旧布局，移除该小组件后重新添加。

**普通 OpenAI API Key 能用吗？**  
本项目查询的是登录账户的 Codex 额度，当前不提供 API Key 登录入口。

**可以直接用于正式商业发布吗？**  
当前发布的是个人测试版本，使用 debug 签名。正式发布还需要完成签名管理、更多机型验证和协议兼容性评估。

## 许可证与第三方组件

本项目采用 [Apache License 2.0](LICENSE)。第三方组件及归属说明见 [NOTICE](NOTICE)。

内嵌官方 [OpenAI Codex](https://github.com/openai/codex) 组件遵循其 Apache-2.0 许可证，许可证副本随 APK 保存在 `assets/codex-LICENSE.txt`。相关接口参考 [Codex app-server 文档](https://learn.chatgpt.com/docs/app-server)。
