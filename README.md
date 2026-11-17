# KiadásKövető

Offline személyes kiadáskövető Androidra, magyar bolti blokkokhoz. Fényképezd le, importáld képként vagy PDF-ként a
blokkot; az alkalmazás a telefonon felismeri a szöveget, kinyeri a tételeket, kategorizál, és kimutatásokat készít —
internet, fiók és felhő nélkül.

Offline personal expense tracker for Hungarian supermarket receipts (Kotlin, Jetpack Compose, Material 3, Room,
CameraX, Tesseract OCR). Everything runs on the device; the app has no network permission.

## Features

- **Capture:** in-app CameraX scanner (multi-shot for long receipts, torch, framing guide), image import
  (JPG/PNG/WebP, several images per receipt), PDF import with page selection, share-to-app, manual entry.
- **Page review:** automatic receipt edge detection, draggable 4-corner perspective crop, rotation, page order.
- **Offline OCR:** Tesseract 5 with bundled Hungarian models (fast by default, accurate mode optional) + English.
  Preprocessing: orientation, perspective, contrast, denoise, adaptive threshold, deskew, quality warnings.
- **Receipt parser:** 10-stage deterministic pipeline; configurable profiles for SPAR, INTERSPAR, Lidl, ALDI, Tesco,
  PENNY and more; weighed products, multi-quantity lines, item and receipt-level discounts, DRS deposits, VAT codes,
  cash rounding, total reconciliation, confidence scoring and review flags. It never invents missing data.
- **Categorization:** 17 Hungarian default categories, abbreviation/brand dictionary, fuzzy OCR-tolerant matching,
  user rules, and remembered corrections (store-aware).
- **Products:** product identities with confirmed merchant aliases, merge/split, barcode (EAN-13/8, UPC-A,
  Code 128, QR via ZXing, offline), price history with package-size normalization.
- **Analytics:** dashboard (month total, change vs previous month, average basket, budget, categories, 6-month trend,
  recent receipts, frequent products); statistics tabs for monthly/daily/weekly spending, categories, products,
  price changes and store comparison.
- **Budgets:** total/category/store, monthly/weekly, local notifications at 50/75/90/100 % (configurable), once per period.
- **Receipts:** search (accent-insensitive), filters (store, period, amount), detail view as a paper slip, editing of
  every field and line, duplicate detection (file hash, image hash, register IDs, content, metadata) with override.
- **Data:** ZIP backup/restore (data + original files), automatic local backup (WorkManager), CSV/JSON export,
  monthly PDF report, database consistency check, full data deletion, biometric app lock.
- **Optional local AI:** GGUF model import and compatibility check (see Known limitations).

## Project layout

```
app/              Compose UI, view models, repositories, import pipeline, backup, workers
core/             money, quantities, text normalization, fuzzy matching (pure Kotlin)
receipt-parser/   receipt interpretation engine + JSON configuration (pure Kotlin)
database/         Room entities, DAOs, exported schemas
ocr/              preprocessing, Tesseract engine, PDF renderer, barcode decoder, bundled tessdata
local-ai/         optional local LLM integration surface
docs/             ARCHITECTURE.md
output/           built APKs + SHA-256 checksums
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [PRIVACY.md](PRIVACY.md) and [DESIGN.md](DESIGN.md).

## Requirements

- JDK 17+ (built with JDK 21)
- Android SDK: platform `android-37.0`, build-tools `37.0.0` (Android Studio installs these automatically)
- Gradle wrapper included (Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21)

## Build

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

The release build is minified with R8. Without `keystore.properties` it is **unsigned**
(`app/build/outputs/apk/release/app-release-unsigned.apk`).

### Release signing

1. Create a key once (keep it safe; updates must use the same key):

```bash
keytool -genkeypair -v -keystore kiadaskoveto-release.jks -alias kiadaskoveto -keyalg RSA -keysize 4096 -validity 10000
```

2. Copy `keystore.properties.example` to `keystore.properties` and fill in the values (the file is git-ignored).
3. Run `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

Alternatively sign the unsigned APK manually with `apksigner sign --ks kiadaskoveto-release.jks app-release-unsigned.apk`.

## Continuous integration

