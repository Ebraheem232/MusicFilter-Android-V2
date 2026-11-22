# Music Filter

Music Filter is an Android application that processes videos locally to reduce or remove background music while preserving spoken human voice as much as possible.

## Why I built it

Many useful educational, informational, and other videos combine speech with background music. Music Filter was created as a practical way to preserve useful spoken content while reducing that music. The project also has a personal Islamic motivation and is provided free of charge; it is not intended as a commercial product.

## Core features

- Select a video from the Android system picker or share a video to the app.
- Preview the original video with seeking, fullscreen, volume, and playback-speed controls.
- Process a complete video or a selected time range.
- Run an optional temporary 15-second quality test before full processing.
- Compare **Original** and **Music removed** with synchronized playback state.
- Rename, save, and share the processed video.
- Extract the processed AAC audio as M4A without rerunning the separation model.
- Preview, rename, save, and share extracted audio.
- Use the interface in English or Arabic, including right-to-left layout support.
- Continue processing in the background with progress, cancellation, recovery, and completion notifications.
- Operate fully offline on the Android device.

See [FEATURES.md](FEATURES.md) for the complete frozen feature set.

## How it works

The production pipeline uses **DTTNet Mixed-FP16 with ONNX Runtime CPU execution**. The bundled production model is `dttnet_fp16_mixed.onnx`.

| Audio/inference setting | Production value |
| --- | ---: |
| Sample rate | 44.1 kHz |
| Channels | Stereo |
| Chunk | 261,120 samples |
| Overlap | 32,640 samples |
| Overlap ratio | 12.5% |
| Stride | 228,480 samples |

At a high level:

```text
Video → audio decoding → DTTNet inference → cleaned audio → video/audio muxing → preview, save, or share
```

## Tech stack

Versions below are taken from the checked-in Gradle configuration.

- Android application written in Java 8
- Android Gradle Plugin 9.0.1
- Gradle 9.1.0
- Compile SDK 36.1; target SDK 36; minimum SDK 29
- AndroidX Core 1.9.0
- AndroidX Media3 / ExoPlayer 1.10.1
- ONNX Runtime Android 1.23.2
- JTransforms 3.1
- Android media APIs including `MediaExtractor`, `MediaCodec`, `MediaMuxer`, `MediaStore`, and the Storage Access Framework

## Offline operation and privacy

Imported video and audio are processed on the Android device. The processing workflow does not require a cloud server or upload media for separation. A processed result remains temporary until the user explicitly chooses to save it. Sharing hands the selected file to an app chosen by the user.

## Requirements

- Android 10 (API 29) or newer.
- A device supported by the native ONNX Runtime package. The current build contains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` native libraries; real-device validation used ARM64 phones.
- Enough free device storage for temporary decoded audio, model files, and the processed output.
- Media is selected through Android's document picker and saved through `MediaStore`; the app does not request broad legacy storage permissions.
- Notification permission is optional on Android versions that request it, but is needed to display processing progress and completion notifications.

## Build

On Windows, with a compatible Android SDK and JDK installed:

```powershell
.\gradlew.bat clean :app:assembleRelease --offline --no-daemon
```

Remove `--offline` when dependencies are not already cached. The current `release` build type uses Android's debug signing configuration for local verification. Configure a private production signing key outside Git before distributing an APK.

## Tested devices

Final v2.0.0 manual device verification was performed on:

### Huawei nova 7 5G

- Kirin 985
- 8 GB RAM
- One measured run on an approximately 35-second test video completed in approximately 57.08 seconds.

### Samsung Galaxy S24 Ultra

- One measured run on the same approximately 35-second test video completed in approximately 46.14 seconds.

These are individual test results, not performance guarantees. Processing time varies with the device, video duration, thermal state, and audio content.

## Screenshots

Real application screenshots will be added under [`screenshots/`](screenshots/README.md). No promotional or generated screenshots are included.

## Demo video

Demo video coming soon.

Replace this line with the final GitHub or LinkedIn demo link when it is ready.

## Download / APK

Release APKs may be provided through GitHub Releases after production signing is complete. Direct permission to redistribute the bundled DTTNet weights in a public repository and APK was granted by the upstream author. The app is not currently published on the Google Play Store.

## Known limitations

- On-device inference may take longer than the video's playback duration, depending on device performance.
- Music-suppression quality depends on the mixture and relative loudness of speech, singing, and instruments.
- Loud or complex music can be harder to suppress completely.
- Singing and speech can overlap in ways that are difficult for source-separation models.

## Project status

**Music Filter v2.0.0 is feature-frozen and considered the current stable version.**

## License and attribution

Original Music Filter source code is licensed under the [Apache License 2.0](LICENSE). That licence does not relicense third-party components or bundled model weights.

Runtime technologies and their upstream licences are summarized in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The inspected DTTNet source repository declares Apache License 2.0. The upstream author separately granted direct permission to redistribute the bundled pretrained weights in a public repository and APK; this permission is not presented as a standalone checkpoint licence.

## Verification

The final manual verification scope and the repository's available instrumentation checks are documented in [TESTING.md](TESTING.md). Release milestones are summarized in [CHANGELOG.md](CHANGELOG.md).
