plugins {
    `kotlin-dsl`
}

// Precompiled convention plugins need the Kotlin Gradle plugin on their classpath.
// The version comes from the shared catalog so there is exactly one place to bump it.
fun plugin(p: Provider<PluginDependency>) = p.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(plugin(libs.plugins.kotlin.jvm))
    // the UI module's (plymeld-ui): one classpath with the Kotlin plugin, so the versions cannot drift apart
    implementation(plugin(libs.plugins.kotlin.compose))
    implementation(plugin(libs.plugins.compose.multiplatform))
}
