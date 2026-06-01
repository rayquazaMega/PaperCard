# PaperCard Goal

Last updated: 2026-06-01

## Product Vision

PaperCard is a CurateLit-inspired arXiv paper discovery product for researchers and research-minded readers. Its core experience is a quiet, mobile-first card feed that helps users review newly published papers every day, quickly understand abstracts in Chinese, save useful papers, and open or download the original PDF.

The product should feel simple, restrained, and useful rather than promotional. The first screen should be the actual reading workflow: a daily stack of papers, not a landing page.

Reference product:

- CurateLit: https://www.curatelit.com/zh/home

Model/API reference:

- Agnes 2.0 Flash: https://agnes-ai.com/doc/agnes-20-flash

## Current Objective

PaperCard is now being maintained as a fully native, standalone Android app for personal use. The installed APK should run on a phone without a companion computer or LAN API server:

- Query arXiv directly from the app.
- Store preferences, paper cache, skipped papers, favorite folders, favorites, and translation cache locally on the phone.
- Call the translation API directly from the phone using the user's personal test key.
- Open, render, save, and share PDFs inside the app.
- Keep a card-first reading workflow where one large paper card dominates the screen, with bottom navigation for Today, Favorites, and Settings.

The current native app delivers:

- Direct arXiv synchronization from Android native code.
- On-device JSON storage for preferences, papers, favorites, folders, skipped state, and translations.
- Direct Agnes translation calls in personal-key mode.
- A large-card native reading UI with bottom Today/Favorites/Settings navigation.
- Native PDF download, cache, rendering, gesture-friendly reading, app-private saving, and PDF sharing through a restricted content provider.

The Web + Node prototype remains in the workspace as a development reference, not as the phone runtime.

## Target Users

Primary users are researchers, graduate students, engineers, and independent readers who want to track a small stream of relevant arXiv papers without manually searching arXiv every day.

The user should be able to:

- Define research keywords and arXiv categories.
- Open the app daily and see a fresh card stack.
- Read the original title and abstract, with Chinese translation generated automatically when the app has a configured translation key.
- Generate Chinese summaries and key points with AI.
- Favorite papers worth revisiting into user-created folders.
- Open, read, and save the original PDF.
- Export saved papers for reference management.

## Product Principles

- Mobile first: the phone experience is the main surface.
- Actual workflow first: avoid marketing-first screens.
- Simple visual language: plain typography, clean spacing, restrained colors, no decorative noise.
- Source transparency: every paper keeps its arXiv abstract URL and PDF URL.
- Standalone first: the Android app should not require a computer, LAN server, or separate backend for daily use.
- Personal-key mode: for this personal app, a disposable test API key may be configured in the app or local native config. The user accepts the risk because the key can be revoked and has no meaningful spend exposure.
- Keep real production keys out of committed source. If the app ever becomes distributable, move translation back behind a backend or use a user-provided key entry screen.
- Local-native storage: preferences, cache, favorites, skipped papers, folders, and translations should live on-device, preferably in SQLite/Room or another durable Android storage layer.
- Optional backend only: any Node server should become a development/migration helper, not a runtime requirement.

## Existing Features

### Daily Paper Sync

Current prototype: the server queries arXiv through the public Atom API and caches the result in `data/store.json`.

Native target: the Android app queries arXiv directly, parses Atom XML on-device, and writes the normalized paper cache to local device storage.

Current behavior:

- Default keywords: `large language model`, `retrieval augmented generation`, `agent`
- Default categories: `cs.AI`, `cs.CL`, `cs.LG`
- Default max results: `36`
- Cache is refreshed when stale or when the client requests a manual refresh.

### Card Review

The app shows one paper at a time as a card.

Supported actions:

- Right-swipe favorite
- Up-swipe skip
- Previous item recovery through the `上一条` action
- Open original arXiv page
- Open PDF reader
- Generate or refresh translation

The native phone layout should be dominated by the active paper card. Avoid top explanatory panels; summary statistics belong in a small corner area. Operation feedback should appear briefly and disappear automatically.

### Preferences

Users can customize:

- Keywords
- arXiv categories
- Maximum synchronized result count
- Translation API key or model settings when running in personal-key mode

The native app should remove the required API server address setting from the main workflow.

### AI Translation

Current prototype: translation is handled by the backend endpoint `POST /api/translate`.

Native target: translation is called directly from the Android app in personal-key mode.

The intended model is Agnes 2.0 Flash using an OpenAI-compatible chat completion API:

- Endpoint: `https://apihub.agnes-ai.com/v1/chat/completions`
- Model: `agnes-2.0-flash`

For the current personal build, a disposable test key may be stored locally for convenience. Do not commit valuable production keys. Do not assume this policy is safe for a public release.

