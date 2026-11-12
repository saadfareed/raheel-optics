#!/usr/bin/env bash
# On-device acceptance gate. Usage: scripts/device-gate.sh [--manual-pause]
# Runs the instrumented tests against the side-by-side QA build (pk.raheeloptics.orders.qa) so the real app's account and
# orders are never touched. Serialised across parallel worktrees with a lock; never uninstalls anything.
set -euo pipefail
APP=pk.raheeloptics.orders.qa
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"
RUN="build/device-evidence/$(date +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
mkdir -p "$RUN"
: "${JAVA_HOME:=$HOME/jdk}"; export JAVA_HOME
log(){ printf '\n== %s\n' "$*" | tee -a "$RUN/gate.log"; }

command -v adb >/dev/null || { echo "adb not found"; exit 2; }
STATE=$(adb get-state 2>/dev/null || true)
[ "$STATE" = device ] || { echo "No authorised device. Enable USB debugging, unlock phone, accept RSA prompt, check 'adb devices'."; exit 2; }
adb shell getprop ro.product.model | tee "$RUN/device.txt"; adb shell getprop ro.build.version.release >> "$RUN/device.txt"

log "build (unit tests + debug APKs)"
./gradlew --console=plain :app:testQaUnitTest :app:assembleQa :app:assembleQaAndroidTest 2>&1 | tee "$RUN/build.log" | tail -20
APK=$(ls app/build/outputs/apk/qa/*.apk | head -1)
TAPK=$(ls app/build/outputs/apk/androidTest/qa/*.apk | head -1)

# One phone, many worktrees: wait for the device lock (flock, 15 min max).
exec 9>/tmp/raheel-device.lock
flock -w 900 9 || { echo "device busy"; exit 3; }

log "install (-r keeps data; -t for test APK)"
adb install -r -t "$APK"  2>&1 | tee -a "$RUN/gate.log"
adb install -r -t "$TAPK" 2>&1 | tee -a "$RUN/gate.log"
# Keep the screen on while the cable is in (touch injection fails on a sleeping screen); restore the setting on exit.
# Pin portrait for every phase (monkey input can rotate the screen and leave it rotated, breaking the next run);
# restore the phone's own settings on exit.
PREV_STAYON=$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')
PREV_AUTO=$(adb shell settings get system accelerometer_rotation | tr -d '\r')
PREV_ROT=$(adb shell settings get system user_rotation | tr -d '\r')
pin_portrait(){ adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 0; }
trap 'adb shell settings put global stay_on_while_plugged_in "${PREV_STAYON:-0}" >/dev/null 2>&1; adb shell settings put system user_rotation "${PREV_ROT:-0}" >/dev/null 2>&1; adb shell settings put system accelerometer_rotation "${PREV_AUTO:-0}" >/dev/null 2>&1 || true' EXIT
adb shell settings put global stay_on_while_plugged_in 7
pin_portrait
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard 2>/dev/null || true

log "instrumented tests (am instrument; avoids Gradle's uninstall cleanup)"
adb logcat -c
set +e
adb shell am instrument -w -r "$RUNNER" > "$RUN/instrumentation.txt" 2>&1
set -e
adb logcat -d -v time > "$RUN/logcat.txt"
grep -E "^(OK|FAILURES)|INSTRUMENTATION_(CODE|STATUS_CODE: -)" "$RUN/instrumentation.txt" | tee -a "$RUN/gate.log" || true
if grep -q "INSTRUMENTATION_STATUS_CODE: -[12]" "$RUN/instrumentation.txt" || ! grep -q "^OK (" "$RUN/instrumentation.txt"; then
  echo "INSTRUMENTED TESTS FAILED — see $RUN/instrumentation.txt"; FAIL=1; fi

log "smoke: cold start + monkey (no crash/ANR)"
pin_portrait; adb shell am force-stop "$APP"; adb logcat -c
adb shell monkey -p "$APP" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 6
adb exec-out screencap -p > "$RUN/launch.png"
adb shell monkey -p "$APP" --throttle 300 --pct-syskeys 0 --pct-anyevent 0 -s 42 300 > "$RUN/monkey.txt" 2>&1 || true
pin_portrait
adb exec-out screencap -p > "$RUN/after-monkey.png"
adb logcat -d -v time > "$RUN/smoke-logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $APP|Process $APP .*has died" "$RUN/smoke-logcat.txt" "$RUN/logcat.txt" >/dev/null; then
  echo "CRASH/ANR DETECTED"; grep -E -A8 "FATAL EXCEPTION|ANR in" "$RUN/smoke-logcat.txt" | head -40; FAIL=1; fi

log "performance snapshot"
adb shell dumpsys gfxinfo "$APP" > "$RUN/gfxinfo.txt" || true
grep -E "Total frames|Janky frames|90th|95th|99th" "$RUN/gfxinfo.txt" | tee -a "$RUN/gate.log" || true

if [ "${1:-}" = "--manual-pause" ]; then
  adb shell am start -n "$APP/com.daymark.todo.MainActivity" >/dev/null
  read -rp "Run the manual acceptance cycle on the phone, then press Enter to capture evidence… "
  adb exec-out screencap -p > "$RUN/manual-final.png"; adb logcat -d -v time > "$RUN/manual-logcat.txt"
fi

echo; echo "Evidence: $RUN"
[ -z "${FAIL:-}" ] && echo "DEVICE GATE: PASS" || { echo "DEVICE GATE: FAIL"; exit 1; }
