#!/bin/bash
#
# Falsifiability control for the OpenCode adapter harness.
#
# It copies the adapter and the harness into a scratch tree, injects five known defects, and runs
# the normal harness there. The harness must report the specific checks that cover those defects;
# if it stays green, the suite is not proving anything. The repository itself is never modified.
#
# The four defects are the ones the JVM can observe. Whether a redirect would hand the credential
# to another host is not one of them: java.net refuses to follow a POST redirect, while the
# HttpURLConnection implementation on Android does follow it. That difference stays an on-device
# check, and the harness only asserts what it can reproduce.
#
# Usage:  bash tests/opencode/mutation-control.sh
set -eo pipefail

TT9_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TT9_MUTANT="$(mktemp -d "${TMPDIR:-/tmp}/tt9-opencode-mutant.XXXXXX")"
TT9_DEST="$TT9_MUTANT/app/src/main/java/io/github/sspanak/tt9/dictation"

mkdir -p "$TT9_DEST" "$TT9_MUTANT/tests/opencode" "$TT9_MUTANT/tests/whistle"
cp "$TT9_REPO/app/src/main/java/io/github/sspanak/tt9/dictation/OpenCodeSettings.java" "$TT9_DEST/"
cp "$TT9_REPO/app/src/main/java/io/github/sspanak/tt9/dictation/OpenCodeTranscriber.java" "$TT9_DEST/"
cp "$TT9_REPO"/tests/opencode/*.java "$TT9_MUTANT/tests/opencode/"
cp "$TT9_REPO"/tests/opencode/*.sh "$TT9_MUTANT/tests/opencode/"
cp -R "$TT9_REPO/tests/opencode/android" "$TT9_MUTANT/tests/opencode/"
cp "$TT9_REPO/tests/whistle/WhistleCheck.java" "$TT9_MUTANT/tests/whistle/"

# 1. the key is encrypted without being trimmed first
perl -0pi -e 's/keyProtector\.encrypt\(trimmedKey\)/keyProtector.encrypt(key)/' "$TT9_DEST/OpenCodeSettings.java"
# 2. a redirect response is no longer refused
perl -0pi -e 's/if \(status >= 300 && status < 400\) \{/if (false) {/' "$TT9_DEST/OpenCodeTranscriber.java"
# 3. thinking is left enabled
perl -0pi -e 's/\.put\("type", "disabled"\)/.put("type", "enabled")/' "$TT9_DEST/OpenCodeTranscriber.java"
# 4. a cut-short response is accepted as a transcript
perl -0pi -e 's/"length"\.equalsIgnoreCase\(finishReason\)/"length-but-not".equalsIgnoreCase(finishReason)/' "$TT9_DEST/OpenCodeTranscriber.java"
# 5. transcribe() clears the cancellation latch, losing a cancel() that arrived first
perl -0pi -e 's/public String transcribe\(byte\[\] wav\) throws Exception \{/public String transcribe(byte[] wav) throws Exception {\n\t\tcancelled = false;/' "$TT9_DEST/OpenCodeTranscriber.java"

TT9_LOG="$TT9_MUTANT/mutant.log"
# Its own build directory, so a mutation run can never clobber a normal one.
TT9_OPENCODE_BUILD_DIR="$TT9_MUTANT/build" bash "$TT9_MUTANT/tests/opencode/run-tests.sh" > "$TT9_LOG" 2>&1 || true

TT9_MISSED=0
check_expectation() {
	if grep -qF "$1" "$TT9_LOG"; then
		echo "caught:   $1"
	else
		echo "MISSED:   $1"
		TT9_MISSED=1
	fi
}

check_expectation "FAIL  the key is trimmed before it is encrypted"
check_expectation "FAIL  thinking stays disabled"
check_expectation "FAIL  a length-truncated response is rejected"
check_expectation "FAIL  HTTP 302 is reported as REJECTED"
check_expectation "FAIL  a cancel before transcribe() is honoured"

if grep -q "^GREEN" "$TT9_LOG"; then
	echo "The mutated adapter still passed, so the harness is not sensitive to these defects." >&2
	TT9_MISSED=1
fi

echo
echo "mutant tree:   $TT9_MUTANT"
echo "mutant output: $TT9_LOG"

if [ "$TT9_MISSED" -ne 0 ]; then
	echo "RED: the harness did not catch every injected defect."
	exit 1
fi

echo "GREEN: the harness detected every injected defect."
