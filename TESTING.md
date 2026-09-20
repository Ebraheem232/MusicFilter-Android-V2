# Testing and Verification

Music Filter v2.0.0 was manually verified on real Android hardware. This document distinguishes that manual device verification from the custom instrumentation checks present in the repository; it does not claim a comprehensive automated test suite.

## Manual device verification

### Core workflow

- Launch the app into the default Home screen.
- Select and preview a video.
- Run the 15-second test workflow.
- Run full-video processing.
- Switch between **Original** and **Music removed**.
- Save and share the processed video.
- Start another video from the result screen.

### Video tools

- Process an entire video.
- Select and process a valid time range.
- Handle short videos and sample boundaries.
- Rename output while preserving `.mp4`.
- Preserve existing files through duplicate-safe naming.

### Audio

- Extract the already processed audio without rerunning separation.
- Preview extracted audio.
- Rename and save M4A output.
- Share extracted audio directly.

### Lifecycle and background behavior

- Cancel active processing.
- Cancel video A, then select video B without stale A state replacing B.
- Relaunch the app and return to the default Home screen when no job is active.
- Continue an active job in the background, subject to device battery restrictions.
- Display active progress and a separate completion notification.
- Avoid stale terminal or `Checking video...` state after completion, failure, or cancellation.

### Compatibility regressions

- Standard playable H.264/AAC MP4 files pass processing preflight.
- The 15-second sample path handles source/keyframe timestamp differences.
- Failed validation clears the `Checking video...` state.
- System Back returns from internal About pages rather than exiting the app.

### Localization

- English interface and left-to-right layout.
- Arabic interface and right-to-left layout.
- Mixed Arabic and `Music Filter` brand rendering.
- Why Music Filter, About, Privacy, and Licences pages.
- Video/audio save dialogs, including localized **Done** and **Open** actions.
- Switching between English and Arabic persists across recreation/relaunch.

## Devices

### Huawei nova 7 5G

- Kirin 985
- 8 GB RAM
- Used for final workflow, lifecycle, notification, media-compatibility, localization, and RTL verification.

### Samsung Galaxy S24 Ultra

- Used for final device compatibility and processing verification.

Individual timing observations for the same approximately 35-second video were about 57.08 seconds on the Huawei and 46.14 seconds on the Samsung. They are observations, not benchmark guarantees.

## Repository instrumentation checks

The repository contains custom Android instrumentation code under `app/src/androidTest/`:

- `FullVideoAudit` exercises media inspection and can run decode/remux or end-to-end background processing against a video for which the app already has a persisted document permission.
- `LifecycleAudit` checks picker cancellation, selection replacement, stale job callbacks, retry ownership, and fresh-launch terminal-state cleanup.

These checks require a connected device and suitable local media. They are not conventional unit tests and were not rerun as part of the documentation-only repository cleanup.

## Build verification

The repository-cleanup verification uses a clean release build. Build success confirms compilation, resource processing, shrinking, and APK packaging; it does not replace the manual device tests above.
