// The desktop UI: Compose Multiplatform for the window and its controls, LWJGL (OpenGL) for the 3D view, drawn
// off-screen and shown as an image inside the Compose window.
plugins {
    id("plymeld.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// LWJGL's native libraries for the machine building it (the trial runs where it is built; packaging for other
// platforms comes with the UI's distribution)
val lwjglNatives: String = run {
    val os = System.getProperty("os.name").lowercase(); val arm = System.getProperty("os.arch") == "aarch64"
    when {
        "linux" in os -> if (arm) "natives-linux-arm64" else "natives-linux"
        "mac" in os -> if (arm) "natives-macos-arm64" else "natives-macos"
        else -> if (arm) "natives-windows-arm64" else "natives-windows"
    }
}

dependencies {
    implementation(project(":plymeld-mesh"))
    // every command the UI offers is one of plymeld-commands' (the parity test walks its model)
    implementation(project(":plymeld-commands"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)   // Velopack's release feed (Updates.kt)
    for (lib in listOf<Provider<MinimalExternalModuleDependency>>(libs.lwjgl.core, libs.lwjgl.glfw, libs.lwjgl.opengl)) {
        implementation(lib)
        runtimeOnly(variantOf(lib) { classifier(lwjglNatives) })
    }
}

compose.desktop {
    application {
        mainClass = "plymeld.ui.MainKt"
    }
}

// The user docs' index for the finder (it finds "a docs page", opened online): each page under docs/user/
// with its first heading, written as a resource; a hit opens the page at [docsBase] + its path. The base is the
// repository's; it changes here when the docs have a home of their own. No docs/user: an empty index.
val docsBase = "https://github.com/plymeld/plymeld/blob/main/"
val docsIndexDir = layout.buildDirectory.dir("generated/docs-index")
val docsIndex = tasks.register("docsIndex") {
    description = "the user docs' pages and titles, for the window's finder"
    val src = rootDir.resolve("docs/user")
    val base = docsBase
    val outDir = docsIndexDir
    inputs.files(fileTree(src)); inputs.property("base", base); outputs.dir(outDir)
    doLast {
        val out = outDir.get().file("plymeld-docs-index.txt").asFile
        out.parentFile.mkdirs()
        val lines = src.walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { it.invariantSeparatorsPath }.map { f ->
            val title = f.useLines { s -> s.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() } ?: f.nameWithoutExtension
            "docs/user/" + f.relativeTo(src).invariantSeparatorsPath + "\t" + title
        }
        out.writeText("base=$base\n" + lines.joinToString("\n") + "\n")
    }
}
sourceSets.main { resources.srcDir(docsIndexDir) }
tasks.named("processResources") { dependsOn(docsIndex) }

// The UI's jars with the WINDOWS natives (Skiko's and LWJGL's), to run on a Windows JDK from a build made elsewhere (e.g.
// under WSL2):
//   java -cp "build\windows\lib\*" plymeld.ui.MainKt ...
val windowsRuntime: Configuration by configurations.creating { extendsFrom(configurations.runtimeClasspath.get()) }
dependencies {
    windowsRuntime(compose.desktop.windows_x64)
    for (lib in listOf<Provider<MinimalExternalModuleDependency>>(libs.lwjgl.core, libs.lwjgl.glfw, libs.lwjgl.opengl)) windowsRuntime(variantOf(lib) { classifier("natives-windows") })
}
// The logo written out (Logo.kt): build/icons/ holds the SVGs, the PNGs and the Windows .ico for the installers and
// the desktop entry; docs/logo.svg (the README's) is refreshed from it, like plymeld.schema.json from `plymeld schema`.
tasks.register<JavaExec>("icons") {
    description = "the logo as SVG, PNGs (16..512) and .ico into build/icons, and docs/logo.svg"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "plymeld.ui.MainKt"
    val dir = layout.buildDirectory.dir("icons").get().asFile
    val readmeLogo = rootDir.resolve("docs/logo.svg")
    args("--icons", dir.path)
    doLast { dir.resolve("plymeld.svg").copyTo(readmeLogo, overwrite = true) }
}
// The UI's jars with THIS machine's natives, for a native-image build of the window (tools/build-native-ui.sh)
tasks.register<Copy>("nativeLib") {
    description = "the UI's jars with this machine's natives into build/native/lib, for tools/build-native-ui.sh"
    from(configurations.runtimeClasspath)
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("native/lib"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.register<Copy>("windowsLib") {
    description = "the UI's jars with Windows natives into build/windows/lib, to run on a Windows JDK"
    from(windowsRuntime)
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("windows/lib"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
