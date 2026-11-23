#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Usage: bash scripts/check-android-launch.sh <apk>}
package=com.ericko.redmiscreenbrightness
activity=$package/.MainActivity

report_failure() {
    echo 'Screen Protection did not render a foreground dashboard.' >&2
    adb logcat -d -b crash >&2 || true
    adb shell dumpsys activity activities >&2 || true
}
trap report_failure ERR

adb install "$apk"
adb shell am force-stop "$package"
adb logcat -b crash -c
adb shell am start -W -n "$activity"

for ((attempt = 1; attempt <= 15; attempt++)); do
    crashes=$(adb logcat -d -b crash)
    if grep -Fq "Process: $package," <<< "$crashes"; then
        echo "$crashes" >&2
        exit 1
    fi
    resumed=$(adb shell dumpsys activity activities)
    if grep -E 'topResumedActivity|mResumedActivity' <<< "$resumed" | grep -Fq "$activity"; then
        break
    fi
    sleep 1
done

# Dump the accessibility tree after the first traversal; am start can report OK
# even when the first frame crashes while it is measuring or drawing.
adb shell uiautomator dump /data/local/tmp/rt5mm-launch.xml
screen=$(adb shell cat /data/local/tmp/rt5mm-launch.xml)
grep -Fq "package=\"$package\"" <<< "$screen"
grep -Fq 'text="Screen Protection"' <<< "$screen"
crashes=$(adb logcat -d -b crash)
if grep -Fq "Process: $package," <<< "$crashes"; then
    echo "$crashes" >&2
    exit 1
fi
echo 'Screen Protection remained in the foreground and rendered its dashboard.'
