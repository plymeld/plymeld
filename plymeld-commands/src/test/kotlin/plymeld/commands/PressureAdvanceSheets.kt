package plymeld.commands

import com.github.ajalt.clikt.core.parse
import java.nio.file.Files
import java.nio.file.Path

/** The pressure-advance plate and its readings, for the tests of `calibrate pressure-advance` and its `readings` and `values`. */
internal object PressureAdvanceSheets {
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    fun copy(): Path {
        val d = Files.createTempDirectory("plymeld-pa-values")
        Files.list(samples).use { s -> s.forEach { Files.copy(it, d.resolve(it.fileName)) } }
        return d
    }
    fun plate(dir: Path, vararg args: String): Path {
        val out = dir.resolve("pa-${args.joinToString("_").replace(Regex("[^A-Za-z0-9_.-]"), "")}.gcode")
        CalibrateCommand().parse(listOf("pressure-advance", "-cf", dir.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2") + args + listOf("-o", out.toString()))
        return out
    }
    fun sheet(g: Path): String = PressureAdvanceRecord.blank(Files.readString(g), g.fileName.toString())
    /** [blank] filled in: each wall's three readings from its row, value, and flow step. */
    fun fill(blank: String, read: (row: String, value: Double?, step: Int) -> Triple<String, String, String?>): String {
        val out = ArrayList<String>(); var row = ""; var value: Double? = null; var step = 1
        for (l in blank.lines()) {
            Regex("""^\[wall\.(front|back)\.\d+]$""").find(l)?.let { row = it.groupValues[1] }
            Regex("""^value = "(.*)"$""").find(l)?.let { value = it.groupValues[1].removeSuffix(" s").toDoubleOrNull() }
            Regex("""^step = "(\d)"$""").find(l)?.let { step = it.groupValues[1].toInt() }
            val r by lazy { read(row, value, step) }
            out += when {
                l == "speed_up = \"?\"" -> "speed_up = \"${r.first}\""
                l == "slow_down = \"?\"" -> "slow_down = \"${r.second}\""
                l == "corner = \"?\"" -> "corner = \"${r.third}\""
                else -> l
            }
        }
        return out.joinToString("\n")
    }
    /** A true value of [k]: grooves below it, flat at it, ridges above; corners to match. */
    fun truth(k: Double): (String, Double?, Int) -> Triple<String, String, String?> = { _, v, _ ->
        when { v!! < k - 1e-9 -> Triple("groove", "ridge", "bulge"); v > k + 1e-9 -> Triple("ridge", "groove", "round"); else -> Triple("flat", "flat", "sharp") } }
    fun values(text: String) = PressureAdvanceRecord.values(text, "readings.toml")
}
