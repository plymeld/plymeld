package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * The writer says each thing once: a line width or an acceleration already in force is not written again, and no travel
 * goes to where the head already is (a scarf seam's ramp, loop and down pass are three paths, and each wrote all three).
 * The hole block with scarf seams on every loop, from the repo's test config. Control: the file has scarfed loops (a
 * ramp's Z moves within an outer wall), so the case that wrote them is there.
 */
class WriterNoRepeatsTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }

    "no line width, acceleration, or travel that repeats what is in force; control: scarf seams are there" {
        val out = Files.createTempDirectory("plymeld-repeats").resolve("block.gcode")
        val status = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            listOf("plan.pose=as-given", "plan.placement=center", "plan.seam=corners", "plan.technique.scarf_seam.enabled=true", "plan.technique.scarf_seam.where=all").flatMap { listOf("-cv", it) } +
            listOf("-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()), { })
        status shouldBe 0
        var width: String? = null; var accel: String? = null; var x: String? = null; var y: String? = null; var feature = ""
        var repeatedWidth = 0; var repeatedAccel = 0; var stayingTravel = 0; var rampMoves = 0
        for (l in Files.readAllLines(out)) {
            val s = l.trim()
            when {
                s.startsWith(";TYPE:") -> { feature = s.removePrefix(";TYPE:"); width = null }
                s.startsWith(";WIDTH:") -> { if (s.removePrefix(";WIDTH:") == width) repeatedWidth++; width = s.removePrefix(";WIDTH:") }
                s.startsWith("SET_VELOCITY_LIMIT") -> { val a = s.substringAfter("ACCEL="); if (a == accel) repeatedAccel++; accel = a }
                s.startsWith(";LAYER_CHANGE") || s.startsWith("; object") || s.startsWith("T") -> { width = null; accel = null }
                s.startsWith("G1 ") || s.startsWith("G2 ") || s.startsWith("G3 ") -> {
                    val w = s.substringBefore(';').split(' ')
                    val nx = w.firstOrNull { it.startsWith("X") }?.substring(1) ?: x
                    val ny = w.firstOrNull { it.startsWith("Y") }?.substring(1) ?: y
                    val travel = w.none { it.startsWith("E") || it.startsWith("Z") } && (w.any { it.startsWith("X") } || w.any { it.startsWith("Y") })
                    if (travel && nx == x && ny == y) stayingTravel++
                    if (feature == "Outer wall" && w.any { it.startsWith("Z") } && w.any { it.startsWith("E") }) rampMoves++
                    x = nx; y = ny
                }
            }
        }
        withClue("scarf ramp moves (control): $rampMoves") { (rampMoves > 100) shouldBe true }
        withClue("repeated widths $repeatedWidth, repeated accelerations $repeatedAccel, travels to where the head is $stayingTravel") {
            (repeatedWidth to repeatedAccel) shouldBe (0 to 0)
            stayingTravel shouldBe 0
        }
    }
})
