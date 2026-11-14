# My TV: TCL 75P8L (Google TV, Android 14, build UTT2.250416.001)

How this fork's setup on my TV works, and how to change or undo it. `<tv>` below means `<tv ip>:<port>`, where the
port is shown under Settings > System > Developer options > Wireless debugging (it changes each time that is
switched on). Wireless debugging is off when the job is done: switch it on again first, then pair if asked.

## What is on the TV

- **Home:** the launcher from this fork (`com.gathin.tvhome`) with the `themes/frame-glass` theme. The bare page shows
  only the wallpaper. Down: the clock, date and weather (Open-Meteo, at most every 30 minutes while Home is on
  screen) with the dock at the bottom: one endless row of white glass app icons, the focus fixed on the first slot
  while the apps slide into it, the app's name above. Up from the dock or Back: the bare page.
- **Your apps:** hold OK on an app for Move (Left/Right, then OK), Remove from Home or App info. *All apps* in the
  settings row lists everything installed; OK adds an app to the dock or takes it off. The dock started with
  YouTube, YouTube Music, Kodi, Stremio, Spotify and Play Store.
- **Settings menu:** one menu, a row across the top: Wi-Fi, Bluetooth, Display, Sound, Inputs, All apps, Apps,
  Settings, Launcher settings. Up on the bare page or the remote's Settings button opens it on Home; Down or Back
  closes it. Inside an app the Settings button does what that app does (`key_filter: false`, see below).
- **Power-on:** opens the PC's HDMI (HDMI 1) if the PC is on, otherwise Home (`power_on_home_always`, `power_on_input`).
  This needs the launcher's Home-button helper (Settings > Accessibility > Home button).
- **Held volume:** on this TV any key-filtering helper drops held-key repeat, so the theme sets `key_filter: false`.
  Volume ramps normally. In exchange, the Settings button does what each app does when you are inside an app.
- **Private DNS:** `dns.adguard-dns.com`, set during the first debloat. It does not block YouTube ads. To undo it:
  `adb -s <tv> shell settings put global private_dns_mode opportunistic`.
- **Debloat:** 31 packages are disabled (none uninstalled). They are listed in `tools/restore-mine.sh`. Projectivy
  Launcher was uninstalled on request; reinstall it from the Play Store if wanted.

## Re-enable an app

```bash
~/platform-tools/adb -s <tv> shell pm enable <package>       # one app
tools/restore-mine.sh <tv>                                     # every app the debloat disabled
tools/restore-mine.sh <tv> --stock-home                        # ...and give Home back to Google TV's launcher
```

## Change the dock, wallpaper or anything else in the theme

Edit `themes/frame-glass/config.json` (keys are explained in `themes/README.md`), then push it:

```bash
TV_ADB_BINARY=~/platform-tools/adb TV_HOST=<tv> ANDROID_SERIAL=<tv> tools/theme.sh themes/frame-glass
```

- **Dock order:** the `dock_apps` list, left to right (package names: `adb shell pm list packages`).
- **Wallpapers:** 30 landscape paintings from the National Gallery of Art's open access collection (CC0; titles
  and links in `themes/frame-glass/CREDITS.md`), in the theme's `wallpapers/` folder; `wallpaper_rotation_minutes`
  sets the interval. Any 16:9 JPEG dropped in that folder joins the slideshow. The
  TV draws its interface at 1920x1080, so 1920x1080 or larger 16:9 pictures look sharp. Pictures that may not be
  shared (Apple's wallpapers) go in a copy of the theme outside the checkout, e.g. `~/tv-theme-private`, and are
  pushed with `tools/theme.sh ~/tv-theme-private`; never commit them.
- **Power-on input:** `power_on_input` is the input id from `adb shell dumpsys tv_input`. Remove the key to always land on Home.

## Rebuild and install everything

```bash
tools/deploy-mine.sh <tv>
```

It pulls, builds and installs the launcher with the Home-button helper, pushes `themes/frame-glass` and switches
the helper back on after restarting Home. Set `THEME=~/tv-theme-private` to push the private copy instead.

## Close debugging afterwards

Switch Wireless debugging and USB debugging off in Developer options, choose "Revoke USB debugging
authorisations", reboot, then check with `tools/venv/bin/python tools/adb-closed.py <tv ip>` (port 5555 must
report closed).
