package plymeld.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.EncodedImageFormat
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path

/**
 * plymeld-ui: the window on a job (the arguments are listed in main), empty when started with
 * no arguments. Besides the window:
 *   plymeld-ui --bench [--gcode FILE | --segments N] [--size WxH] [--frames N] [--png OUT.png]   the 3D view's speed (Bench)
 *   plymeld-ui --bench-edit -cf FILE... -m FILE [--height MM]      the edit cycle timed (open, slice, lock a layer height, slice again)
 *   plymeld-ui --gl-info [--gl-platform x11|wayland]
 */
fun main(args: Array<String>) {
    // Velopack's install, update, and uninstall hooks (Updates.kt): the command line's PATH entry (CliPath.kt), then exit at
    // once, as the updater waits for it
    if (Updates.isHook(args)) { CliPath.hook(args); return }
    // A native executable has no java.home, and Skiko's AWT bridge loads ${java.home}/lib/libjawt.so
    // (bin\jawt.dll on Windows) from it, so it is the executable's own directory: the native build scripts put the
    // library there. A JVM run has its java.home and is left alone.
    if (System.getProperty("java.home") == null) ProcessHandle.current().info().command().orElse(null)
        ?.let { Path.of(it).toAbsolutePath().parent?.toString() }?.let { System.setProperty("java.home", it) }
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    fun opts(name: String): List<String> = args.indices.filter { args[it] == name && it + 1 < args.size }.map { args[it + 1] }
    val platform = opt("--gl-platform")
    if ("--gl-info" in args) { GlContext.open(platform).use { println(it.describe) }; return }
    // --icons DIR: the logo written out (SVG, PNGs, .ico) for the README, the desktop entry, and the installers (Logo.kt)
    opt("--icons")?.let { dir -> for (p in Logo.writeIcons(Path.of(dir))) println("icon: $p"); return }
    val (w, h) = (opt("--size") ?: "1600x1000").split('x').map { it.toInt() }
    // the window: a job as `plymeld plan` takes it: -cf FILE... -m FILE... [--machine KEY]
    //   [--slice]: slice and check it too (step 5), before a screenshot or as the window opens
    //   [--tab model|plan|layers] [--why] [--console]: what the window shows first (a screenshot of the Plan tab, the drawer, the console)
    //   [--theme dark|light]: the colors (otherwise the theme last chosen in the window, kept in plymeld's config folder)
    // With no arguments at all (a shortcut, the installed app) the window opens empty, waiting for a model.
    if ("--bench" !in args) {
        // started with no console (the Windows executable is a GUI program; a shortcut; a double-click): what the
        // program and the JDK print goes to a log file beside the window's other files, not nowhere
        if (System.console() == null && opt("--screenshot") == null && "--bench-edit" !in args) logToFile()
        Ink.load(); opt("--theme")?.let { Ink.dark = it != "light" }
        // --bench-edit [--height MM]: the edit cycle timed through the window's own path (open, slice, lock a layer height, slice again)
        if ("--bench-edit" in args) { BenchEdit.run(opts("-cf").ifEmpty { JobState.LastJob.read()?.configFiles ?: emptyList() }, opts("-m"), opt("--machine"), platform, opt("--height") ?: "0.16"); return }
        // a model alone: with the last job's config files (a convenience the window keeps; missing files and plan files left
        // out, the model's own plan file added: JobState.configsFor), or with none
        val (cfs, startNotes) = if (opts("-cf").isNotEmpty()) opts("-cf") to emptyList()
            else JobState.configsFor(opts("-m"), JobState.LastJob.read()?.configFiles ?: emptyList())
        val look: (JobState) -> Unit = { j ->
            val s = j.viewer
            opt("--view")?.let { v -> s.preset(Camera.View.valueOf(v.uppercase())) }
            if ("--ortho" in args) s.useOrthographic(true)
            if ("--single" in args) s.useSingleLayer(true)
            opt("--layers")?.let { r -> val (lo, hi) = r.split(':').map { it.toInt() - 1 }; s.setLayers(lo, hi) }
            opt("--color")?.let { c -> if (c == "tool") j.colorByTool() else s.useColorBy(ColorBy.valueOf(c.uppercase())) }
            if ("--travels" in args) s.useTravels(true)
            opt("--moves")?.let { s.moveTo(it.toInt()) }
        }
        //   [--find WORDS]: the finder open with WORDS typed
        //   [--link WORDS]: a step's link followed once the job is in (WindowUi.follow: "Turn or move", "Another printer", ...)
        //   [--pick-machine KEY]: step 2's choice of machine picked (WindowUi.pickMachine), for a picture of the job on it
        window(cfs, opts("-m"), opt("--machine"), "--slice" in args, opt("--screenshot")?.let { Path.of(it) }, w, h, platform,
            opt("--tab")?.let { StageTab.valueOf(it.uppercase()) } ?: StageTab.MODEL, "--why" in args, "--console" in args, look, opt("--find"), opt("--setting"), opt("--lock-height"), "--ask-printer" in args, opt("--tray")?.let { TrayTab.valueOf(it.uppercase()) },
            opt("--link"), opt("--pick-machine"), startNotes)
        return
    }
    // --bench: the 3D view's speed on a g-code file's toolpaths, or on synthetic rings of --segments lines
    val gcode = opt("--gcode")?.let { Path.of(it) }
    Viewport3D(platform).use { vp ->
        val t = System.nanoTime()
        val p = gcode?.let { GcodeToolpaths.read(it) } ?: Synthetic.toolpaths(opt("--segments")?.toInt() ?: 1_000_000)
        val parseMs = (System.nanoTime() - t) / 1e6
        val label = gcode?.let { "$it (read in %.0f ms)".format(java.util.Locale.ROOT, parseMs) } ?: "synthetic rings (not a print)"
        Bench.run(vp, p, label, w, h, opt("--frames")?.toInt() ?: 120, opt("--png")?.let { Path.of(it) })
    }
}

