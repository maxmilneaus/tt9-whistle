#!/bin/bash
#
# make-module.sh - stage and pack the Magisk module for the TT9 Whistle (Qin F22) build.
#
# It reads the signed APK that scripts/build-qin.sh produced and writes two things:
#   dist/module/                                the staging tree, left on disk for inspection:
#                                                 module.prop
#                                                 customize.sh
#                                                 META-INF/com/google/android/update-binary
#                                                 system/app/TT9Whistle/TT9Whistle.apk
#                                                 system/app/TT9Whistle/lib/arm/libneedle.so
#   dist/qin_tt9_whistle-64.0-magisk.zip        the module, ready for
#                                               Magisk -> Modules -> Install from storage
#
# It never installs, flashes or reboots anything and never talks to a device.
#
# ROLLBACK - the exact way back, in this order:
#   1. give the stock keyboard back to the phone:
#        adb -s <serial> shell settings put secure default_input_method io.github.sspanak.tt9/.ime.TraditionalT9
#   2. disable only this module (it stays installed but is not mounted after the next boot):
#        adb -s <serial> shell "/debug_ramdisk/su -c 'touch /data/adb/modules/qin_tt9_whistle/disable'"
#   3. reboot the phone:
#        adb -s <serial> reboot
#   In the Magisk app the same thing is: Modules -> qin_tt9_whistle -> toggle it off
#   (or Remove) -> reboot. Removing the module unmounts /system/app/TT9Whistle, so
#   TT9 Whistle disappears again and the phone keeps the stock keyboard and its data.
#   Do step 1 before step 2/3 so the phone never reboots without an enabled keyboard.
#
# Permissions and SELinux: the zipped entries keep their modes (the APK is 0644, the
# engine 0755). macOS has no chcon, so the SELinux labels are applied on the phone by
# customize.sh through Magisk's set_perm, which is the supported place for them.
#
# Usage
#   ./make-module.sh                    use dist/TT9Whistle64.apk
#   ./make-module.sh --apk FILE         a different signed APK
#   ./make-module.sh --dist DIR         write into DIR instead of dist/
#   ./make-module.sh --any-payloads     accept a full-flavour APK (not lite)
#   ./make-module.sh --help
set -euo pipefail

readonly SELF="$0"
readonly REPO_ROOT="$(cd "$(dirname "$SELF")" && pwd)"

readonly APP_PACKAGE="io.github.sspanak.tt9.qin"
readonly APP_LABEL="TT9 Whistle"
readonly APP_VERSION_NAME="64.0-qin"
readonly APP_VERSION_CODE="1646"
readonly MODULE_ID="qin_tt9_whistle"
readonly MODULE_NAME="TT9 Whistle (Qin F22 IME)"
readonly MODULE_DIRNAME="TT9Whistle"
readonly MODULE_ZIP_NAME="$MODULE_ID-${APP_VERSION_NAME%-qin}-magisk.zip"
readonly NEEDLE_ABI="armeabi-v7a"
readonly NEEDLE_SYSTEM_ABI_DIR="arm"
readonly NEEDLE_SO="libneedle.so"
readonly SOURCE_ENGINE="$REPO_ROOT/app/src/main/jniLibs/$NEEDLE_ABI/$NEEDLE_SO"

log()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
bytes_of()  { stat -f '%z' "$1"; }
mib_of()    { printf '%s' "$(( $(bytes_of "$1") / 1048576 ))"; }
mode_of()   { stat -f '%Lp' "$1"; }

usage() { sed -n '2,38p' "$SELF" | sed -e 's/^#//' -e 's/^ //'; }

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
		[[ -x "$d/aapt2" && -x "$d/apksigner" ]] && best="$d"
	done
	printf '%s' "$best"
}

verify_apk() {
	local apk="$1" tools="$2" strict_lite="$3"
	local badging pkg ver code abi label listed dict_zips ngram_zips digest certs rc

	[[ -f "$apk" ]] || die "signed APK not found: $apk
 Build it first with scripts/build-qin.sh, or pass --apk <path>."
	[[ -n "$tools" ]] || die "no Android build-tools with aapt2 and apksigner.
 Checked the sdk.dir in local.properties and ANDROID_HOME/ANDROID_SDK_ROOT ('$(resolve_sdk)')."

	badging="$("$tools/aapt2" dump badging "$apk" 2>/dev/null || true)"
	[[ -n "$badging" ]] || die "aapt2 could not read $apk; is it an APK?"
	pkg="$(printf '%s\n' "$badging"  | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
	ver="$(printf '%s\n' "$badging"  | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
	code="$(printf '%s\n' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
	abi="$(printf '%s\n' "$badging"  | sed -n 's/^native-code: *//p' | head -1)"
	label="$(printf '%s\n' "$badging" | sed -n "s/^application-label:'\([^']*\)'.*/\1/p" | head -1)"

	[[ "$pkg" == "$APP_PACKAGE" ]]      || die "$apk is package '$pkg', expected '$APP_PACKAGE'"
	[[ "$code" == "$APP_VERSION_CODE" ]] || die "$apk is versionCode '$code', expected '$APP_VERSION_CODE'"
	[[ "$ver" == "$APP_VERSION_NAME" ]]  || die "$apk is versionName '$ver', expected '$APP_VERSION_NAME'"
	[[ "$label" == "$APP_LABEL" ]]       || die "$apk label is '$label', expected '$APP_LABEL'"
	case "$abi" in *"$NEEDLE_ABI"*) ;; *) die "$apk has no $NEEDLE_ABI native code (native-code: ${abi:-none}); the Qin F22 needs it" ;; esac
	set +e
	certs="$("$tools/apksigner" verify --print-certs "$apk" 2>&1)"
	rc=$?
	set -e
	[[ "$rc" == "0" ]] || die "$apk is not signed (apksigner verify failed):
