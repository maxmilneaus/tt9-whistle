#!/bin/bash
#
# Deterministic JVM harness for the warm (prepared) Whistle transcriber.
#
# It compiles PreparedWhistleTranscriber.java, LocalWhistleTranscriber.java, DictationSettings.java
# and the shared Transcriber interface with a plain JDK, then runs the protocol checks that need no
# device: argument construction, the depth/thread/language ranges, result parsing and the
# cancellation latch. Nothing is written inside the repository, no device or emulator is used, and
# no audio or transcript is ever logged.
#
# Usage:  bash tests/whistle/run-tests.sh
#
# Overrides:
#   TT9_JAVA_HOME                 JDK 17 home (default: JAVA_HOME, then /usr/libexec/java_home -v 17)
#   ANDROID_SDK_ROOT              Android SDK root (default: ~/Library/Android/sdk)
#   TT9_ANDROID_PLATFORM_JAR      android.jar to compile against
#   TT9_GRADLE_MODULES            Gradle module cache (default: ${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1)
#   TT9_ANDROIDX_ANNOTATION_JAR   androidx.annotation jar (default: found in the module cache)
#   TT9_ANDROIDX_PREFERENCE_AAR   androidx.preference aar (default: found in the module cache)
#   TT9_WHISTLE_BUILD_DIR         scratch directory (default: $TMPDIR/tt9-whistle-tests)
set -eo pipefail

TT9_REPO="$(cd "$(dirname "$0")/../.." && pwd)"
TT9_DICTATION="$TT9_REPO/app/src/main/java/io/github/sspanak/tt9/dictation"
TT9_TESTS="$TT9_REPO/tests/whistle"

TT9_JAVA="$TT9_JAVA_HOME"
if [ -z "$TT9_JAVA" ]; then
	TT9_JAVA="$JAVA_HOME"
fi
if [ -z "$TT9_JAVA" ]; then
	TT9_JAVA="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [ ! -x "$TT9_JAVA/bin/javac" ]; then
	echo "Cannot find a JDK. Set TT9_JAVA_HOME to a JDK 17 home." >&2
	exit 1
fi

TT9_SDK="$ANDROID_SDK_ROOT"
if [ -z "$TT9_SDK" ]; then
	TT9_SDK="$HOME/Library/Android/sdk"
fi
TT9_ANDROID="$TT9_ANDROID_PLATFORM_JAR"
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

if [ ! -f "$TT9_DICTATION/PreparedWhistleTranscriber.java" ]; then
	echo "Cannot find the warm transcriber in $TT9_DICTATION." >&2
	exit 1
fi

TT9_BUILD="$TT9_WHISTLE_BUILD_DIR"
if [ -z "$TT9_BUILD" ]; then
	TT9_BUILD="$TMPDIR/tt9-whistle-tests"
fi
mkdir -p "$TT9_BUILD/classes"

# DictationSettings.java needs these two at compile time only; the suites read its language list
# and its clamps and never touch the preferences screen.
TT9_MODULES="$TT9_GRADLE_MODULES"
if [ -z "$TT9_MODULES" ]; then
	TT9_MODULES="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
fi

TT9_ANNOTATION="$TT9_ANDROIDX_ANNOTATION_JAR"
if [ -z "$TT9_ANNOTATION" ]; then
	TT9_ANNOTATION="$(ls "$TT9_MODULES"/androidx.annotation/annotation/*/*/*.jar 2>/dev/null | tail -1)"
fi
if [ ! -f "$TT9_ANNOTATION" ]; then
	echo "Cannot find androidx.annotation. Set TT9_ANDROIDX_ANNOTATION_JAR to the jar." >&2
	exit 1
fi

TT9_PREFERENCE_AAR="$TT9_ANDROIDX_PREFERENCE_AAR"
if [ -z "$TT9_PREFERENCE_AAR" ]; then
	TT9_PREFERENCE_AAR="$(ls "$TT9_MODULES"/androidx.preference/preference/*/*/*.aar 2>/dev/null | tail -1)"
