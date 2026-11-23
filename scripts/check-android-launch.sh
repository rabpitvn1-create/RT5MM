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

foreground() {
    local activities
    activities=$(adb shell dumpsys activity activities)
    grep -E 'topResumedActivity|mResumedActivity' <<< "$activities" | grep -F "$activity" >/dev/null
}

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
    if foreground; then
        break
    fi
    sleep 1
done

foreground

# Require the first rendered dashboard; am start can report OK before a crash.
adb shell rm -f /data/local/tmp/rt5mm-launch.xml
adb shell uiautomator dump /data/local/tmp/rt5mm-launch.xml
screen=$(adb shell cat /data/local/tmp/rt5mm-launch.xml)
grep -Fq "package=\"$package\"" <<< "$screen"
grep -Fiq 'text="Screen Protection"' <<< "$screen"
foreground
crashes=$(adb logcat -d -b crash)
if grep -Fq "Process: $package," <<< "$crashes"; then
    echo "$crashes" >&2
    exit 1
fi
echo 'Screen Protection remained in the foreground and rendered its dashboard.'
