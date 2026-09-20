# Music Filter — Final Features

**Feature freeze: v2.0.0**

## Video input

- Select a local video through Android's document picker.
- Receive a video shared from another Android app.
- Show the selected filename, duration, and file size.
- Validate the media container, duration, video track, and audio track before processing.
- Preview the selected video with play/pause, seek, fullscreen, volume, and playback-speed controls.

## Processing

- Run the bundled DTTNet Mixed-FP16 model fully on-device through ONNX Runtime CPU execution.
- Process the entire video.
- Process a user-selected start/end range.
- Run a temporary 15-second test from the current preview position.
- Display progress and elapsed-time status.
- Cancel or retry work safely.

## Comparison

- Switch between **Original** and **Music removed** results.
- Preserve playback position and play state when switching comparison mode.
- Compare the same media range for full, section, and 15-second results.

## Video output

- Preview the processed video before saving.
- Rename the output before saving while preserving the `.mp4` extension.
- Save to `Movies/MusicFilter` through Android `MediaStore`.
- Avoid silently overwriting duplicate filenames.
- Share a processed video without saving it first.
- Process another video from the result screen.

## Audio extraction

- Remux the processed music-removed AAC track to a temporary `.m4a` file.
- Avoid rerunning DTTNet or transcoding the cleaned audio.
- Preview extracted audio.
- Rename and save audio to `Music/MusicFilter` with duplicate-safe naming.
- Share extracted audio directly.

## Languages and localization

- English interface.
- Arabic interface.
- Right-to-left page layout, mirrored navigation, and bidi-safe mixed text and filenames.
- Localized settings, processing/result states, dialogs, errors, About, Privacy, Licences, and Why Music Filter content.

## Background and lifecycle behavior

- Foreground-service processing continues when the app leaves the foreground, subject to Android/device battery restrictions.
- Active-processing and separate dismissible completion notifications.
- Job identity prevents stale callbacks from replacing a newer selection.
- Relaunch, cancellation, retry, and interrupted-job handling.
- Temporary-file cleanup protects active input and preview files.

## Privacy and offline operation

- Media separation runs locally on the Android device.
- No cloud processing server, account, or login is required.
- Results are not permanently saved until the user explicitly chooses **Save**.
