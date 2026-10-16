#!/bin/bash
#
# build-qin.sh - build, align and sign the TT9 Whistle (Qin F22) lite release APK.
#
# This is the only supported way to produce the artifact that make-module.sh
# packages. It never installs anything, never talks to a device, and never
# writes inside app/src.
#
# Steps
#   1. check the pinned local toolchain: JDK 17, Gradle 9.5.0, the Android
#      build-tools (zipalign/apksigner/aapt2) and the personal debug keystore;
#   2. verify the app identity in app/build.gradle and the asset layout the lite
#      release depends on (English payloads in the lite source set, whistle.cact
#      and the arm engine in main);
#   3. run :app:assembleLiteRelease with the generated-language tasks skipped,
#      because the exact v64.0 generated assets are already in the source tree;
#   4. zipalign, then sign into dist/TT9Whistle64.apk;
#   5. re-read the result with aapt2/unzip and print size, badging and sha256.
#
# The output is idempotent: running it twice rebuilds the same file in place.
#
# Usage
#   scripts/build-qin.sh                  build and sign dist/TT9Whistle64.apk
#   scripts/build-qin.sh --no-sign        build, leave the release APK unsigned
#   scripts/build-qin.sh --dist DIR       write into DIR instead of dist/
#   scripts/build-qin.sh --help
#
# Environment overrides (all optional)
#   JAVA_HOME                 JDK to build with (default: the local JDK 17 below)
#   GRADLE                    gradle launcher (default: the pinned local 9.5.0)
#   ANDROID_HOME/ANDROID_SDK_ROOT   SDK, when local.properties has no sdk.dir
#   KEYSTORE / KEYSTORE_ALIAS / KEYSTORE_PASS   personal signing identity
#   GRADLE_ARGS               extra gradle arguments (default: --console=plain)
#
# Every missing prerequisite stops the script with a message that names it.
set -euo pipefail

readonly SELF="$0"
readonly SCRIPT_DIR="$(cd "$(dirname "$SELF")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly WORKSPACE_ROOT="$(cd "$REPO_ROOT/../.." && pwd)"

readonly APP_DIR="$REPO_ROOT/app"
readonly MAIN_ASSETS="$APP_DIR/src/main/assets"
readonly LITE_ASSETS="$APP_DIR/src/lite/assets"
readonly GRADLE_FILE="$APP_DIR/build.gradle"
readonly CONSTANTS_FILE="$APP_DIR/constants.gradle"

readonly APP_PACKAGE="io.github.sspanak.tt9.qin"
readonly APP_VERSION_NAME="64.0-qin"
readonly APP_VERSION_CODE="1646"
readonly ASSEMBLE_TASK=":app:assembleLiteRelease"

readonly DEFAULT_JAVA_HOME="${HOME}/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
readonly PINNED_GRADLE="$WORKSPACE_ROOT/work/toolchains/gradle-9.5.0/bin/gradle"

readonly WHISTLE_MODEL="$MAIN_ASSETS/whistle/whistle.cact"
readonly LITE_DICT="$LITE_ASSETS/languages/dictionaries/en-utf8.zip"
readonly LITE_NGRAM="$LITE_ASSETS/languages/ngrams/en-ngrams.zip"
readonly NEEDLE_SO="$APP_DIR/src/main/jniLibs/armeabi-v7a/libneedle.so"

# The generated-language tasks. The pinned v64.0 assets are already in the tree,
# so running these would regenerate app/src/main/assets, app/src/full/assets and
# the root downloads/ directory from the local app/languages inputs.
SKIP_TASKS=(
	validateLanguages
	buildDefinition
	buildDictionaryDownloads
	copyDownloadsToAssets
	generateNgramsMeta
	copyNgramsToAssets
	copyNgramsToDownloads
	generateDocs
)
# These two rewrite tracked files (StatusIcon.java, AndroidManifest.xml) from the
# current git state. The committed values are already correct, and this script is
# not allowed to touch app/src, so they are skipped too.
SKIP_FILE_TASKS=(updateStatusIcons updateManifest)
GUARDED_FILES=(
	app/src/main/AndroidManifest.xml
	app/src/main/java/io/github/sspanak/tt9/ui/StatusIcon.java
)

