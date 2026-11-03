package plymeld.geom

/**
 * A point on the kernel's integer grid.
 *
 * All geometry in `plymeld-geom` lives on an integer lattice so that predicates
 * (orientation, intersection) are exact. The grid unit is fixed by
 * [Units.PER_MM]; conversions from millimetres happen at the module boundary,
 * never inside an algorithm.
 */
data class IntPoint(val x: Long, val y: Long) {
    operator fun plus(o: IntPoint) = IntPoint(x + o.x, y + o.y)
    operator fun minus(o: IntPoint) = IntPoint(x - o.x, y - o.y)

    /** 2D cross product `this × o`; exact for |coords| < 2^62. */
    infix fun cross(o: IntPoint): Long = x * o.y - y * o.x

    infix fun dot(o: IntPoint): Long = x * o.x + y * o.y
}

object Units {
    /** Grid units per millimetre. 1 µm resolution. */
    const val PER_MM: Long = 1_000

    fun mm(v: Double): Long = Math.round(v * PER_MM)
    fun toMm(v: Long): Double = v.toDouble() / PER_MM
}

/**
 * Sign of the orientation of the triangle (a, b, c):
 * +1 counter-clockwise, -1 clockwise, 0 collinear. Exact.
 */
fun orientation(a: IntPoint, b: IntPoint, c: IntPoint): Int {
    val v = (b - a) cross (c - a)
    return if (v > 0) 1 else if (v < 0) -1 else 0
}
