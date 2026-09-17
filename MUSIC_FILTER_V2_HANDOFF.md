# MUSIC FILTER — COMPLETE PROJECT HANDOFF

## 1. Exact project location and repository state
- **Absolute current project root path:** `C:\Users\Ebrahim\Documents\MusicFilter-Android-V2`
- **Repository name:** N/A
- **Git branch:** N/A
- **Latest commit:** N/A
- **Uncommitted changes:** N/A
- **Git status:** `fatal: not a git repository`.
- **Important modified/untracked files:** All current `.java` files in `app/src/main/java/com/musicfilter/app/`, as well as `encoding_check.py`.
- **Repository State:** The project is **NOT** currently using Git properly. Ensure the code is backed up or initialized as a Git repository before continuing.

## 2. Android project configuration
- **Package/Application ID:** `com.musicfilter.app.v2`
- **versionName:** `2.0.0`
- **versionCode:** `1`
- **min SDK:** 29
- **target SDK:** 36
- **compile SDK:** 36
- **Android Gradle Plugin version:** 9.0.1
- **Gradle version:** 9.1.0
- **Java/Kotlin version:** Java 8 (Compatibility level configured in `build.gradle`). The project uses pure Java, no Kotlin.
- **Build variants:** `release` configured with `minifyEnabled true` and `shrinkResources true`.
- **ABI packaging:** Standard native library packaging via Gradle.

**Important Dependencies:**
- ONNX Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`
- Media3: `androidx.media3:media3-*:1.10.1` (exoplayer, ui, inspector, muxer)
- JTransforms: `com.github.wendykierp:JTransforms:3.1` (For offline exact 6,144-point complex STFT/ISTFT)
- AndroidX Core: `androidx.core:core:1.9.0`

## 3. Current production AI model
- **Model Name:** DTTNet Mixed-FP16
- **Exact filename:** `dttnet_fp16_mixed.onnx`
- **Source path:** `app/src/main/assets/models/dttnet/dttnet_fp16_mixed.onnx`
- **APK asset path:** `models/dttnet/dttnet_fp16_mixed.onnx`
- **Expected extracted device path:** `getExternalFilesDir(null) / models/dttnet/dttnet_fp16_mixed.onnx`
- **File size:** 12,082,166 bytes
- **SHA-256:** `1414E65FE7AFF5C5316C337FE9C59468DF86350CDECF4D837BCB1318159C2C19`
- **Sample rate:** 44100
- **Chunk size:** 261,120 samples (approx 5.92 seconds)
- **Overlap:** The user explicitly requested **12.5%** overlap, but current code in `DttNetEngine.java` defines `OVERLAP_SAMPLES = CHUNK_SAMPLES / 2` (**50%**). The next agent must correct this.
- **Current ORT thread configuration:** Managed by `CpuExecutionPolicy.java` (using optimal threads).
- **Execution provider:** CPU

**`active-model.json` Configuration:**
Located at `app/src/main/assets/models/active-model.json`. The app parses this to find the target offline model to extract.
Current contents:
`{ "name": "Mixed FP16", "path": "models/dttnet/dttnet_fp16_mixed.onnx", "bytes": 12082166 }`

## 4. Current model preparation implementation
- **Flow:** When the app starts or the user visits the Model page, `MainActivity.java` (`prepareAndStart()` / `modelPage()`) parses `active-model.json`. If `model.isFile()` is false or size mismatches, it opens the asset `InputStream` and copies it block-by-block to a `FileOutputStream` in `getExternalFilesDir(null)`.
- **CURRENT REAL-DEVICE STATUS:** MODEL PREPARATION FAILED ON HUAWEI.
- **Error:**
  - Offline AI model | Preparing offline AI model...
  - Storage used: 0 B
  - Retry preparation → Failed
  - Music removal therefore cannot run
- **Root cause:** ROOT CAUSE NOT YET VERIFIED. Do not claim this works just because it compiles.

## 5. Processing pipeline
- **Video selection:** `ACTION_GET_CONTENT` or `ACTION_OPEN_DOCUMENT` intent triggered in `MainActivity.java`.
- **Audio extraction:** `FullMediaProcessor.java` parses the media source using Android APIs.
- **Decoding:** Extracts PCM audio data.
- **STFT/preprocessing:** Handled by `DttNetEngine.java` using JTransforms.
- **DTTNet chunking/inference:** `DttNetEngine.java` runs ONNX Runtime on the buffered frequency chunks.
- **Overlap/reconstruction/ISTFT:** `DttNetEngine.java` applies ISTFT and overlap-adds the windows to create cleaned PCM.
- **Mux with original video:** `androidx.media3.muxer.Muxer` is used to stream-copy the original video track and inject the cleaned audio track into a temporary `.mp4`.
- **Temporary preview:** Results are played back inside `MainActivity.java` using ExoPlayer.
- **Optional Save / Share:** User explicitly initiates save to MediaStore.

## 6. File/save behaviour
- **IMPORTANT:** NO AUTO-SAVE.
- **Temporary result location:** Cached at `getCacheDir() + "/processing/<UUID>/result.mp4"`.
- **Cleanup logic:** `MainActivity.clearTemporary()` deletes lingering cache files.
- **MediaStore Save flow:** When explicitly requested, `MediaProcessingService.java` copies the temp file to `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.
- **Final public destination:** `Movies/MusicFilter/` (ONLY after the user explicitly presses Save).
- **Duplicate filename handling:** MediaStore resolves duplicates, or a unique `DISPLAY_NAME` is used.

