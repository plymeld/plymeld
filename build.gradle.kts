// Root project: holds nothing but the shared version. Modules apply `plymeld.kotlin-conventions`.
allprojects {
    group = "com.plymeld"
    version = providers.gradleProperty("plymeld.version").getOrElse("0.0.0-dev")
}