$certs"
	# build-tools 34 names the line 'Signer #1 certificate SHA-256 digest:', 37 says
	# 'V3.0 Signer: certificate SHA-256 digest:'. Take whichever is present.
	digest="$(printf '%s\n' "$certs" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
	[[ -n "$digest" ]] || die "apksigner verify reported no certificate digest for $apk"
	log "  apk        : ${apk##*/} -> $pkg $ver ($code), label '$label', $abi"
	log "  signature  : sha256 $digest"

	listed="$(unzip -l "$apk")"
	for f in "lib/$NEEDLE_ABI/$NEEDLE_SO" "assets/whistle/whistle.cact" "assets/languages/dictionaries/en-utf8.zip" "assets/languages/ngrams/en-ngrams.zip"; do
		case "$listed" in *"$f"*) ;; *) die "$apk is missing $f" ;; esac
	done
	dict_zips="$(printf '%s\n' "$listed" | grep -c 'assets/languages/dictionaries/.*\.zip$' || true)"
	ngram_zips="$(printf '%s\n' "$listed" | grep -c 'assets/languages/ngrams/.*\.zip$' || true)"
	if [[ "$strict_lite" == "yes" ]]; then
		[[ "$dict_zips" == "1" ]] || die "$apk bundles $dict_zips dictionary payloads; the lite build bundles en-utf8.zip only.
 Build the lite release, or pass --any-payloads to package a full-flavour APK."
		[[ "$ngram_zips" == "1" ]] || die "$apk bundles $ngram_zips ngram payloads; the lite build bundles en-ngrams.zip only.
 Build the lite release, or pass --any-payloads to package a full-flavour APK."
	fi
	log "  payloads   : $dict_zips dictionary zip(s), $ngram_zips ngram zip(s) bundled"
}

stage_module() {
	local apk="$1" stage="$2" staged_so="$3" f
	rm -rf "$stage"
	mkdir -p "$stage/system/app/$MODULE_DIRNAME/lib/$NEEDLE_SYSTEM_ABI_DIR" \
		"$stage/META-INF/com/google/android"

	cp "$apk" "$stage/system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk"
	unzip -p "$apk" "lib/$NEEDLE_ABI/$NEEDLE_SO" > "$staged_so"
	[[ -s "$staged_so" ]] || die "extracting lib/$NEEDLE_ABI/$NEEDLE_SO from $apk produced an empty file"
	[[ -f "$SOURCE_ENGINE" ]] || die "missing engine for comparison: ${SOURCE_ENGINE#$REPO_ROOT/}"
	[[ "$(sha256_of "$SOURCE_ENGINE")" == "$(sha256_of "$staged_so")" ]] \
		|| die "the engine inside $apk differs from ${SOURCE_ENGINE#$REPO_ROOT/}"

	cat > "$stage/module.prop" <<PROP
id=$MODULE_ID
name=$MODULE_NAME
version=$APP_VERSION_NAME
versionCode=$APP_VERSION_CODE
author=Max
description=Preinstalls the TT9 Whistle IME ($APP_PACKAGE) as a system app under /system/app/$MODULE_DIRNAME, with the on-device Whistle engine in the app's own lib/$NEEDLE_SYSTEM_ABI_DIR directory. English words and word pairs ship inside the APK; every other language stays downloadable. Disable or remove this module in Magisk to uninstall it again.
PROP

	# Runs on the phone during the Magisk install. set_perm with a context is the only
	# place the SELinux label can be applied from this side, and the .so must stay
	# executable: DictationEngine execs it from nativeLibraryDir.
	cat > "$stage/customize.sh" <<'CUSTOMIZE'
SKIPUNZIP=0
ui_print "- staging /system/app/TT9Whistle"
set_perm_recursive $MODPATH/system/app/TT9Whistle 0 0 0755 0644
set_perm $MODPATH/system/app/TT9Whistle/TT9Whistle.apk 0 0 0644 u:object_r:system_file:s0
set_perm $MODPATH/system/app/TT9Whistle/lib/arm/libneedle.so 0 0 0755 u:object_r:system_file:s0
ui_print "- TT9 Whistle will appear after the next reboot"
CUSTOMIZE

	cat > "$stage/META-INF/com/google/android/updater-script" <<'SCRIPT'
# The Magisk installer stub ignores this file; update-binary does the work.
SCRIPT

	# Stock Magisk module installer template: sources util_functions.sh from the
	# installed Magisk, then lets Magisk unpack the zip into $MODPATH and run
	# customize.sh.
	cat > "$stage/META-INF/com/google/android/update-binary" <<'BINARY'
#!/sbin/sh
umask 022

ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ "$MAGISK_VER_CODE" -lt 20400 ] && require_new_magisk