# Set by publish_apk(), consumed by report_apk().
PUBLISHED_APK=""

log()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
bytes_of()  { stat -f '%z' "$1"; }
mib_of()    { printf '%s' "$(( $(bytes_of "$1") / 1048576 ))"; }

usage() { sed -n '2,35p' "$SELF" | sed -e 's/^#//' -e 's/^ //'; }

resolve_sdk() {
	local sdk=""
	if [[ -f "$REPO_ROOT/local.properties" ]]; then
		sdk="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -1 | tr -d '[:space:]')"
	fi
	[[ -n "$sdk" ]] || sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
	printf '%s' "$sdk"
}

resolve_build_tools() {
	local sdk best="" d
	sdk="$(resolve_sdk)"
	[[ -n "$sdk" ]] || return 0
	for d in "$sdk"/build-tools/*; do
		[[ -x "$d/zipalign" && -x "$d/apksigner" && -x "$d/aapt2" ]] && best="$d"
	done
	printf '%s' "$best"
}

resolve_java_home() {
	if [[ -n "${JAVA_HOME:-}" ]]; then printf '%s' "$JAVA_HOME"; else printf '%s' "$DEFAULT_JAVA_HOME"; fi
}

resolve_gradle() {
	if [[ -n "${GRADLE:-}" ]]; then printf '%s' "$GRADLE"; else printf '%s' "$PINNED_GRADLE"; fi
}

gradle_value() { # value of a `key = value` line in app/build.gradle
	sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$GRADLE_FILE" | head -1 | tr -d '"'
}

# app/build.gradle writes applicationId as "${PACKAGE_NAME}.qin", where
# PACKAGE_NAME comes from app/constants.gradle. Resolve that one level so the
# identity check compares real values instead of Groovy source text.
resolve_groovy_value() {
	local value="$1" name replacement line
	while [[ "$value" =~ \$\{([A-Za-z_][A-Za-z0-9_]*)\} ]]; do
		name="${BASH_REMATCH[1]}"
		line="$(sed -n "s/^[[:space:]]*ext\.$name[[:space:]]*=[[:space:]]*//p" "$CONSTANTS_FILE" | head -1)"
		line="${line//\"/}"
		line="${line//\'/}"
		replacement="$line"
		[[ -n "$replacement" ]] || die "app/build.gradle uses \${$name}, which app/constants.gradle does not define"
		value="${value//\$\{$name\}/$replacement}"
	done
	printf '%s' "$value"
}

snapshot_guarded_files() {
	local f
	for f in "${GUARDED_FILES[@]}"; do
		[[ -f "$REPO_ROOT/$f" ]] && printf '%s  %s\n' "$(sha256_of "$REPO_ROOT/$f")" "$f"
	done
}

check_toolchain() {
	local java_home gradle build_tools keystore version

	java_home="$(resolve_java_home)"
	[[ -x "$java_home/bin/java" ]] || die "no JDK at '$java_home'.
 Set JAVA_HOME to a JDK 17+ (this workspace compiles with $DEFAULT_JAVA_HOME)."
	version="$("$java_home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
	[[ -n "$version" ]] || die "could not read the Java version from $java_home/bin/java"
	[[ "$version" -ge 17 ]] || die "JDK $version at $java_home is too old; this build needs JDK 17 or newer"
	log "  java       : $java_home (JDK $version)"

	gradle="$(resolve_gradle)"
	[[ -x "$gradle" ]] || die "no gradle launcher at '$gradle'.
 The pinned local copy is $PINNED_GRADLE (set GRADLE to use another one).
 The wrapper is deliberately not used: it would download Gradle 9.5.0."
	log "  gradle     : $gradle"

	build_tools="$(resolve_build_tools)"
	[[ -n "$build_tools" ]] || die "no Android build-tools with zipalign, apksigner and aapt2.
 Checked the sdk.dir in local.properties and ANDROID_HOME/ANDROID_SDK_ROOT ('$(resolve_sdk)')."
	log "  build-tools: $build_tools"

	keystore="${KEYSTORE:-$HOME/.android/debug.keystore}"
	[[ -f "$keystore" ]] || die "no personal signing keystore at '$keystore'.
 Set KEYSTORE to an existing keystore (this script never creates or shares one)."
	log "  keystore   : $keystore (alias ${KEYSTORE_ALIAS:-androiddebugkey})"

	[[ -f "$GRADLE_FILE" ]] || die "missing app/build.gradle at $GRADLE_FILE"
	[[ -f "$REPO_ROOT/gradle.properties" ]] || die "missing gradle.properties at the repo root"
}

