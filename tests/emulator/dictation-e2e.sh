#!/usr/bin/env bash
#
# tests/emulator/dictation-e2e.sh - prove the dictation path end to end on an emulator.
#
# Installs the debug APK, selects the fork keyboard, opens the debug test-field activity, taps the
# first field, arms a stored clip, presses the voice key twice through the debug broadcasts, then
# reads the field back. Exits non-zero when no transcript landed in the field.
#
# Debug builds only: the receivers and the test fields live in app/src/debug and are absent from
# release builds. Nothing here simulates physical keys or touches the real keypad behaviour.
#
# Usage:   tests/emulator/dictation-e2e.sh [serial]          (default: emulator-5554)
# Options: APK=path        APK to install (default: the lite debug build)
#          CLIP=asset      clip asset to dictate (default: clips/latency-test.wav)
#          STOP_AFTER=4.2  seconds to record before the second voice-key press. Keep it clearly below
#                          the clip length: the clip also ends the recording by itself, and a stop
#                          press that lands while the model is still transcribing cancels it.
#          RESULT_WAIT=30  seconds to wait for a transcript to appear in the field.
#          RESET=0         keep the app's saved settings instead of clearing them first.

set -euo pipefail

SERIAL="${1:-${SERIAL:-emulator-5554}}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

APK="${APK:-$REPO/app/build/outputs/apk/lite/debug/tt9-v64.0-debug.apk}"
CLIP="${CLIP:-clips/latency-test.wav}"
STOP_AFTER="${STOP_AFTER:-4.2}"
RESULT_WAIT="${RESULT_WAIT:-30}"
RESET="${RESET:-1}"

PKG="io.github.sspanak.tt9.qin"
IME="$PKG/io.github.sspanak.tt9.ime.TraditionalT9"
TEST_ACTIVITY="$PKG/io.github.sspanak.tt9.dictation.TestFieldsActivity"
CLIP_RECEIVER="$PKG/io.github.sspanak.tt9.dictation.DebugClipReceiver"
VOICE_RECEIVER="$PKG/io.github.sspanak.tt9.dictation.DebugVoiceKeyReceiver"
CLIP_ACTION="io.github.sspanak.tt9.qin.DEBUG_DICTATE_CLIP"
VOICE_ACTION="io.github.sspanak.tt9.qin.DEBUG_VOICE_KEY"
FIRST_FIELD="field_a"

UI_XML="${TMPDIR:-/tmp}/tt9-dictation-e2e-ui.xml"
ADB=(adb -s "$SERIAL")
PYTHON=$(command -v python3 || true)

log() { printf '%s\n' "$*"; }
die() { printf 'dictation-e2e: %s\n' "$*" >&2; exit 1; }


