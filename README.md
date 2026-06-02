# PaperCard

[中文](#中文) | [English](#english)

## 中文

PaperCard 是一个受 CurateLit 启发的 arXiv 阅读应用。当前 Android 目标是原生、独立运行的个人应用，安装后的 APK 不需要配套 Node 服务或局域网 API 地址。

### 功能

- Android 端直接同步 arXiv，可配置关键词、分类和每次同步数量。
- 今日页是一张主论文卡：右滑上一条，左滑略过；底部固定显示 `PDF`、`原文`、`收藏`。
- 收藏时如果存在多个收藏夹，会弹出菜单选择目标收藏夹。
- 设置页可控制是否翻译标题、abstract，以及是否翻译图片 caption；图片 caption 默认不翻译。
- AI 精读是原生聊天界面，可从收藏创建聊天、添加论文、开启新对话，并且只有用户发送消息时才请求 AI。
- arXiv HTML、PDF 和论文图片都会缓存在应用私有目录中，便于再次查看收藏内容时复用。
- PDF 默认连续阅读，支持缩放、分享和保存副本。

### Web 原型

```bash
npm install
copy .env.example .env
npm run dev
```

Web app 和 Node API 仍保留在仓库中作为原型和开发参考；原生 Android app 正常使用时不需要它们。

### Android 构建

```bash
npm run android:debug
```

如果 Java JDK 和 Android SDK 可用，debug APK 会生成在：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

个人翻译 key 可以在 Android app 设置中填写，也可以在构建前放入本地配置。不要提交真实生产 key。

```properties
AGNES_API_KEY=your_disposable_test_key
AGNES_API_URL=https://apihub.agnes-ai.com/v1/chat/completions
AGNES_MODEL=agnes-2.0-flash
```

本仓库也支持 Windows PowerShell 下使用 `.toolchains/` 内的本地工具链：

```powershell
$env:JAVA_HOME=(Resolve-Path '.toolchains\jdk-17').Path
$env:ANDROID_HOME=(Resolve-Path '.toolchains\android-sdk').Path
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
Set-Content -Path 'android\local.properties' -Value ("sdk.dir=" + ($env:ANDROID_HOME -replace '\\','/'))
npm run android:debug
```

清空本机数据只会删除 PaperCard 的私有偏好、缓存、PDF/图片/HTML 文件和应用私有下载副本。

## English

PaperCard is a CurateLit-inspired arXiv reader. The current Android target is a native, standalone personal app, so the installed APK does not need a companion Node server or LAN API address.

### Features

- Direct arXiv sync from Android with configurable keywords, categories, and result count.
- The Today screen is a single large paper card: swipe right for the previous paper and swipe left to skip; the bottom row shows `PDF`, `Original`, and `Favorite`.
- When multiple favorite folders exist, favoriting opens a folder picker.
- Settings can toggle title translation, abstract translation, and image caption translation; image caption translation is off by default.
- AI Deep Read is a native chat UI. It can create chats from favorites, add papers, start a new conversation, and only calls AI after the user sends a message.
- arXiv HTML, PDFs, and paper images are cached in the app-private cache so favorite content can be revisited without unnecessary downloads.
- PDFs default to continuous reading and support zoom, share, and app-private save-copy actions.

### Web Prototype

```bash
npm install
copy .env.example .env
npm run dev
```

The Web app and Node API remain in the repository as a prototype and development reference. The native Android app does not need them at runtime.

### Android Build

```bash
npm run android:debug
```

When a Java JDK and Android SDK are available, the debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

A personal translation key can be entered in Android settings or provided in local-only build configuration. Do not commit real production keys.

```properties
AGNES_API_KEY=your_disposable_test_key
AGNES_API_URL=https://apihub.agnes-ai.com/v1/chat/completions
AGNES_MODEL=agnes-2.0-flash
```

This workspace also supports the local `.toolchains/` setup on Windows PowerShell:

```powershell
$env:JAVA_HOME=(Resolve-Path '.toolchains\jdk-17').Path
$env:ANDROID_HOME=(Resolve-Path '.toolchains\android-sdk').Path
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
Set-Content -Path 'android\local.properties' -Value ("sdk.dir=" + ($env:ANDROID_HOME -replace '\\','/'))
npm run android:debug
```

Clearing local data only removes PaperCard's private preferences, caches, PDF/image/HTML files, and app-private saved copies.
