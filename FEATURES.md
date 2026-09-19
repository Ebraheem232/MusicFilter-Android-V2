# Music Filter — Final Feature Set

Music Filter processes video fully offline on the device with the bundled DTTNet model. It preserves speech while removing or reducing background music, and it never permanently saves a result until the user explicitly chooses Save.

## Input and processing

- Select a video from Android Gallery or Files, or share a video to Music Filter.
- Review the filename, duration, file size, and preflight validation before processing.
- Process the entire video or a validated start-to-end section.
- Optionally process a temporary 15-second quality test from the current preview position.
- Run the production offline DTTNet configuration in the background with progress, elapsed time, cancellation, retry, lifecycle recovery, and duplicate-job protection.

## Preview and comparison

- Preview video with play/pause, seeking, fullscreen, volume, and playback-speed controls.
- Switch between Original and Music removed while preserving playback position, play state, speed, and volume where practical.
- Compare the exact same range for section and 15-second test results.

## Video output

- Preview, save, or share the processed video.
- Rename the video before saving while preserving `.mp4` and using duplicate-safe naming in `Movies/MusicFilter`.
- Share directly without saving first.

## Audio output

- Extract the processed, music-removed AAC audio to a temporary `.m4a` without rerunning DTTNet or transcoding.
- Preview that audio with play/pause, seeking, current time, and total duration.
- Rename before saving while preserving `.m4a` and using duplicate-safe naming in `Music/MusicFilter`.
- Share directly without saving first.

## App behavior

- Processing notifications appear only while work is active; successful completion uses a separate dismissible notification.
- Temporary files can be cleared while active input, processing, preview, sample, and extracted-audio files remain protected.
- Settings include theme, English/Arabic language, default playback speed, keep-screen-awake behavior, background processing, notifications, offline AI model information, and storage controls.
- About includes Music Filter information, the localized Why Music Filter was created page, privacy, licences, and app version.
- Arabic content uses right-to-left layout and bidi-safe rendering for the Music Filter name.

## Feature Freeze

Music Filter's planned user-facing feature set is complete.

Future development is limited to bug fixes, Android/device compatibility, performance improvements, security/privacy fixes, and AI separation model replacement or improvement unless the feature freeze is explicitly reopened.

## Intentionally Excluded

- YouTube downloader
- Instagram downloader
- TikTok downloader
- Batch processing
- History or library database
- Cloud processing
- Accounts or login
- Cloud sync
- Social or community system
- Multiple AI models
- AI-strength slider
- EQ
- Noise-removal suite
- Full video editor
- Crop, text, filters, or effects
- MP3 transcoding
- Embedded social apps
- Subtitles
- Unrelated AI tools
