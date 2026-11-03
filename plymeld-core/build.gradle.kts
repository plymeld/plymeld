// Pipeline orchestration: Mesh -> Layers -> Regions -> Paths -> GCode.
plugins {
    id("plymeld.kotlin-conventions")
}

dependencies {
    implementation(project(":plymeld-geom"))
    implementation(project(":plymeld-mesh"))
    implementation(project(":plymeld-config"))
    implementation(project(":plymeld-model"))
    implementation(project(":plymeld-program"))
    api(project(":plymeld-machine"))
    implementation(project(":plymeld-predict"))
    // api: core's results carry the writer's measures (Predict.Prediction.strings, IncrementalSlicer.lastStrings: StringRisk.Result)
    api(project(":plymeld-gcode"))
}
