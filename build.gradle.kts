// Root project: holds nothing but the shared version. Modules apply `plymeld.kotlin-conventions`.
// The version is a release's own, from its tag (the release workflow passes -Pplymeld.version=0.YYYYMMDD.N); every
// other build is 0.0.0-dev.
allprojects {
    group = "com.plymeld"
    version = providers.gradleProperty("plymeld.version").getOrElse("0.0.0-dev")
}
