#!/bin/bash
# Local dev loop on this Windows PC: build debug APK, install on the USB phone, launch, screenshot.
# Usage: tools/dev.sh build | install | run | shot [name] | all [name] | log
# Uses the JDK/SDK bundled with Unity and an ASCII junction (C:\Users\Public\KeyfeRadyo),
# because the user folder contains non-ASCII characters.
set -e
UNITY_ANDROID="/c/Program Files/Unity/Hub/Editor/6000.6.4f1/Editor/Data/PlaybackEngines/AndroidPlayer"
export JAVA_HOME="$UNITY_ANDROID/OpenJDK"
export GRADLE_USER_HOME='C:\Users\Public\KeyfeTools\gradle-home'
export TMP='C:\Users\Public\KeyfeTools\tmp' TEMP='C:\Users\Public\KeyfeTools\tmp'
ADB="$UNITY_ANDROID/SDK/platform-tools/adb.exe"
GRADLE=/c/Users/Public/KeyfeTools/gradle-8.13/bin/gradle
PROJECT=/c/Users/Public/KeyfeRadyo
PKG=com.keyfekederradyo.android
SHOTS="$PROJECT/build/shots"

build()   { rm -f "$PROJECT/app/build/outputs/apk/debug/app-debug.apk"; (cd "$PROJECT" && "$GRADLE" assembleDebug -Pandroid.overridePathCheck=true --console=plain -q 2>&1 | grep -vE 'read-only|Parsing legacy|Loading local|SDK Manager found|is deprecated|overridePathCheck|current default' || true); test -f "$PROJECT/app/build/outputs/apk/debug/app-debug.apk"; }
install() { "$ADB" install -r "$(cygpath -w "$PROJECT/app/build/outputs/apk/debug/app-debug.apk")"; }
run()     { "$ADB" shell am force-stop $PKG; "$ADB" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; }
# Taps only when our app is the focused window, so a stray tap can never hit another app or the launcher
tap()     { "$ADB" shell dumpsys window | grep -q "mCurrentFocus=.*$PKG" || { echo "not in app, tap skipped"; return 1; }; "$ADB" shell input tap "$1" "$2"; }
shot()    { mkdir -p "$SHOTS"; "$ADB" exec-out screencap -p > "$SHOTS/${1:-shot}.png"; echo "$SHOTS/${1:-shot}.png"; }

case "$1" in
  build) build ;;
  install) install ;;
  run) run ;;
  shot) shot "$2" ;;
  tap) tap "$2" "$3" ;;
  all) build && install && run && sleep 6 && shot "$2" ;;
  log) "$ADB" logcat -d -t 300 | grep -iE "keyfe|AndroidRuntime|ExoPlayer|FATAL" ;;
  *) echo "usage: $0 build|install|run|tap x y|shot [name]|all [name]|log"; exit 1 ;;
esac
