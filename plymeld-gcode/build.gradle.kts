// G-code emission, post-processors, .gcode.3mf packaging.
plugins {
    id("plymeld.kotlin-conventions")
}

dependencies {
    implementation(project(":plymeld-geom"))
    implementation(project(":plymeld-machine"))
    implementation(project(":plymeld-model"))
    implementation(project(":plymeld-program"))
}