# Prints one line per EditText in the current window, in screen order:
#   <index>|<text>|<tap x>|<tap y>
ui_fields() {
	"$PYTHON" - "$UI_XML" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
	root = ET.parse(sys.argv[1]).getroot()
except Exception as error:
	sys.exit("dictation-e2e: cannot parse the UI dump: %s" % error)
index = 0
for node in root.iter('node'):
	if node.get('class') != 'android.widget.EditText':
		continue
	numbers = re.findall(r'-?\d+', node.get('bounds') or '')
	if len(numbers) != 4:
		continue
	x1, y1, x2, y2 = (int(value) for value in numbers)
	print('%d|%s|%d|%d' % (index, (node.get('text') or '').replace('\n', ' '), (x1 + x2) // 2, (y1 + y2) // 2))
	index += 1
PY
}


# Copies the current window's view hierarchy into UI_XML. Returns non-zero when there is nothing
# readable yet, which is normal while an activity is still coming up.
dump_ui() {
	"${ADB[@]}" shell uiautomator dump /sdcard/tt9-e2e-ui.xml >/dev/null 2>&1 || true
	"${ADB[@]}" exec-out cat /sdcard/tt9-e2e-ui.xml > "$UI_XML" 2>/dev/null || true
	[ -s "$UI_XML" ] && grep -q "<hierarchy" "$UI_XML"
}


field_line() { # <index>
	ui_fields | awk -F'|' -v wanted="$1" '$1 == wanted { print; exit }'
}


# ---------------------------------------------------------------------------- checks

[ -n "$PYTHON" ] || die "python3 is required to read the view hierarchy."
command -v adb >/dev/null || die "adb is not on PATH."
[ -f "$APK" ] || die "APK not found: $APK  (build it with: gradle assembleLiteDebug)"
[ "$("${ADB[@]}" get-state 2>/dev/null || true)" = "device" ] || die "no device at $SERIAL."

log "device    $SERIAL"
log "apk       $APK"
log "clip      $CLIP (stop after ${STOP_AFTER}s)"

# ---------------------------------------------------------------------------- set up

if ! "${ADB[@]}" install -r -t "$APK" > /tmp/tt9-dictation-e2e-install.log 2>&1; then
	cat /tmp/tt9-dictation-e2e-install.log >&2
	die "install failed."
fi
log "installed"

if [ "$RESET" = "1" ]; then
	"${ADB[@]}" shell pm clear "$PKG" >/dev/null 2>&1 || true # back to the shipped defaults, including the Whistle engine
fi
"${ADB[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
"${ADB[@]}" shell ime enable "$IME" >/dev/null 2>&1 || true
if ! "${ADB[@]}" shell ime set "$IME" >/dev/null 2>&1; then
	die "could not select $IME."
fi
selected=$("${ADB[@]}" shell settings get secure default_input_method | tr -d '\r')
[ "$selected" = "$IME" ] || log "warning   the selected keyboard is '$selected', not the fork."

log "keyboard  $selected"

"${ADB[@]}" shell am start -W -n "$TEST_ACTIVITY" >/dev/null 2>&1 || die "could not open the test fields."
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

# The activity draws two empty fields whose hints are their tags, so "empty" means the hint text.
field=""
for _ in $(seq 1 20); do
	dump_ui || true
	field=$(field_line 0 | sed 's/\r//g' || true)
	[ -n "$field" ] && break
	sleep 0.5
done
[ -n "$field" ] || die "the test fields never appeared."

tap_x=$(printf '%s' "$field" | cut -d'|' -f3)
tap_y=$(printf '%s' "$field" | cut -d'|' -f4)
"${ADB[@]}" shell input tap "$tap_x" "$tap_y" >/dev/null 2>&1
sleep 1.5 # let the keyboard come up
log "tapped    $FIRST_FIELD at ${tap_x},${tap_y}"

# ---------------------------------------------------------------------------- dictate

"${ADB[@]}" shell am broadcast -a "$CLIP_ACTION" -n "$CLIP_RECEIVER" --es clip "$CLIP" >/dev/null 2>&1
sleep 0.5
"${ADB[@]}" shell am broadcast -a "$VOICE_ACTION" -n "$VOICE_RECEIVER" >/dev/null 2>&1
log "recording (voice key pressed)"
sleep "$STOP_AFTER"
"${ADB[@]}" shell am broadcast -a "$VOICE_ACTION" -n "$VOICE_RECEIVER" >/dev/null 2>&1
log "stopped   (voice key pressed again)"

text=""
waited=0
while [ "$waited" -lt "$RESULT_WAIT" ]; do
	sleep 1
	waited=$((waited + 1))
	dump_ui || true
	field=$(field_line 0 || true)
	text=$(printf '%s' "$field" | cut -d'|' -f2)
	if [ -n "$text" ] && [ "$text" != "$FIRST_FIELD" ]; then
		break
	fi
	text=""
done

# ---------------------------------------------------------------------------- report

log ""
log "--- logcat (${waited}s after stopping) ---"
"${ADB[@]}" logcat -d -v time 2>/dev/null \
	| grep -E "TT9Dictation|DictationEngine|VoiceInputOps|VoiceHandler|Whistle|OpenCode" \
	| tail -25 || true

log ""
log "field_a: ${text:-(empty)}"
if [ -z "$text" ]; then
	log "FAIL - nothing was inserted into $FIRST_FIELD."
	exit 1
fi

log "PASS - the dictation landed in $FIRST_FIELD."