check_app_identity() {
	local id code name
	id="$(resolve_groovy_value "$(gradle_value applicationId)")"
	code="$(gradle_value versionCode)"
	name="$(resolve_groovy_value "$(gradle_value versionName)")"
	[[ "$id" == "$APP_PACKAGE" ]]       || die "app/build.gradle declares applicationId '$id', expected '$APP_PACKAGE'"
	[[ "$code" == "$APP_VERSION_CODE" ]] || die "app/build.gradle declares versionCode '$code', expected '$APP_VERSION_CODE'"
	[[ "$name" == "$APP_VERSION_NAME" ]] || die "app/build.gradle declares versionName '$name', expected '$APP_VERSION_NAME'"
	grep -q '^[[:space:]]*lite[[:space:]]*{' "$GRADLE_FILE" || die "app/build.gradle no longer declares a 'lite' product flavor"
	grep -q 'useLegacyPackaging[[:space:]]*=[[:space:]]*true' "$GRADLE_FILE" \
		|| die "app/build.gradle no longer sets jniLibs useLegacyPackaging = true.
 The Qin module needs libneedle.so extracted from the APK into nativeLibraryDir."
	log "  identity   : $id $name ($code), lite flavor, useLegacyPackaging=true"
}

check_source_assets() {
	local f zips full_zips
	for f in "$WHISTLE_MODEL" "$LITE_DICT" "$LITE_NGRAM" "$NEEDLE_SO"; do
		[[ -s "$f" ]] || die "missing prerequisite asset: ${f#$REPO_ROOT/}"
	done
	[[ "$(bytes_of "$WHISTLE_MODEL")" -gt 1000000 ]] || die "$WHISTLE_MODEL looks truncated ($(bytes_of "$WHISTLE_MODEL") bytes)"

	zips=$(find "$MAIN_ASSETS/languages/dictionaries" -maxdepth 1 -name '*.zip' 2>/dev/null | wc -l | tr -d ' ')
	[[ "$zips" == "0" ]] || die "$zips dictionary payload zip(s) in app/src/main/assets/languages/dictionaries.
 Payloads belong to one flavour: app/src/full/assets (all languages) or
 app/src/lite/assets (English only). A lite build would bundle both."
	zips=$(find "$MAIN_ASSETS/languages/ngrams" -maxdepth 1 -name '*.zip' 2>/dev/null | wc -l | tr -d ' ')
	[[ "$zips" == "0" ]] || die "$zips ngram payload zip(s) in app/src/main/assets/languages/ngrams; see the dictionary message above"

	full_zips=$(find "$APP_DIR/src/full/assets/languages/dictionaries" -maxdepth 1 -name '*.zip' 2>/dev/null | wc -l | tr -d ' ')
	log "  assets     : whistle.cact $(mib_of "$WHISTLE_MODEL") MiB, lite English payloads present, full flavour keeps $full_zips dictionary zips"
}

run_gradle() {
	local log_file="$1" java_home="$2" gradle="$3"; shift 3
	local -a gradle_args=("$@")
	local -a extra=()
	read -r -a extra <<< "${GRADLE_ARGS:---console=plain}"
	local attempt=1 rc=0

	while :; do
		set +e
		( cd "$REPO_ROOT" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
			"$gradle" "${gradle_args[@]}" "${extra[@]}" 2>&1 ) | tee "$log_file"
		rc="${PIPESTATUS[0]}"
		set -e
		[[ "$rc" == "0" ]] && return 0
		if [[ "$attempt" == "1" ]] && grep -q -e 'Timeout waiting to lock' -e 'Could not acquire' -e 'has an incompatible' "$log_file"; then
			warn "gradle reported a lock problem; waiting 20s and retrying once"
			sleep 20
			attempt=2
			continue
		fi
		return "$rc"
	done
}

