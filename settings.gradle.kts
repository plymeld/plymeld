rootProject.name = "plymeld"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()   // the UI (plymeld-ui): Compose Multiplatform depends on some androidx artifacts published only there
    }
}

include(
    "plymeld-geom",
    "plymeld-model",
    "plymeld-program",
    "plymeld-predict",
    "plymeld-mesh",
    "plymeld-config",
    "plymeld-machine",
    "plymeld-gcode",
    "plymeld-core",
    "plymeld-printer",
    "plymeld-commands",
    "plymeld-cli",
    "plymeld-check",
    "plymeld-ui",
)