## 7. Foreground/background architecture
- **Service:** `MediaProcessingService.java` manages processing logic as a Foreground Service.
- **WakeLock:** `PowerManager.PARTIAL_WAKE_LOCK` is acquired and released upon completion/cancellation.
- **Persistent job state:** Tracked via `ProcessingState.java` using `SharedPreferences`.
- **Process-death recovery:** Managed via `START_REDELIVER_INTENT`.
- **Huawei-specific scheduling finding:** Foreground/top-app processing restored approximately 32.6 sec for the 18-second benchmark, while Home/locked execution on the tested Huawei moved the process to a restricted background cpuset and was around ~135 sec. Do not generalize this behaviour to all Android devices.

## 8. Model research history — DO NOT repeat old work
- **DTTNet full/mixed-FP16:** Selected production architecture.
- **DTTNet INT8 / partial INT8:** Rejected due to severe clipping and inconsistent distortion.
- **DTTNet-Mini/distillation:** Rejected due to insufficient quality.
- **TIGER-DnR:** Rejected due to ORT export limitations / poor performance.
- **DeepFilterNet3:** Rejected due to low quality in extreme test cases.
- **Spleeter:** Rejected; outdated with heavy vocal bleed.
- **UVR (MDX23C):** Rejected; overly heavy, extreme memory usage, and latency.
- **MRX / TUSS / PolarFormer / SCNet / BandIt Plus / SuDoRM-RF:** Rejected due to complex Java translation requirements, heavy memory footprints, or mobile-unfriendly architectures.
**Final architectural decision remains:** DTTNet Mixed-FP16 + ONNX Runtime CPU + 12.5% overlap. Do NOT reopen model research unless explicitly requested.

## 9. UI architecture and V1 requirement
- **V1 UX requirement:** V1 UI/UX should remain the visual baseline. We do not want a redesign.
- **App name:** Must be **Music Filter** (with a space).
- **Implementation:** `MainActivity.java` creates all UI dynamically using pure Java programmatic layouts (no XML layouts for the screens). Methods like `home()`, `settings()`, `modelPage()`, and `about()` instantiate nested `LinearLayout` and `TextView` elements to form the UI.

## 10. CURRENT REAL-DEVICE UI DEFECTS
These are OPEN BUGS confirmed on real-device UAT. Do not assume they are fixed just because it compiles:

**A. Model preparation failure**
Storage used: 0 B
Retry preparation → Failed
Main app function therefore currently does not work.

**B. Placeholder ... UI**
... Original
... Music removed
... English
... System default
and standalone ... entries.
These should be proper icons/check/radio indicators rather than text placeholders.

**C. Result selector layout**
Music removed wraps/clips incorrectly.

**D. Preview**
Verify whether an actual video player exists and works. The current result screen visibly says Preview ready, but the screenshot did not show an actual usable video preview.