build_release() {
	local dist="$1" java_home gradle log_file before after task stale
	local -a args=("$ASSEMBLE_TASK")

	java_home="$(resolve_java_home)"
	gradle="$(resolve_gradle)"
	log_file="$dist/build-qin-gradle.log"
	for task in "${SKIP_TASKS[@]}" "${SKIP_FILE_TASKS[@]}"; do args+=(-x "$task"); done

	step "Gradle $ASSEMBLE_TASK"
	log "  skipped    : ${SKIP_TASKS[*]}"
	log "               (${SKIP_FILE_TASKS[*]}; the generated assets are already in the source tree)"
	log "  log        : ${log_file#$REPO_ROOT/}"

	# A failed or interrupted packaging leaves a half-written APK behind, and the
	# packager updates an existing file in place, so a stale one can look like a
	# successful but wrong (or duplicated) result. Start from no release APK.
	for stale in "$APP_DIR"/build/outputs/apk/lite/release/*.apk; do
		[[ -f "$stale" ]] || continue
		rm -f "$stale"
		log "  removed    : stale build output ${stale#$REPO_ROOT/}"
	done

	before="$(snapshot_guarded_files)"
	run_gradle "$log_file" "$java_home" "$gradle" "${args[@]}" \
		|| die "gradle $ASSEMBLE_TASK failed; see ${log_file#$REPO_ROOT/} for the full output"
	after="$(snapshot_guarded_files)"
	if [[ "$before" != "$after" ]]; then
		warn "a tracked file changed during the build; the generator tasks did not stay skipped:"
		printf '%s\n---\n%s\n' "$before" "$after" >&2
		die "refusing to publish an artifact built over a modified source tree"
	fi
	log "  guarded    : AndroidManifest.xml and StatusIcon.java unchanged"
}

publish_apk() {
	local dist="$1" do_sign="$2" build_tools release_apk out aligned digest f certs rc
	local -a candidates=()
	for f in "$APP_DIR"/build/outputs/apk/lite/release/*.apk; do [[ -f "$f" ]] && candidates+=("$f"); done
	[[ "${#candidates[@]}" -gt 0 ]] || die "gradle reported success but no APK is under app/build/outputs/apk/lite/release/"
	[[ "${#candidates[@]}" == 1 ]] || die "${#candidates[@]} release APKs under app/build/outputs/apk/lite/release/: $(printf '%s ' "${candidates[@]##*/}").
 Remove app/build/outputs and build again; a stale partial APK cannot be told apart from a fresh one."
	release_apk="${candidates[0]}"
	log "  built      : ${release_apk#$REPO_ROOT/} ($(mib_of "$release_apk") MiB, sha256 $(sha256_of "$release_apk"))"

	mkdir -p "$dist"
	build_tools="$(resolve_build_tools)"
	out="$dist/TT9Whistle64.apk"
	if [[ "$do_sign" == "no" ]]; then
		out="$dist/TT9Whistle64-unsigned.apk"
		rm -f "$out"
		cp "$release_apk" "$out"
		warn "--no-sign: $out is not signed and cannot be installed"
		PUBLISHED_APK="$out"
		return 0
	fi

	step "Align and sign"
	aligned="$dist/.TT9Whistle64-aligned.apk"
	rm -f "$out" "$aligned"
	"$build_tools/zipalign" -p -f 4 "$release_apk" "$aligned"
	"$build_tools/apksigner" sign \
		--ks "${KEYSTORE:-$HOME/.android/debug.keystore}" \
		--ks-key-alias "${KEYSTORE_ALIAS:-androiddebugkey}" \
		--ks-pass "pass:${KEYSTORE_PASS:-android}" \
		--key-pass "pass:${KEYSTORE_PASS:-android}" \
		--out "$out" "$aligned"
	rm -f "$aligned"
	[[ -f "$out" ]] || die "apksigner did not produce $out"
	set +e
	certs="$("$build_tools/apksigner" verify --print-certs "$out" 2>&1)"
	rc=$?
	set -e
	[[ "$rc" == "0" ]] || die "apksigner verify rejected $out:
$certs"
	digest="$(printf '%s\n' "$certs" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
	[[ -n "$digest" ]] || die "apksigner verify reported no certificate digest for $out"
	rm -f "$out.idsig"
	log "  signer     : ${KEYSTORE:-$HOME/.android/debug.keystore} sha256 $digest (personal build only)"
	PUBLISHED_APK="$out"
}

