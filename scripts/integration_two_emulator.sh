#!/usr/bin/env bash
# Two-emulator integration harness for the Live Scam Call Detector.
# Run on a host with Android SDK, two emulators (phone + Wear OS), and adb.
set -euo pipefail

PHONE_SERIAL="${PHONE_SERIAL:-emulator-5554}"
WEAR_SERIAL="${WEAR_SERIAL:-emulator-5556}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "Building debug APKs..."
"$ROOT_DIR/gradlew" :mobile:assembleDebug :wear:assembleDebug

MOBILE_APK="$ROOT_DIR/mobile/build/outputs/apk/debug/mobile-debug.apk"
WEAR_APK="$ROOT_DIR/wear/build/outputs/apk/debug/wear-debug.apk"

echo "Installing APKs..."
adb -s "$PHONE_SERIAL" install -r "$MOBILE_APK"
adb -s "$WEAR_SERIAL" install -r "$WEAR_APK"

echo "Clearing logcat..."
adb -s "$PHONE_SERIAL" logcat -c
adb -s "$WEAR_SERIAL" logcat -c

echo "Launching apps..."
adb -s "$PHONE_SERIAL" shell am start -n com.aicallscreening.mobile/.MainActivity
adb -s "$WEAR_SERIAL" shell am start -n com.aicallscreening.wear/.MainActivity

echo "Starting phone monitoring..."
adb -s "$PHONE_SERIAL" shell am broadcast -a com.aicallscreening.TEST_START_MONITOR 2>/dev/null || \
  adb -s "$PHONE_SERIAL" shell input tap 540 1400

sleep 2

echo "Starting watch capture..."
adb -s "$WEAR_SERIAL" shell input tap 200 280

echo "Simulating incoming GSM call on phone..."
adb -s "$PHONE_SERIAL" emu gsm call +15551234567

echo "Waiting for pipeline activity (30s)..."
sleep 30

echo "=== Phone log highlights ==="
adb -s "$PHONE_SERIAL" logcat -d | rg "CallDetectionManager|MonitorService|GeminiLiveClient|/scam/" || true

echo "=== Watch log highlights ==="
adb -s "$WEAR_SERIAL" logcat -d | rg "CallListenerService|AudioCaptureService|/scam/" || true

assert_log() {
  local serial="$1"
  local pattern="$2"
  local label="$3"
  if adb -s "$serial" logcat -d | rg -q "$pattern"; then
    echo "PASS: $label"
  else
    echo "FAIL: $label (pattern: $pattern)"
    exit 1
  fi
}

assert_log "$PHONE_SERIAL" "MonitorService|GeminiLiveClient" "Phone monitor/Gemini activity"
assert_log "$WEAR_SERIAL" "AudioCaptureService|bytes=" "Watch audio streaming"
assert_log "$PHONE_SERIAL" "/scam/incoming_call|Incoming/active call" "Incoming call notification path"

echo "Integration harness finished. Manually verify /scam/alert if Gemini returns high risk."
