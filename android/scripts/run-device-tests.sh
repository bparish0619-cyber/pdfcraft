#!/usr/bin/env bash
set -euo pipefail
mkdir -p device-results
# The app is built as one APK per ABI, so install the split the emulator can run.
app="$(find test-apks -name '*x86_64*.apk' ! -name '*androidTest*' -print -quit)"
test -n "$app" || app="$(find test-apks -name 'app-debug.apk' -print -quit)"
test -n "$app" || { echo 'No debug APK found in test-apks:' >&2; find test-apks -type f >&2; exit 1; }
tests="$(find test-apks -name '*x86_64*androidTest*.apk' -print -quit)"
test -n "$tests" || tests="$(find test-apks -name '*androidTest*.apk' -print -quit)"
test -n "$tests" || { echo 'No instrumentation APK found in test-apks:' >&2; find test-apks -type f >&2; exit 1; }
adb install -r "$app"
adb install -r "$tests"
# Stream logcat to a file: a 12+ minute engine run overflows the on-device ring
# buffer, which would drop exactly the early Gecko console output we need.
adb logcat -c
adb logcat -v time > device-results/logcat.txt &
logcat=$!
trap 'kill "$logcat" 2>/dev/null || true' EXIT
status=0
# Instrumentation can exit zero even when tests fail; inspect its final status too.
adb shell am instrument -w -r com.pdfcraft.android.qa.test/androidx.test.runner.AndroidJUnitRunner \
  | tee device-results/instrumentation.txt || status=$?
kill "$logcat" 2>/dev/null || true
if grep -Eq '^OK \([1-9][0-9]* tests?\)' device-results/instrumentation.txt; then
  exit 0
fi
echo "::group::Gecko console and smoke harness output"
grep -aE 'PDFCraftSmoke|GeckoConsole|Gecko/|E AndroidRuntime|lowmemorykiller|Fatal signal' \
  device-results/logcat.txt | tail -300 || true
echo "::endgroup::"
echo "Instrumentation did not report a passing run (am instrument status ${status})." >&2
exit 1
