#!/usr/bin/env bash
# Rebuilds app/libs/onnxruntime-android-<version>-pwnall.aar: ONNX Runtime for arm64-v8a and
# armeabi-v7a without telemetry, NNAPI, XNNPACK or WebGPU, and with only the operators ops.config
# names. README.md beside this script says why and how to check the result.
#
#   tools/onnxruntime-android/build.sh            # about an hour on an M-series Mac
#   WORK=/some/dir tools/onnxruntime-android/build.sh
#
# Needs: git, curl, python3.12, the Android SDK and NDK 28 or newer (NDK 28 aligns ELF segments to
# 16 KB, which Google Play requires). A JDK 17 is fetched into $WORK: ORT's own Java build runs AGP
# 7.4.2, whose jlink step fails under Android Studio's JDK 21. A Mac with Apple silicon: the JDK and
# the aapt2 fetched here are that machine's, and so are the paths taken when nothing names others.
#
# What it downloads it takes by what it is: the JDK archive by SHA-256, cmake and ninja one release
# each with the wheel's SHA-256, ORT's dependencies by the SHA-1 its deps.txt pins, and ORT itself
# by the commit its tag has to be.
set -euo pipefail

ORT_TAG="${ORT_TAG:-v1.30.0}"
# The commit that tag is, as the libraries of the committed AAR name it in their build-info string
# ("git-commit-id=f2c39fe"). Seven digits tell a tag that was moved from one that was not; they
# would not stop a commit made to begin with them. The script prints the whole id it built from,
# which is what belongs here.
ORT_COMMIT="${ORT_COMMIT:-f2c39fe}"
VERSION="${ORT_TAG#v}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
WORK="${WORK:-$(mktemp -d)}"
JDK17_URL="https://api.adoptium.net/v3/binary/version/jdk-17.0.20.1%2B1/mac/aarch64/jdk/hotspot/normal/eclipse"
# Of OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz, the archive that address answers with:
# the figure Adoptium publishes for it, and the one the download comes to.
JDK17_SHA256="196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8"
if [ ! -d "$WORK/jdk17" ]; then
    mkdir -p "$WORK"
    curl -sSL -o "$WORK/jdk17.tar.gz" "$JDK17_URL"
    echo "$JDK17_SHA256  $WORK/jdk17.tar.gz" | shasum -a 256 -c - >/dev/null ||
        { echo "the JDK archive is not the one JDK17_SHA256 names" >&2; exit 1; }
    # Unpacked only once it has been checked, so a second run never meets half a JDK.
    mkdir -p "$WORK/jdk17"
    tar -xzf "$WORK/jdk17.tar.gz" -C "$WORK/jdk17"
fi
JAVA_HOME="$(ls -d "$WORK"/jdk17/*/Contents/Home | head -1)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
# The desktop onnxruntime used by make_ops_config.py reports to Microsoft unless told not to.
export ORT_DISABLE_TELEMETRY=1

python3.12 -m venv "$WORK/venv"
# Programs the build runs, so one release of each and no other file under its name: the releases
# "cmake>=3.28,<4" and "ninja" resolved to by PyPI's upload dates when the committed AAR was built,
# and the SHA-256 of their macOS wheels. pip refuses anything else.
cat > "$WORK/requirements.txt" <<'REQ'
cmake==3.31.10 --hash=sha256:ad697643a00d9ba85179590a383c4f7401169b55ebf4b8b2938daf28c6bdeb6d
ninja==1.13.2 --hash=sha256:fd82e26c0706ad4ab88e5fdd26f3fab0a987a90f810160f6c322e752c6af298b
REQ
"$WORK/venv/bin/pip" install --quiet --only-binary :all: --require-hashes -r "$WORK/requirements.txt"
export PATH="$WORK/venv/bin:$PATH"

