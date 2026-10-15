#!/bin/bash
#
# Deterministic JVM harness for the optional OpenCode dictation adapter.
#
# It compiles app/src/main/java/io/github/sspanak/tt9/dictation/OpenCodeSettings.java and
# OpenCodeTranscriber.java with a plain JDK against android.jar, then runs three suites against
# in-memory fakes and a loopback HTTPS server. Nothing is written inside the repository, no
# device or emulator is used, and the credential is always a fake literal.
#
# Usage:  bash tests/opencode/run-tests.sh
#
# Overrides:
#   TT9_JAVA_HOME              JDK 17 home (default: /usr/libexec/java_home -v 17)
#   ANDROID_SDK_ROOT           Android SDK root (default: ~/Library/Android/sdk)
#   TT9_ANDROID_PLATFORM_JAR   android.jar to compile against
#   TT9_OPENCODE_JSON_JAR      pre-downloaded org.json jar (default: fetched into the build dir)
#   TT9_OPENCODE_BUILD_DIR     scratch directory (default: $TMPDIR/tt9-opencode-tests)
set -eo pipefail

TT9_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TT9_DICTATION="$TT9_REPO/app/src/main/java/io/github/sspanak/tt9/dictation"
TT9_TESTS="$TT9_REPO/tests/opencode"
TT9_JAVA="$TT9_JAVA_HOME"
TT9_SDK="$ANDROID_SDK_ROOT"
TT9_ANDROID="$TT9_ANDROID_PLATFORM_JAR"

if [ -z "$TT9_JAVA" ]; then
	TT9_JAVA="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [ -z "$TT9_SDK" ]; then
	TT9_SDK="$HOME/Library/Android/sdk"
fi
TT9_BUILD="$TT9_OPENCODE_BUILD_DIR"
if [ -z "$TT9_BUILD" ]; then
	TT9_BUILD="$TMPDIR/tt9-opencode-tests"
fi

if [ ! -x "$TT9_JAVA/bin/javac" ]; then
	echo "Cannot find a JDK. Set TT9_JAVA_HOME to a JDK 17 home." >&2
	exit 1
fi

if [ -z "$TT9_ANDROID" ]; then
	for TT9_PLATFORM in android-37.0 android-37 android-35 android-34; do
		if [ -f "$TT9_SDK/platforms/$TT9_PLATFORM/android.jar" ]; then
			TT9_ANDROID="$TT9_SDK/platforms/$TT9_PLATFORM/android.jar"
			break
		fi
	done
fi
if [ ! -f "$TT9_ANDROID" ]; then
	echo "Cannot find android.jar. Set TT9_ANDROID_PLATFORM_JAR or ANDROID_SDK_ROOT." >&2
	exit 1
fi

if [ ! -f "$TT9_DICTATION/OpenCodeTranscriber.java" ] || [ ! -f "$TT9_DICTATION/OpenCodeSettings.java" ]; then
	echo "Cannot find the OpenCode adapter in $TT9_DICTATION." >&2
	exit 1
fi

mkdir -p "$TT9_BUILD/classes" "$TT9_BUILD/sources"

# org.json ships with the Android platform at runtime; the JVM harness needs a real jar.
TT9_JSON="$TT9_OPENCODE_JSON_JAR"
if [ -z "$TT9_JSON" ]; then
	TT9_JSON="$TT9_BUILD/json-20250517.jar"
fi
if [ ! -f "$TT9_JSON" ]; then
	echo "Fetching org.json into $TT9_JSON"
	if ! curl -fsSL "https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar" -o "$TT9_JSON"; then
		echo "Could not download org.json. Set TT9_OPENCODE_JSON_JAR to a local copy." >&2
		exit 1
	fi
fi

# A throwaway self-signed certificate, for the loopback server only.
TT9_JKS="$TT9_BUILD/localhost.jks"
if [ ! -f "$TT9_JKS" ]; then
	"$TT9_JAVA/bin/keytool" -genkeypair -keystore "$TT9_JKS" -alias localhost \
		-storepass android -keypass android -keyalg RSA -keysize 2048 -validity 30 \
		-dname 'CN=localhost' -ext 'SAN=dns:localhost' >/dev/null 2>&1
fi

# The adapter implements io.github.sspanak.tt9.dictation.Transcriber. When that shared interface
# has not landed yet, compile against a scratch copy so the adapter itself can still be checked.
TT9_TRANSCRIBER="$TT9_DICTATION/Transcriber.java"
if [ ! -f "$TT9_TRANSCRIBER" ]; then
	TT9_TRANSCRIBER="$TT9_BUILD/sources/Transcriber.java"
	echo "WARNING: the shared Transcriber interface is not in the repository yet; using a scratch copy."
	cat > "$TT9_TRANSCRIBER" <<'JAVA'
package io.github.sspanak.tt9.dictation;

/** Scratch copy of the shared interface, for this harness only. */
interface Transcriber {
	String transcribe(byte[] wav) throws Exception;

	void cancel();
}
JAVA
fi

echo "JDK:       $TT9_JAVA"
echo "android:   $TT9_ANDROID"
echo "org.json:  $TT9_JSON"
echo "build dir: $TT9_BUILD"
echo

# android.util.Base64 is a stub in android.jar, so the harness brings a working delegate.
if ! "$TT9_JAVA/bin/javac" -nowarn -d "$TT9_BUILD/classes" "$TT9_TESTS/android/util/Base64.java"; then
	echo "RED: the JVM base64 shim did not compile."
	exit 1
fi

TT9_SOURCES="$TT9_BUILD/sources.txt"
: > "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/OpenCodeSettings.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/OpenCodeTranscriber.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_TRANSCRIBER" >> "$TT9_SOURCES"
for TT9_TEST in "$TT9_TESTS"/*.java; do
	printf '"%s"\n' "$TT9_TEST" >> "$TT9_SOURCES"
done

if ! "$TT9_JAVA/bin/javac" -Xlint:all -Xlint:-serial -cp "$TT9_JSON:$TT9_ANDROID" \
	-d "$TT9_BUILD/classes" "@$TT9_SOURCES"; then
	echo
	echo "RED: the adapter and its harness did not compile."
	exit 1
fi

TT9_FAILED=0
run_suite() {
	local TT9_MAIN="$1"
	shift
	echo "---- $TT9_MAIN"
	if ! "$TT9_JAVA/bin/java" --add-modules jdk.httpserver -cp "$TT9_BUILD/classes:$TT9_JSON" "$TT9_MAIN" "$@"; then
		TT9_FAILED=1
	fi
	echo
}

run_suite io.github.sspanak.tt9.dictation.OpenCodeSettingsTest
run_suite io.github.sspanak.tt9.dictation.OpenCodeRequestContractTest
run_suite io.github.sspanak.tt9.dictation.OpenCodeTransportTest "$TT9_JKS"

if [ "$TT9_FAILED" -eq 0 ]; then
	echo "GREEN: all OpenCode adapter checks passed."
else
	echo "RED: one or more OpenCode adapter checks failed."
	exit 1
fi
