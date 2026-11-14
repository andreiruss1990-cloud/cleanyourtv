#!/usr/bin/env bash
# Re-enable every package the debloat disabled on my TCL 75P8L (state read back on 2026-11-14), one pm call each,
# and optionally give Home back to the stock Google TV launcher.
# Usage: tools/restore-mine.sh <tv ip>:<wireless debugging port> [--stock-home]
#        A single app instead:  ~/platform-tools/adb -s <tv ip>:<port> shell pm enable <package>
set -uo pipefail
TARGET="${1:?usage: tools/restore-mine.sh <tv ip>:<port> [--stock-home]}"
ADB="${TV_ADB_BINARY:-$HOME/platform-tools/adb}"
"$ADB" connect "$TARGET" >/dev/null 2>&1 || true
PACKAGES=(
  android.autoinstalls.config.google.gtvpai
  com.amazon.amazonvideo.livingroom
  com.android.tv.feedbackconsent
  com.google.android.apps.mediashell
  com.google.android.apps.tv.launcherx
  com.google.android.feedback
  com.google.android.partnersetup
  com.google.android.play.games
  com.google.android.tungsten.setupwraith
  com.netflix.ninja
  com.netflix.tokenmanager
  com.tcl.channelplus
  com.tcl.esticker
  com.tcl.exhibit
  com.tcl.gallery
  com.tcl.guard
  com.tcl.interactive
  com.tcl.logkit
  com.tcl.magiconnectfree
  com.tcl.messagebox
  com.tcl.miracast
  com.tcl.ocean.instructions
  com.tcl.partnercustomizer
  com.tcl.repairguide
  com.tcl.smartalexa
  com.tcl.t_solo
  com.tcl.ttvs
  com.tcl.tv.tclhome_passive
  com.tcl.ui_mediaCenter
  com.tcl.usercenter
  com.tcl.waterfall.overseas
)
for p in "${PACKAGES[@]}"; do
  printf '%s: ' "$p"; "$ADB" -s "$TARGET" shell pm enable "$p" | tr -d '\r'
done
if [ "${2:-}" = --stock-home ]; then
  "$ADB" -s "$TARGET" shell cmd package set-home-activity com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity
fi
"$ADB" -s "$TARGET" shell sync
echo "Still disabled:"; "$ADB" -s "$TARGET" shell pm list packages -d