fi
if [ ! -f "$TT9_PREFERENCE_AAR" ]; then
	echo "Cannot find androidx.preference. Set TT9_ANDROIDX_PREFERENCE_AAR to the aar." >&2
	exit 1
fi

TT9_PREFERENCE="$TT9_BUILD/androidx-preference-classes.jar"
if [ ! -f "$TT9_PREFERENCE" ] || [ "$TT9_PREFERENCE_AAR" -nt "$TT9_PREFERENCE" ]; then
	unzip -o -q "$TT9_PREFERENCE_AAR" classes.jar -d "$TT9_BUILD/preference"
	mv "$TT9_BUILD/preference/classes.jar" "$TT9_PREFERENCE"
fi

echo "JDK:        $TT9_JAVA"
echo "android:    $TT9_ANDROID"
echo "annotation: $TT9_ANNOTATION"
echo "preference: $TT9_PREFERENCE_AAR"
echo "build dir:  $TT9_BUILD"
echo

TT9_UTIL="$TT9_REPO/app/src/main/java/io/github/sspanak/tt9/util"
TT9_TESTS_JAVA="$TT9_REPO/tests/java/io/github/sspanak/tt9/dictation"

# Logger reads this constant; Gradle generates it for the real app, so the harness supplies it.
TT9_CONFIG="$TT9_BUILD/sources/io/github/sspanak/tt9/BuildConfig.java"
mkdir -p "$(dirname "$TT9_CONFIG")"
printf 'package io.github.sspanak.tt9;\n\n/** Stand-in for the Gradle-generated constant Logger reads; this harness is not a Gradle build. */\npublic final class BuildConfig {\n\tpublic static final boolean DEBUG = false;\n}\n' > "$TT9_CONFIG"

TT9_SOURCES="$TT9_BUILD/sources.txt"
: > "$TT9_SOURCES"
printf '"%s"\n' "$TT9_CONFIG" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_UTIL/Logger.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/Transcriber.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/LocalWhistleTranscriber.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/DictationSettings.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/TranscriptionCancelledException.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_DICTATION/PreparedWhistleTranscriber.java" >> "$TT9_SOURCES"
printf '"%s"\n' "$TT9_TESTS_JAVA/LocalWhistleTranscriberTest.java" >> "$TT9_SOURCES"
for TT9_TEST in "$TT9_TESTS"/*.java; do
	printf '"%s"\n' "$TT9_TEST" >> "$TT9_SOURCES"
done

if ! "$TT9_JAVA/bin/javac" -Xlint:all -Xlint:-serial -cp "$TT9_ANDROID:$TT9_ANNOTATION:$TT9_PREFERENCE" -d "$TT9_BUILD/classes" "@$TT9_SOURCES"; then
	echo
	echo "RED: the warm transcriber and its harness did not compile."
	exit 1
fi

TT9_FAILED=0
run_suite() {
	local TT9_MAIN="$1"
	shift
	echo "---- $TT9_MAIN"
	if ! "$TT9_JAVA/bin/java" -cp "$TT9_BUILD/classes:$TT9_ANDROID" "$TT9_MAIN" "$@"; then
		TT9_FAILED=1
	fi
	echo
}

run_suite io.github.sspanak.tt9.dictation.PreparedWhistleArgumentsTest
run_suite io.github.sspanak.tt9.dictation.PreparedWhistleResultTest "$TT9_DICTATION/PreparedWhistleTranscriber.java"
run_suite io.github.sspanak.tt9.dictation.PreparedWhistleLifecycleTest
run_suite io.github.sspanak.tt9.dictation.LocalWhistleTranscriberTest

if [ "$TT9_FAILED" -eq 0 ]; then
	echo "GREEN: all warm Whistle protocol checks passed."
else
	echo "RED: one or more warm Whistle protocol checks failed."
	exit 1
fi
