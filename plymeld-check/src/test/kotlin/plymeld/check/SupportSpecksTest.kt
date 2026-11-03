package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.core.LayerSupport
import plymeld.core.Supports
import plymeld.core.TreeSupports
import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * plan.support.column.width (Supports.dropSpecks; the "support dabs" of a 30-part model's review): an
 * overhang is kept only where the overhang stacked over a column's width of height around it is at least that wide.
 * Judged as a stack, a sloped face (thin strips, one per layer) keeps all its support; a one-layer speck a fraction of
 * a line wide gets none; a flat fin narrower than a column gets none, a wider one
 * is supported; a locked spot keeps its speck. Controls: without the filter, each speck has its column.
 */
class SupportSpecksTest : StringSpec({
    fun u(v: Double) = Math.round(v * 1000)
    fun sq(x0: Double, y0: Double, x1: Double, y1: Double) = Polygon(longArrayOf(u(x0), u(y0), u(x1), u(y0), u(x1), u(y1), u(x0), u(y1)))
    fun area(set: List<Polygon>) = if (set.isEmpty()) 0.0 else Booleans.unionPositive(set).sumOf { it.signedAreaMm2() }
    fun all(ls: LayerSupport) = ls.base + ls.interfaceTop + ls.interfaceBottom + ls.walls
    fun total(gen: List<LayerSupport>) = gen.sumOf { area(all(it)) }
    val h = 80L; val n = 60
    val tops = LongArray(n) { (it + 1) * h }
    val block = sq(0.0, 0.0, 20.0, 20.0)
    // 0.14 mm step per 0.08 mm layer (about 30 deg); a column two 0.42 mm lines wide
    fun params(minWidth: Long, regions: Supports.Regions? = null) = Supports.Params(140, 420, 1, 2, 2, false, 420, regions, minWidthUm = minWidth)
    val width = 840L
    val tree = TreeSupports.Params(null, 840, 3000, 5.0, 2000, leanStepUm = 140)
    fun gen(model: List<List<Polygon>>, p: Supports.Params, t: LongArray) = TreeSupports.generate(model, LongArray(n) { h }, p, tree, t)

    "a sloped face, one thin strip per layer, keeps all its support: the strips are judged as their stack" {
        val model = List(n) { j -> listOf(sq(0.0, 0.0, 20.0 + 0.25 * (j - 19).coerceIn(0, 20), 20.0)) }
        val strips = Supports.overhangs(model, params(0), tops)
        strips.count { it.isNotEmpty() } shouldBe 20
        (strips[30][0].bounds().let { it[2] - it[0] }).toDouble() shouldBe (110.0 plusOrMinus 2.0)   // 0.25 advance - 0.14 step: 0.11 mm wide, 20 long
        val plain = gen(model, params(0), tops)
        val kept = gen(model, params(width), tops)
        total(plain) shouldBeGreaterThan 50.0
        total(kept) shouldBe (total(plain) plusOrMinus total(plain) * 0.01)
    }

    "a one-layer speck 0.3 mm wide gets no support; control: a column without the filter" {
        // a nub 0.9 mm out from the wall (clear of the side gap) and 0.3 mm wide: its overhang 0.76 x 0.3 mm
        val nub = sq(20.0, 9.85, 20.9, 10.15)
        val model = List(n) { j -> if (j == 30) Booleans.union(listOf(block), listOf(nub)) else listOf(block) }
        val plain = gen(model, params(0), tops)
        total(plain) shouldBeGreaterThan 0.0
        total(gen(model, params(width), tops)) shouldBe 0.0
        // the speck inside a locked spot is kept
        val locked = Supports.Regions(null, listOf(Supports.Box(u(19.0), u(21.0), u(9.0), u(11.0), tops[25], tops[35])))
        total(gen(model, params(width, locked), tops)) shouldBeGreaterThan 0.0
    }

    "a flat fin narrower than a column gets no support; a wider one is supported" {
        fun fin(w: Double) = List(n) { j -> if (j >= 30) Booleans.union(listOf(block), listOf(sq(20.0, 5.0, 20.0 + w, 15.0))) else listOf(block) }
        total(gen(fin(0.8), params(0), tops)) shouldBeGreaterThan 0.0
        total(gen(fin(0.8), params(width), tops)) shouldBe 0.0
        val wide = gen(fin(1.5), params(width), tops)
        total(wide) shouldBe (total(gen(fin(1.5), params(0), tops)) plusOrMinus 0.5)
        total(wide) shouldBeGreaterThan 0.0
    }
})