**E. Arabic hadith missing**
The actual intended quote was replaced visually by: `« ... ... ... ... »`.
The intended text is: `«لَيَكُونَنَّ مِنْ أُمَّتِي أَقْوَامٌ، يَسْتَحِلُّونَ الحِرَ وَالحَرِيرَ، وَالخَمْرَ وَالمَعَازِفَ»`. Valid Arabic Unicode must NOT be removed by the encoding checker.

**F. Playback speed**
Displays only 1; expected a proper user-facing value such as 1×.

**G. Model status duplication**
Settings displays duplicated wording similar to:
Offline AI model Preparing offline AI model...

**H. Empty Settings/About row**
There is a standalone chevron with no visible label.

**I. App version**
Settings shows only 2.0.0 instead of a proper App version / 2.0.0 row.

**J. Background-processing subtitle**
Enabled automatically Battery guidance appears poorly separated/laid out.

## 11. Encoding history
- **Previous Mojibake Issue:** Automated file manipulation via PowerShell injected BOMs and double-encoded UTF-8 characters resulting in `Ã`, `Â`, `Ãƒâ€º`, etc., across the UI.
- **Affected Files:** `MainActivity.java` and `MediaProcessingService.java`.
- **Current Safeguard:** `encoding_check.py` checks for bad character sequences.
- **IMPORTANT:** Intentional valid Unicode (Arabic text, `×`, `•`, `✓`, normal punctuation) MUST remain supported. The checker must detect mojibake, NOT force ASCII-only UI.

## 12. Exact latest release artifact
- **Filename:** `MusicFilter-V2-Release.apk`
- **Path:** `C:\Users\Ebrahim\.gemini\antigravity\brain\bb76d70f-a6c3-4b2c-980f-76d463caa026\MusicFilter-V2-Release.apk`
- **Size:** 163.9 MB (171,863,725 bytes)
- **SHA-256:** `1C684C627E0701ECBECE0C424131E51C0B6D1B4963E756727C392339A0F65620`
- **versionName/versionCode:** 2.0.0 / 1
- **Package:** com.musicfilter.app.v2
- **Confirmation:** Huawei ADB update SUCCESS and encoding check PASS are still the current values.
- **CRITICAL WARNING:** Although installation succeeded, actual Huawei UAT subsequently exposed model-preparation and UI defects, so this APK must NOT be considered production-ready.

## 13. Huawei development device
- **Device model:** Huawei nova 7 5G
- **Android/EMUI version:** Unknown EMUI version (exhibits aggressive background CPUSet restrictions).
- **ADB serial:** `DBPDU20721006203`
- **Install command:** `adb -s DBPDU20721006203 install -r <apk>`
- **Useful tools:** `adb logcat`, `adb shell dumpsys package`, `adb shell am kill`. Testing on Samsung Galaxy S24 Ultra is deferred until Huawei blockers are resolved.

## 14. Build/run/install commands
Commands using Windows PowerShell:

- **Clean project:** `./gradlew clean`
- **Assemble release:** `./gradlew assembleRelease`
- **Find output APK:** `Get-ChildItem -Path "app\build\outputs\apk\release"`
- **Calculate SHA256:** `Get-FileHash -Path "app\build\outputs\apk\release\app-release.apk" -Algorithm SHA256`
- **ADB update/install:** `adb -s DBPDU20721006203 install -r "app\build\outputs\apk\release\app-release.apk"`
- **Clean uninstall/install:**
  `adb -s DBPDU20721006203 uninstall com.musicfilter.app.v2`
  `adb -s DBPDU20721006203 install "app\build\outputs\apk\release\app-release.apk"`
- **Launch app:** `adb -s DBPDU20721006203 shell am start -n com.musicfilter.app.v2/com.musicfilter.app.MainActivity`
- **Inspect logs:** `adb -s DBPDU20721006203 logcat`
- **Inspect package version:** `adb -s DBPDU20721006203 shell dumpsys package com.musicfilter.app.v2 | Select-String -Pattern "versionName|versionCode"`

