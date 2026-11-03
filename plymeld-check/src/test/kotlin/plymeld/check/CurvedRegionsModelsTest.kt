package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.decide.CurvedRegions
import plymeld.mesh.StlReader
import plymeld.mesh.ThreeMfReader
import plymeld.mesh.TriangleSoup
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

/**
 * The curved-top survey on real models: the 3DBenchy in test-models/, and
 * any models named in PLYMELD_SURVEY_MODELS (paths separated by ':'; none are in the repo), with a 45 deg, 4 mm nozzle,
 * band layers 0.2 mm, the tool's thinnest 0.08 mm. Prints each cap's band or why it has none, and writes a top view per
 * model to build/curved-regions/: green, a sloped top the band's layers follow; blue, a flat top inside a band; orange,
 * the band where its layers fade; red, a cap with no band; gray, the top's height. The 3DBenchy's cabin roof must get a
 * band whose layers follow most of it, with the chimney left out of it.
 */
class CurvedRegionsModelsTest : StringSpec({
    val p = CurvedRegions.Params(angleDeg = 45.0, nozzleHeightMm = 4.0, layerMm = 0.2, minLayerMm = 0.08)
    val out = Path.of("build", "curved-regions").also { Files.createDirectories(it) }

    fun load(path: Path): TriangleSoup = if (path.toString().endsWith(".stl", true)) StlReader.read(path) else {
        val parts = ThreeMfReader.read(path).parts.map { it.soup }
        val c = FloatArray(parts.sumOf { it.count } * 9); var at = 0
        for (s in parts) { System.arraycopy(s.coords, 0, c, at, s.count * 9); at += s.count * 9 }
        TriangleSoup(c, parts.sumOf { it.count })
    }

    fun report(name: String, r: CurvedRegions.Result, ms: Long) {
        println("== $name: grid ${r.nx} x ${r.ny} at ${r.cellMm} mm, ${ms} ms; ${r.caps.size} caps, ${"%.0f".format(Locale.ROOT, r.caps.sumOf { it.areaMm2 })} mm2; " +
            "${r.bands.size} bands following ${"%.0f".format(Locale.ROOT, r.bands.sumOf { it.followedMm2 })} mm2; ${r.overlaps.size} overlapping pairs")
        val rows = (r.bands.map { it.cap to it.toString() } + r.rejected.map { it.cap to "cap ${it.cap.id} (%.1f mm2, z %.2f..%.2f): NO BAND: ${it.why}".format(Locale.ROOT, it.cap.areaMm2, it.cap.zLoMm, it.cap.zHiMm) })
            .sortedByDescending { it.first.areaMm2 }
        for ((_, s) in rows.take(40)) println("  $s")
        // the shifted warp, each cap's island in its own layers (the model's own layers 0.08 mm, the tool's thickest 0.3 mm)
        val sh = CurvedRegions.shifted(r, p, soupLayerMm = 0.08, maxLayerMm = 0.3)
        val islands = sh.mapNotNull { it.second as? CurvedRegions.Island }
        println("  shifted, own layers: ${islands.size} of ${sh.size} caps get an island, following ${"%.0f".format(Locale.ROOT, islands.sumOf { it.followedMm2 })} mm2")
        for ((cap, x) in sh.sortedByDescending { it.first.areaMm2 }.take(30))
            println("    " + (if (x is CurvedRegions.Island) x.toString() + CurvedRegions.seedOf(r, cap).let { (sx, sy) -> "; lock at x %.2f, y %.2f".format(Locale.ROOT, sx, sy) }
                else "cap ${cap.id} (%.1f mm2, z %.2f..%.2f): NO ISLAND: $x".format(Locale.ROOT, cap.areaMm2, cap.zLoMm, cap.zHiMm)))
        // the picture: one pixel per cell, scaled up
        val scale = maxOf(1, 1600 / maxOf(r.nx, r.ny))
        val img = BufferedImage(r.nx * scale, r.ny * scale, BufferedImage.TYPE_INT_RGB)
        val zMax = r.top.filter { !it.isNaN() }.maxOrNull() ?: 1.0
        val kind = IntArray(r.nx * r.ny)          // 0 none, 1 red, 2 orange, 3 blue, 4 green
        val capCell = BooleanArray(r.nx * r.ny); r.caps.forEach { c -> c.cells.forEach { capCell[it] = true } }
        r.rejected.forEach { j -> j.cap.cells.forEach { kind[it] = 1 } }
        for (b in r.bands) b.cells.forEachIndexed { q, a ->
            val follows = b.surface[q] - r.top[a] < CurvedRegions.FOLLOW_MM
            val k = if (follows && capCell[a]) 4 else if (follows) 3 else 2
            kind[a] = maxOf(kind[a], k)
        }
        for (i in 0 until r.nx) for (j in 0 until r.ny) {
            val a = i * r.ny + j
            val c = when (kind[a]) {
                4 -> Color(0x2e8b57); 3 -> Color(0x9ec5e8); 2 -> Color(0xf0ad4e); 1 -> Color(0xd9534f)
                else -> if (r.top[a].isNaN()) Color.WHITE else { val g = (220 - 160 * r.top[a] / zMax).toInt().coerceIn(40, 230); Color(g, g, g) }
            }
            for (u in 0 until scale) for (v in 0 until scale) img.setRGB(i * scale + u, (r.ny - 1 - j) * scale + v, c.rgb)
        }
        val f = out.resolve("$name.png")
        ImageIO.write(img, "png", f.toFile())
        println("  -> ${f.toAbsolutePath()}")
    }

    "the 3DBenchy: the cabin roof gets a band whose layers follow most of it, the chimney left out; any PLYMELD_SURVEY_MODELS reported" {
        val benchy = load(testModelsDir().resolve("3DBenchy.stl"))
        val t0 = System.currentTimeMillis()
        val r = CurvedRegions.survey(benchy, p)
        report("3DBenchy", r, System.currentTimeMillis() - t0)
        // the roof: the largest cap between z 37 and 41 (the model as given: its bottom at z 0)
        val roof = r.caps.filter { it.zLoMm > 37.0 && it.zHiMm < 41.0 }.maxByOrNull { it.areaMm2 }!!
        val band = r.bands.firstOrNull { it.cap === roof }
        (band != null) shouldBe true
        (band!!.followedMm2 > 0.7 * roof.areaMm2) shouldBe true
        band.cells.none { r.top[it] > roof.zHiMm + 1e-6 } shouldBe true          // the chimney (to z 48) is not in the band
        for (m in System.getenv("PLYMELD_SURVEY_MODELS").orEmpty().split(':').filter { it.isNotBlank() }) {
            val path = Path.of(m)
            val s = load(path)
            val t1 = System.currentTimeMillis()
            report(path.fileName.toString().substringBeforeLast('.'), CurvedRegions.survey(s, p), System.currentTimeMillis() - t1)
        }
    }
})