install_module
exit 0
BINARY

	chmod 0755 "$stage/customize.sh" "$stage/META-INF/com/google/android/update-binary"
	chmod 0644 "$stage/module.prop" "$stage/META-INF/com/google/android/updater-script" \
		"$stage/system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk"
	chmod 0755 "$staged_so"

	# Belt and braces: nothing in the tree may be world-writable or an unexpected type.
	while IFS= read -r f; do
		case "$(mode_of "$f")" in
			644|755) ;;
			*) die "unexpected mode $(mode_of "$f") on ${f#$stage/}" ;;
		esac
	done < <(find "$stage" -type f)

	# A fixed timestamp for every entry makes the packed zip byte-identical between
	# runs, so the sha256 recorded here identifies one artifact. The phone does not
	# care about the timestamps.
	find "$stage" -exec touch -t 198001010000 {} +
}

pack_module() {
	local stage="$1" out="$2" listed
	rm -f "$out"
	# -X keeps macOS resource forks and extra fields out of the zip; the Unix modes
	# of the staged files are what the phone sees.
	( cd "$stage" && /usr/bin/zip -q -r -X "$out" . )
	listed="$(unzip -l "$out")"
	case "$listed" in *" module.prop"*) ;; *) die "the module zip has no module.prop at its root" ;; esac
	case "$listed" in *"system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk"*) ;; *) die "the module zip has no staged APK" ;; esac
	case "$listed" in *"system/app/$MODULE_DIRNAME/lib/$NEEDLE_SYSTEM_ABI_DIR/$NEEDLE_SO"*) ;; *) die "the module zip has no staged engine" ;; esac
	case "$listed" in *"customize.sh"*) ;; *) die "the module zip has no customize.sh" ;; esac
	case "$listed" in *".DS_Store"*|*"__MACOSX"*) die "the module zip picked up macOS metadata" ;; esac
}

main() {
	local apk="" dist="$REPO_ROOT/dist" out="" strict_lite="yes"
	while [[ $# -gt 0 ]]; do
		case "$1" in
			--apk)          shift; apk="${1:?--apk needs a file}" ;;
			--dist)         shift; dist="${1:?--dist needs a directory}" ;;
			--out)          shift; out="${1:?--out needs a file}" ;;
			--any-payloads) strict_lite="no" ;;
			-h|--help)      usage; exit 0 ;;
			*) die "unknown argument: $1 (try --help)" ;;
		esac
		shift
	done
	[[ -n "$apk" ]] || apk="$dist/TT9Whistle64.apk"
	[[ -n "$out" ]] || out="$dist/$MODULE_ZIP_NAME"
	local tools stage="$dist/module" staged_so
	tools="$(resolve_build_tools)"
	staged_so="$stage/system/app/$MODULE_DIRNAME/lib/$NEEDLE_SYSTEM_ABI_DIR/$NEEDLE_SO"

	step "Verify input APK"
	verify_apk "$apk" "$tools" "$strict_lite"

	step "Stage $stage"
	mkdir -p "$dist"
	stage_module "$apk" "$stage" "$staged_so"
	log "  module.prop: $MODULE_ID $APP_VERSION_NAME ($APP_VERSION_CODE)"
	log "  apk        : system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk (mode $(mode_of "$stage/system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk"), $(mib_of "$stage/system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk") MiB)"
	log "  engine     : system/app/$MODULE_DIRNAME/lib/$NEEDLE_SYSTEM_ABI_DIR/$NEEDLE_SO (mode $(mode_of "$staged_so"), $(bytes_of "$staged_so") bytes)"

	step "Pack"
	pack_module "$stage" "$out"
	log "  module     : ${out#$REPO_ROOT/} ($(bytes_of "$out") bytes)"

	step "Result"
	log "  module zip : sha256 $(sha256_of "$out")"
	log "  staged apk : sha256 $(sha256_of "$stage/system/app/$MODULE_DIRNAME/$MODULE_DIRNAME.apk")"
	log "  staged so  : sha256 $(sha256_of "$staged_so")"
	log "  stage dir  : ${stage#$REPO_ROOT/} (kept for inspection; safe to delete)"
	log ""
	log "Nothing was installed, flashed or rebooted."
	log "Next, on the phone: Magisk -> Modules -> Install from storage -> ${out##*/}, then reboot."
	log "Rollback: see the comment at the top of this script."
}

main "$@"