## 15. Important source files
- **`app/src/main/java/com/musicfilter/app/MainActivity.java`**: Main programmatic UI, ExoPlayer preview, model extraction initiation, lifecycle.
- **`app/src/main/java/com/musicfilter/app/MediaProcessingService.java`**: Core Foreground Service orchestrating background operations, notifications, Wakelocks, and saving.
- **`app/src/main/java/com/musicfilter/app/FullMediaProcessor.java`**: Video reading, track muxing, audio stream extracting.
- **`app/src/main/java/com/musicfilter/app/DttNetEngine.java`**: AI Engine running the DTTNet model via ORT, applying STFT/ISTFT logic.
- **`app/src/main/java/com/musicfilter/app/ProcessingState.java`**: Persistent shared preference state for crash recovery.
- **`app/src/main/java/com/musicfilter/app/OfflineVideoProcessor.java`**: Simple wrapper around processing.
- **`app/src/main/AndroidManifest.xml`**: Application permissions, service registrations.
- **`build.gradle` (root) & `app/build.gradle`**: Build configuration.
- **`encoding_check.py`**: Automated script to catch mojibake encoding errors before build.
- **`app/src/main/res/drawable/ic_chevron_right.xml`**: Vector drawable replacing text arrows in UI.
- **`app/src/main/assets/models/active-model.json`**: Runtime AI configuration linking the loaded UI state to the `dttnet_fp16_mixed.onnx` file.
- **`app/src/main/assets/models/dttnet/`**: Directory storing the ONNX model files.

## 16. Known good vs NOT VERIFIED
| Component | Status | Notes |
| :--- | :--- | :--- |
| UI Encoding / Compilation | **VERIFIED WORKING** | Source clean; build passes. |
| Model preparation on Huawei | **CURRENTLY BROKEN** | `Storage used: 0 B` error. |
| UI Elements (Placeholders, alignment) | **CURRENTLY BROKEN** | Multiple alignment and string mapping bugs. |
| End-to-end processing | **NOT YET VERIFIED** | Requires working model extraction first. |
| Samsung Galaxy S24 Ultra tests | **NOT YET VERIFIED** | Deferred. |

## 17. Final continuation instructions for the next agent
- **Do not redesign the app.** Keep the V1 UX structure.
- **Do not reopen AI-model research.**
- **Do not perform Samsung-specific optimization yet.**
- **Fix the current Huawei blockers first.** 
- **Main priority #1:** Make bundled DTTNet preparation actually work from a clean install.
- **Main priority #2:** Repair the visible UI defects listed above.
- **Main priority #3:** Prove an actual video can be processed end-to-end on Huawei.
- **No auto-save.**
- **Preserve preview-before-save.**
- **Keep processing fully offline.**
- **Only call the build “final” after actual end-to-end device verification.**

# 18. FINAL UI / UX TARGET

The next agent must understand that we are NOT asking for a redesign.

The target is:

**V1 Music Filter UI/UX + fixed bugs + V2 AI/backend improvements**

Keep the current visual identity:

- dark teal / dark green background
- mint/emerald accent colour
- white main text
- grey secondary text
- rounded buttons/cards
- simple clean layout
- minimal and professional appearance

Do not introduce a completely new design, navigation system, colour scheme, or visual identity.

---

## App name

The user-facing application name must always be:

**Music Filter**

with a space.

Check later in:

- launcher name
- home screen
- notifications
- Settings
- About
- Share text
- dialogs

Internal package names can stay unchanged.

---

## Home screen

When the app opens normally, it should show the normal V1-style home screen.

Main action:

**Select Video**

There must be:

- no automatic error
- no "No video selected" warning
- no stale processing state
- no automatic failed state

The previous V1 bug where the app complained that no video had been selected immediately after opening MUST remain documented as something that must not return.

---

## After selecting a video

Show the selected video clearly.

Where practical show:

- thumbnail
- filename
- duration
- optionally file size

Primary action:

**Remove Music**

Do not show technical model information to normal users.

---

## Processing screen

While processing, show:

- progress
- accurate percentage if available
- elapsed time
- simple status text
- Cancel button

Example:

**Removing background music…**

Do not expose:

- ONNX terminology
- model paths
- ADB instructions
- chunk/debug information
- Java exceptions

Do not show inaccurate estimated remaining time.

---

## Result / Preview screen

After processing finishes, the user must get a REAL usable video preview.

The user must be able to:

- see the video
- play/pause
- seek
- hear the audio
- compare the original video with the cleaned version

The screen currently saying only:

`Preview ready`

is not enough if no actual player is visible.

