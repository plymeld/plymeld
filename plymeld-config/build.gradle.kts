// plymeld's config files (TOML / JSON, units, and layering), bundled filament definitions;
// importers from QIDIStudio / Orca / Flash Studio settings, for investigation only.
plugins {
    id("plymeld.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":plymeld-model"))
    // the bundled machine models and g-code dialects, the valid values of hardware.machine.<key>.model / firmware.dialect
    implementation(project(":plymeld-machine"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlj)
}

// The repository files outside this module that its tests read (SamplesTest: plymeld.schema.json is what
// `plymeld schema` writes, and the test config in test-models/config reads cleanly), declared as the tests' inputs: a
// change to one runs them again, and they are never skipped as up to date while the text has changed (the commit check,
// tools/git-hooks/pre-commit, trusts Gradle's up-to-date checks); every test task's (test, and the commit check's
// fastTest / slowTest)
tasks.withType<Test>().configureEach {
    inputs.file(rootDir.resolve("plymeld.schema.json")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("schemaJson")
    inputs.files(fileTree(rootDir.resolve("test-models/config")) { include("*.toml") }).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("testConfig")
}