/**
 * The window on a job: opened live, or drawn off-screen to [screenshot] once the job is
 * planned (and sliced, with [slice]), for review where no screen can be watched.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun window(configFiles: List<String>, modelFiles: List<String>, machine: String?, slice: Boolean, screenshot: Path?, w: Int, h: Int, platform: String?,
                   tab: StageTab = StageTab.MODEL, why: Boolean = false, console: Boolean = false, look: ((JobState) -> Unit)? = null, find: String? = null, setting: String? = null,
                   lockHeight: String? = null, askPrinter: Boolean = false, trayTab: TrayTab? = null, link: String? = null, pickMachine: String? = null,
                   notes: List<String> = emptyList()) {
    if (screenshot != null) {
        Viewport3D(platform).use { vp ->
            val j = JobState(ViewerState(vp))
            notes.forEach { j.note(it) }
            // the buttons wired as in the live window, so the picture shows them as they are there
            val ui = WindowUi(tab, why, console || trayTab != null, find, setting).also { u -> trayTab?.let { u.trayTab = it } }
            val scene = ImageComposeScene(w, h, Density(1f)) { WindowApp(j, ui, onOpen = {}, onSlice = {}, onSave = {}, onSend = { j.send() }, onAddModel = {}, onAddConfig = {}) }
            try {
                scene.render()                                   // lays out: the view learns its size
                val planned = java.util.concurrent.CountDownLatch(1)
                j.open(configFiles, modelFiles, machine) { planned.countDown() }
                planned.await()
                // --ask-printer: step 2 live (status, loadout, camera), its refusal when the machine has no connection
                if (askPrinter) { val l = java.util.concurrent.CountDownLatch(1); j.askPrinter { l.countDown() }; l.await() }
                // --lock-height MM: the first object's flat layers locked at MM through the drawer's own path (for a picture of an edit)
                lockHeight?.let { mm -> val obj = j.marks?.objectOf?.values?.firstOrNull(); if (obj != null) { val l = java.util.concurrent.CountDownLatch(1); j.lockHeight(obj, mm) { l.countDown() }; l.await() } }
                // --pick-machine KEY: the job opened again on KEY through step 2's choice
                pickMachine?.let { k -> val l = java.util.concurrent.CountDownLatch(1); ui.pickMachine(j, k) { l.countDown() }; l.await() }
                if (slice) { val sliced = java.util.concurrent.CountDownLatch(1); j.slice(j.sliceOut()) { sliced.countDown() }; sliced.await() }
                // --link WORDS: a step's link followed, as its click does (an unknown link is refused, not ignored)
                link?.let { l -> androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { require(ui.follow(l)) { "--link: no step link '$l'" } } }
                // the Layers tab's view for the picture (--view, --ortho, --layers LO:HI, --single, --color, --travels)
                look?.let { it(j) }
                j.viewer.fit()
                j.viewer.awaitFrames()
                for (t in 1..3) scene.render(t * 100_000_000L)
                val img = scene.render(1_000_000_000L)
                Files.createDirectories(screenshot.toAbsolutePath().parent)
                Files.write(screenshot, img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("screenshot: $screenshot")
            } finally { scene.close() }
        }
        return
    }
    application {
        val state = remember {
            JobState(ViewerState(Viewport3D(platform))).also { j ->
                notes.forEach { j.note(it) }
                // fit once the job is in: the view may not have had its size when the model was loaded
                j.open(configFiles, modelFiles, machine) { j.viewer.fit(); if (slice) j.slice(j.sliceOut()) { j.viewer.fit() } }
            }
        }
        val ui = remember { WindowUi(tab, why, console || trayTab != null, find, setting).also { u -> trayTab?.let { u.trayTab = it } } }
        // the window's chords (Ctrl+K the finder, Ctrl+J the tray, Ctrl+D the drawer, Ctrl+Z / Ctrl+Shift+Z undo and redo), Escape
        // closes the finder or the drawer; the viewer's keys only while the finder is not taking the typing
        Window(onCloseRequest = ::exitApplication, title = "plymeld", icon = remember { Logo.IconPainter() }, state = rememberWindowState(width = 1600.dp, height = 1000.dp),
            onKeyEvent = { e -> e.type == KeyEventType.KeyDown && when {
                ui.chord(e, state) -> true
                e.key == Key.Escape -> ui.escape()
                ui.finderOpen -> false
                else -> key(state.viewer, e)
            } }) {
            // the dialogs offer only the files they take (a filter by extension)
            fun pick(title: String, mode: Int, extensions: List<String>, then: (Path) -> Unit) {
                val d = FileDialog(window, title, mode)
                d.setFilenameFilter { _, n -> extensions.any { n.endsWith(".$it", ignoreCase = true) } }
                d.isVisible = true
                val f = d.file ?: return
                then(Path.of(d.directory, f))
            }
            WindowApp(state, ui,
                // a model opens the job on it; a .gcode is shown to look at (JobState.previewGcode)
                onOpen = { pick("Open a model (.stl, .3mf) or a sliced file (.gcode)", FileDialog.LOAD, listOf("stl", "3mf", "gcode")) { p ->
                    if (p.toString().endsWith(".gcode", ignoreCase = true)) state.previewGcode(p) else state.openModel(p.toString()) } },
                onAddConfig = { pick("Add a config file (.toml)", FileDialog.LOAD, listOf("toml")) { p -> state.addConfig(p.toString()) } },
                onAddModel = { pick("Add a model to the plate (.stl, .3mf)", FileDialog.LOAD, listOf("stl", "3mf")) { p -> state.addModel(p.toString()) } },
                // a model dropped on the stage joins the plate (or opens the job when none is open); a config file joins the job;
                // a .gcode is shown to look at
                onDrop = { uris -> for (u in uris) {
                    val p = (if (u.startsWith("file:")) Path.of(java.net.URI(u)) else Path.of(u)).toString()
                    when (p.substringAfterLast('.').lowercase()) {
                        "stl", "3mf" -> state.addModel(p); "toml" -> state.addConfig(p); "gcode" -> state.previewGcode(Path.of(p))
                        else -> state.note("dropped $p: not a model (.stl, .3mf), a config file (.toml) or a sliced file (.gcode)")
                    }
                } },
                // the job as open now (not the models the window was started with)
                onSlice = { state.slice(state.sliceOut()) },
                // the file's own name offered, .gcode only; copied off the UI thread, a failure said in the console
                onSave = { state.gcode?.let { g ->
                    val d = FileDialog(window, "Save the g-code", FileDialog.SAVE)
                    d.file = g.fileName.toString(); d.setFilenameFilter { _, n -> n.endsWith(".gcode", ignoreCase = true) }
                    d.isVisible = true
                    val f = d.file
                    if (f != null) {
                        val p = Path.of(d.directory, if (f.endsWith(".gcode", ignoreCase = true)) f else "$f.gcode")
                        Thread({
                            try { Files.copy(g, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING); state.note("g-code saved: $p") }
                            catch (e: Exception) { state.note("plymeld: the g-code was not saved to $p: ${e.message ?: e}") }
                        }, "plymeld-save").apply { isDaemon = true }.start()
                    }
                } },
                onSend = { state.send() })
        }
    }
}

/**
 * The keyboard, as OrcaSlicer documents its own (its wiki, keyboard shortcuts, read) so its users' habits
 * carry over: Ctrl+0..6 the views (default, top, bottom, front, behind, left, right); in the preview, Up / Down the layer,
 * Left / Right the move, Home / End the move slider's ends, Shift (or Ctrl) five at a time; L one layer at a time.
 * plymeld's own: Z fit, O orthographic. To become settings with the mouse bindings.
 */
