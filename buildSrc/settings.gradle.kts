dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()   // Compose Multiplatform's Gradle plugin reaches some androidx artifacts published only there
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
