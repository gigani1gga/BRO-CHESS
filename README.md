# Chess Lab (native Kotlin)

Two chess engines start knowing only the rules and teach themselves by playing each other (TD(lambda) learning of
piece-square tables and piece values). You can watch games, play against the engine at three strengths, and train
against Stockfish in the Arena tab. Kotlin + Jetpack Compose, no WebView.

## Automatic APK builds

`.github/workflows/android.yml` rebuilds the APK whenever code changes (every push, and after "Organize code" has
unzipped an uploaded zip). Each build:

- compiles Stockfish 17 for Android (arm64-v8a, armeabi-v7a, x86_64) and bundles it as `libstockfish.so` (cached after the first run)
- runs the unit tests (a failing test is reported in the run summary but never blocks the APK)
- builds a release APK signed with the **permanent key** `app/chesslab.keystore`
- gets `versionCode = 100 + run number`, so it always installs as an update over the previous build
- is published at a permanent link: `https://github.com/<user>/<repo>/releases/latest/download/ChessLab.apk`

The keystore and its passwords are committed on purpose so every build uses the same key. Keep the repo private
if you do not want anyone else to be able to sign an APK that updates yours.

Manual build: `gradle testDebugUnitTest assembleRelease` (Gradle 8.9, JDK 17).

## Engine

- Allocation-free mailbox move generator, verified against standard perft counts.
- **Searcher**: iterative deepening alpha-beta (PVS), 64-bit transposition table, quiescence search with check
  evasions, MVV-LVA / killer / history ordering, late-move reductions, check extensions, repetition and 50-move
  draw detection. Evaluation blends classical terms (material, piece-square tables, passed/doubled pawns, bishop pair)
  with the self-learned piece-square values once they become meaningful.
- Play mode searches off the UI thread; strength Easy / Medium / Hard = 0.25 s / 1.2 s / 3.5 s per move.
- Self-play learning threads use one-ply greedy play for speed; the Arena trains with search-based play.

## Stockfish arena

The Arena tab drives the bundled Stockfish through UCI. Its skill level rises as our evaluation improves. If the
process dies or stalls it is restarted automatically; persistent failures are shown on screen.