The final result screen must contain the real player.

---

## Original / Music removed switch

Keep the V1-style segmented selector.

It should contain exactly:

**Original**

and

**Music removed**

Requirements:

- both labels stay on one line
- no clipping
- no wrapping
- properly centred
- clear selected/unselected state
- no literal `...`
- no corrupted Unicode symbols

If icons are needed, use proper Android vector drawables.

Do not use text characters as fake icons.

---

## Result actions

Keep these actions:

**Save**

**Share**

**Process another video**

Save should remain the primary action.

Share should be secondary.

Process another video should be a lighter action.

---

## CRITICAL: NO AUTO-SAVE

This behaviour must never return.

Correct workflow:

Select video  
→ Remove Music  
→ Processing  
→ Temporary result  
→ Preview  
→ User decides  
→ **Save**

The processed video must NOT automatically appear in Gallery.

Only after the user explicitly presses:

**Save**

should the file be written to:

`Movies/MusicFilter/`

---

## Share without Save

The user must also be able to:

Process  
→ Preview  
→ Share

without permanently saving the result first.

Sharing should use the temporary processed file through FileProvider/content URI.

Sharing must not silently add the video to Gallery.

---

# Settings screen target

Keep the current Settings structure but clean it up properly.

The intended structure is:

### General

**Theme**  
subtitle = current theme

**Language**  
subtitle = current language

### Playback

**Default playback speed**  
example:

`1×`

**Keep screen awake**  
subtitle:

`While Music Filter is open and processing.`

### Processing

**Background processing**

Use a clean understandable subtitle.

Do not make text such as:

`Enabled automatically Battery guidance`

look merged together.

**Notifications**  
subtitle:

`Progress and completion`

### Model

**Offline AI model**

Possible subtitles:

Before preparation:

`Bundled • Preparing…`

After success:

`Ready`

If preparation fails:

`Preparation failed • Tap to retry`

Do not repeat:

`Offline AI model Offline AI model...`

### Storage

**Save location**  
`Movies/MusicFilter`

**Temporary files**  
show actual cache size

**Save processing report**  
subtitle:

`Device information and timings only`

### About

**About Music Filter**

**Why Music Filter was created**

**Privacy**

**Licences**

**App version**  
`2.0.0`

There must be NO:

- blank rows
- floating chevrons
- invisible labels
- empty clickable items
- unexplained placeholders

---

# Theme selection

Theme options should be:

- System default
- Light
- Dark

Use a proper radio/check selection indicator.

Do not display:

`... System default`

or standalone:

`...`

---

# Language selection

If only English is actually implemented right now, show only:

**English**

with a proper selected indicator.

Do not show fake empty language choices.

Do not show:

`... English`

or standalone:

`...`

Additional languages can be implemented later.

---

# Playback speed

The Settings page currently shows only:

`1`

This should be a proper speed display such as:

`1×`

Other playback speeds must also display cleanly.

---

# Unicode / encoding rule

The app MUST support valid Unicode correctly.

Valid characters include:

- Arabic
- `×`
- `•`
- `✓`
- normal punctuation

Do NOT solve encoding problems by deleting valid Unicode or replacing content with `...`.

The actual problem was mojibake such as:

`Ã`
`Â`
`â`

The encoding checker should detect corrupted encoding, not force everything to ASCII.

---

# Why Music Filter was created page

Preserve this page and its existing intention.

The page explains that Music Filter was created to allow people to benefit from useful videos while removing background music.

Keep the Islamic motivation section.

The hadith must display correctly:

`«لَيَكُونَنَّ مِنْ أُمَّتِي أَقْوَامٌ، يَسْتَحِلُّونَ الحِرَ وَالحَرِيرَ، وَالخَمْرَ وَالمَعَازِفَ»`

Keep:

**Narrated by al-Bukhari.**

Do NOT replace the Arabic with:

`« ... ... ... »`

The Huawei phone can display Arabic correctly.

---

# Model page

The page should be understandable to a normal user.

Example when working:

**Offline AI model**

**Ready**

Storage used:

`XX MB`

Explanation:

`Included with this app. No download is required. Model updates are delivered with app updates.`

If preparation fails:

**Preparation failed**

**Retry preparation**

Never show:

- ADB instructions
- raw filesystem paths
- stack traces
- developer commands

