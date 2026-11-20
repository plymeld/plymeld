#!/usr/bin/env bash
# Builds the fast-starting plymeld binary with GraalVM native-image.
# Input: the CLI's installDist jars (run `./gradlew :plymeld-cli:installDist` first).
# Output: plymeld-cli/build/native/plymeld, and on Linux the release's command-line download beside that folder,
# plymeld-cli/build/plymeld-linux-<arch>.tar.gz (a plymeld/ folder: the executable, any library native-image put beside
# it, and its lib/ folder; made here so the executable keeps its mode, which a Windows tar would not; tools/package-velopack.cmd copies it).
# Needs GRAALVM_HOME: a GraalVM for JDK 21 with native-image.
# The reachability metadata the image needs (the JDK's imaging reaches back into Java through JNI, among others) is in
# the CLI's jar (plymeld-cli/src/main/resources/META-INF/native-image), where native-image finds it; tools/native-smoke.sh
# traces it, and checks the built executable with every command.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
graal="${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM for JDK 21 with native-image}"
lib="$here/plymeld-cli/build/install/plymeld/lib"
[ -d "$lib" ] || { echo "no $lib - run ./gradlew :plymeld-cli:installDist first" >&2; exit 1; }
cp="$(ls "$lib"/*.jar | tr '\n' ':')"; cp="${cp%:}"
out="$here/plymeld-cli/build/native"
mkdir -p "$out"
"$graal/bin/native-image" --no-fallback -O2 -H:+ReportExceptionStackTraces -H:IncludeResources="config/.*\\.toml|plymeld-version\\.txt|plymeld-update-feed\\.txt" \
  --initialize-at-build-time=org.slf4j,org.apache.sshd.common.file.root.RootedFileSystemProvider \
  -cp "$cp" plymeld.cli.MainKt -o "$out/plymeld" "$@"
# the executable's own folder is its java.home (Main.kt): the JDK's fonts look in its lib/ for a font configuration
# (fontconfig.bfc, which a JDK ships where it has one, as on Windows), and ask the system's fontconfig only when lib/ is
# there; so lib/ goes beside the executable, with the JDK's file where there is one
mkdir -p "$out/lib"
[ -f "$graal/lib/fontconfig.bfc" ] && cp "$graal/lib/fontconfig.bfc" "$out/lib/"
if [ "$(uname -s)" = Linux ]; then
  case "$(uname -m)" in x86_64) arch=x64 ;; aarch64) arch=arm64 ;; *) arch="$(uname -m)" ;; esac
  tarball="$here/plymeld-cli/build/plymeld-linux-$arch.tar.gz"
  tar -czf "$tarball" -C "$here/plymeld-cli/build" --exclude='*.txt' --transform 's,^native,plymeld,' native
  echo "command-line download: $tarball"
fi
