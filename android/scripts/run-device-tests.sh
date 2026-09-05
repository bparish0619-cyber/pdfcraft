#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
adb install -r "$(find test-apks -name app-debug.apk -print -quit)"
adb install -r "$(find test-apks -name app-debug-androidTest.apk -print -quit)"
adb logcat -c
# Instrumentation can exit zero even when tests fail; inspect its final status too.
adb shell am instrument -w -r com.pdfcraft.android.qa.test/androidx.test.runner.AndroidJUnitRunner | tee device-results/instrumentation.txt
adb logcat -d > device-results/logcat.txt
rg -q '^OK \([1-9][0-9]* tests?\)' device-results/instrumentation.txt