Expected translation output:

- `title_zh`
- `summary_zh`
- `key_points`
- `reading_note`

### PDF Reader

Legacy prototype: the Web app uses PDF.js to render arXiv PDFs in canvas form. This was intentional because Android WebView often cannot display embedded PDF files reliably.

The backend provides a PDF proxy:

- `GET /api/papers/:readerId/pdf`
- `GET /api/papers/:readerId/pdf?download=1`

Native behavior: download PDFs directly with Android networking, cache them locally when useful, and render them with Android `PdfRenderer`. The reader should feel phone-native: vertical and horizontal scrolling, pinch/button zoom, page gestures, and floating close/share/save/page controls over the document.

### Favorites and Export

Current prototype: favorite folders and favorites are stored locally in `data/store.json`.

Native target: favorite folders and favorites are stored on-device.

The export endpoint returns BibTeX:

- `GET /api/export/bibtex`

## Architecture

### Main Files

- `server/index.js`: Express API server, arXiv sync, store management, translation, PDF proxy, BibTeX export.
- `src/App.tsx`: Main React UI and interaction logic.
- `src/api.ts`: Frontend API client and API base URL handling.
- `src/styles.css`: Responsive UI styling.
- `android/app/src/main/java/com/papercard/reader/MainActivity.java`: Native card-first app shell and Today/Favorites/Settings UI.
- `android/app/src/main/java/com/papercard/reader/PaperStore.java`: On-device store for preferences, papers, folders, favorites, skipped state, and translations.
- `android/app/src/main/java/com/papercard/reader/ArxivClient.java`: Native arXiv Atom query and parser.
- `android/app/src/main/java/com/papercard/reader/AgnesClient.java`: Direct Agnes translation client.
- `android/app/src/main/java/com/papercard/reader/PdfActivity.java`: Native PDF reader.
- `android/app/src/main/java/com/papercard/reader/PaperFileProvider.java`: Restricted content provider for app-cached PDF sharing.
- `android/`: Native Android project.
- `public/manifest.webmanifest`: Web app manifest.
- `.env.example`: Environment variable template.

### Native Migration Target

Longer-term target stack:

- Kotlin Android app.
- Jetpack Compose for UI.
- Room/SQLite for local papers, folders, favorites, skipped state, preferences, and translations.
- OkHttp or Ktor client for arXiv, translation API, and PDF downloads.
- WorkManager for manual or scheduled paper sync.
- Native Android storage APIs for PDF cache and export files.

The current native implementation is intentionally Java/programmatic UI to keep the personal app lightweight and dependency-minimal. Kotlin/Compose/Room remain possible future upgrades, not requirements for the current personal build.

### Runtime Layout

Current prototype local development usually runs two processes:

- Vite frontend: `http://localhost:5173`
- API server: `http://localhost:4173`

The native target should not need these processes for normal app use.

### Data Storage

Current prototype storage is file-based:

- `data/store.json`

This directory is ignored by git and should be treated as runtime state.

The native app should replace this with on-device storage.

## Commands

Install dependencies:

```bash
npm install
```

Run Web app and API together:

```bash
npm run dev
```

Run only the API:

```bash
npm run server
```

Build Web assets:

```bash
npm run build
```

Build Android debug APK:

```bash
npm run android:debug
```

Current debug APK output path:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Local Android Toolchain Notes

This workspace has been built successfully with a local, non-system toolchain under `.toolchains/`.

The important local paths are:

- `.toolchains/jdk-21`
- `.toolchains/android-sdk`

PowerShell setup for rebuilds:

```powershell
$env:JAVA_HOME=(Resolve-Path '.toolchains\jdk-21').Path
$env:ANDROID_HOME=(Resolve-Path '.toolchains\android-sdk').Path
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
Set-Content -Path 'android\local.properties' -Value ("sdk.dir=" + ($env:ANDROID_HOME -replace '\\','/'))
npm run android:debug
```

The native Android build currently uses the local Java 21 toolchain and Java 17 source/target compatibility.

## API Summary

Health:

```text
GET /api/health
```

Preferences:

```text
GET /api/preferences
PUT /api/preferences
```

Papers:

```text
GET /api/papers
GET /api/papers?refresh=1
```

Paper actions:

```text
POST /api/actions
```

Body:

```json
{
  "paperId": "2605.31594v1",
  "action": "favorite"
}
```

Allowed actions:

- `favorite`
- `skip`
- `clear`

Favorites:

```text
GET /api/favorites
```

Favorite folders:

```text
GET /api/folders
POST /api/folders
DELETE /api/folders/:folderId
```

Translation:

```text
POST /api/translate
```

Body:

```json
{
  "paperId": "2605.31594v1",
  "force": false
}
```

PDF:

```text
GET /api/papers/:readerId/pdf
GET /api/papers/:readerId/pdf?download=1
```

BibTeX:

```text
GET /api/export/bibtex
```

## Security and Credential Policy

Do not commit valuable production API keys.

For the next personal-native phase, a disposable test key may be configured on-device or in local-only files because the app is personal, not distributed, and the key can be deleted at any time.

Allowed:

- `.env` with local credentials, ignored by git.
- Environment variables set in the shell or service manager.
- `.env.example` with placeholder values only.
- Local-only Android config files that are ignored by git.
- A user-facing settings field for entering a personal test key.

Not allowed:

- API keys in `src/`
- API keys in `public/`
- API keys in `android/app/src/main/assets/`
- API keys in README examples
- API keys in committed build artifacts

Legacy prototype: the frontend can call the PaperCard backend, and the backend can call Agnes.

Native target: the APK may call Agnes directly in personal-key mode. If PaperCard is ever shared publicly, return to backend-mediated translation or require each user to provide their own key.

## Verification Checklist

Before handing off a change, verify:

- `npm run build` succeeds.
- `GET /api/health` returns `ok: true`.
- `GET /api/papers?refresh=1` returns papers.
- Card buttons work for favorite, skip, and `上一条`.
- Keyword settings can be edited and saved.
- Translation fails gracefully when `AGNES_API_KEY` is missing.
- Translation works when `AGNES_API_KEY` is configured.
- PDF reader opens and renders the first page.
- PDF reader supports scrolling, zooming, app-private saving, and sharing.
- `npm run android:debug` succeeds when Android toolchain is available.
- APK exists at `android/app/build/outputs/apk/debug/app-debug.apk`.

For native migration changes, additionally verify:

- The APK works without `npm run server`.
- A fresh install can sync arXiv papers directly on the phone.
- Favorites, folders, skipped state, preferences, and translations survive app restart.
- Translation failure is shown gracefully when the key is missing or invalid.
- PDF open/download works without the Node proxy.
- The main Today screen is visually dominated by the active paper card with bottom Today/Favorites/Settings navigation.

## Known Limitations

- No real user accounts yet.
- No cross-device sync yet.
- The Web prototype still depends on the Node backend, but the Android APK does not.
- Native storage is currently lightweight JSON in private app storage; Room/SQLite would be more robust for a broader release.
- Translation cache is local and has no invalidation policy beyond force refresh.
- Recommendation scoring is simple keyword/category/recency weighting, not a learned model.
- Only arXiv is implemented right now, though the product direction may later include bioRxiv, medRxiv, and other preprint sources.
- arXiv may rate-limit direct refreshes; failures should stay visible and non-fatal.

## Suggested Roadmap

### Near Term

- Keep refining the native card UI, especially drag feedback and one-handed ergonomics.
- Add a proper previous-card history model if `上一条` should navigate beyond last-action recovery.
- Improve folder selection while favoriting from the Today card without cluttering the main reading surface.
- Add graceful arXiv backoff after rate-limit responses.

### Medium Term

- Move native storage from lightweight JSON to SQLite/Room if the local dataset grows.
- Add BibTeX export from on-device favorites.
- Add reset/import/export for local reading state and preferences.
- Add category descriptions and presets.
- Add scheduled on-device daily sync through WorkManager.
- Add release APK signing config.
- Add smarter ranking from user feedback.
- Add multi-source support for bioRxiv and medRxiv.

### Longer Term

- Add personalized weekly research reports.
- Add Zotero and Notion export/sync.
- Add semantic search over saved papers.
- Add topic clustering and reading history analytics.
- Add optional sync service only if cross-device sync becomes important.

## Maintenance Guidance

Keep frontend changes small and mobile-checked. The phone view is the product's center of gravity.

During the migration, keep backend API responses stable only as long as the React prototype remains useful for comparison. Native app behavior takes priority.

Keep model prompts strict. Translation should return compact JSON that the UI can render without extra parsing tricks.

The Android app is now intended to become the primary product, not just a lightweight shell around the Web experience.

Do not let generated files obscure source changes. Important generated/runtime paths are ignored:

- `node_modules/`
- `dist/`
- `data/`
- `.toolchains/`
- Android build outputs

## Definition of Done

A maintenance task is done when:

- The requested behavior works in the installed native Android APK when it affects the phone app.
- Mobile layout remains usable and visually consistent with the card-first direction.
- API behavior is verified when the legacy Web/Node prototype is affected.
- Android build is updated when native settings or source files change.
- Credentials remain out of source control.
- GOAL.md is updated if the task changes product direction, architecture, setup, or long-term maintenance expectations.

For native migration tasks, "done" means the behavior works in the installed Android APK without requiring the Node server, unless the task explicitly targets the legacy prototype.
