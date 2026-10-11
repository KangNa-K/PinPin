#!/usr/bin/env bash
# Build the debug APK, install it to the connected device, and
# restart the app so the new code actually takes effect.
#
# Run from project root:  ./install.sh
#
# Why the LANG / LC_ALL / JAVA_TOOL_OPTIONS exports: on this machine
# the path contains CJK characters ("项目/项目/pinpin"). When Gradle
# spawns the KSP worker JVM, the inherited locale is the terminal
# default (C / ASCII) and the worker falls back to encoding the path
# as Unicode escapes ("u9879u76EE"), which then don't match the real
# filesystem. Forcing UTF-8 on the JVM fixes it. Keep these in sync
# with whatever locale your IDE / terminal uses.
#
# Multi-device: ANDROID_SERIAL=<serial> ./install.sh
# adb will be told to use that device; the AGP install task will too.

set -e

# Prefer an explicit JDK 17. On macOS, fall back to Android Studio's
# bundled runtime when the shell does not have a working JAVA_HOME.
if ! java -version >/dev/null 2>&1 && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

./gradlew :app:installDebug "$@"

# AGP's installDebug replaces the APK and the system will kill the
# running process to apply it, but foreground services
# (BallService, PinOverlayService) are not automatically restarted —
# the new code doesn't get a chance to run until the user re-opens
# the app. So we force-stop (clears any in-flight a11y / overlay
# state that was holding stale classloaders) and re-launch
# MainActivity; MainActivity.onCreate then starts both
# PinOverlayService and BallService itself.
#
# Note: we deliberately don't `am start-foreground-service
# BallService` here — BallService is android:exported="false" and
# adb shell's uid doesn't have permission to start it. The
# in-process path through MainActivity works because that call is
# made by the app's own uid.
#
# `|| true` on force-stop because a non-installed app errors out
# here, which is fine — the subsequent start will just no-op.
PKG=com.pinpin.app
MAIN_ACTIVITY=com.pinpin.app.ui.MainActivity

echo ">>> force-stop $PKG"
adb shell am force-stop "$PKG" || true

echo ">>> start $MAIN_ACTIVITY"
adb shell am start -n "$PKG/$MAIN_ACTIVITY" >/dev/null
