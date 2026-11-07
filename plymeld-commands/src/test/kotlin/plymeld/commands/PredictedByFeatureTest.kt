package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * `slice` says where the predicted time goes, by feature: the hole block from the repo's test config. Its features'
 * minutes add up to the predicted total (no tool changes here) and their shares to 100 %; the walls, the infill, and
 * the travels are there. Control: the total is read from the "predicted:" line, independently of the by-feature one.
 */
class PredictedByFeatureTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }

    "the predicted time by feature adds up to the total; the walls, infill, and travel among them" {
        val out = Files.createTempDirectory("plymeld-byfeature").resolve("block.gcode")
        val lines = ArrayList<String>()
        Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } + listOf("plan.pose=as-given", "plan.placement=center").flatMap { listOf("-cv", it) } +
            listOf("-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()), lines::add) shouldBe 0
        val total = Regex("^predicted: ([0-9.]+) min").find(lines.single { it.startsWith("predicted: ") })!!.groupValues[1].toDouble()
        val byFeature = lines.single { it.startsWith("predicted by feature: ") }.removePrefix("predicted by feature: ")
        val parts = Regex("([A-Za-z ]+?) ([0-9.]+) min \\(([0-9]+) %\\)").findAll(byFeature).map { Triple(it.groupValues[1].trim(), it.groupValues[2].toDouble(), it.groupValues[3].toInt()) }.toList()
        withClue(byFeature) {
            parts.map { it.first }.containsAll(listOf("Outer wall", "Inner wall", "Sparse infill", "travel")) shouldBe true
            parts.sumOf { it.second } shouldBe (total plusOrMinus 0.05 * parts.size)
            (parts.sumOf { it.third } in 97..103) shouldBe true
            parts.map { it.second } shouldBe parts.map { it.second }.sortedDescending()
        }
    }
})
