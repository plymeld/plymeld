// Reference harness: g-code parser/virtual printer, comparators.
plugins {
    id("plymeld.kotlin-conventions")
}

dependencies {
    implementation(project(":plymeld-core"))
    implementation(project(":plymeld-mesh"))
    implementation(project(":plymeld-geom"))
    implementation(project(":plymeld-config"))
    implementation(project(":plymeld-predict"))
    implementation(project(":plymeld-program"))
    implementation(project(":plymeld-gcode"))
}
