# MusicFilter Android prototype

## Version 0.1

The app lets the user choose a local video, explicitly upload it to their own
local WSL MusicFilter bridge, and play the returned BS-RoFormer speech-only
result. It is designed for the Android emulator's `10.0.2.2` host address.

Before tapping **Remove music**, start the bridge in WSL:

```bash
cd ~/musicfilter
conda activate musicfilter
python scripts/serve_android.py
```

The bridge listens only on WSL localhost and saves each job under
`data/processed/android_jobs/`. It is not exposed as a public server.

## Open and run

Open this `android/` folder as a project in Android Studio. Use the installed
Android 16.0 QPR2 SDK (API 36.1) and a physical Android device or emulator.
