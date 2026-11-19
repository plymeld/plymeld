package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.check.render.ColorBy
import plymeld.check.render.Others
import plymeld.check.render.Render
import plymeld.check.render.RenderRequest
import plymeld.commands.SyntheticGcode.Companion.render
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The whole-print views' pictures stay the same bytes as the drawing they were first made with: a synthetic print (two
 * tools, rings and diagonal fill, a line laid twice, a ramp between layers) drawn in 3D, from outside, from below, by
 * order and by speed, with lines chosen, and as flat regions at a given size, PNG and SVG, each picture's SHA-256 against
 * the hash recorded from the plain one-pass depth buffer the faster drawing replaced. A changed hash is a changed picture.
 */
class RenderSolidBytesTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-render-bytes")

    "the whole-print views draw the same bytes as the drawing they were recorded from" {
        val f = RenderSolidSample.write(dir.resolve("sample.gcode"))
        val got = LinkedHashMap<String, String>()
        for ((name, args) in RenderSolidSample.VIEWS) for (fmt in listOf("png", "svg")) {
            val o = dir.resolve("$name-$fmt")
            // no annotations: text is drawn with the machine's fonts, which differ between machines; the drawing does not
            val (code, _, err) = render(f.toString(), *args.toTypedArray(), "--no-annotations", "--format", fmt, "-o", o.toString())
            code shouldBe 0; err shouldBe emptyList()
            got["$name.$fmt"] = sha256(Files.list(o).use { s -> s.toList().single() })
        }
        if (got != EXPECTED) {
            val out = Path.of("build", "render-solid-hashes.txt")
            Files.writeString(out, got.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
        }
        got shouldBe EXPECTED
    }

    "the whole-print views are the same bytes on one thread and on several" {
        // 40 layers: five pieces read on threads of their own, as well as bands of rows drawn at once; and the same print
        // marking no layers, its layers read from the moves' heights in each piece
        for (markers in listOf(true, false)) {
            val f = RenderSolidSample.write(dir.resolve("tall-$markers.gcode"), 40, markers)
            fun hashes(threads: Int): List<String> = listOf<(Path) -> RenderRequest>(
                { o -> RenderRequest(f, threeD = true, noAnnotations = true, outDir = o) },
                { o -> RenderRequest(f, threeD = true, threeDFrom = listOf(60.0 to 45.0), colorBy = ColorBy.ORDER, noAnnotations = true, outDir = o) },
                { o -> RenderRequest(f, threeD = true, threeDFrom = listOf(150.0 to -25.0), noAnnotations = true, outDir = o) },
                { o -> RenderRequest(f, outside = listOf("front"), noAnnotations = true, outDir = o) },
                { o -> RenderRequest(f, outside = listOf("top"), colorBy = ColorBy.SPEED, onlyTool = listOf(1), others = Others.FAINT, noAnnotations = true, outDir = o) },
                // a cut from the side reads every layer, in pieces on the threads
                { o -> RenderRequest(f, cutY = listOf(16.0), colorBy = ColorBy.ORDER, noAnnotations = true, outDir = o) },
            ).mapIndexed { k, req -> Render.run(req(dir.resolve("threads-$markers-$threads-$k")), threads) { }.files.single().let(::sha256) }
            val one = hashes(1)
            hashes(3) shouldBe one
            hashes(8) shouldBe one
        }
        // and the sample the hashes were recorded from, on one thread and on eight
        val s = RenderSolidSample.write(dir.resolve("sample-threads.gcode"))
        for (t in listOf(1, 8)) Render.run(RenderRequest(s, threeD = true, noAnnotations = true, outDir = dir.resolve("sample-$t")), t) { }
            .files.single().let(::sha256) shouldBe EXPECTED["3d.png"]
    }
}) {
    companion object {
        fun sha256(p: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) }

        /** Recorded from the one-pass drawing (commit 51ce920). */
        val EXPECTED: Map<String, String> = linkedMapOf(
            "3d.png" to "ee48802fb12e2d114258d34e16b89bc2e73072e1e7b1bfe7d8c2818886d59fa7",
            "3d.svg" to "2aa05e42b25f8ee7f8bd85da2ec136a6a296d20dbcf302d6ff1a1d679b7a7306",
            "front.png" to "a34fd195c8454d823a6808743fc5ad3f29a4fd8de96616a3cf58c2b581f1d914",
            "front.svg" to "f696099256dd72447aef80042417da5ee1bdf6a578b0277f381fdee34d62bc87",
            "top.png" to "f9c16535b805eaa60df13a32a648bec66cad1c4fe7e8a7108a2ed2b512d74c97",
            "top.svg" to "d942930f191dcd27496a7b4c8b3c4df2acb0b039dc1ec817b75cd68c1f514fa4",
            "below.png" to "ac6f84da60a125c745e5ca4e7f22e84bb35124dca5e487c348eaeb4e8966e874",
            "below.svg" to "925468c892b4f74c9c8030337ca2d7812210145c03e58e1c1955cd80123552cd",
            "order.png" to "d141d91524f48bfa711fb7fbeff25955857cfd2a3da96126f3c475f331a4d565",
            "order.svg" to "6b1aba27d289fe3d02c5da15c92b2c7fd333d1af47fceb7471f43758f2680bfe",
            "speed.png" to "baee59535d129a0226173aed62180b0cd20aca6a4db73f0355ad623e384771e2",
            "speed.svg" to "bce1c1d77c55bdce9d583c4511cbf406b85d554012deaa0ba559a548448d8a98",
            "flat.png" to "c8ac3f6580e96b43b830da492c7815d0a579bf91314ca7889e15b809104d378b",
            "flat.svg" to "2aae891e1826686d9d8581c1e592609aed3c586e77dda8c5f6f119aa41732376",
            "left-flat.png" to "edcc07a35dcb0eb2c74bc7e612c3d924beea6f50697098bcd29cff6359059683",
            "left-flat.svg" to "67af56d2f980cb4fb44487d6e8464f0219713370c09aff170de8069345eae0af",
        )
    }
}

