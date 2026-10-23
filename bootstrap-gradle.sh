#!/bin/sh
# bootstrap-gradle.sh — robustly provisions the Gradle wrapper jar.
#
# The gradle-wrapper.jar is a binary file (~63 KB) that can't be checked in
# via text. This script ensures it exists by trying, in order:
#   1. Use a system-installed Gradle (>= 8.0) to regenerate the wrapper
#   2. Download the canonical jar from the Gradle GitHub release
#   3. Download the full Gradle 8.7 distribution, extract, and use it once
#
# POSIX sh only — must work when invoked via dash (Debian's /bin/sh).
# Run this once before invoking ./gradlew.

set -e
cd "$(dirname "$0")"

JAR="gradle/wrapper/gradle-wrapper.jar"
PROPS="gradle/wrapper/gradle-wrapper.properties"

jar_present() {
    if [ -f "$JAR" ]; then
        SIZE=$(wc -c < "$JAR" 2>/dev/null || echo 0)
        [ "${SIZE:-0}" -gt 1000 ]
    else
        false
    fi
}

if jar_present; then
    echo "[bootstrap] $JAR already present ($SIZE bytes)."
    exit 0
fi

mkdir -p gradle/wrapper

# --- Option 1: use system gradle (only if it's recent enough) --------------
if command -v gradle >/dev/null 2>&1; then
    GV=$(gradle --version 2>/dev/null | awk '/Gradle / {print $2; exit}')
    echo "[bootstrap] Found system gradle: $GV"

    # Extract major version (everything before first dot). Need >= 8 for AGP 8.5.
    MAJOR=$(echo "$GV" | cut -d. -f1)
    if [ -n "$MAJOR" ] && [ "$MAJOR" -ge 8 ] 2>/dev/null; then
        echo "[bootstrap] Regenerating wrapper with system gradle $GV..."
        gradle wrapper --gradle-version 8.7 --distribution-type bin || true
        if jar_present; then
            echo "[bootstrap] Wrapper jar created via system gradle."
            exit 0
        fi
    else
        echo "[bootstrap] System gradle is too old ($GV, need >= 8.0). Skipping."
    fi
fi

# --- Option 2: download the wrapper jar directly from Gradle's GitHub -----
URL1="https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"
URL2="https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar"

echo "[bootstrap] Downloading wrapper jar from a reliable source..."

download_to() {
    # download_to URL DEST — tries curl then wget. Returns 0 on success.
    _url="$1"
    _dest="$2"
    if command -v curl >/dev/null 2>&1; then
        if curl -fsSL "$_url" -o "$_dest"; then
            return 0
        fi
    fi
    if command -v wget >/dev/null 2>&1; then
        if wget -q "$_url" -O "$_dest"; then
            return 0
        fi
    fi
    return 1
}

for URL in "$URL1" "$URL2"; do
    echo "[bootstrap] Trying: $URL"
    if download_to "$URL" "$JAR.tmp"; then
        mv "$JAR.tmp" "$JAR"
        if jar_present; then
            echo "[bootstrap] Got wrapper jar via download ($SIZE bytes)."
            exit 0
        fi
    fi
done

# --- Option 3: download the full Gradle 8.7 distribution, extract, use --
DIST_URL="https://services.gradle.org/distributions/gradle-8.7-bin.zip"
echo "[bootstrap] Falling back to: download full Gradle 8.7 distribution and use it once."
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT INT TERM

if ! download_to "$DIST_URL" "$TMPDIR/gradle.zip"; then
    echo "[bootstrap] ERROR: need curl or wget, or install Gradle >= 8.0 and re-run."
    exit 1
fi

echo "[bootstrap] Extracting Gradle 8.7..."
if ! unzip -q "$TMPDIR/gradle.zip" -d "$TMPDIR"; then
    echo "[bootstrap] ERROR: unzip failed."
    exit 1
fi

GRADLE_BIN="$TMPDIR/gradle-8.7/bin/gradle"
if [ ! -x "$GRADLE_BIN" ]; then
    echo "[bootstrap] ERROR: extracted Gradle binary not found at $GRADLE_BIN"
    exit 1
fi

echo "[bootstrap] Generating wrapper with Gradle 8.7..."
"$GRADLE_BIN" wrapper --gradle-version 8.7 --distribution-type bin || true

if jar_present; then
    echo "[bootstrap] Wrapper jar created via Gradle 8.7 ($SIZE bytes)."
    exit 0
fi

echo "[bootstrap] ERROR: Could not obtain the wrapper jar by any method."
echo "[bootstrap] Manual fix: download gradle-8.7-bin.zip from https://services.gradle.org/distributions/"
echo "[bootstrap]                extract, then run gradle-8.7/bin/gradle wrapper --gradle-version 8.7 --distribution-type bin"
exit 1
