# Bangla Dictionary (BnDict) — MVP

Kotlin · Jetpack Compose · Room · Retrofit · DataStore · MVVM · ML Kit on-device translation.
**Status:** written but not compiled in the authoring sandbox (no access to Google's Maven there). Expect to fix small first-build errors.

## What the MVP does
- **Floating dictionary** (priority): draggable bubble + popup over other apps; popup shows Bangla meaning, phonetic, POS, definitions, examples (with Bangla), synonyms/antonyms; expand/collapse, move, close, speak EN/BN, copy, save, "Open ›" full entry.
- **Three ways to trigger it** (Android restricts reading other apps, so we use only supported methods):
  1. *Select text → "Bangla Dictionary"* in the selection menu (`ACTION_PROCESS_TEXT`) — works in most browsers/PDF/ebook readers, no overlay permission.
  2. *Copy text → tap the bubble* — an invisible activity gains focus so Android allows the clipboard read (Android 10+).
  3. *Auto-popup on copy* via `ClipboardManager` listener — reliable on Android 9 and below; Android 10+ blocks background clipboard access.
- Word vs sentence vs paragraph detection; direction auto-detected (Bengali characters → BN→EN).
- Room cache (offline for anything looked up), recents, prefix suggestions, saved items, DataStore settings.

## Not yet built (next stages)
Bundled offline dictionary dataset, word of the day, folders/learning progress/flashcards, manual EN⇄BN toggle, text-size & dark-mode settings, Accessibility-service option, bundled Noto Sans Bengali font.

## Open in Android Studio
1. Android Studio Koala (2024.1) or newer, JDK 17. **File → Open →** the `BnDict` folder. Let Gradle sync (downloads Gradle 8.7).
2. Run on a device/emulator with Android 8.0+ (Google Play services needed for ML Kit).

## Translation provider (no keys needed)
Translation uses **ML Kit on-device** (English↔Bengali): no API key, no text sent to a server; models (~30 MB each) download once.
To use a cloud provider instead, replace `Repository.translate()` only. **Never put a secret key in the APK** — call your own backend (e.g. a small Cloud Function) that holds the key; keep its URL in `local.properties` → `BuildConfig`.
Definitions come from the free `dictionaryapi.dev`; check its terms and credit its sources (Wiktionary-derived). For a full offline dictionary, import a dataset you are licensed to use (e.g. Wiktionary dumps under CC BY-SA) into Room. Do not bundle datasets you don't have rights to.

## Test on a real phone
1. Phone: Settings → About → tap *Build number* 7× → Developer options → enable **USB debugging**. Connect via USB, accept the prompt.
2. Run ▶ in Android Studio, choose your phone.
3. Open the app → Settings tab → **Download offline models** (Wi-Fi).
4. Turn on **Show floating bubble** → grant "Display over other apps" → return.
5. Open any PDF/browser page: (a) select a word → "Bangla Dictionary"; (b) copy a word → tap the bubble; (c) copy a sentence → tap the bubble.

## Signed APK / AAB
Build → **Generate Signed Bundle / APK** → create a keystore (keep it and its passwords safe and backed up; losing it blocks updates) → choose *Android App Bundle* for Play, or *APK* for sideloading → release.

## Publish on Google Play
1. Create a Play Console developer account (one-time fee).
2. Replace the placeholder icon, set a unique `applicationId`, bump `versionCode`.
3. Upload the signed **.aab**; complete Store listing, Content rating, **Data safety** (word sent to dictionaryapi.dev; clipboard read on user action; local cache), and a **privacy policy URL**.
4. Declare the **foreground service (special use)** reason and the **overlay (SYSTEM_ALERT_WINDOW)** justification: "floating dictionary the user turns on".
5. Test via internal testing track, then roll out. Don't add ads inside the popup.
