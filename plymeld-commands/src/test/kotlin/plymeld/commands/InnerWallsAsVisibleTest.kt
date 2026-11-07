package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * plan.mechanism.motion.inner_walls.as_visible: inner walls at the visible lines' speed and acceleration, the outer
 * wall's, so a wall laid fast right behind it does not ripple through. The hole block from the repo's test config.
 * Control: off (bundled), the inner walls run faster than the outer wall.
 */
class InnerWallsAsVisibleTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    /** Per feature, the feed rates (mm/min) and accelerations its extruding moves run at, past the first layer. */
    fun motion(vararg cv: String): Map<String, Pair<Set<Double>, Set<String>>> {
        val out = Files.createTempDirectory("plymeld-inner").resolve("block.gcode")
        Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } + (listOf("plan.pose=as-given", "plan.placement=center") + cv).flatMap { listOf("-cv", it) } +
            listOf("-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()), { }) shouldBe 0
        val feeds = HashMap<String, MutableSet<Double>>(); val accels = HashMap<String, MutableSet<String>>()
        var feature = ""; var f = 0.0; var a = ""; var layer = -1
        for (l in Files.readAllLines(out)) {
            when {
                l.startsWith(";LAYER_CHANGE") -> layer++
                l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
                l.startsWith("SET_VELOCITY_LIMIT") -> a = l.substringAfter("ACCEL=")
                l.startsWith("G1 ") || l.startsWith("G2 ") || l.startsWith("G3 ") -> {
                    Regex(" F([0-9.]+)").find(l)?.let { f = it.groupValues[1].toDouble() }
                    if (layer >= 1 && Regex(" E[0-9.]+").containsMatchIn(l) && Regex(" [XY]").containsMatchIn(l)) {
                        feeds.getOrPut(feature) { HashSet() } += f; accels.getOrPut(feature) { HashSet() } += a }
                }
            }
        }
        return feeds.keys.associateWith { (feeds.getValue(it) to accels.getValue(it)) }
    }

    "on: inner walls at the outer wall's feed rate and acceleration; control: off, faster than the outer wall" {
        val on = motion("plan.mechanism.motion.inner_walls.as_visible=true")
        val outer = on.getValue("Outer wall")
        on.getValue("Inner wall").first.max() shouldBe outer.first.max()
        on.getValue("Inner wall").second shouldBe outer.second
        val off = motion()
        (off.getValue("Inner wall").first.max() > off.getValue("Outer wall").first.max()) shouldBe true
    }
})
