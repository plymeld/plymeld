# Third-party notices

plymeld's packages include the libraries below. Each keeps its own license; plymeld's own license is in
[LICENSE](LICENSE) and [NOTICE](NOTICE).

**Draft.** The licenses are the ones each library's published POM declares, read for the versions listed. The
dependency license audit before release checks them against the packages as built, transitive dependencies included,
and the native parts listed at the end.

## Libraries

| Library | Version | Used by | License |
|---|---|---|---|
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.4.20 | CLI, window | Apache-2.0 |
| kotlinx.serialization core and JSON (`org.jetbrains.kotlinx`) | 1.11.0 | CLI, window | Apache-2.0 |
| kotlinx.coroutines core (`org.jetbrains.kotlinx`) | 1.9.0 | window | Apache-2.0 |
| kotlinx-datetime (`org.jetbrains.kotlinx`) | 0.7.1 | window | Apache-2.0 |
| atomicfu (`org.jetbrains.kotlinx`) | 0.28.0 | window | Apache-2.0 |
| JetBrains annotations (`org.jetbrains:annotations`) | 13.0 (CLI), 23.0.0 (window) | CLI, window | Apache-2.0 |
| Clikt (`com.github.ajalt.clikt`) | 5.1.0 | CLI, window | Apache-2.0 |
| Mordant (`com.github.ajalt.mordant`) | 3.0.2 | CLI, window | Apache-2.0 |
| Colormath (`com.github.ajalt.colormath`) | 3.6.0 | CLI, window | MIT |
| tomlj (`org.tomlj:tomlj`) | 1.1.1 | CLI, window | Apache-2.0 |
| ANTLR 4 runtime (`org.antlr:antlr4-runtime`, used by tomlj) | 4.11.1 | CLI, window | BSD-3-Clause |
| Checker Framework qualifiers (`org.checkerframework:checker-qual`) | 3.21.2 | CLI, window | MIT |
| Apache MINA SSHD (`org.apache.sshd:sshd-core`, `sshd-common`) | 2.20.0 | CLI, window | Apache-2.0 (NOTICE below) |
| SLF4J API (`org.slf4j:slf4j-api`) | 1.7.36 | CLI, window | MIT |
| SLF4J's Commons Logging bridge (`org.slf4j:jcl-over-slf4j`) | 1.7.36 | CLI, window | Apache-2.0 |
| JNA (`net.java.dev.jna:jna`) | 5.14.0 | CLI, window | LGPL-2.1-or-later or Apache-2.0, either at the user's choice |
| Compose Multiplatform: runtime, UI, foundation, animation, Material, Material 3, desktop (`org.jetbrains.compose.*`) | 1.12.1 (Material 3: 1.9.0) | window | Apache-2.0 |
| AndroidX libraries Compose uses: annotation, collection, arch core, Compose runtime, lifecycle, saved state, navigation event (`androidx.*`, `org.jetbrains.androidx.*`) | as resolved by Compose 1.12.1 | window | Apache-2.0 |
| Skiko (`org.jetbrains.skiko`) | 0.150.1 | window | Apache-2.0 |
| JetBrains Runtime API (`org.jetbrains.runtime:jbr-api`) | 1.9.0 | window | Apache-2.0 |
| JSpecify (`org.jspecify:jspecify`) | 1.0.0 | window | Apache-2.0 |
| LWJGL: core, GLFW, OpenGL (`org.lwjgl`) | 3.4.3 | window | BSD-3-Clause |

## Notices passed on

Apache-2.0 asks that a library's NOTICE file travel with it. Of the libraries above, only Apache MINA SSHD ships one:

```
Apache MINA SSHD
Copyright 2008-2026 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

(`sshd-common`'s NOTICE is the same, with "Copyright 2018-2026".)

## Not covered yet

- **Native libraries inside the jars**: Skiko carries Skia and the libraries Skia builds with; LWJGL carries GLFW. Their
  own licenses and notices are to be listed, for each platform's package.
- **The release executables**: built with GraalVM Native Image, which puts parts of GraalVM into each executable. Which
  GraalVM distribution builds the releases is still open, and its terms decide what goes here.
- **The installer and updater**: Velopack's launcher and update program, which the Windows installer and the AppImage
  contain.