Those belong in logs only.

---

# Notifications

During processing:

**Music Filter**

`Removing background music…`

with Cancel if supported.

When processing finishes:

the ongoing foreground notification MUST stop.

A normal completion notification may then show:

**Music Filter**

`Your video is ready`

There must be:

- no zombie processing notification
- no mojibake
- no developer/debug wording

---

# Error messages

Errors must be understandable to normal users.

Examples:

`This video could not be opened.`

`This video does not contain an audio track.`

`The offline AI model could not be prepared. Tap to retry.`

`There is not enough storage space to process this video.`

Do not expose raw exceptions.

---

# Responsive layout

The Huawei screenshots already proved that some elements clip or wrap badly.

The next agent must test/fix:

- Original/Music removed selector
- Settings rows
- bottom sheets
- long text
- small screens
- scrolling
- portrait layout
- button sizes
- text wrapping

Nothing important should be clipped.

---

# CURRENT UI BUGS THAT MUST REMAIN LISTED AS OPEN

Make sure the handoff clearly says these are still OPEN on the latest Huawei build:

1. `... Original`
2. `... Music removed`
3. `... English`
4. `... System default`
5. standalone `...` entries
6. Music removed selector clipping/wrapping
7. actual video preview needs verification/fixing
8. Arabic hadith replaced by dots
9. playback speed shows `1` instead of `1×`
10. duplicated Offline AI model status
11. empty Settings/About row with floating chevron
12. App version displayed incorrectly
13. Background processing subtitle layout is poor
14. Model preparation currently FAILS completely on Huawei

Do NOT mark these fixed merely because the project compiles.

---

# VERY IMPORTANT MODEL CONFIGURATION REGRESSION

Your handoff found that:

`DttNetEngine.java`

currently defines:

`OVERLAP_SAMPLES = CHUNK_SAMPLES / 2`

which means:

**50% overlap**

This is WRONG for the locked production configuration.

The required production configuration is:

**12.5% overlap**

with:

Chunk:

`261,120 samples ≈ 5.92 sec`

Overlap:

`32,640 samples ≈ 0.74 sec`

Stride:

`228,480 samples ≈ 5.18 sec`

This must be clearly marked as an OPEN CODE REGRESSION for the next agent to fix.

Do NOT change it now. Only document it.

---

# MODEL ARCHITECTURE IS LOCKED

The next agent must NOT restart model research.

Production remains:

**DTTNet Mixed-FP16**
+
**ONNX Runtime CPU**
+
**12.5% overlap**

We already spent extensive time testing alternatives.

Do not repeat:

- INT8
- partial INT8
- DTTNet Mini
- distillation
- TIGER
- DeepFilterNet
- Spleeter
- UVR
- MRX
- TUSS
- PolarFormer
- SCNet
- BandIt
- SuDoRM

unless I explicitly reopen model research later.

---

# Priority order for the new agent

The next coding agent should work in this exact order:

### Priority 1
Fix bundled DTTNet model preparation.

The app currently cannot perform its main function.

### Priority 2
Correct the accidental 50% overlap regression back to the required 12.5%.

### Priority 3
Successfully process one real video end-to-end on the Huawei.

### Priority 4
Fix the visible UI bugs and placeholders.

### Priority 5
Verify the real preview/player.

### Priority 6
Verify:

- no auto-save
- Save
- Share without Save
- Process another video
- temporary cleanup

### Priority 7
Run complete Huawei UAT.

Only after Huawei works properly should Samsung Galaxy S24 Ultra testing happen.

---

# Git / project migration warning

The current handoff confirms:

**This project is NOT currently a Git repository.**

That is dangerous.

Clearly tell the next agent that after migration to the new workspace we should initialize proper Git/version control BEFORE making major fixes.

We need reliable history so we can identify regressions and roll back safely.

Do NOT initialize Git yourself now unless I explicitly ask.

---

# Final rule for the next agent

The intended end state is:

**A polished, working version of the existing V1 Music Filter UI powered by the V2 DTTNet backend.**

NOT a completely new application.

The next agent may clean broken implementation details internally, but must not redesign the product unnecessarily.

The app is only considered finished when it works end-to-end on the real Huawei device.

Compilation alone is NOT verification.
