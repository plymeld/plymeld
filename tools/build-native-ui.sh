#!/usr/bin/env bash
# Builds the plymeld window as a native executable with GraalVM native-image. Two steps, because Compose Desktop (Skiko),
# LWJGL and AWT load native libraries and use reflection and JNI that native-image cannot see statically:
#   1. `agent`: run the window off-screen under the tracing agent on a job given as its arguments, which writes the
#      reachability metadata (JNI, reflection, resources, proxies) to plymeld-ui/src/main/resources/META-INF/native-image/
#      (committed; each platform's run adds its own classes and natives, so one set serves both builds; the Windows twin
#      build-native-ui.cmd runs the live window under the agent too);
#   2. `build`: native-image over the UI's jars (`./gradlew :plymeld-ui:nativeLib` first) to plymeld-ui/build/native/plymeld-ui.
# Needs GRAALVM_HOME: a GraalVM for JDK 21 with native-image.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
graal="${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM for JDK 21 with native-image}"
lib="$here/plymeld-ui/build/native/lib"
meta="$here/plymeld-ui/src/main/resources/META-INF/native-image/com.plymeld/plymeld-ui"
[ -d "$lib" ] || { echo "no $lib - run ./gradlew :plymeld-ui:nativeLib first" >&2; exit 1; }
cp="$(ls "$lib"/*.jar | tr '\n' ':')"; cp="${cp%:}"
case "${1:-build}" in
  agent)
    shift
    mkdir -p "$meta"
    # the tracing agent merges into the metadata already there; the run is a screenshot of the job given
    exec "$graal/bin/java" "-agentlib:native-image-agent=config-merge-dir=$meta,config-write-period-secs=10" -cp "$cp" plymeld.ui.MainKt "$@" ;;
  build)
    shift
    out="$here/plymeld-ui/build/native"
    # the natives Skiko and LWJGL unpack from their jars, the bundled config, the machines, the version; slf4j (MINA) at build time
    "$graal/bin/native-image" --no-fallback -O2 -H:+ReportExceptionStackTraces -H:+UnlockExperimentalVMOptions \
      -H:IncludeResources=".*\\.so|.*\\.dll|.*\\.sha256|.*\\.sha1|config/.*\\.toml|plymeld-version\\.txt" \
      -H:ConfigurationFileDirectories="$meta" --initialize-at-build-time=org.slf4j,org.apache.sshd.common.file.root.RootedFileSystemProvider \
      -cp "$cp" plymeld.ui.MainKt -o "$out/plymeld-ui" "$@"
    # Skiko's AWT bridge dlopens ${java.home}/lib/libjawt.so, and the executable sets java.home to its own directory
    # (Main.kt); native-image copies the other AWT libraries beside the executable but not this one
    mkdir -p "$out/lib"
    cp "$graal/lib/libjawt.so" "$out/lib/libjawt.so" ;;
  *) echo "usage: $0 agent [window args...] | build [native-image args...]" >&2; exit 2 ;;
esac
