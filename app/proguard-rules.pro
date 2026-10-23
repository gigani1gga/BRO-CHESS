# Compose and AndroidX ship their own consumer rules; nothing extra is needed.
# Keep the app stable after minification.
-keep class com.example.chesslab.arena.UciProcess { *; }
