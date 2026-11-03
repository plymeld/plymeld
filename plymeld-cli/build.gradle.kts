// The command-line front end: `main` over plymeld-commands (every command lives there, so the UI builds from the same
// tree).
plugins {
    id("plymeld.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":plymeld-commands"))
}

application {
    mainClass.set("plymeld.cli.MainKt")
    applicationName = "plymeld"
    // C2 as the top JIT tier, not Graal's: on a GraalVM JDK, Graal's JIT was seen to leave the hottest geometry
    // uncompiled in some launches (minutes instead of seconds), and C2 was faster on the measured job anyway.
    // JAVA_OPTS comes after these, so it can override.
    // UnlockExperimentalVMOptions: on a stock OpenJDK 21 UseJVMCICompiler is an EXPERIMENTAL flag, which
    // IgnoreUnrecognizedVMOptions does not cover: without the unlock the JVM refuses to start.
    applicationDefaultJvmArgs = listOf("-XX:+IgnoreUnrecognizedVMOptions", "-XX:+UnlockExperimentalVMOptions", "-XX:-UseJVMCICompiler")
}
