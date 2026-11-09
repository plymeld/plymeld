// Every command plymeld has, declared once: the command tree the CLI parses
// and the UI builds its controls from, the command model read from that tree (`plymeld commands`), and the one
// entry that runs a command line in-process. plymeld-cli is the parser's `main` over it; plymeld-ui the window over it.
plugins {
    id("plymeld.kotlin-conventions")
}

dependencies {
    api(libs.clikt)
    api(project(":plymeld-core"))
    api(project(":plymeld-config"))
    // Progress (the window draws it, the commands run with it) lives in plymeld-program
    api(project(":plymeld-program"))
    implementation(project(":plymeld-mesh"))
    implementation(project(":plymeld-geom"))
    implementation(project(":plymeld-printer"))
    implementation(project(":plymeld-check"))
    // the journal of edits is a JSON file (the approved JSON library, as plymeld-config's schema uses it); tests read `--explain-json` back with it too
    implementation(libs.kotlinx.serialization.json)
}

// The repository files outside this module that its tests read (DocsConsistencyTest: the published Markdown; the
// calibration tests: the test config in test-models/config), declared as the tests' inputs: a change to one runs them
// again, and they are never skipped as up to date while the text has changed (the commit check,
// tools/git-hooks/pre-commit, trusts Gradle's up-to-date checks); every test task's (test, and the commit check's
// fastTest / slowTest)
tasks.withType<Test>().configureEach {
    // files, not trees, at the root and in a module: a tree there would take in build outputs other tasks write
    inputs.files(rootDir.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty().toList() + rootDir.resolve("plymeld-geom/DESIGN.md"))
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("rootDocs")
    inputs.files(listOf("docs", "tools", "test-models").map { d -> fileTree(rootDir.resolve(d)) { include("**/*.md", "**/*.toml") } })
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("docs")
}

// Bake the project version into a resource the commands read at runtime (PlymeldVersion), and the release feed the
// update check asks (UpdateFeed.bakedFeed): -Pplymeld.feed, else PLYMELD_FEED in Gradle's environment (the release
// workflow sets it), else none, as in a development build, which never checks.
// The closure captures locals, not the script object — the configuration cache
// refuses to serialize script references.
tasks.processResources {
    val v = project.version.toString()
    val feed = providers.gradleProperty("plymeld.feed").orElse(providers.environmentVariable("PLYMELD_FEED")).getOrElse("")
    inputs.property("version", v)
    inputs.property("feed", feed)
    filesMatching("plymeld-version.txt") {
        expand("version" to v)
    }
    filesMatching("plymeld-update-feed.txt") {
        expand("feed" to feed)
    }
}