[ -d "$WORK/src" ] || git clone --depth 1 --branch "$ORT_TAG" https://github.com/microsoft/onnxruntime.git "$WORK/src"
cd "$WORK/src"
BUILT_FROM="$(git rev-parse HEAD)"
case "$BUILT_FROM" in
"$ORT_COMMIT"*) ;;
*)
    echo "$ORT_TAG is commit $BUILT_FROM here, not $ORT_COMMIT: a moved tag, or another release's ORT_COMMIT" >&2
    exit 1
    ;;
esac

# The packaging script forces vcpkg with Microsoft's internal asset cache, which needs Microsoft's
# credentials; without the two flags the build takes its sources from cmake/deps.txt.
python3.12 - <<'PY'
import pathlib
path = pathlib.Path("tools/ci_build/github/android/build_aar_package.py")
text = path.read_text()
forced = '+ ["--config=" + build_config, "--use_vcpkg", "--use_vcpkg_ms_internal_asset_cache"]'
text = text.replace(forced, '+ ["--config=" + build_config]')
# `gradle build` also compiles and runs the Java bindings' own tests and lint, whose dependencies
# are one more download each; the AAR needs only assembleRelease.
text = text.replace('[*gradle_command, "build"]', '[*gradle_command, "assembleRelease"]')
path.write_text(text)
cmake = pathlib.Path("cmake/onnxruntime_java.cmake")
cmake.write_text(cmake.read_text().replace(
    "${COMMON_GRADLE_ARGS}\n      build\n", "${COMMON_GRADLE_ARGS}\n      assembleRelease\n"))
PY

# CMake's own downloader can fail its DNS lookups, so every dependency is fetched with curl
# into the mirror ORT looks in first (src/mirror/<host>/<path>), each checked against deps.txt's SHA-1.
python3.12 - <<'PY'
import hashlib, os, subprocess
for line in open("cmake/deps.txt"):
    if not line.strip() or line.startswith("#"):
        continue
    name, url, sha1 = line.strip().split(";")
    target = "mirror/" + url.removeprefix("https://")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if not os.path.exists(target):
        subprocess.run(["curl", "-sSL", "--retry", "4", "-o", target, url], check=True)
    if hashlib.sha1(open(target, "rb").read()).hexdigest() != sha1:
        raise SystemExit(f"{name}: SHA-1 mismatch for {url}")
PY

# An outbound filter can keep the freshly fetched JDK off the network, so the one artifact ORT's
# Java build downloads late (aapt2, for verifyReleaseResources) is put into the shared Gradle cache
# first with Android Studio's JDK.
STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -d "$STUDIO_JDK" ]; then
    mkdir -p "$WORK/prefetch"
    printf "rootProject.name = 'prefetch'\n" > "$WORK/prefetch/settings.gradle"
    cat > "$WORK/prefetch/build.gradle" <<'GRADLE'
repositories { google() }
configurations { aapt2 }
dependencies { aapt2 'com.android.tools.build:aapt2:7.4.2-8841542:osx' }
tasks.register('prefetch') { doLast { configurations.aapt2.resolve() } }
GRADLE
    JAVA_HOME="$STUDIO_JDK" java/gradlew -p "$WORK/prefetch" --no-daemon --quiet prefetch
fi

python3.12 tools/ci_build/github/android/build_aar_package.py \
    --build_dir "$WORK/build" --config Release \
    --android_sdk_path "$SDK" --android_ndk_path "$NDK" \
    --include_ops_by_config "$HERE/ops.config" "$HERE/settings.json"

AAR="$WORK/build/aar_out/Release/com/microsoft/onnxruntime/onnxruntime-android/$VERSION/onnxruntime-android-$VERSION.aar"
cp "$AAR" "$ROOT/app/libs/onnxruntime-android-$VERSION-pwnall.aar"
# The libraries name their source files by the build directory; README.md says what replaces it.
python3.12 -I "$HERE/neutral_paths.py" "$ROOT/app/libs/onnxruntime-android-$VERSION-pwnall.aar" "$WORK"
# Both lines go into README.md beside this script, which keeps them for the AAR that is committed.
echo "built from $ORT_TAG, commit $BUILT_FROM"
shasum -a 256 "$ROOT/app/libs/onnxruntime-android-$VERSION-pwnall.aar"
