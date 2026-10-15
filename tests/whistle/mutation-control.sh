#
# Mutation control for the warm Whistle harness: inject the defects the suites claim to catch and
# check that every one of them is reported. A mutation that is not caught means the harness would
# have passed on the broken code, so this script is the evidence that the checks are sensitive.
#
# Usage:  bash tests/whistle/mutation-control.sh
#
set -eo pipefail

TT9_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TT9_SOURCE="app/src/main/java/io/github/sspanak/tt9/dictation"
TT9_MUTANT="$(mktemp -d "${TMPDIR:-/tmp}/tt9-whistle-mutant.XXXXXX")"
TT9_DEST="$TT9_MUTANT/$TT9_SOURCE"

mkdir -p "$TT9_DEST" "$TT9_MUTANT/tests/whistle"
for TT9_FILE in Transcriber.java LocalWhistleTranscriber.java DictationSettings.java PreparedWhistleTranscriber.java; do
	cp "$TT9_REPO/$TT9_SOURCE/$TT9_FILE" "$TT9_DEST/"
done
cp "$TT9_REPO"/tests/whistle/*.java "$TT9_REPO"/tests/whistle/*.sh "$TT9_MUTANT/tests/whistle/"

TT9_MISAPPLIED=0
mutate() {
	if ! perl -0pi -e "$1" "$TT9_DEST/PreparedWhistleTranscriber.java"; then
		echo "MUTATION ERROR: $2" >&2
		TT9_MISAPPLIED=1
	fi
	if ! grep -qF "$3" "$TT9_DEST/PreparedWhistleTranscriber.java"; then
		echo "MUTATION NOT APPLIED: $2" >&2
		TT9_MISAPPLIED=1
	fi
}

# The cancel latch is cleared as the job starts, so a cancellation that lands before
# transcribe() is silently dropped and the instance may start a second job.
mutate 's/\tpublic String transcribe\(byte\[\] wav\) throws Exception \{/\tpublic String transcribe(byte[] wav) throws Exception {\n\t\tcancelled.set(false);/' "a cleared cancel latch" "cancelled.set(false);"

# The depth clamp becomes the identity, so whatever the caller asks for reaches the runner.
mutate 's/\t\tif \(depth < MIN_DEPTH\) \{\n\t\t\treturn FULL_DEPTH;\n\t\t\}\n\t\treturn Math.min\(depth, MAX_DEPTH\);/\t\treturn depth;/' "an unclamped depth" "return depth;"

# The thread clamp becomes the identity.
mutate 's/\t\tif \(threads <= AUTO_THREADS\) \{\n\t\t\treturn AUTO_THREADS;\n\t\t\}\n\t\treturn Math.min\(threads, MAX_THREADS\);/\t\treturn threads;/' "an unclamped thread count" "return threads;"

# Any language is sent, whatever the model knows.
mutate 's/\t\treturn DictationSettings.isSupportedLanguage\(language\) \? language : DictationSettings.LANGUAGE_ENGLISH;/\t\treturn language;/' "an unclamped language" "return language;"

TT9_LOG="$TT9_MUTANT/mutant.log"
TT9_WHISTLE_BUILD_DIR="$TT9_MUTANT/build" bash "$TT9_MUTANT/tests/whistle/run-tests.sh" > "$TT9_LOG" 2>&1 || true

TT9_MISSED="$TT9_MISAPPLIED"
check_expectation() {
	if grep -qF "$1" "$TT9_LOG"; then
		echo "caught:   $1"
	else
		echo "MISSED:   $1"
		TT9_MISSED=1
	fi
}

check_expectation "FAIL  a cancel before transcribe() is honoured"
check_expectation "FAIL  depth 9 never reaches the runner outside 2..8"
check_expectation "FAIL  threads 99 never reaches the runner outside 1..4"
check_expectation "FAIL  an unsupported language never reaches the runner"

if grep -q "^GREEN" "$TT9_LOG"; then
	echo "The mutated transcriber still passed, so the harness is not sensitive to these defects." >&2
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
