package plymeld.commands

import plymeld.config.Config
import plymeld.core.FlashStudioHeader
import plymeld.core.Predict
import plymeld.core.PrinterPicture
import plymeld.machine.Dialect
import plymeld.model.Setup
import plymeld.model.SliceSettings

/**
 * The machine's own file header, for the machines whose screens read one: Flash Studio's for the
 * Creator 5 (FlashStudioHeader). Other machines' g-code is returned as it is. (The preview pictures, withPictures
 * below, are any machine's that asks for them.)
 */
internal fun withMachineHeader(text: String, config: Config, setup: Setup, s: SliceSettings, prediction: Predict.Prediction): String {
    if (s.dialect != Dialect.FLASHFORGE) return text
    // every filament of the loadout by number, as Flash Studio lists them: every tool of the machine (the setup holds only up
    // to the highest one in use), then the slots swapped in by hand on a tool (a number past the tools')
    val machine = config.machine
    val onMachine = config.keysOf("tool").count { config.entry(listOf("hardware", "tool", it, "machine"))?.value == machine }
    fun number(sp: plymeld.model.Spool) = sp.id.removePrefix("spool ").trim().toIntOrNull() ?: (sp.tool + 1)
    val n = maxOf(setup.tools.size, onMachine, setup.spools.maxOfOrNull { number(it) } ?: 0)
    val tools = (1..n).map { i ->
        val sp = setup.spools.firstOrNull { number(it) == i }
        FlashStudioHeader.Tool(sp?.let { filamentText(config, it.material, "kind") }, sp?.values?.get("hardware.filament.color")?.value as String?)
    }
    return FlashStudioHeader.prepend(text, PlymeldVersion.value, tools, s.filamentDiameterMm, prediction)
}

/**
 * [text] with the machine's preview pictures (hardware.machine.gcode.pictures; PrinterPicture) of [parts] in their printed pose, each
 * part in the color of the filament its tool ([partTools], 0-based) holds, else a neutral gray. A machine that asks for none
 * gets [text] as it is. [log] gets the time the pictures took.
 */
internal fun withPictures(text: String, parts: List<plymeld.mesh.TriangleSoup>, partTools: List<Int>, setup: Setup, s: SliceSettings, log: (String) -> Unit): String {
    if (s.pictures.isEmpty()) return text
    val t0 = System.nanoTime()
    val drawn = parts.mapIndexed { i, p ->
        val color = partTools.getOrNull(i)?.let { t -> setup.spools.firstOrNull { it.tool == t }?.values?.get("hardware.filament.color")?.value as String? }
        PrinterPicture.Part(p, PrinterPicture.rgbOf(color) ?: PrinterPicture.NEUTRAL_RGB)
    }
    val pictures = s.pictures.map { (w, h) -> PrinterPicture.Picture(w, h, PrinterPicture.png(PrinterPicture.render(drawn, w, h))) }
    log("preview picture(s) ${s.pictures.joinToString { (w, h) -> "${w}x$h" }} (hardware.machine.<key>.gcode.pictures): ${(System.nanoTime() - t0) / 1_000_000} ms, ${parts.sumOf { it.count }} triangles")
    return PrinterPicture.insert(text, PrinterPicture.block(pictures))
}

/** A text field of a filament, following its `like` chain. */
private fun filamentText(config: Config, key: String, field: String): String? {
    var k: String? = key
    val seen = HashSet<String>()
    while (k != null && seen.add(k)) {
        config.entry(listOf("hardware", "filament", k, field))?.let { return it.value as String? }
        k = config.entry(listOf("hardware", "filament", k, "like"))?.value as String?
    }
    return null
}
