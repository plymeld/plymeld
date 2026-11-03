// plymeld's own concept model: entities, the settings catalog, resolution with provenance.
// One dependency, the printer models' vocabulary (plymeld-machine, which depends on nothing): every other module speaks
// this vocabulary; importers map foreign files onto it.
plugins {
    id("plymeld.kotlin-conventions")
}

dependencies {
    api(project(":plymeld-machine"))
}
