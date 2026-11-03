// Shared build conventions for every plymeld module. Applied as `id("plymeld.kotlin-conventions")`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findBundle("kotest").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Test classes run in parallel JVMs (one JVM per module took 15-25 min, two thirds of it a dozen tests that slice
    // real models, one class after another). Three forks per module, each at the 512 MB Gradle gives one fork, so a
    // test that needs more memory still fails here rather than only on a smaller machine; modules already run in
    // parallel (org.gradle.parallel), so this is bounded by memory, not processors.
    maxParallelForks = 3
    // -Pplymeld.testHeap=6g for a development run on a large model (a survey over millions of triangles); 512m otherwise
    maxHeapSize = (project.findProperty("plymeld.testHeap") as String?) ?: "512m"
    // C2 as the top JIT tier, as the CLI ships (plymeld-cli's applicationDefaultJvmArgs). Under GraalVM's own JIT a
    // test JVM was seen failing nearly every top-tier compile (4.4 million bailouts against 3,300 compiles,
    // ~18,000 a second), so the slicing code ran at C1 speed: ToolChangeGcodeTest went from 16.5 s to over 10 minutes.
    jvmArgs("-XX:+IgnoreUnrecognizedVMOptions", "-XX:+UnlockExperimentalVMOptions", "-XX:-UseJVMCICompiler")
    // the per-layer cache (LayerCache) small in a test JVM: its 768 MB default is for a window's heap, and a slicing test
    // ran out of the 512 MB with it; 48 MB still exercises it (LayerCacheTest)
    systemProperty("plymeld.layerCache.mb", "48")
    // Tests tagged LocalFixtures replay crash dumps a developer puts in test-models/crash/ (git-ignored,
    // CrashReplayTest). OFF by default; on with -Pplymeld.localFixtures.
    if (!project.hasProperty("plymeld.localFixtures")) systemProperty("kotest.tags", "!LocalFixtures")
    // Review pictures (plymeld vs another slicer) go to build/renders/, never into the repo; -Pplymeld.refreshRenders
    // rewrites the committed ones in docs/renders/ instead (plymeld-check's ReviewRenders.kt).
    if (project.hasProperty("plymeld.refreshRenders")) systemProperty("plymeld.refreshRenders", "true")
    outputs.dir(project.layout.buildDirectory.dir("renders"))
    // A test JVM's home is under build/, and so are plymeld's own folders (PLYMELD_HOME, UserDirs: the search log, a line
    // per technique decision read later for how often the search short-circuits in real use, and the window's files), so
    // the tests' decisions are never counted and `~/` in a test's config stays inside the build. PLYMELD_HOME also keeps a
    // developer's XDG_* or APPDATA variables from sending a test's files to their real folders.
    val home = project.layout.buildDirectory.dir("test-home")
    doFirst { home.get().asFile.mkdirs() }
    systemProperty("user.home", home.get().asFile.absolutePath)
    environment("PLYMELD_HOME", home.get().dir("plymeld").asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
    }
}

// The tests in two parts: `fastTest` is every test but the suites listed in
// tools/slow-suites.txt; `slowTest` is those suites alone. CI (.github/workflows/ci.yml) runs one, then the other; locally
// they run only when asked (PLYMELD_COMMIT_CHECK=tests for tools/git-hooks/pre-commit, PLYMELD_SLOW_CHECK=1 for
// tools/git-hooks/post-commit and tools/slow-check.sh). `test` stays everything. The two share `test`'s classes, classpath
// and settings, and read the list as an input (a change to it runs them again).
val slowSuitesFile = rootProject.file("tools/slow-suites.txt")
val slowSuites: List<String> = if (slowSuitesFile.exists())
    slowSuitesFile.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() } else emptyList()
val allTests = tasks.named<Test>("test")
tasks.register<Test>("fastTest") {
    description = "Every test but the slow suites (tools/slow-suites.txt): CI's first part"
    group = "verification"
    testClassesDirs = allTests.get().testClassesDirs
    classpath = allTests.get().classpath
    if (slowSuitesFile.exists()) inputs.file(slowSuitesFile).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("slowSuites")
    filter { slowSuites.forEach { excludeTestsMatching(it) }; isFailOnNoMatchingTests = false }
}
tasks.register<Test>("slowTest") {
    description = "The slow suites alone (tools/slow-suites.txt): CI's second part"
    group = "verification"
    testClassesDirs = allTests.get().testClassesDirs
    classpath = allTests.get().classpath
    if (slowSuitesFile.exists()) inputs.file(slowSuitesFile).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("slowSuites")
    filter { slowSuites.forEach { includeTestsMatching(it) }; isFailOnNoMatchingTests = false }
    // a plain value, not the script's list: a closure holding a script object cannot be stored in the configuration cache
    // (the first slow run failed on it, "cannot serialize Gradle script object references")
    val any = slowSuites.isNotEmpty()
    onlyIf { any }
}
