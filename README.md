# Chess Lab (native Kotlin)

Two chess engines start knowing only the rules and teach themselves by playing each other (TD(λ) learning of
piece-square tables and piece values). You can watch games, play against the stronger engine, and train
against Stockfish in the Arena tab.

Native rewrite of the former Capacitor/WebView app: Kotlin + Jetpack Compose, no WebView, no JavaScript.

## Build

Open the folder in Android Studio (Koala or newer, JDK 17) and run, or:

```
gradle testDebugUnitTest assembleDebug
```

CI (`.github/workflows/android.yml`) does the same and uploads the debug APK.

## Performance notes

- Allocation-free mailbox engine (flat `IntArray`s, precomputed ray/knight/king tables, packed int moves).
  Move generation is checked against standard perft counts (`EngineTest`).
- Self-play runs on `cores - 1` threads (max 8). Each worker trains private weight copies for ~250 ms and
  merges its delta into the shared weights; stale batches are dropped after a reset.
- The board is one `Canvas` draw (no per-square composables). Stats update once per second.
- Training pauses when the app is in the background; weights are saved to app storage and restored on launch.
- Release builds use R8 minification and resource shrinking.

## Stockfish arena

The Arena tab drives a native UCI engine. Place a native Android Stockfish build named `libstockfish.so` in
`app/src/main/jniLibs/<abi>/` (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and rebuild. Without it the tab shows a
notice and the Lab tab works as normal.
