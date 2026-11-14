#!/usr/bin/env bash
# One command for this fork: pull, build and install the launcher, push the frame-glass theme, restart Home.
# Usage: tools/deploy-mine.sh <tv ip>:<wireless debugging port>      e.g. tools/deploy-mine.sh 192.168.1.50:34077
# The port changes each time Wireless debugging is switched off and on; read it in the TV's Developer options.
set -euo pipefail
TARGET="${1:?usage: tools/deploy-mine.sh <tv ip>:<port>}"
cd "$(dirname "$0")/.."
git pull --ff-only
export TV_ADB_BINARY="${TV_ADB_BINARY:-$HOME/platform-tools/adb}"
export TV_HOST="$TARGET"
export ANDROID_SERIAL="$TARGET"
# The theme uses power_on_home_always, which needs HomeKeyService.
export HOME_KEY_SERVICE="${HOME_KEY_SERVICE:-1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -z "${JAVA_HOME:-}" ]; then JAVA_HOME="$(echo "$HOME"/jdk17/*/Contents/Home)"; export JAVA_HOME; fi
"$TV_ADB_BINARY" connect "$TARGET" >/dev/null 2>&1 || true
(cd launcher && ./build-and-install.sh)
# A folder the adb shell made (drwxrws--- shell) cannot be listed by the launcher; let the launcher create it.
"$TV_ADB_BINARY" shell rm -rf /sdcard/Android/data/com.gathin.tvhome/files/wallpapers || true
tools/theme.sh "${THEME:-themes/frame-glass}"
"$TV_ADB_BINARY" shell am force-stop com.gathin.tvhome
# A force-stop switches the launcher's accessibility helpers off; put HomeKeyService back (keeping other entries).
if [ "$HOME_KEY_SERVICE" = 1 ]; then
  "$TV_ADB_BINARY" shell 'svc=com.gathin.tvhome/.HomeKeyService; s=$(settings get secure enabled_accessibility_services); case "$s" in *HomeKeyService*) ;; null|"") settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services "$s:$svc" ;; esac; settings put secure accessibility_enabled 1'
fi
"$TV_ADB_BINARY" shell input keyevent KEYCODE_HOME
echo "Done. Press Home on the remote if the screen did not change."
