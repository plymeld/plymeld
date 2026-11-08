package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * `plymeld report`: the zip holds the system, the config as plymeld reads it, the plan for the models, and a g-code file's
 * header and source block, never its moves; the home folder's path and network addresses are hidden; the models and the
 * whole g-code only with --include-model; the issue's address carries the title and the text. Controls: without -m no
 * plan, without --include-model no model file, and a text without an address or the home path is unchanged.
 */
class ReportCommandTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val d = Files.createTempDirectory("plymeld-report")
    val model = root.resolve("test-models/holeblock.stl").toString()
    fun entries(zip: Path): Map<String, String> = ZipFile(zip.toFile()).use { z -> z.entries().toList().associate { it.name to String(z.getInputStream(it).readBytes()) } }
    fun report(name: String, vararg extra: String): Pair<Map<String, String>, List<String>> {
        val out = ArrayList<String>()
        val zip = d.resolve(name)
        Commands.invoke(listOf("report") + cfs.flatMap { listOf("-cf", it) } + listOf("-o", zip.toString()) + extra, { out += it }) shouldBe 0
        return entries(zip) to out
    }
    // a g-code file of the block, for its header and source block
    val gcode = d.resolve("block.gcode")
    Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } + listOf("-cv", "plan.pose=as-given", "-cv", "plan.placement=center", "-m", model, "-o", gcode.toString()), { }) shouldBe 0

    "the system, the config, the plan, and the g-code's header and source block, not its moves; control: without -m no plan, without --include-model no files" {
        val (z, out) = report("full.zip", "-m", model, "--gcode", gcode.toString())
        z.keys shouldContainAll listOf("system.txt", "config.txt", "plan.txt", "gcode-header-and-source.txt")
        z.getValue("system.txt") shouldContain "plymeld "
        z.getValue("config.txt") shouldContain "\$ plymeld config"
        z.getValue("plan.txt") shouldContain "\$ plymeld plan"
        z.getValue("plan.txt") shouldContain "(exit 0)"
        val g = z.getValue("gcode-header-and-source.txt")
        g shouldContain SourceBlock.START
        g shouldContain SourceBlock.END
        g shouldNotContain "\nG1 "                                        // no moves
        z.keys.none { it.startsWith("files/") } shouldBe true
        out.last() shouldContain ReportCommand.ISSUES
        val (z2, _) = report("bare.zip")
        ("plan.txt" in z2) shouldBe false
    }

    "--include-model adds the model and the whole g-code" {
        val (z, _) = report("withmodel.zip", "-m", model, "--gcode", gcode.toString(), "--include-model")
        z.keys shouldContainAll listOf("files/holeblock.stl", "files/block.gcode")
    }

    "the home folder's path and network addresses are hidden; control: a text with neither is unchanged" {
        val t = "config: /home/someone/plymeld/a.toml\nhost = \"192.168.1.20\"\nmac aa:bb:cc:dd:ee:ff\n"
        val r = ReportCommand.redacted(t, "/home/someone")
        r shouldBe "config: ~/plymeld/a.toml\nhost = \"<address>\"\nmac <address>\n"
        ReportCommand.redacted("layer height 0.2 mm, version 0.20261107.1\n", "/home/someone") shouldBe "layer height 0.2 mm, version 0.20261107.1\n"
    }

    "the issue's address carries the title and the text, the zip named" {
        val url = ReportCommand.issueUrl("Problem report: plymeld 1", ReportCommand.issueBody("plymeld 1\nLinux", listOf("system.txt"), "r.zip", null))
        url.startsWith(ReportCommand.ISSUES + "?title=") shouldBe true
        val body = URLDecoder.decode(url.substringAfter("&body="), Charsets.UTF_8)
        body shouldContain "## What happened"
        body shouldContain "Attached: `r.zip`"
        body shouldContain "plymeld 1\nLinux"
    }
})