private fun key(s: ViewerState, e: KeyEvent): Boolean {
    val step = if (e.isShiftPressed || e.isCtrlPressed) 5 else 1
    if (e.isCtrlPressed) {
        val v = when (e.key) {
            Key.Zero -> Camera.View.ISO; Key.One -> Camera.View.TOP; Key.Two -> Camera.View.BOTTOM; Key.Three -> Camera.View.FRONT
            Key.Four -> Camera.View.BACK; Key.Five -> Camera.View.LEFT; Key.Six -> Camera.View.RIGHT
            else -> null
        }
        if (v != null) { s.preset(v); return true }
    }
    when (e.key) {
        Key.DirectionUp -> s.stepLayer(step); Key.DirectionDown -> s.stepLayer(-step)
        Key.DirectionRight -> s.stepMove(step); Key.DirectionLeft -> s.stepMove(-step)
        Key.MoveHome -> s.moveTo(0); Key.MoveEnd -> s.moveTo(null)
        Key.L -> s.useSingleLayer(!s.singleLayer)
        Key.Z -> s.fit(); Key.O -> s.useOrthographic(!s.orthographic)
        else -> return false
    }
    return true
}

/**
 * System.out and System.err to `window.log` in [plymeld.config.UserDirs.state] (truncated at each start), for a window
 * started with no console: stack traces and the slicer's lines are otherwise lost. Left alone if the file cannot be opened.
 */
private fun logToFile() {
    try {
        val dir = plymeld.config.UserDirs.current().state
        Files.createDirectories(dir)
        val out = java.io.PrintStream(Files.newOutputStream(dir.resolve("window.log")), true, Charsets.UTF_8)
        System.setOut(out); System.setErr(out)
        println("plymeld window, ${java.time.LocalDateTime.now().withNano(0)}: started with no console; this file holds what it prints")
    } catch (_: Exception) { }
}
