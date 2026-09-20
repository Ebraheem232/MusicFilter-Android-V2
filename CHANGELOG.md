# Changelog

## v2.0.0 — Final Stable

- Integrated the production DTTNet source-separation pipeline.
- Deployed the Mixed-FP16 ONNX model with ONNX Runtime Android CPU execution.
- Finalized 44.1 kHz stereo inference with 261,120-sample chunks and 12.5% overlap.
- Completed full-video, selected-section, and temporary 15-second processing workflows.
- Added synchronized **Original** / **Music removed** A/B comparison.
- Completed processed-video preview, rename, duplicate-safe save, and share flows.
- Added cleaned AAC extraction to M4A with audio preview, save, and share.
- Added English and Arabic interfaces with right-to-left support.
- Added foreground/background processing status and separate completion notifications.
- Fixed lifecycle ownership, stale callbacks, cancellation, retry, and fresh-launch state handling.
- Fixed standard MP4 preflight compatibility, 15-second sample remux compatibility, and stale `Checking video...` UI.
- Completed final navigation, notification-icon, save-dialog, and localization fixes.

This changelog starts with the verified stable v2.0.0 release. Earlier version numbers are not reconstructed because the Git history does not establish separate public releases.