[`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml) runs on every push to `main` (and only
there): unit tests, then the R8 release build. The APK and its `SHA256SUMS.txt` are attached to the workflow run as an
artifact named `KiadasKoveto-<version>-<commit>` (Actions tab → the run → Artifacts; kept for 30 days).

**GitHub releases:** when the tag `v<versionName>` (from `app/build.gradle.kts`) does not exist yet, the same run
publishes a GitHub release with that tag on the pushed commit, with the APK and `SHA256SUMS.txt` attached. To publish a
new release, bump `versionName` (and `versionCode`) and push to `main`; pushes without a bump only produce the run
artifact.

Builds are cached: the Gradle User Home and Gradle build cache (unchanged tasks are restored instead of re-run), the
Android SDK platform/build-tools and Robolectric's Android jars. Optional repository secrets
(Settings → Secrets and variables → Actions):

| Secret | Purpose |
|---|---|
| `GRADLE_ENCRYPTION_KEY` | Also caches the Gradle configuration cache. Any random value, e.g. `openssl rand -base64 16`. |
| `ANDROID_KEYSTORE_BASE64` | Your release keystore, base64-encoded (`base64 -w0 kiadaskoveto-release.jks`). Without it the APK is unsigned. |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Key alias, e.g. `kiadaskoveto`. |
| `ANDROID_KEY_PASSWORD` | Key password. |

The signing values are written to a temporary `keystore.properties` (avoid backslashes in the passwords) and removed
when the job ends. The workflow never generates a key: always sign with the same key, or installed apps cannot be
updated.

## Tests

```bash
./gradlew test
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

`test` runs the JVM/Robolectric suites (parser fixtures for SPAR, Lidl, ALDI, Tesco, PENNY, INTERSPAR, a generic shop
and real on-device OCR transcripts of a SPAR photo and a Tesco PDF, including misread years, VAT codes and
multiplication signs; money/amount parsing; categorization; duplicates; image algorithms; GGUF parsing; Room DAOs;
repositories, budgets, dashboard view model, exports). `connectedDebugAndroidTest` needs a device or emulator and runs
the UI navigation tests and end-to-end offline OCR import of a generated photo, a PDF and two photo-like receipts.

Last verified run (Android 16 x86_64 emulator): JVM suites 84 tests, 0 failures; instrumented 7 tests, 0 failures.
Manually checked on the emulator: onboarding, camera capture (multi-shot), image picker, PDF share, page review,
processing, review/confirm, duplicate warning, receipt detail, statistics, dark mode and a tablet layout
(navigation rail, centered content).

## Known limitations

- **Speed was only measured on an x86_64 emulator** (4 vCPUs, software rendering), not on a phone. Release build,
  one photographed receipt through the app: preprocessing 2–3 s, text recognition 13–21 s, cold start 4–5 s. The same
  recognition takes 5–6 s when nothing is drawn on screen (instrumented test), because the emulator renders the UI on
  the host CPU and that competes with Tesseract's threads. Phones render on the GPU and run Tesseract's ARM NEON code,
  so they should be faster, but this is not verified. Debug builds are 2–3× slower still (no ahead-of-time
  compilation), so judge speed with the release build. If recognition is slow on your phone, keep "Gyors" mode
  (the default).
- **OCR accuracy** was verified on generated, photo-like test receipts and their real OCR output, not on real thermal
  receipts from each chain. The store profiles follow general Hungarian receipt conventions and are configurable in
  `parser_config.json`; expect to correct some lines (they are flagged for review, and corrections are remembered).
- **Local LLM:** a GGUF model can be imported and checked for compatibility, but no inference runtime (llama.cpp) is
  bundled, so the AI assist toggle cannot be enabled. The rule-based engine does all categorization.
- **Encrypted database (SQLCipher) is not implemented.** Data relies on Android's file-based encryption of app-private
  storage plus the optional biometric app lock.
- **Release signing:** no signing key was created; the release APK in `output/` is unsigned until you sign it.
- **Camera auto-capture / live edge tracking** is not implemented; edge detection runs on the captured photo in the
  page review screen, where the corners can be adjusted.
- The debug APK is large (~135 MB) because it is not minified; the R8 release build is ~52 MB (four CPU ABIs + the
  Hungarian/English OCR models).

## Install

- **Android Studio:** File → Open → select this folder, let Gradle sync, then Run ▶ on a device (Android 10+).
- **APK:** enable "Install unknown apps" for your file manager, copy `output/KiadasKoveto-debug.apk` to the phone and
  open it, or with USB debugging:

```bash
adb install -r output/KiadasKoveto-debug.apk
```

- **Release APK:** `output/KiadasKoveto-release-unsigned.apk` is the faster, minified build, but Android refuses to
  install an unsigned APK. Sign it first (see Release signing above), then install the signed file. A debug and a
  release build have different package names (`hu.kiadaskoveto.app.debug` / `hu.kiadaskoveto.app`), so their data is
  separate.
- **Checksums:** `output/SHA256SUMS.txt` lists the SHA-256 of each APK:

```bash
cd output && sha256sum -c SHA256SUMS.txt
```
