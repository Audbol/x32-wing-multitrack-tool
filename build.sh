#!/usr/bin/env bash
# Builds the X32 / M32 / WING Multitrack Tool on Linux (or macOS for the jar). Needs a JDK 17+.
#   ./build.sh            dist/X32WingTool-<ver>.jar
#   ./build.sh --exe      + dist/X32WingTool/ (self-contained app with its own Java runtime)
#   ./build.sh --release  + .tar.gz, .deb and .rpm (needs fakeroot for deb, rpm-build/rpm for rpm)
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-}"
VERSION=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' src/x32wing/Main.java)
[ -n "$VERSION" ] || { echo "Could not read VERSION from Main.java" >&2; exit 1; }
BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"

# FlatLaf (Apache-2.0), pinned by version and SHA-256, fetched from Maven Central once.
FLATLAF_VERSION=3.7.2
FLATLAF_SHA256=917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4
FLATLAF="lib/flatlaf-$FLATLAF_VERSION.jar"
mkdir -p lib
if [ ! -f "$FLATLAF" ]; then
    echo "Downloading FlatLaf $FLATLAF_VERSION from Maven Central"
    curl -fsSL -o "$FLATLAF" "https://repo1.maven.org/maven2/com/formdev/flatlaf/$FLATLAF_VERSION/flatlaf-$FLATLAF_VERSION.jar"
fi
if ! echo "$FLATLAF_SHA256  $FLATLAF" | sha256sum -c --status - 2>/dev/null; then
    rm -f "$FLATLAF"; echo "FlatLaf checksum mismatch - download deleted" >&2; exit 1
fi

rm -rf build dist
mkdir -p build/classes/x32wing build/jpkg build/deps dist
find src -name '*.java' > build/sources.txt
"${BIN}javac" --release 17 -encoding UTF-8 -Xlint:all,-serial -cp "$FLATLAF" -d build/classes @build/sources.txt
cp packaging/icon-512.png build/classes/x32wing/icon.png
# Merge FlatLaf into one runnable jar (its module-info and manifest are dropped; licence kept).
(cd build/deps && "${BIN}jar" --extract --file "../../$FLATLAF")
rm -f build/deps/module-info.class build/deps/META-INF/MANIFEST.MF
find build/deps/META-INF/versions -name module-info.class -delete 2>/dev/null || true
mv build/deps/META-INF/LICENSE build/deps/META-INF/LICENSE-FlatLaf.txt
cp -r build/deps/. build/classes/
printf 'Multi-Release: true\nEnable-Native-Access: ALL-UNNAMED\nImplementation-Version: %s\n' "$VERSION" > build/manifest.txt
"${BIN}jar" --create --file build/jpkg/X32WingTool.jar --manifest build/manifest.txt --main-class x32wing.Main -C build/classes .
cp build/jpkg/X32WingTool.jar "dist/X32WingTool-$VERSION.jar"
echo "Built dist/X32WingTool-$VERSION.jar"
[ "$MODE" = "--exe" ] || [ "$MODE" = "--release" ] || exit 0

COMMON=(--name X32WingTool --input build/jpkg --main-jar X32WingTool.jar --main-class x32wing.Main
        --app-version "$VERSION" --vendor Audbol --copyright "Copyright (c) 2026 Audbol, MIT License"
        --description "Multitrack tool for Behringer X32/M32 X-Live and WING-LIVE recordings"
        --icon packaging/icon-512.png --add-launcher x32wing=packaging/cli-launcher.properties
        --java-options -Xmx2g --java-options --enable-native-access=ALL-UNNAMED
        --add-modules java.base,java.desktop,java.prefs)

"${BIN}jpackage" --type app-image "${COMMON[@]}" --dest dist
echo "Built dist/X32WingTool/ (bin/X32WingTool = app, bin/x32wing = command line)"
[ "$MODE" = "--release" ] || exit 0

ARCH=$(uname -m)
cp README.md LICENSE THIRD-PARTY-NOTICES.md dist/X32WingTool/
tar -C dist -czf "dist/X32WingTool-$VERSION-linux-$ARCH.tar.gz" X32WingTool
echo "Built dist/X32WingTool-$VERSION-linux-$ARCH.tar.gz"

# packaging/linux overrides jpackage's menu entry, Debian maintainer scripts and RPM spec.
LINUX=(--linux-package-name x32wingtool --linux-app-category sound --linux-menu-group "AudioVideo;Audio;"
       --linux-shortcut --about-url https://github.com/Audbol/x32-wing-multitrack-tool --license-file LICENSE
       --app-content README.md,LICENSE,THIRD-PARTY-NOTICES.md --resource-dir packaging/linux)
"${BIN}jpackage" --type deb "${COMMON[@]}" "${LINUX[@]}" --linux-deb-maintainer "1499026+Audbol@users.noreply.github.com" --dest dist
"${BIN}jpackage" --type rpm "${COMMON[@]}" "${LINUX[@]}" --linux-rpm-license-type MIT --dest dist

(cd dist && sha256sum *.jar *.tar.gz *.deb *.rpm > SHA256SUMS.txt)
echo "Release assets:"
ls -l dist | grep -v '^d'