report_apk() {
	local apk="$1" build_tools badging pkg ver code abi label listed size mib dict_zips ngram_zips f
	build_tools="$(resolve_build_tools)"
	badging="$("$build_tools/aapt2" dump badging "$apk" 2>/dev/null || true)"
	[[ -n "$badging" ]] || die "aapt2 could not read $apk"
	pkg="$(printf '%s\n' "$badging"  | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
	ver="$(printf '%s\n' "$badging"  | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
	code="$(printf '%s\n' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
	abi="$(printf '%s\n' "$badging"  | sed -n 's/^native-code: *//p' | head -1)"
	label="$(printf '%s\n' "$badging" | sed -n "s/^application-label:'\([^']*\)'.*/\1/p" | head -1)"

	[[ "$pkg" == "$APP_PACKAGE" ]]      || die "$apk has package '$pkg', expected '$APP_PACKAGE'"
	[[ "$code" == "$APP_VERSION_CODE" ]] || die "$apk has versionCode '$code', expected '$APP_VERSION_CODE'"
	[[ "$ver" == "$APP_VERSION_NAME" ]]  || die "$apk has versionName '$ver', expected '$APP_VERSION_NAME'"
	case "$abi" in *armeabi-v7a*) ;; *) die "$apk has no armeabi-v7a native code (native-code: ${abi:-none}); the F22 cannot run it" ;; esac
	[[ "$label" == "TT9 Whistle" ]] || warn "$apk label is '$label', expected 'TT9 Whistle'"

	listed="$(unzip -l "$apk")"
	for f in "lib/armeabi-v7a/libneedle.so" "assets/whistle/whistle.cact" "assets/languages/dictionaries/en-utf8.zip" "assets/languages/ngrams/en-ngrams.zip"; do
		case "$listed" in *"$f"*) ;; *) die "$apk is missing $f" ;; esac
	done
	dict_zips="$(printf '%s\n' "$listed" | grep -c 'assets/languages/dictionaries/.*\.zip$' || true)"
	ngram_zips="$(printf '%s\n' "$listed" | grep -c 'assets/languages/ngrams/.*\.zip$' || true)"
	[[ "$dict_zips" == "1" && "$ngram_zips" == "1" ]] \
		|| die "$apk bundles $dict_zips dictionary and $ngram_zips ngram payloads; a lite build must bundle English only"

	size="$(bytes_of "$apk")"
	mib="$(mib_of "$apk")"
	[[ "$size" -gt $((5 * 1024 * 1024)) ]] || die "$apk is only $size bytes; the build looks broken"
	(( size >= 8 * 1024 * 1024 && size <= 60 * 1024 * 1024 )) \
		|| die "$apk is $mib MiB, which is not a plausible lite release; remove app/build/outputs and build again"
	(( size >= 20 * 1024 * 1024 && size <= 28 * 1024 * 1024 )) || warn "$apk is $mib MiB; the expected lite release is about 20-28 MB"

	step "Result"
	log "  apk        : ${apk#$REPO_ROOT/}"
	log "  package    : $pkg $ver ($code), label '$label'"
	log "  native     : $abi"
	log "  payloads   : $dict_zips dictionary, $ngram_zips ngram (English only)"
	log "  size       : $size bytes ($mib MiB)"
	log "  sha256     : $(sha256_of "$apk")"
	log ""
	log "Nothing was installed and no device was touched. Next: ./make-module.sh"
}

main() {
	local dist="$REPO_ROOT/dist" do_sign="yes" apk
	while [[ $# -gt 0 ]]; do
		case "$1" in
			--dist)    shift; dist="${1:?--dist needs a directory}" ;;
			--no-sign) do_sign="no" ;;
			-h|--help) usage; exit 0 ;;
			*) die "unknown argument: $1 (try --help)" ;;
		esac
		shift
	done
	mkdir -p "$dist"

	step "Prerequisites"
	check_toolchain
	check_source_assets
	check_app_identity

	build_release "$dist"
	publish_apk "$dist" "$do_sign"
	[[ -n "$PUBLISHED_APK" ]] || die "no APK was published"
	report_apk "$PUBLISHED_APK"
}

main "$@"