/** The synthetic print [RenderSolidBytesTest] draws, and the views it draws (shared with the drawing's other tests). */
object RenderSolidSample {
    val VIEWS: List<Pair<String, List<String>>> = listOf(
        "3d" to listOf("--3d"),
        "front" to listOf("--outside", "front"),
        "top" to listOf("--outside", "top"),
        "below" to listOf("--3d", "--3d-from", "150,-25"),
        "order" to listOf("--3d", "--3d-from", "60,45", "--color-by", "order"),
        "speed" to listOf("--3d", "--color-by", "speed", "--only-tool", "1", "--others", "faint"),
        "flat" to listOf("--3d", "--picture-size", "160x120"),
        "left-flat" to listOf("--outside", "left", "--picture-size", "200x150", "--color-by", "tool"),
    )

    /**
     * [layers] layers 0.2 mm thick (six by default) over a 12 mm square: a wall loop by tool 0, a ring of arc chords by tool 1 inside it, diagonal
     * fill at alternating angles at two speeds, one line laid twice exactly (a tie in depth everywhere along it), and a
     * ramp rising across the top layer.
     */
    fun write(p: Path, layers: Int = 6, markers: Boolean = true): Path {
        val g = SyntheticGcode(markers = markers)
        for (k in 1..layers) {
            val z = 0.2 * k
            g.layer(z, 0.2)
            g.feature("Outer wall", 0.45).tool(0).loop(10.0, 10.0, 22.0, 22.0, 0.45)
            g.feature("Inner wall", 0.45).tool(1).travel(19.0, 16.0).arcAsLines(19.0, 16.0, 16.0, 16.0, k % 2 == 0, 0.42, 24)
            g.feature("Sparse infill", 0.45).tool(0)
            for (j in 0 until 6) {
                val a = 10.6 + j * 1.8
                if (k % 2 == 0) g.travel(a, 10.6).line(10.6, a, 0.45, if (j % 2 == 0) 40.0 else 120.0)
                else g.travel(a, 21.4).line(10.6, 21.4 - (a - 10.6), 0.45, if (j % 2 == 0) 40.0 else 120.0)
            }
            g.feature("Top surface", 0.4).travel(12.0, 13.0).line(20.0, 13.0, 0.4)
            g.travel(12.0, 13.0).line(20.0, 13.0, 0.4)
        }
        g.raw("G1 X12 Y20 F9000").raw(";TYPE:Ironing").raw("G1 X20 Y20 Z${String.format(java.util.Locale.ROOT, "%.3f", 0.2 * layers + 0.2)} E0.3 F1200")
        return g.write(p)
    }
}
