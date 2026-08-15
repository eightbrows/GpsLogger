# アプリ固有のProGuard/R8ルール
# 基本ルールは proguard-android-optimize.txt から読み込まれます

# 縮小によって問題が出た場合、ここに保持ルールを追加します
# 例: -keep class io.github.eightbrows.gpslogger.log.** { *; }