# PaperCard

PaperCard is a CurateLit-inspired arXiv reader. The Android target is now a native, standalone app for personal use, so the installed APK does not need a companion Node server or LAN API address.

## Features

- Daily arXiv refresh directly from the Android app with configurable keywords and categories.
- Native mobile card deck with right-swipe favorite, up-swipe skip, and `上一条` recovery.
- Agnes 2.0 Flash translation from the phone in personal-key mode.
- On-device preferences, paper cache, favorite folders, favorites, skipped state, and translation cache.
- Native PDF download, cache, render, and app-private save action.
- BibTeX export by copying saved-paper entries from the native app.

## Web Prototype

```bash
npm install
copy .env.example .env
npm run dev
```

The Web app and Node API remain in the workspace as a prototype/development reference. The native Android app does not need them at runtime.

## Android

```bash
npm run android:debug
```

The debug APK is expected at `android/app/build/outputs/apk/debug/app-debug.apk` when a Java JDK and Android SDK are available.

For a personal translation key, either enter it in the Android app settings or place local-only values in `android/local.properties` before building:

```properties
AGNES_API_KEY=your_disposable_test_key
AGNES_API_URL=https://apihub.agnes-ai.com/v1/chat/completions
AGNES_MODEL=agnes-2.0-flash
```

This workspace also supports a local, non-system toolchain under `.toolchains/`. To rebuild with it on Windows PowerShell:

```powershell
$env:JAVA_HOME=(Resolve-Path '.toolchains\jdk-21').Path
$env:ANDROID_HOME=(Resolve-Path '.toolchains\android-sdk').Path
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
Set-Content -Path 'android\local.properties' -Value ("sdk.dir=" + ($env:ANDROID_HOME -replace '\\','/'))
npm run android:debug
```

The native Android app queries arXiv, calls Agnes, stores reading state, and opens PDFs directly on the phone. Clearing local data in settings removes only PaperCard's private preferences/cache/download files.
