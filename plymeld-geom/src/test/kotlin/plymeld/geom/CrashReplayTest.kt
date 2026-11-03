package plymeld.geom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Replays boolean operands a failure dumped (CrashDump): put a dump in test-models/crash/ (git-ignored: dumps are
 * fragments of someone's model) to reproduce it. Each must assemble, and the result must satisfy the operation's area
 * bounds. Diagnostic output names any vertex where the kept pieces do not balance (in != out), which is where an open
 * chain starts. With no dumps there is nothing to replay; the tests are LocalFixtures ones.
 */
class CrashReplayTest : StringSpec({
    val dir = crashDir()
    val dumps = dir?.let { d -> Files.list(d).use { it.toList() }.filter { it.toString().endsWith(".txt") }.sorted() } ?: emptyList()
    for (f in dumps) {
        "replay ${f.fileName}".config(tags = setOf(LocalFixtures)) {
            val d = CrashDump.read(Files.readString(f))
            // diagnostics first, so a failure shows WHERE the balance breaks
            val kept = Booleans.keptPieces(d.a, d.b, d.op, null, d.positiveA)
            val bal = HashMap<Pair<Long, Long>, Int>()
            for (i in 0 until kept.size / 4) {
                bal.merge(kept[4 * i] to kept[4 * i + 1], 1, Int::plus)
                bal.merge(kept[4 * i + 2] to kept[4 * i + 3], -1, Int::plus)
            }
            val bad = bal.filterValues { it != 0 }
            println("crash replay ${f.fileName}: op=${d.op} A=${d.a.size} B=${d.b.size} kept=${kept.size / 4}; unbalanced vertices: ${bad.size} ${bad.entries.take(6)}")
            if (bad.isNotEmpty()) {
                val log = StringBuilder()
                Booleans.keptPieces(d.a, d.b, d.op, log, d.positiveA)
                val keys = bad.keys.map { (x, y) -> "IntPoint(x=$x, y=$y)" }
                log.lines().filter { l -> keys.any { l.contains(it) } }.forEach { println("  $it") }
            }
            val r = Booleans.op(d.a, d.b, d.op, d.positiveA)
            val area = r.sumOf { it.signedAreaMm2() }
            if (d.op == Booleans.Op.INTERSECTION) {
                val aa = Booleans.normalize(d.a).sumOf { it.signedAreaMm2() }
                val bb = Booleans.normalize(d.b).sumOf { it.signedAreaMm2() }
                (area <= minOf(aa, bb) + 1e-6) shouldBe true
            }
            bad.size shouldBe 0
        }
    }
})

/** test-models/crash/ in this checkout or a directory above it; null when there is none. */
private fun crashDir(): Path? {
    var p: Path? = Path.of("").toAbsolutePath()
    while (p != null) {
        val c = p.resolve("test-models/crash")
        if (Files.isDirectory(c)) return c
        p = p.parent
    }
    return null
}
